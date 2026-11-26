package com.bitchat.android.mosh

import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Synthetic keys and bytes only (AGENTS.md privacy rules). The "JPEGs" are just the magic bytes.
class ProfilePhotoTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01)
    private val other = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x02)
    private val priya = "b".repeat(64)
    private val jo = "c".repeat(64)

    private class Mem : PhotoPersistence {
        val photos = HashMap<String, ProfilePhoto>(); val sent = HashMap<String, Pair<Long, Long>>()
        override fun loadPhotos() = HashMap(photos)
        override fun savePhoto(key: String, p: ProfilePhoto?) { if (p == null) photos.remove(key) else photos[key] = p }
        override fun loadSent() = HashMap(sent)
        override fun saveSent(key: String, sent: Pair<Long, Long>?) { if (sent == null) this.sent.remove(key) else this.sent[key] = sent }
    }

    private var now = 1_000L
    private fun book(p: PhotoPersistence? = null, decodes: (ByteArray) -> Boolean = { true }) = ProfilePhotoBook({ now }, decodes, p)

    @Test fun photo_golden_bytes() {
        val hex = MoshCodec.encode(ProfilePhoto(0x0102030405060708, jpeg)).data.joinToString("") { "%02x".format(it) }
        assertEquals("01" + "0102030405060708" + "0004" + "ffd8ff01", hex)
        val removed = MoshCodec.encode(ProfilePhoto(1, ByteArray(0)))
        assertEquals(NoisePayloadType.PROFILE_PHOTO, removed.type)
        assertEquals("01" + "0000000000000001" + "0000", removed.data.joinToString("") { "%02x".format(it) })
    }

    @Test fun photo_round_trips() {
        val p = ProfilePhoto(42, jpeg)
        assertEquals(p, MoshCodec.decode(MoshCodec.encode(p)))
    }

    @Test fun bad_photos_are_rejected() {
        // Not a JPEG.
        assertNull(MoshCodec.decode(MoshCodec.encodeUnchecked(ProfilePhoto(1, byteArrayOf(1, 2, 3)))))
        // Too big.
        val big = ByteArray(MoshCodec.MAX_PHOTO_BYTES + 1).also { jpeg.copyInto(it) }
        assertNull(MoshCodec.decode(MoshCodec.encodeUnchecked(ProfilePhoto(1, big))))
        // No time.
        assertNull(MoshCodec.decode(MoshCodec.encodeUnchecked(ProfilePhoto(0, jpeg))))
        // Length says more than is there, and trailing bytes.
        assertNull(MoshCodec.decode(NoisePayload(NoisePayloadType.PROFILE_PHOTO, byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 9, -1, -40, -1))))
        val ok = MoshCodec.encode(ProfilePhoto(1, jpeg)).data
        assertNull(MoshCodec.decode(NoisePayload(NoisePayloadType.PROFILE_PHOTO, ok + byteArrayOf(0))))
    }

    @Test fun photos_are_friend_only() {
        val friends = object : FriendRegistry {
            override fun status(peerID: String) = if (peerID == "friend") FriendStatus.FRIEND else FriendStatus.NOT_FRIEND
            override fun friends() = emptyList<Friend>()
            override fun displayName(peerID: String): String? = null
        }
        var got: MoshPayload? = null
        val payload = MoshCodec.encode(ProfilePhoto(5, jpeg))
        assertFalse(MoshInbox.receive("stranger", payload, friends) { _, p -> got = p })
        assertNull(got)
        assertTrue(MoshInbox.receive("friend", payload, friends) { _, p -> got = p })
        assertEquals(ProfilePhoto(5, jpeg), got)
    }

    @Test fun newer_photo_wins_and_removal_sticks() {
        val b = book()
        assertTrue(b.applyFriend(priya, ProfilePhoto(10, jpeg)))
        assertFalse(b.applyFriend(priya, ProfilePhoto(10, other)))
        assertFalse(b.applyFriend(priya, ProfilePhoto(9, other)))
        assertArrayEquals(jpeg, b.photos.value[priya])
        assertTrue(b.applyFriend(priya, ProfilePhoto(11, ByteArray(0))))
        assertNull(b.photos.value[priya])
        // An older copy arriving late does not bring it back.
        assertFalse(b.applyFriend(priya, ProfilePhoto(10, jpeg)))
        assertNull(b.photos.value[priya])
    }

    @Test fun photos_that_do_not_decode_or_come_from_the_future_are_dropped() {
        val b = book(decodes = { false })
        assertFalse(b.applyFriend(priya, ProfilePhoto(10, jpeg)))
        val c = book()
        assertFalse(c.applyFriend(priya, ProfilePhoto(now + ProfilePhotoBook.MAX_FUTURE_MS + 1, jpeg)))
        assertFalse(c.applyFriend(ProfilePhotoBook.ME, ProfilePhoto(10, jpeg)))
        assertTrue(c.photos.value.isEmpty())
    }

    @Test fun my_photo_goes_once_per_change_then_twice_a_day() {
        val b = book()
        assertTrue(b.due(listOf(priya, jo)).isEmpty()) // no photo: nothing to send
        val p = assertNotNullAndGet(b.setMine(jpeg))
        assertEquals(listOf(priya, jo), b.due(listOf(priya, jo)))
        b.markSent(priya, p.updatedAtMs)
        assertEquals(listOf(jo), b.due(listOf(priya, jo)))
        now += ProfilePhotoBook.RESEND_MS
        assertEquals(listOf(priya, jo), b.due(listOf(priya, jo)))
        b.markSent(priya, p.updatedAtMs)
        // A change goes out again at once, even to friends who just got the old one.
        val removed = assertNotNullAndGet(b.removeMine())
        assertTrue(removed.jpeg.isEmpty() && removed.updatedAtMs > p.updatedAtMs)
        assertEquals(listOf(priya, jo), b.due(listOf(priya, jo)))
        assertNull(b.photos.value[ProfilePhotoBook.ME])
        assertNull(b.removeMine()) // already removed
    }

    @Test fun my_new_photo_is_newer_even_if_the_clock_went_back() {
        val b = book()
        val first = assertNotNullAndGet(b.setMine(jpeg))
        now -= 500
        assertTrue(assertNotNullAndGet(b.setMine(other)).updatedAtMs > first.updatedAtMs)
    }

    @Test fun kept_across_restarts_and_wiped() {
        val mem = Mem()
        val b = book(mem)
        b.setMine(jpeg); b.applyFriend(priya, ProfilePhoto(10, other)); b.markSent(priya, b.mine()!!.updatedAtMs)
        val again = book(mem)
        assertArrayEquals(jpeg, again.photos.value[ProfilePhotoBook.ME])
        assertArrayEquals(other, again.photos.value[priya])
        assertTrue(again.due(listOf(priya)).isEmpty())
        again.forget(priya)
        assertNull(again.photos.value[priya]); assertEquals(listOf(priya), again.due(listOf(priya)))
        again.wipe()
        assertTrue(mem.photos.isEmpty() && mem.sent.isEmpty() && again.photos.value.isEmpty())
    }

    private fun <T> assertNotNullAndGet(v: T?): T { assertNotNull(v); return v!! }
}
