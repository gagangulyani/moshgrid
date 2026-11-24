package com.bitchat.android.mosh

import com.bitchat.android.model.NoisePayload

/**
 * How a payload left the phone. QUEUED: no friend nearby and no signal right now; it waits on this
 * phone and goes over the internet as soon as a relay is reachable (lost if the app is killed).
 */
enum class MoshRoute { MESH, MESH_AND_NOSTR, NOSTR, QUEUED, RATE_LIMITED, NONE }

/**
 * Chooses a path for a MoshGrid payload: mesh first, Nostr when the mesh cannot reach the friend.
 *
 * Locations over Nostr are throttled: at most once per [NOSTR_LOCATION_MIN_INTERVAL_MS] per friend,
 * and not at all while the friend was reachable on the mesh within [MESH_GRACE_MS]. Every Nostr DM
 * is a gift-wrapped event and public relays rate-limit, so ten friends every 2 minutes would be
 * 300 events an hour. Help and meeting points are never throttled.
 *
 * Help, Help cancels and meeting points also go over Nostr when the mesh accepted them: a mesh
 * "send" has no delivery receipt, and a friend who just walked away still looks connected until
 * the peer entry times out. Receivers apply duplicates once (latest wins, alert IDs).
 */
class MoshSender(
    private val clock: () -> Long,
    private val sendMesh: (NoisePayload, String) -> Boolean,
    /** False when nothing could be sent (for example the friend's internet key is not known yet). */
    private val sendNostr: (NoisePayload, String) -> Boolean,
    private val internetAllowed: () -> Boolean,
    private val lastMeshSeenMs: (String) -> Long?,
    /** True while at least one relay is connected. With no signal, sends wait in the relay queue. */
    private val internetOnline: () -> Boolean = { true },
) {
    private val lastNostrLocationMs = HashMap<String, Long>()
    /** Critical payloads already queued while offline, so repeats do not pile up duplicates. */
    private val queuedOffline = HashSet<String>()

    @Synchronized
    fun send(payload: MoshPayload, toPeerID: String, meshOnly: Boolean = false): MoshRoute {
        val encoded = MoshCodec.encode(payload)
        if (meshOnly) return if (sendMesh(encoded, toPeerID)) MoshRoute.MESH else MoshRoute.NONE
        val critical = payload is HelpAlert || payload is HelpCancel || payload is MeetingPoint
        if (sendMesh(encoded, toPeerID)) {
            com.bitchat.android.mosh.monitor.Counters.shared.inc("packet_sent_mesh")
            if (!critical || !internetAllowed()) return MoshRoute.MESH
            if (!internetOnline()) {
                // Bluetooth carried it. Queue one internet copy for when signal returns, not one per repeat.
                val key = offlineKey(payload, toPeerID) ?: return MoshRoute.MESH
                if (key !in queuedOffline && sendNostr(encoded, toPeerID)) queuedOffline += key
                return MoshRoute.MESH
            }
            queuedOffline.clear()
            if (!sendNostr(encoded, toPeerID)) return MoshRoute.MESH
            com.bitchat.android.mosh.monitor.Counters.shared.inc("packet_sent_nostr")
            return MoshRoute.MESH_AND_NOSTR
        }
        if (!internetAllowed()) return MoshRoute.NONE
        val now = clock()
        val online = internetOnline()
        if (online) queuedOffline.clear()
        if (!online) {
            // A location would be stale by the time signal returns: do not queue it.
            if (payload is LocationUpdate) return MoshRoute.NONE
            val key = offlineKey(payload, toPeerID) ?: return MoshRoute.NONE
            if (key in queuedOffline) return MoshRoute.QUEUED
            if (!sendNostr(encoded, toPeerID)) return MoshRoute.NONE
            queuedOffline += key
            return MoshRoute.QUEUED
        }
        if (payload is LocationUpdate) {
            val seen = lastMeshSeenMs(toPeerID)
            if (seen != null && now - seen <= MESH_GRACE_MS) return MoshRoute.NONE
            val last = lastNostrLocationMs[toPeerID]
            if (last != null && now - last < NOSTR_LOCATION_MIN_INTERVAL_MS) return MoshRoute.RATE_LIMITED
        }
        if (!sendNostr(encoded, toPeerID)) {
            com.bitchat.android.mosh.monitor.Counters.shared.inc("nostr_no_key")
            return MoshRoute.NONE
        }
        if (payload is LocationUpdate) lastNostrLocationMs[toPeerID] = now
        com.bitchat.android.mosh.monitor.Counters.shared.inc("packet_sent_nostr")
        return MoshRoute.NOSTR
    }

    private fun offlineKey(payload: MoshPayload, to: String): String? = to + "|" + when (payload) {
        is HelpAlert -> "h" + payload.alertId
        is HelpCancel -> "c" + payload.alertId
        is MeetingPoint -> "m" + payload.pointId + payload.setAtMs
        else -> return null
    }

    companion object {
        const val NOSTR_LOCATION_MIN_INTERVAL_MS = 300_000L
        const val MESH_GRACE_MS = 120_000L
    }
}
