package com.bitchat.android.mosh

import com.bitchat.android.model.IdentityAnnouncement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Presence announcements and leave packets are sent in clear, so they must not carry the user's name. */
class AnnouncementPrivacyTest {

    @Test
    fun local_announcement_carries_empty_nickname() {
        val a = IdentityAnnouncement.forLocalPeer(MoshPrivacy.ANNOUNCED_NICKNAME, ByteArray(32) { 1 }, ByteArray(32) { 2 })
        val decoded = IdentityAnnouncement.decode(a.encode()!!)!!
        assertEquals("", decoded.nickname)
    }

    @Test
    fun encoded_announcement_nickname_tlv_has_zero_length() {
        val bytes = IdentityAnnouncement.forLocalPeer(MoshPrivacy.ANNOUNCED_NICKNAME, ByteArray(32) { 1 }, ByteArray(32) { 2 }).encode()!!
        assertEquals(0x01.toByte(), bytes[0]) // NICKNAME TLV type
        assertEquals(0.toByte(), bytes[1])    // length 0
    }

    @Test
    fun leave_payload_is_empty() {
        assertTrue(MoshPrivacy.leavePayload().isEmpty())
    }
}
