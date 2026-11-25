package com.bitchat.android.mosh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where one of my group messages is, for one member. Order matters: later states replace earlier ones. */
enum class GroupDelivery { WAITING, SENT, DELIVERED, READ }

data class MemberStatus(val state: GroupDelivery, val atMs: Long, val sentOverInternet: Boolean = false)

data class StoredGroupMessage(
    val id: String,
    val fromKeyHex: String,
    val kind: Int,
    val text: String,
    val sentAtMs: Long,
    val receivedAtMs: Long,
    /** For my own messages: one entry per member it was for. Empty for messages from others. */
    val status: Map<String, MemberStatus> = emptyMap(),
    /** For messages from others: true once I have seen them (and a read receipt was due). */
    val seen: Boolean = false,
)

data class Group(
    val roster: GroupRoster,
    /** Members who said they left. They get nothing more from us. */
    val left: Set<String> = emptySet(),
    /** True after I left, or after the creator took me out. The chat stays, read-only. */
    val closed: Boolean = false,
    val messages: List<StoredGroupMessage> = emptyList(),
    /** No notifications for this group's messages (Help still rings: it is separate). */
    val muted: Boolean = false,
) {
    val id: String get() = roster.groupId
    val unread: Int get() = messages.count { !it.seen && it.kind == GroupMessage.TEXT && it.status.isEmpty() }
    val lastAtMs: Long get() = messages.lastOrNull()?.receivedAtMs ?: roster.updatedAtMs
}

/** Everything the store keeps, as written to disk. */
data class GroupSnapshot(val groups: List<Group> = emptyList(), val orphans: List<Pair<String, GroupMessage>> = emptyList())

interface GroupPersistence {
    fun load(): GroupSnapshot?
    fun save(s: GroupSnapshot)
}

/**
 * Groups and their messages. Rules (see docs/MOSH_GROUPS.md):
 * - A roster is accepted only from its creator, only if the creator is a QR-verified friend, only if
 *   I am in it, and only if it is newer than the one I have. A group's creator never changes.
 * - Messages and receipts are accepted only from current members, and only for groups I am in.
 * - A message for a group I do not know yet waits (up to [MAX_ORPHANS]) until its roster arrives.
 */
class GroupStore(
    private val myKeyHex: () -> String?,
    private val persistence: GroupPersistence? = null,
) {
    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    val groups: StateFlow<List<Group>> = _groups.asStateFlow()
    private val orphans = ArrayList<Pair<String, GroupMessage>>()

    init {
        persistence?.load()?.let { snap -> _groups.value = snap.groups; orphans += snap.orphans }
    }

    fun group(id: String): Group? = _groups.value.firstOrNull { it.id == id }

    /** Members I send to: everyone but me and anyone who left. Empty once the group is closed. */
    @Synchronized
    fun recipients(groupId: String): List<GroupMember> {
        val g = group(groupId) ?: return emptyList()
        if (g.closed) return emptyList()
        val me = myKeyHex()
        return g.roster.members.filter { it.noiseKeyHex != me && it.noiseKeyHex !in g.left }
    }

    @Synchronized
    fun create(groupId: String, name: String, me: GroupMember, others: List<GroupMember>, nowMs: Long): GroupRoster {
        val roster = GroupRoster(groupId, name.trim(), me.noiseKeyHex, nowMs, listOf(me) + others.distinctBy { it.noiseKeyHex })
        MoshCodec.encode(roster) // throws on an invalid roster (too many members, bad name)
        put(Group(roster))
        return roster
    }

    /** New roster from the creator (me): rename, add or remove people. Null if I am not the creator. */
    @Synchronized
    fun edit(groupId: String, name: String, members: List<GroupMember>, nowMs: Long): GroupRoster? {
        val g = group(groupId) ?: return null
        if (g.roster.creatorKeyHex != myKeyHex() || g.closed) return null
        val roster = g.roster.copy(name = name.trim(), members = members.distinctBy { it.noiseKeyHex },
            updatedAtMs = maxOf(nowMs, g.roster.updatedAtMs + 1))
        MoshCodec.encode(roster)
        put(g.copy(roster = roster, left = g.left.filter { k -> roster.members.any { it.noiseKeyHex == k } }.toSet()))
        return roster
    }

    @Synchronized
    fun applyRoster(fromKeyHex: String, fromIsFriend: Boolean, r: GroupRoster): Boolean {
        val me = myKeyHex() ?: return false
        if (!fromIsFriend || fromKeyHex != r.creatorKeyHex) return false
        val old = group(r.groupId)
        if (old != null && (old.roster.creatorKeyHex != r.creatorKeyHex || r.updatedAtMs <= old.roster.updatedAtMs)) return false
        val inIt = r.members.any { it.noiseKeyHex == me }
        if (old == null && !inIt) return false
        val base = old ?: Group(r)
        put(base.copy(roster = r, closed = base.closed || !inIt,
            left = base.left.filter { k -> r.members.any { it.noiseKeyHex == k } }.toSet()))
        // Messages that arrived before the roster.
        val waiting = orphans.filter { it.second.groupId == r.groupId }
        orphans.removeAll(waiting.toSet())
        waiting.forEach { (from, m) -> applyMessage(from, m, m.sentAtMs) }
        save()
        return true
    }

    /** Returns true for a new message (send a delivered receipt), false for a repeat or a refusal. */
    @Synchronized
    fun applyMessage(fromKeyHex: String, m: GroupMessage, nowMs: Long): Boolean {
        val g = group(m.groupId)
        if (g == null) {
            if (orphans.none { it.first == fromKeyHex && it.second.messageId == m.messageId }) {
                orphans += fromKeyHex to m
                while (orphans.size > MAX_ORPHANS) orphans.removeAt(0)
                save()
            }
            return false
        }
        if (g.closed || fromKeyHex == myKeyHex() || g.roster.members.none { it.noiseKeyHex == fromKeyHex }) return false
        if (g.messages.any { it.id == m.messageId && it.fromKeyHex == fromKeyHex }) return true // repeat: ack again
        val stored = StoredGroupMessage(m.messageId, fromKeyHex, m.kind, m.text, m.sentAtMs, nowMs,
            seen = m.kind == GroupMessage.LEFT)
        val left = if (m.kind == GroupMessage.LEFT) g.left + fromKeyHex else g.left
        put(g.copy(left = left, messages = (g.messages + stored).sortedBy { it.receivedAtMs }.takeLast(MAX_MESSAGES)))
        return true
    }

    /** Adds my message with every recipient waiting. Null if I can't send to this group. */
    @Synchronized
    fun addOutgoing(groupId: String, messageId: String, kind: Int, text: String, nowMs: Long): GroupMessage? {
        val g = group(groupId) ?: return null
        val me = myKeyHex() ?: return null
        val to = recipients(groupId)
        if (g.closed || to.isEmpty()) return null
        val m = GroupMessage(groupId, messageId, kind, text, nowMs)
        MoshCodec.encode(m)
        val status = to.associate { it.noiseKeyHex to MemberStatus(GroupDelivery.WAITING, nowMs) }
        put(g.copy(messages = (g.messages + StoredGroupMessage(messageId, me, kind, text, nowMs, nowMs, status, seen = true))
            .takeLast(MAX_MESSAGES)))
        return m
    }

    @Synchronized
    fun markSent(groupId: String, messageId: String, memberKey: String, nowMs: Long, overInternet: Boolean) =
        upgrade(groupId, messageId, memberKey) { old ->
            if (old.state >= GroupDelivery.SENT) old.copy(sentOverInternet = old.sentOverInternet || overInternet)
            else MemberStatus(GroupDelivery.SENT, nowMs, old.sentOverInternet || overInternet)
        }

    @Synchronized
    fun applyReceipt(fromKeyHex: String, r: GroupReceipt): Boolean {
        val g = group(r.groupId) ?: return false
        if (g.roster.members.none { it.noiseKeyHex == fromKeyHex }) return false
        val target = if (r.read) GroupDelivery.READ else GroupDelivery.DELIVERED
        return upgrade(r.groupId, r.messageId, fromKeyHex) { old ->
            if (old.state >= target) old else MemberStatus(target, r.atMs, old.sentOverInternet)
        }
    }

    /** Marks a group's messages seen; returns the ones that need a read receipt (author key to message id). */
    @Synchronized
    fun markSeen(groupId: String): List<Pair<String, String>> {
        val g = group(groupId) ?: return emptyList()
        val due = g.messages.filter { !it.seen && it.status.isEmpty() && it.fromKeyHex != myKeyHex() }
        if (due.isEmpty()) return emptyList()
        put(g.copy(messages = g.messages.map { if (it in due) it.copy(seen = true) else it }))
        return due.map { it.fromKeyHex to it.id }
    }

    /** I leave: the chat stays but nothing more is sent or accepted. Returns the goodbye to send first. */
    @Synchronized
    fun leave(groupId: String, messageId: String, nowMs: Long): Pair<GroupMessage, List<GroupMember>>? {
        val g = group(groupId) ?: return null
        val to = recipients(groupId)
        put(g.copy(closed = true))
        return GroupMessage(groupId, messageId, GroupMessage.LEFT, "", nowMs) to to
    }

    @Synchronized
    fun setMuted(groupId: String, muted: Boolean) { group(groupId)?.let { put(it.copy(muted = muted)) } }

    /** Deletes this group's messages on this phone only. My undelivered ones stop retrying too. */
    @Synchronized
    fun clearMessages(groupId: String) { group(groupId)?.let { put(it.copy(messages = emptyList())) } }

    @Synchronized
    fun delete(groupId: String) {
        _groups.value = _groups.value.filterNot { it.id == groupId }
        save()
    }

    @Synchronized
    fun wipe() {
        _groups.value = emptyList(); orphans.clear(); save()
    }

    /** My messages still not delivered to someone, newer than [sinceMs]: (group, message, member). */
    @Synchronized
    fun undelivered(sinceMs: Long): List<Triple<Group, StoredGroupMessage, GroupMember>> {
        val me = myKeyHex() ?: return emptyList()
        return _groups.value.filter { !it.closed }.flatMap { g ->
            g.messages.filter { it.fromKeyHex == me && it.sentAtMs >= sinceMs }.flatMap { m ->
                m.status.filter { it.value.state < GroupDelivery.DELIVERED && it.key !in g.left }.mapNotNull { (k, _) ->
                    g.roster.members.firstOrNull { it.noiseKeyHex == k }?.let { Triple(g, m, it) }
                }
            }
        }
    }

    private fun upgrade(groupId: String, messageId: String, memberKey: String, f: (MemberStatus) -> MemberStatus): Boolean {
        val g = group(groupId) ?: return false
        val me = myKeyHex()
        val m = g.messages.firstOrNull { it.id == messageId && it.fromKeyHex == me } ?: return false
        val old = m.status[memberKey] ?: return false
        val new = f(old)
        if (new == old) return false
        put(g.copy(messages = g.messages.map { if (it === m) it.copy(status = it.status + (memberKey to new)) else it }))
        return true
    }

    private fun put(g: Group) {
        _groups.value = (_groups.value.filterNot { it.id == g.id } + g).sortedByDescending { it.lastAtMs }
        save()
    }

    private fun save() { persistence?.save(GroupSnapshot(_groups.value, orphans.toList())) }

    companion object {
        const val MAX_MESSAGES = 500
        const val MAX_ORPHANS = 50
    }
}

/** WhatsApp-style tick for one of my messages, from every member's state. */
fun overallDelivery(status: Map<String, MemberStatus>): GroupDelivery = when {
    status.isEmpty() -> GroupDelivery.WAITING
    status.values.all { it.state == GroupDelivery.READ } -> GroupDelivery.READ
    status.values.all { it.state >= GroupDelivery.DELIVERED } -> GroupDelivery.DELIVERED
    status.values.any { it.state >= GroupDelivery.SENT } -> GroupDelivery.SENT
    else -> GroupDelivery.WAITING
}
