package com.bitchat.android.mosh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpRepeaterTest {
    private var now = 0L
    private var pos = Triple(0.1, 0.2, 5f)
    private val sent = mutableListOf<MoshPayload>()
    private val r = HelpRepeater({ now }, { pos }, { sent += it })

    @Test fun raise_sends_immediately() {
        r.raise("red cap"); assertEquals(1, sent.size); assertTrue(sent[0] is HelpAlert)
    }

    @Test fun tick_before_2_minutes_does_not_resend() {
        r.raise(""); now = 119_999; r.tick(); assertEquals(1, sent.size)
    }

    @Test fun tick_after_2_minutes_resends_with_same_alert_id_and_new_position() {
        val id = r.raise(""); now = 120_000; pos = Triple(0.3, 0.4, 5f); r.tick()
        val second = sent[1] as HelpAlert
        assertEquals(id, second.alertId); assertEquals(0.3, second.lat, 0.0)
    }

    @Test fun cancel_stops_resends_and_sends_help_cancel() {
        val id = r.raise(""); r.cancel(); now = 500_000; r.tick()
        assertEquals(2, sent.size); assertEquals(HelpCancel(id, 0), sent[1])
    }

    @Test fun no_position_yet_sends_unknown_location() {
        val r2 = HelpRepeater({ now }, { null }, { sent += it })
        r2.raise("")
        val h = sent.last() as HelpAlert
        assertTrue(HelpRepeater.isLocationUnknown(h))
    }
}
