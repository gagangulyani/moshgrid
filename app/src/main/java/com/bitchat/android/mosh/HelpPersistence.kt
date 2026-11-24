package com.bitchat.android.mosh

import android.content.SharedPreferences
import android.util.Base64
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType

/** Our own Help: survives the app being killed so repeats and cancel repeats resume on restart. */
data class HelpOutbox(val alertId: String?, val note: String, val cancelledId: String?, val cancelledAtMs: Long)

interface HelpOutboxPersistence {
    fun load(): HelpOutbox?
    fun save(o: HelpOutbox?)
}

/** Friends' Help we hold, and alert IDs already cancelled, so a restart neither loses nor revives one. */
data class ReceivedHelp(val active: Map<String, HelpAlert>, val cancelled: Map<String, Long>)

interface ReceivedHelpPersistence {
    fun load(): ReceivedHelp?
    fun save(r: ReceivedHelp)
}

/** SharedPreferences storage for our own Help. */
class PrefsHelpOutbox(private val prefs: SharedPreferences) : HelpOutboxPersistence {
    override fun load(): HelpOutbox? {
        if (!prefs.contains(K_NOTE)) return null
        return HelpOutbox(
            alertId = prefs.getString(K_ID, null),
            note = prefs.getString(K_NOTE, "") ?: "",
            cancelledId = prefs.getString(K_CANCELLED, null),
            cancelledAtMs = prefs.getLong(K_CANCELLED_AT, 0L),
        )
    }

    override fun save(o: HelpOutbox?) {
        val e = prefs.edit()
        if (o == null) e.remove(K_ID).remove(K_NOTE).remove(K_CANCELLED).remove(K_CANCELLED_AT)
        else e.putString(K_ID, o.alertId).putString(K_NOTE, o.note).putString(K_CANCELLED, o.cancelledId).putLong(K_CANCELLED_AT, o.cancelledAtMs)
        e.apply()
    }

    private companion object {
        const val K_ID = "help_out_id"
        const val K_NOTE = "help_out_note"
        const val K_CANCELLED = "help_out_cancelled"
        const val K_CANCELLED_AT = "help_out_cancelled_at"
    }
}

/**
 * SharedPreferences storage for friends' Help. Alerts are stored in the wire format ([MoshCodec]),
 * not by reflection, so release shrinking cannot change what was saved. Unreadable data loads as nothing.
 */
class PrefsReceivedHelp(private val prefs: SharedPreferences) : ReceivedHelpPersistence {
    override fun load(): ReceivedHelp? = runCatching {
        val active = lines(K_ACTIVE).mapNotNull { line ->
            val (peer, b64) = line.split(" ", limit = 2)
            val h = MoshCodec.decode(NoisePayload(NoisePayloadType.HELP, Base64.decode(b64, Base64.NO_WRAP))) as? HelpAlert
            h?.let { peer to it }
        }.toMap()
        val cancelled = lines(K_CANCELLED).associate { line ->
            val (b64, at) = line.split(" ", limit = 2)
            String(Base64.decode(b64, Base64.NO_WRAP), Charsets.UTF_8) to at.toLong()
        }
        ReceivedHelp(active, cancelled)
    }.getOrNull()

    override fun save(r: ReceivedHelp) {
        val active = r.active.entries.joinToString("\n") { (peer, h) ->
            peer + " " + Base64.encodeToString(MoshCodec.encode(h).data, Base64.NO_WRAP)
        }
        val cancelled = r.cancelled.entries.joinToString("\n") { (id, at) ->
            Base64.encodeToString(id.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) + " " + at
        }
        prefs.edit().putString(K_ACTIVE, active).putString(K_CANCELLED, cancelled).apply()
    }

    private fun lines(key: String) = (prefs.getString(key, "") ?: "").lines().filter { it.isNotBlank() }

    private companion object {
        const val K_ACTIVE = "help_in_active"
        const val K_CANCELLED = "help_in_cancelled"
    }
}

/**
 * Friends' last locations, the meeting point and when each friend was last heard. Saved so that a
 * restart (app or phone) does not lose where a friend was last seen, which matters most when that
 * friend's phone has since died. Kept for [FriendSnapshot.KEEP_MS]; the panic wipe clears it.
 */
data class FriendSnapshot(
    val locations: Map<String, LocationUpdate>,
    val meeting: MeetingPoint?,
    val lastHeard: Map<String, Long>,
    /** True when this phone set [meeting] (only those are re-sent). */
    val meetingIsOwn: Boolean = false,
) {
    companion object { const val KEEP_MS = 12 * 3_600_000L }
}

interface FriendSnapshotPersistence {
    fun load(): FriendSnapshot?
    fun save(s: FriendSnapshot)
}

/** SharedPreferences storage for [FriendSnapshot], in the wire format like [PrefsReceivedHelp]. */
class PrefsFriendSnapshot(private val prefs: SharedPreferences) : FriendSnapshotPersistence {
    override fun load(): FriendSnapshot? = runCatching {
        fun decode(type: NoisePayloadType, b64: String) = MoshCodec.decode(NoisePayload(type, Base64.decode(b64, Base64.NO_WRAP)))
        val locations = lines(K_LOCATIONS).mapNotNull { line ->
            val (peer, b64) = line.split(" ", limit = 2)
            (decode(NoisePayloadType.LOCATION, b64) as? LocationUpdate)?.let { peer to it }
        }.toMap()
        val meeting = prefs.getString(K_MEETING, null)?.takeIf { it.isNotBlank() }
            ?.let { decode(NoisePayloadType.MEETING_POINT, it) as? MeetingPoint }
        val heard = lines(K_HEARD).associate { line -> val (peer, at) = line.split(" ", limit = 2); peer to at.toLong() }
        FriendSnapshot(locations, meeting, heard, prefs.getBoolean(K_MEETING_OWN, false))
    }.getOrNull()

    override fun save(s: FriendSnapshot) {
        prefs.edit()
            .putString(K_LOCATIONS, s.locations.entries.joinToString("\n") { (peer, l) ->
                peer + " " + Base64.encodeToString(MoshCodec.encode(l).data, Base64.NO_WRAP)
            })
            .putString(K_MEETING, s.meeting?.let { Base64.encodeToString(MoshCodec.encode(it).data, Base64.NO_WRAP) } ?: "")
            .putString(K_HEARD, s.lastHeard.entries.joinToString("\n") { (peer, at) -> "$peer $at" })
            .putBoolean(K_MEETING_OWN, s.meetingIsOwn)
            .apply()
    }

    private fun lines(key: String) = (prefs.getString(key, "") ?: "").lines().filter { it.isNotBlank() }

    private companion object {
        const val K_LOCATIONS = "friends_locations"
        const val K_MEETING = "meeting_point"
        const val K_HEARD = "friends_heard"
        const val K_MEETING_OWN = "meeting_point_own"
    }
}
