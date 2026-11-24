package com.bitchat.android.mosh

import com.bitchat.android.model.DeliveryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class SendRuleAndTicksTest {
    private fun at(lat: Double, lon: Double, t: Long) = LocationUpdate(lat, lon, 5f, t, 50)
    // 0.0001 degrees of latitude is about 11.1 m.
    private val tenMetres = 0.00009
    private val sixteenMetres = 0.000144

    @Test fun sends_first_fix() = assertTrue(shouldSend(null, at(0.0, 0.0, 0), 0))
    @Test fun does_not_send_after_10m_and_60s() = assertFalse(shouldSend(at(0.0, 0.0, 0), at(tenMetres, 0.0, 60_000), 60_000))
    @Test fun sends_after_16m() = assertTrue(shouldSend(at(0.0, 0.0, 0), at(sixteenMetres, 0.0, 30_000), 30_000))
    @Test fun sends_after_120s_without_moving() = assertTrue(shouldSend(at(0.0, 0.0, 0), at(0.0, 0.0, 120_000), 120_000))

    @Test
    fun distance_of_one_degree_latitude_is_about_111km() {
        assertEquals(111_195.0, distanceM(0.0, 0.0, 1.0, 0.0), 50.0)
    }

    @Test fun sending_is_clock() = assertEquals(Tick.CLOCK, tickFor(DeliveryStatus.Sending, 0, 0))
    @Test fun sent_is_one_grey() = assertEquals(Tick.ONE_GREY, tickFor(DeliveryStatus.Sent, 0, 1_000))
    @Test fun delivered_is_two_grey() = assertEquals(Tick.TWO_GREY, tickFor(DeliveryStatus.Delivered("x", Date(0)), 0, 0))
    @Test fun read_is_two_blue() = assertEquals(Tick.TWO_BLUE, tickFor(DeliveryStatus.Read("x", Date(0)), 0, 0))
    @Test fun failed_is_not_confirmed() = assertEquals(Tick.NOT_CONFIRMED, tickFor(DeliveryStatus.Failed("x"), 0, 0))
    @Test fun sent_older_than_10_minutes_is_not_confirmed() = assertEquals(Tick.NOT_CONFIRMED, tickFor(DeliveryStatus.Sent, 0, 600_001))
    @Test fun partially_delivered_is_two_grey() = assertEquals(Tick.TWO_GREY, tickFor(DeliveryStatus.PartiallyDelivered(1, 2), 0, 0))

    @Test fun last_known_fix_picks_the_newest_recent_one() {
        val now = 1_000_000L
        assertEquals("b", freshestRecent(listOf(now - 60_000L to "a", now - 5_000L to "b"), now))
    }

    @Test fun last_known_fix_ignores_old_and_future_fixes() {
        val now = 1_000_000L
        assertEquals(null, freshestRecent(listOf(now - LAST_KNOWN_MAX_AGE_MS - 1 to "old", now + 1 to "future"), now))
        assertEquals(null, freshestRecent(emptyList<Pair<Long, String>>(), now))
    }
}
