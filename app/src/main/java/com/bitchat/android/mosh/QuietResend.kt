package com.bitchat.android.mosh

/**
 * Low-traffic re-sending for long-lived state (late Help cancels, meeting points): Bluetooth every
 * time; the internet only for a friend Bluetooth cannot reach, and only once per item while the
 * app runs (an earlier internet copy may have been lost with a restart before it left the phone).
 */
class QuietResend(
    private val friendIds: () -> List<String>,
    private val send: (MoshPayload, String, Boolean) -> MoshRoute,
) {
    private val sentOverInternet = HashSet<String>()

    /** [itemKey] identifies the item (for example an alert ID), so each goes over the internet once. */
    @Synchronized
    fun send(p: MoshPayload, itemKey: String): Map<String, MoshRoute> = friendIds().associateWith { friend ->
        val mesh = send(p, friend, true)
        if (mesh != MoshRoute.NONE) return@associateWith mesh
        val key = "$friend|$itemKey"
        if (key in sentOverInternet) return@associateWith MoshRoute.NONE
        val r = send(p, friend, false)
        if (r == MoshRoute.NOSTR || r == MoshRoute.QUEUED || r == MoshRoute.MESH_AND_NOSTR) sentOverInternet += key
        r
    }
}
