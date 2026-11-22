package com.bitchat.android.mosh

import com.bitchat.android.favorites.FavoriteRelationship
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class FriendRegistryTest {
    private val keyA = ByteArray(32) { 0x0A }
    private val fpA = "fp-a"

    private fun rel(isFavorite: Boolean, theyFavoritedUs: Boolean, name: String = "Priya") =
        FavoriteRelationship(keyA, null, name, isFavorite, theyFavoritedUs, Date(0), Date(0))

    private fun registry(relation: FavoriteRelationship?, verified: Set<String>) = DefaultFriendRegistry(
        relationships = { listOfNotNull(relation) },
        fingerprintOfKey = { fpA },
        peerIdOfKey = { "peer-a" },
        verifiedFingerprints = { verified },
    )

    @Test
    fun favourite_without_verified_fingerprint_is_not_friend() {
        assertFalse(registry(rel(true, true), emptySet()).isFriend("peer-a"))
    }

    @Test
    fun verified_fingerprint_without_favourite_is_not_friend() {
        assertFalse(registry(rel(false, true), setOf(fpA)).isFriend("peer-a"))
        assertFalse(registry(null, setOf(fpA)).isFriend("peer-a"))
    }

    @Test
    fun favourite_and_verified_is_friend() {
        val r = registry(rel(true, true), setOf(fpA))
        assertTrue(r.isFriend("peer-a"))
        assertEquals(FriendStatus.FRIEND, r.status("peer-a"))
        assertEquals(listOf("peer-a"), r.friends().map { it.peerID })
    }

    @Test
    fun one_way_scan_shows_waiting_state() {
        val r = registry(rel(true, false), setOf(fpA))
        assertEquals(FriendStatus.WAITING_FOR_THEM, r.status("peer-a"))
        assertTrue(r.isFriend("peer-a"))
    }

    @Test
    fun display_name_comes_from_the_qr_record() {
        val r = registry(rel(true, true, name = "Priya"), setOf(fpA))
        assertEquals("Priya", r.displayName("peer-a"))
        assertNull(r.displayName("someone-else"))
    }
}
