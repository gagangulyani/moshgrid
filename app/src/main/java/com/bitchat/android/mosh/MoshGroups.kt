package com.bitchat.android.mosh

import android.content.Context
import android.content.SharedPreferences
import com.bitchat.android.noise.NoisePeerIdentity
import com.bitchat.android.services.ContactIdentityResolver
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Groups on the phone: sending (one locked copy per member, Bluetooth first, internet once),
 * receiving, receipts and retries. Rules live in [GroupStore]; the wire format in docs/MOSH_GROUPS.md.
 */
object MoshGroups {
    private const val PREFS_KEY = "groups_v1"
    /** Undelivered messages are retried over Bluetooth for this long. */
    private const val RETRY_FOR_MS = 12 * 60 * 60 * 1000L

    @Volatile private var app: Context? = null
    @Volatile private var myNostrHex: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Sends a payload to one member: (payload, peerID, member, internet allowed) -> (mesh accepted, internet accepted). */
    @Volatile internal var transport: ((MoshPayload, String, GroupMember, Boolean) -> Pair<Boolean, Boolean>)? = null

    lateinit var store: GroupStore
        private set

    /** The group whose chat is on screen: its messages are read as they arrive. */
    private val _open = MutableStateFlow<String?>(null)
    val open: StateFlow<String?> = _open.asStateFlow()

    val isInitialised: Boolean get() = app != null

    fun init(context: Context, securePrefs: SharedPreferences) {
        if (app != null) return
        app = context.applicationContext
        val gson = Gson()
        store = GroupStore(myKeyHex = { myKeyHex() }, persistence = object : GroupPersistence {
            override fun load(): GroupSnapshot? = runCatching {
                securePrefs.getString(PREFS_KEY, null)?.let { gson.fromJson(it, GroupSnapshot::class.java) }
            }.onFailure { MoshMonitor.failed("groups_load_failed", it) }.getOrNull()?.let { sanitise(it) }
            override fun save(s: GroupSnapshot) { securePrefs.edit().putString(PREFS_KEY, gson.toJson(s)).apply() }
        })
        MoshInbox.groupMember = { peer -> memberByPeer(peer) != null }
        scope.launch { myNostrHex = com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(context.applicationContext)?.publicKeyHex }
    }

    /** Gson fills missing lists and maps with null; make an older or damaged file safe to use. */
    @Suppress("SENSELESS_COMPARISON")
    private fun sanitise(s: GroupSnapshot): GroupSnapshot = GroupSnapshot(
        groups = (s.groups ?: emptyList()).filter { it != null && it.roster != null }.map { g ->
            g.copy(left = g.left ?: emptySet(), messages = (g.messages ?: emptyList()).filter { it != null }
                .map { it.copy(status = it.status ?: emptyMap()) })
        },
        orphans = (s.orphans ?: emptyList()).filter { it != null && it.first != null && it.second != null },
    )

    fun myKeyHex(): String? = com.bitchat.android.services.VerificationService.myNoiseKeyHex()

    private fun peerOf(m: GroupMember): String? = ContactIdentityResolver.bytesFromHex(m.noiseKeyHex)?.let { NoisePeerIdentity.derivePeerID(it) }

    /** A member of any of my open groups, by mesh peer ID. */
    fun memberByPeer(peerID: String): GroupMember? = if (!isInitialised) null else
        store.groups.value.filter { !it.closed }.flatMap { it.roster.members }.firstOrNull { peerOf(it) == peerID }

    /** Noise key of a group member who wrote to us over Nostr, if their group's creator told us their key. */
    fun noiseKeyForNostr(senderPubkey: String): ByteArray? {
        if (!isInitialised) return null
        val hex = ContactIdentityResolver.nostrPubkeyHex(senderPubkey) ?: return null
        val m = store.groups.value.filter { !it.closed }.flatMap { it.roster.members }.firstOrNull { it.nostrPubHex == hex } ?: return null
        return ContactIdentityResolver.bytesFromHex(m.noiseKeyHex)
    }

    private fun meAsMember(name: String): GroupMember? {
        val key = myKeyHex() ?: return null
        return GroupMember(key, myNostrHex ?: "", name.take(32))
    }

    /** One of my friends as a group member, with the Nostr key we got when we added each other. */
    fun memberFromFriend(f: Friend): GroupMember {
        val npub = runCatching {
            com.bitchat.android.favorites.FavoritesPersistenceService.shared
                .getFavoriteStatus(ContactIdentityResolver.bytesFromHex(f.noiseKeyHex) ?: ByteArray(0))?.peerNostrPublicKey
        }.getOrNull()
        return GroupMember(f.noiseKeyHex, npub?.let { ContactIdentityResolver.nostrPubkeyHex(it) } ?: "", f.displayName.take(32))
    }

    /** Creates a group with me and [friends]; returns its id, or null if my identity is not ready. */
    fun create(name: String, myName: String, friends: List<Friend>): String? {
        val me = meAsMember(myName) ?: return null
        val roster = store.create(UUID.randomUUID().toString(), name, me, friends.map { memberFromFriend(it) }, System.currentTimeMillis())
        sendRoster(roster, roster.members)
        return roster.groupId
    }

    /** Creator only: rename and change members. Removed people get the new roster too, so their chat closes. */
    fun edit(groupId: String, name: String, keep: List<GroupMember>, add: List<Friend>): Boolean {
        val old = store.group(groupId)?.roster ?: return false
        val me = old.members.firstOrNull { it.noiseKeyHex == myKeyHex() } ?: return false
        val members = (listOf(me) + keep.filter { it.noiseKeyHex != me.noiseKeyHex } + add.map { memberFromFriend(it) }).distinctBy { it.noiseKeyHex }
        val roster = store.edit(groupId, name, members, System.currentTimeMillis()) ?: return false
        sendRoster(roster, (old.members + roster.members).distinctBy { it.noiseKeyHex })
        return true
    }

    fun sendText(groupId: String, text: String): Boolean {
        val m = store.addOutgoing(groupId, UUID.randomUUID().toString(), GroupMessage.TEXT, text.trim(), System.currentTimeMillis()) ?: return false
        val g = store.group(groupId) ?: return false
        store.recipients(groupId).forEach { deliver(g, m, it, allowInternet = true) }
        return true
    }

    fun leave(groupId: String) {
        val (bye, to) = store.leave(groupId, UUID.randomUUID().toString(), System.currentTimeMillis()) ?: return
        to.forEach { send(bye, it) }
    }

    fun setOpen(groupId: String?) {
        _open.value = groupId
        if (groupId != null) { markSeen(groupId); app?.let { GroupNotifier.cancel(it, groupId) } }
    }

    private fun markSeen(groupId: String) {
        val g = store.group(groupId) ?: return
        store.markSeen(groupId).forEach { (author, id) ->
            g.roster.members.firstOrNull { it.noiseKeyHex == author }?.let { send(GroupReceipt(groupId, id, read = true, atMs = System.currentTimeMillis()), it) }
        }
    }

    /** Called for every group payload that passed [MoshInbox]. */
    fun onReceived(context: Context, fromPeerID: String, p: MoshPayload) {
        if (!isInitialised) return
        val fromKey = MoshRuntime.friends.friends().firstOrNull { it.peerID == fromPeerID }?.noiseKeyHex
            ?: memberByPeer(fromPeerID)?.noiseKeyHex ?: return
        val isFriend = MoshRuntime.friends.isFriend(fromPeerID)
        when (p) {
            is GroupRoster -> if (store.applyRoster(fromKey, isFriend, p)) {
                // A roster can unlock messages that came early: acknowledge those.
                store.group(p.groupId)?.messages?.filter { it.status.isEmpty() && it.fromKeyHex != myKeyHex() }?.forEach { m ->
                    p.members.firstOrNull { it.noiseKeyHex == m.fromKeyHex }?.let { send(GroupReceipt(p.groupId, m.id, false, System.currentTimeMillis()), it) }
                }
                if (_open.value == p.groupId) markSeen(p.groupId)
            }
            is GroupMessage -> {
                val wasNew = store.group(p.groupId)?.messages?.none { it.id == p.messageId && it.fromKeyHex == fromKey } ?: true
                if (store.applyMessage(fromKey, p, System.currentTimeMillis())) {
                    val sender = store.group(p.groupId)?.roster?.members?.firstOrNull { it.noiseKeyHex == fromKey } ?: return
                    send(GroupReceipt(p.groupId, p.messageId, read = false, atMs = System.currentTimeMillis()), sender)
                    if (_open.value == p.groupId) markSeen(p.groupId)
                    else if (wasNew && p.kind == GroupMessage.TEXT && store.group(p.groupId)?.muted != true) GroupNotifier.show(context, store.group(p.groupId) ?: return, sender.name, p.text)
                }
            }
            is GroupReceipt -> store.applyReceipt(fromKey, p)
            else -> Unit
        }
    }

    /** Every minute: retry undelivered messages over Bluetooth (the internet copy went once). */
    fun tick() {
        if (!isInitialised) return
        store.undelivered(System.currentTimeMillis() - RETRY_FOR_MS).forEach { (g, m, member) ->
            // They may not have the group yet: the roster goes first (they ignore repeats).
            if (g.roster.creatorKeyHex == myKeyHex()) send(g.roster, member)
            deliver(g, GroupMessage(g.id, m.id, m.kind, m.text, m.sentAtMs), member, allowInternet = m.status[member.noiseKeyHex]?.sentOverInternet != true)
        }
    }

    fun wipe() { if (isInitialised) store.wipe() }

    private fun sendRoster(r: GroupRoster, to: List<GroupMember>) {
        val me = myKeyHex()
        to.filter { it.noiseKeyHex != me }.forEach { send(r, it) }
    }

    private fun deliver(g: Group, m: GroupMessage, member: GroupMember, allowInternet: Boolean) {
        val (mesh, net) = send(m, member, allowInternet)
        if (mesh || net) store.markSent(g.id, m.messageId, member.noiseKeyHex, System.currentTimeMillis(), overInternet = net)
    }

    private fun send(p: MoshPayload, to: GroupMember, allowInternet: Boolean = true): Pair<Boolean, Boolean> {
        val peer = peerOf(to) ?: return false to false
        val t = transport ?: return false to false
        return runCatching { t(p, peer, to, allowInternet) }.onFailure { MoshMonitor.failed("group_send_failed", it) }.getOrDefault(false to false)
    }
}
