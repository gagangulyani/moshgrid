package com.bitchat.android.mosh

import com.bitchat.android.model.NoisePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoshRoutingTest {
    private val loc = LocationUpdate(0.5, 0.25, 10f, 1L, 50)
    private val help = HelpAlert("h", 0.5, 0.25, 10f, "", 1L)

    private class Friends(private val ids: Set<String>) : FriendRegistry {
        override fun status(peerID: String) = if (peerID in ids) FriendStatus.FRIEND else FriendStatus.NOT_FRIEND
        override fun friends() = emptyList<Friend>()
        override fun displayName(peerID: String): String? = null
    }

    @Test
    fun mosh_payload_from_non_friend_is_dropped() {
        var got: MoshPayload? = null
        val accepted = MoshInbox.receive("stranger", MoshCodec.encode(loc), Friends(setOf("friend"))) { _, p -> got = p }
        assertFalse(accepted)
        assertEquals(null, got)
    }

    @Test
    fun mosh_payload_from_friend_reaches_sink() {
        var got: MoshPayload? = null
        assertTrue(MoshInbox.receive("friend", MoshCodec.encode(loc), Friends(setOf("friend"))) { _, p -> got = p })
        assertEquals(loc, got)
    }

    private class Harness(var now: Long = 0L, var meshUp: Boolean = false, var internet: Boolean = true, var meshSeenAt: Long? = null) {
        val nostrSent = mutableListOf<Pair<NoisePayload, String>>()
        val sender = MoshSender(
            clock = { now },
            sendMesh = { _, _ -> meshUp },
            sendNostr = { p, to -> nostrSent += p to to },
            internetAllowed = { internet },
            lastMeshSeenMs = { meshSeenAt },
        )
    }

    @Test
    fun mesh_is_tried_first() {
        val h = Harness(meshUp = true)
        assertEquals(MoshRoute.MESH, h.sender.send(loc, "f"))
        assertTrue(h.nostrSent.isEmpty())
    }

    @Test
    fun internet_off_never_uses_nostr() {
        val h = Harness(internet = false)
        assertEquals(MoshRoute.NONE, h.sender.send(help, "f"))
        assertTrue(h.nostrSent.isEmpty())
    }

    @Test
    fun location_over_nostr_is_limited_to_once_per_5_minutes_per_friend() {
        val h = Harness()
        assertEquals(MoshRoute.NOSTR, h.sender.send(loc, "f"))
        h.now = 299_999
        assertEquals(MoshRoute.RATE_LIMITED, h.sender.send(loc, "f"))
        assertEquals(MoshRoute.NOSTR, h.sender.send(loc, "other"))
        h.now = 300_000
        assertEquals(MoshRoute.NOSTR, h.sender.send(loc, "f"))
    }

    @Test
    fun location_waits_for_mesh_when_friend_was_on_mesh_in_last_2_minutes() {
        val h = Harness(now = 200_000, meshSeenAt = 100_000)
        assertEquals(MoshRoute.NONE, h.sender.send(loc, "f"))
        h.now = 220_001
        assertEquals(MoshRoute.NOSTR, h.sender.send(loc, "f"))
    }

    @Test
    fun help_is_never_rate_limited() {
        val h = Harness()
        assertEquals(MoshRoute.NOSTR, h.sender.send(help, "f"))
        assertEquals(MoshRoute.NOSTR, h.sender.send(help, "f"))
    }
}
