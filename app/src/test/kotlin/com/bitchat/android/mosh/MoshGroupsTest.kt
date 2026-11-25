package com.bitchat.android.mosh

import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Synthetic keys and names only (AGENTS.md privacy rules).
class MoshGroupsTest {
    private fun key(c: Char) = c.toString().repeat(64)
    private val me = GroupMember(key('a'), key('1'), "Ana")
    private val priya = GroupMember(key('b'), key('2'), "Priya")
    private val jo = GroupMember(key('c'), "", "Jo")
    private fun roster(at: Long = 100, members: List<GroupMember> = listOf(priya, me, jo), creator: String = priya.noiseKeyHex) =
        GroupRoster("g-1", "Camp crew", creator, at, members)

    // ---- wire format ----

    @Test fun roster_message_and_receipt_round_trip() {
        val r = roster()
        val m = GroupMessage("g-1", "m-1", GroupMessage.TEXT, "main stage at 4? 🎶", 200)
        val left = GroupMessage("g-1", "m-2", GroupMessage.LEFT, "", 300)
        val rc = GroupReceipt("g-1", "m-1", read = true, atMs = 400)
        assertEquals(NoisePayloadType.GROUP_ROSTER, MoshCodec.encode(r).type)
        assertEquals(r, MoshCodec.decode(MoshCodec.encode(r)))
        assertEquals(m, MoshCodec.decode(MoshCodec.encode(m)))
        assertEquals(left, MoshCodec.decode(MoshCodec.encode(left)))
        assertEquals(rc, MoshCodec.decode(MoshCodec.encode(rc)))
    }

    /** Golden vector: pins the byte layout in docs/MOSH_GROUPS.md so other clients can match it. */
    @Test fun receipt_golden_bytes() {
        val bytes = MoshCodec.encode(GroupReceipt("g", "m", read = true, atMs = 0x0102030405060708)).data
        val hex = bytes.joinToString("") { "%02x".format(it) }
        assertEquals("01" + "0167" + "016d" + "01" + "0102030405060708", hex)
    }

    @Test fun roster_golden_bytes() {
        val a = "0".repeat(62) + "0a"; val b = "0".repeat(62) + "0b"
        val r = GroupRoster("g", "G", a, 1, listOf(GroupMember(a, "", "A"), GroupMember(b, b, "B")))
        val hex = MoshCodec.encode(r).data.joinToString("") { "%02x".format(it) }
        assertEquals("01" + "0167" + "0147" + a + "0000000000000001" + "02" +
            a + "00" + "0141" + b + "20" + b + "0142", hex)
    }

    @Test fun bad_group_payloads_are_rejected() {
        // Creator not a member.
        assertNull(MoshCodec.decode(MoshCodec.encodeUnchecked(roster(members = listOf(me, jo)))))
        // One member only.
        assertNull(MoshCodec.decode(MoshCodec.encodeUnchecked(roster(members = listOf(priya)))))
        // Duplicate member.
        assertNull(MoshCodec.decode(MoshCodec.encodeUnchecked(roster(members = listOf(priya, me, me)))))
        // Unknown kind, blank text, text on a leave.
        assertNull(MoshCodec.decode(MoshCodec.encodeUnchecked(GroupMessage("g", "m", 7, "x", 1))))
        assertNull(MoshCodec.decode(MoshCodec.encodeUnchecked(GroupMessage("g", "m", GroupMessage.TEXT, "  ", 1))))
        assertNull(MoshCodec.decode(MoshCodec.encodeUnchecked(GroupMessage("g", "m", GroupMessage.LEFT, "hi", 1))))
        // Too long.
        assertNull(MoshCodec.decode(MoshCodec.encodeUnchecked(GroupMessage("g", "m", GroupMessage.TEXT, "x".repeat(501), 1))))
        // Trailing bytes.
        val ok = MoshCodec.encode(GroupReceipt("g", "m", false, 1))
        assertNull(MoshCodec.decode(NoisePayload(ok.type, ok.data + 0)))
        // Truncated.
        val r = MoshCodec.encode(roster())
        assertNull(MoshCodec.decode(NoisePayload(r.type, r.data.copyOf(r.data.size - 3))))
    }

    @Test fun thirteen_members_cannot_be_encoded() {
        val many = (0 until 13).map { GroupMember("%064x".format(it + 1), "", "P$it") }
        assertNull(MoshCodec.decode(MoshCodec.encodeUnchecked(GroupRoster("g", "Big", many[0].noiseKeyHex, 1, many))))
    }

    // ---- store rules ----

    private fun store() = GroupStore({ me.noiseKeyHex })

    @Test fun roster_only_from_creator_who_is_a_friend_and_includes_me() {
        val s = store()
        assertFalse("not a friend", s.applyRoster(priya.noiseKeyHex, fromIsFriend = false, roster()))
        assertFalse("not the creator", s.applyRoster(jo.noiseKeyHex, fromIsFriend = true, roster()))
        assertFalse("I'm not in it", s.applyRoster(priya.noiseKeyHex, true, roster(members = listOf(priya, jo))))
        assertTrue(s.applyRoster(priya.noiseKeyHex, true, roster()))
        assertFalse("older or same version", s.applyRoster(priya.noiseKeyHex, true, roster(at = 100)))
        assertTrue(s.applyRoster(priya.noiseKeyHex, true, roster(at = 101).copy(name = "Crew")))
        assertEquals("Crew", s.group("g-1")!!.roster.name)
    }

    @Test fun creator_cannot_be_replaced() {
        val s = store()
        s.applyRoster(priya.noiseKeyHex, true, roster())
        assertFalse(s.applyRoster(jo.noiseKeyHex, true, roster(at = 500, creator = jo.noiseKeyHex)))
    }

    @Test fun removed_from_roster_closes_the_group() {
        val s = store()
        s.applyRoster(priya.noiseKeyHex, true, roster())
        s.applyRoster(priya.noiseKeyHex, true, roster(at = 200, members = listOf(priya, jo)))
        assertTrue(s.group("g-1")!!.closed)
        assertTrue(s.recipients("g-1").isEmpty())
    }

    @Test fun messages_only_from_members_and_deduplicated() {
        val s = store()
        s.applyRoster(priya.noiseKeyHex, true, roster())
        val m = GroupMessage("g-1", "m-1", GroupMessage.TEXT, "hi", 10)
        assertFalse(s.applyMessage(key('d'), m, 11))
        assertTrue(s.applyMessage(jo.noiseKeyHex, m, 11))
        assertTrue("repeat is acked again", s.applyMessage(jo.noiseKeyHex, m, 12))
        assertEquals(1, s.group("g-1")!!.messages.size)
        assertEquals(1, s.group("g-1")!!.unread)
    }

    @Test fun message_before_roster_waits_then_applies() {
        val s = store()
        assertFalse(s.applyMessage(jo.noiseKeyHex, GroupMessage("g-1", "m-1", GroupMessage.TEXT, "early", 10), 11))
        s.applyRoster(priya.noiseKeyHex, true, roster())
        assertEquals("early", s.group("g-1")!!.messages.single().text)
    }

    @Test fun orphan_from_non_member_is_dropped_when_roster_arrives() {
        val s = store()
        s.applyMessage(key('d'), GroupMessage("g-1", "m-1", GroupMessage.TEXT, "spoof", 10), 11)
        s.applyRoster(priya.noiseKeyHex, true, roster())
        assertTrue(s.group("g-1")!!.messages.isEmpty())
    }

    @Test fun receipts_move_ticks_forward_only_and_per_member() {
        val s = store()
        val r = s.create("g-2", "Us", me, listOf(priya, jo), 1)
        assertEquals(me.noiseKeyHex, r.creatorKeyHex)
        val m = s.addOutgoing("g-2", "m-1", GroupMessage.TEXT, "where are you", 5)!!
        fun mine() = s.group("g-2")!!.messages.single()
        assertEquals(GroupDelivery.WAITING, overallDelivery(mine().status))
        s.markSent("g-2", m.messageId, priya.noiseKeyHex, 6, overInternet = false)
        assertEquals(GroupDelivery.SENT, overallDelivery(mine().status))
        s.applyReceipt(priya.noiseKeyHex, GroupReceipt("g-2", "m-1", read = true, atMs = 7))
        s.applyReceipt(jo.noiseKeyHex, GroupReceipt("g-2", "m-1", read = false, atMs = 8))
        assertEquals(GroupDelivery.DELIVERED, overallDelivery(mine().status))
        // A late "delivered" never downgrades a read.
        s.applyReceipt(priya.noiseKeyHex, GroupReceipt("g-2", "m-1", read = false, atMs = 9))
        assertEquals(GroupDelivery.READ, mine().status[priya.noiseKeyHex]!!.state)
        assertEquals(7L, mine().status[priya.noiseKeyHex]!!.atMs)
        s.applyReceipt(jo.noiseKeyHex, GroupReceipt("g-2", "m-1", read = true, atMs = 10))
        assertEquals(GroupDelivery.READ, overallDelivery(mine().status))
        // Receipt from outside the group is ignored.
        assertFalse(s.applyReceipt(key('d'), GroupReceipt("g-2", "m-1", read = true, atMs = 11)))
    }

    @Test fun undelivered_lists_who_still_needs_it() {
        val s = store()
        s.create("g-2", "Us", me, listOf(priya, jo), 1)
        s.addOutgoing("g-2", "m-1", GroupMessage.TEXT, "hi", 5)
        s.applyReceipt(priya.noiseKeyHex, GroupReceipt("g-2", "m-1", false, 6))
        assertEquals(listOf(jo.noiseKeyHex), s.undelivered(0).map { it.third.noiseKeyHex })
    }

    @Test fun seen_returns_read_receipts_due_once() {
        val s = store()
        s.applyRoster(priya.noiseKeyHex, true, roster())
        s.applyMessage(jo.noiseKeyHex, GroupMessage("g-1", "m-1", GroupMessage.TEXT, "hi", 10), 11)
        assertEquals(listOf(jo.noiseKeyHex to "m-1"), s.markSeen("g-1"))
        assertTrue(s.markSeen("g-1").isEmpty())
        assertEquals(0, s.group("g-1")!!.unread)
    }

    @Test fun someone_leaving_stops_sends_to_them() {
        val s = store()
        s.applyRoster(priya.noiseKeyHex, true, roster())
        s.applyMessage(jo.noiseKeyHex, GroupMessage("g-1", "bye", GroupMessage.LEFT, "", 10), 11)
        assertEquals(listOf(priya.noiseKeyHex), s.recipients("g-1").map { it.noiseKeyHex })
    }

    @Test fun leaving_closes_and_says_goodbye_to_the_rest() {
        val s = store()
        s.applyRoster(priya.noiseKeyHex, true, roster())
        val (bye, to) = s.leave("g-1", "bye", 20)!!
        assertEquals(GroupMessage.LEFT, bye.kind)
        assertEquals(setOf(priya.noiseKeyHex, jo.noiseKeyHex), to.map { it.noiseKeyHex }.toSet())
        assertNull(s.addOutgoing("g-1", "m", GroupMessage.TEXT, "still here?", 21))
    }

    @Test fun only_creator_can_edit() {
        val s = store()
        s.applyRoster(priya.noiseKeyHex, true, roster())
        assertNull(s.edit("g-1", "Mine now", listOf(me, jo), 300))
        s.create("g-2", "Us", me, listOf(priya), 1)
        assertNotNull(s.edit("g-2", "Us all", listOf(me, priya, jo), 2))
        assertEquals(3, s.group("g-2")!!.roster.members.size)
    }

    @Test fun survives_a_restart() {
        var saved: GroupSnapshot? = null
        val p = object : GroupPersistence {
            override fun load() = saved
            override fun save(s: GroupSnapshot) { saved = s }
        }
        GroupStore({ me.noiseKeyHex }, p).applyRoster(priya.noiseKeyHex, true, roster())
        assertEquals("Camp crew", GroupStore({ me.noiseKeyHex }, p).group("g-1")!!.roster.name)
    }

    @Test fun mute_and_clear_are_local() {
        val s = store()
        s.applyRoster(priya.noiseKeyHex, true, roster())
        s.applyMessage(jo.noiseKeyHex, GroupMessage("g-1", "m-1", GroupMessage.TEXT, "hi", 10), 11)
        s.setMuted("g-1", true)
        assertTrue(s.group("g-1")!!.muted)
        s.clearMessages("g-1")
        assertTrue(s.group("g-1")!!.messages.isEmpty())
        assertEquals(3, s.group("g-1")!!.roster.members.size)
        assertTrue(s.group("g-1")!!.muted)
    }
}
