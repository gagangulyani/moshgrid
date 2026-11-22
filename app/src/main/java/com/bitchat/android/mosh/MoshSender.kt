package com.bitchat.android.mosh

import com.bitchat.android.model.NoisePayload

enum class MoshRoute { MESH, NOSTR, RATE_LIMITED, NONE }

/**
 * Chooses a path for a MoshGrid payload: mesh first, Nostr when the mesh cannot reach the friend.
 *
 * Locations over Nostr are throttled: at most once per [NOSTR_LOCATION_MIN_INTERVAL_MS] per friend,
 * and not at all while the friend was reachable on the mesh within [MESH_GRACE_MS]. Every Nostr DM
 * is a gift-wrapped event and public relays rate-limit, so ten friends every 2 minutes would be
 * 300 events an hour. Help and meeting points are never throttled.
 */
class MoshSender(
    private val clock: () -> Long,
    private val sendMesh: (NoisePayload, String) -> Boolean,
    private val sendNostr: (NoisePayload, String) -> Unit,
    private val internetAllowed: () -> Boolean,
    private val lastMeshSeenMs: (String) -> Long?,
) {
    private val lastNostrLocationMs = HashMap<String, Long>()

    @Synchronized
    fun send(payload: MoshPayload, toPeerID: String): MoshRoute {
        val encoded = MoshCodec.encode(payload)
        if (sendMesh(encoded, toPeerID)) return MoshRoute.MESH
        if (!internetAllowed()) return MoshRoute.NONE
        val now = clock()
        if (payload is LocationUpdate) {
            val seen = lastMeshSeenMs(toPeerID)
            if (seen != null && now - seen <= MESH_GRACE_MS) return MoshRoute.NONE
            val last = lastNostrLocationMs[toPeerID]
            if (last != null && now - last < NOSTR_LOCATION_MIN_INTERVAL_MS) return MoshRoute.RATE_LIMITED
            lastNostrLocationMs[toPeerID] = now
        }
        sendNostr(encoded, toPeerID)
        return MoshRoute.NOSTR
    }

    companion object {
        const val NOSTR_LOCATION_MIN_INTERVAL_MS = 300_000L
        const val MESH_GRACE_MS = 120_000L
    }
}
