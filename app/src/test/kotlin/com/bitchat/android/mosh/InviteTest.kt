package com.bitchat.android.mosh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Synthetic keys and names only (AGENTS.md privacy rules).
class InviteTest {
    private fun key(c: Char) = c.toString().repeat(64)
    private val me = key('a'); private val meNostr = key('1')
    private val priya = key('b'); private val priyaNostr = key('2')
    private val token = ByteArray(16) { it.toByte() }
    private var now = 1_000_000L
    private var nextRandom = 0
    private fun book() = InviteBook({ now }, { n -> ByteArray(n) { (nextRandom + it).toByte() }.also { nextRandom += 100 } })

    @Test fun link_golden_and_round_trip() {
        val link = InviteLink(me, meNostr, token, 0x01020304, "Ana")
        val bytes = java.util.Base64.getUrlDecoder().decode(link.encode())
        val hex = bytes.joinToString("") { "%02x".format(it) }
        assertEquals("01" + me + meNostr + "000102030405060708090a0b0c0d0e0f" + "0000000001020304" + "03" + "416e61", hex)
        assertEquals(link, InviteLink.parse(link.url()))
        assertEquals(link, InviteLink.parse("moshgrid://add#" + link.encode()))
        assertEquals(link, InviteLink.parse(link.encode()))
        assertTrue(link.url().startsWith("https://moshgrid.vercel.app/add#"))
    }

    @Test fun bad_links_are_rejected() {
        val good = InviteLink(me, meNostr, token, 10, "Ana").encode()
        assertNull(InviteLink.parse("https://moshgrid.vercel.app/add#"))
        assertNull(InviteLink.parse("not a link"))
        assertNull(InviteLink.parse(good.dropLast(4)))                                       // cut short
        assertNull(InviteLink.parse(InviteLink(me, meNostr, token, 10, " ").encode()))      // blank name
        assertNull(InviteLink.parse(InviteLink("0".repeat(64), meNostr, token, 10, "Ana").encode())) // empty key
        assertNull(InviteLink.parse(InviteLink(me, meNostr, token, 0, "Ana").encode()))     // no expiry
        val extra = java.util.Base64.getUrlDecoder().decode(good) + byteArrayOf(0)
        assertNull(InviteLink.parse(java.util.Base64.getUrlEncoder().encodeToString(extra)))
        val v2 = java.util.Base64.getUrlDecoder().decode(good).also { it[0] = 2 }
        assertNull(InviteLink.parse(java.util.Base64.getUrlEncoder().encodeToString(v2)))
    }

    @Test fun invite_payload_golden_and_rejects() {
        val p = FriendInvite(FriendInvite.REQUEST, token, priya, priyaNostr, "Priya", 0x0102030405060708)
        val hex = MoshCodec.encode(p).data.joinToString("") { "%02x".format(it) }
        assertEquals("01" + "00" + "000102030405060708090a0b0c0d0e0f" + priya + priyaNostr + "05" + "5072697961" + "0102030405060708", hex)
        assertEquals(p, MoshCodec.decode(MoshCodec.encode(p)))
        assertNull(MoshCodec.decode(MoshCodec.encodeUnchecked(FriendInvite(2, token, priya, priyaNostr, "Priya", 1))))
        assertNull(MoshCodec.decode(MoshCodec.encodeUnchecked(FriendInvite(0, token, priya, priyaNostr, "", 1))))
        assertNull(MoshCodec.decode(MoshCodec.encodeUnchecked(FriendInvite(0, token, priya, priyaNostr, "Priya", 0))))
    }

    @Test fun invites_never_pass_the_friend_gate() {
        val friends = object : FriendRegistry {
            override fun status(peerID: String) = FriendStatus.NOT_FRIEND
            override fun friends() = emptyList<Friend>()
            override fun displayName(peerID: String): String? = null
        }
        val payload = MoshCodec.encode(FriendInvite(FriendInvite.REQUEST, token, priya, priyaNostr, "Priya", 1))
        assertFalse(MoshInbox.receive("stranger", payload, friends) { _, _ -> })
    }

    private fun request(t: ByteArray, noise: String = priya, nostr: String = priyaNostr) =
        FriendInvite(FriendInvite.REQUEST, t, noise, nostr, "Priya", now)

    @Test fun request_needs_my_current_token_and_its_signer() {
        val b = book()
        val (t, _) = b.currentToken()
        assertFalse(b.onRequest(priyaNostr, request(ByteArray(16)), me) { false })      // wrong token
        assertFalse(b.onRequest(key('9'), request(t), me) { false })                     // signed by someone else
        assertFalse(b.onRequest(meNostr, request(t, noise = me, nostr = meNostr), me) { false }) // myself
        assertFalse(b.onRequest(priyaNostr, request(t), me) { true })                    // already a friend
        assertTrue(b.onRequest(priyaNostr, request(t), me) { false })
        assertFalse(b.onRequest(priyaNostr, request(t), me) { false })                   // repeat: kept once
        assertEquals(1, b.requests.value.size)
        assertTrue(b.requests.value.single().token.contentEquals(t))
    }

    @Test fun expired_or_replaced_links_stop_working() {
        val b = book()
        val (t, _) = b.currentToken()
        b.newToken()
        assertFalse(b.onRequest(priyaNostr, request(t), me) { false })
        val c = book()
        val (t2, _) = c.currentToken()
        now += InviteBook.LINK_VALID_MS + 1
        assertFalse(c.onRequest(priyaNostr, request(t2), me) { false })
        // A token close to running out is replaced, so a link handed out lasts at least a day.
        val d = book()
        val (t3, _) = d.currentToken()
        now += InviteBook.LINK_VALID_MS - InviteBook.MIN_LEFT_MS + 1
        assertFalse(d.currentToken().first.contentEquals(t3))
    }

    @Test fun too_many_requests_are_refused() {
        val b = book()
        val (t, _) = b.currentToken()
        repeat(InviteBook.MAX_REQUESTS) { i ->
            val k = "%064x".format(i + 1)
            assertTrue(b.onRequest(k, request(t, noise = k, nostr = k), me) { false })
        }
        assertFalse(b.onRequest(priyaNostr, request(t), me) { false })
    }

    @Test fun accept_only_answers_a_request_i_sent() {
        val b = book()
        val link = InviteLink(priya, priyaNostr, token, (now + 60_000) / 1000, "Priya")
        val accept = FriendInvite(FriendInvite.ACCEPT, token, priya, priyaNostr, "Priya", now)
        assertNull(b.onAccept(priyaNostr, accept))                                       // nothing sent yet
        assertNotNull(b.send(link, me))
        assertNull(b.onAccept(key('9'), accept))                                         // wrong signer
        assertNull(b.onAccept(priyaNostr, FriendInvite(FriendInvite.ACCEPT, ByteArray(16), priya, priyaNostr, "Priya", now)))
        assertNull(b.onAccept(priyaNostr, FriendInvite(FriendInvite.ACCEPT, token, key('c'), priyaNostr, "Priya", now)))
        assertNotNull(b.onAccept(priyaNostr, accept))
        assertNull(b.onAccept(priyaNostr, accept))                                       // used up
    }

    @Test fun cannot_send_with_expired_or_own_link() {
        val b = book()
        assertNull(b.send(InviteLink(priya, priyaNostr, token, (now - 1) / 1000, "Priya"), me))
        assertNull(b.send(InviteLink(me, meNostr, token, (now + 60_000) / 1000, "Ana"), me))
    }

    @Test fun unanswered_requests_repeat_until_accepted_or_expired() {
        val b = book()
        val link = InviteLink(priya, priyaNostr, token, (now + 2 * 60 * 60_000) / 1000, "Priya")
        assertNotNull(b.send(link, me))
        assertTrue(b.dueResends().isEmpty())                                             // just sent
        now += InviteBook.RESEND_MS
        assertEquals(1, b.dueResends().size)
        assertTrue(b.dueResends().isEmpty())                                             // not twice in a row
        now += InviteBook.RESEND_MS
        assertEquals(1, b.dueResends().size)
        assertNotNull(b.onAccept(priyaNostr, FriendInvite(FriendInvite.ACCEPT, token, priya, priyaNostr, "Priya", now)))
        now += InviteBook.RESEND_MS
        assertTrue(b.dueResends().isEmpty())                                             // answered
        val late = InviteLink(key('c'), key('3'), token, (now + 60 * 60_000) / 1000, "Jo")
        assertNotNull(b.send(late, me))
        now += 2 * InviteBook.RESEND_MS + 1
        assertTrue(b.dueResends().isEmpty())                                             // their link ran out
    }
}
