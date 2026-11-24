package com.bitchat.android.mosh

import org.junit.Assert.assertEquals
import org.junit.Test

class MeetingResenderTest {
    private var now = 0L
    private var record: MeetingPoint? = null
    private var meshUp = true
    private val sent = mutableListOf<Triple<MeetingPoint, String, Boolean>>()
    private val r = MeetingResender({ now }, { record }, QuietResend({ listOf("ana") }) { p, to, meshOnly ->
        sent += Triple(p as MeetingPoint, to, meshOnly)
        if (meshOnly) (if (meshUp) MoshRoute.MESH else MoshRoute.NONE) else MoshRoute.NOSTR
    })
    private fun mp(id: String, at: Long, cleared: Boolean = false) = MeetingPoint(id, 0.001, 0.002, "gate", at, "x", cleared)

    @Test fun nothing_to_send_without_a_meeting_point() { r.tick(); assertEquals(0, sent.size) }

    @Test fun resends_every_3_minutes_then_stops_after_3_hours() {
        record = mp("m1", 0)
        r.tick(); assertEquals(1, sent.size)
        now = 60_000; r.tick(); assertEquals(1, sent.size)
        now = MeetingResender.RESEND_MS; r.tick(); assertEquals(2, sent.size)
        now = MeetingResender.RESEND_FOR_MS + 1; r.tick()
        val count = sent.size
        now += 10 * MeetingResender.RESEND_MS; r.tick(); assertEquals(count, sent.size)
    }

    @Test fun a_new_or_cleared_point_goes_out_at_once() {
        record = mp("m1", 0); r.tick()
        now = 30_000; record = mp("m1", 30_000, cleared = true); r.tick()
        assertEquals(2, sent.size); assertEquals(true, sent.last().first.cleared)
    }

    @Test fun friend_out_of_bluetooth_range_gets_it_over_internet_once() {
        meshUp = false; record = mp("m1", 0)
        r.tick(); now = MeetingResender.RESEND_MS; r.tick(); now = 2 * MeetingResender.RESEND_MS; r.tick()
        assertEquals(1, sent.count { !it.third })
        assertEquals(3, sent.count { it.third })
    }
}
