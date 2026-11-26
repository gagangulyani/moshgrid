package com.bitchat.android.mosh.monitor

import com.bitchat.android.mosh.MoshMonitor
import io.sentry.SentryEvent
import io.sentry.protocol.Message
import io.sentry.protocol.SentryException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureReportingTest {
    private val hour = 60 * 60_000L

    @Test fun `limiter sends each failure name at most once an hour`() {
        val l = FailureLimiter()
        assertTrue(l.allow("map_failed", 0))
        assertFalse(l.allow("map_failed", hour - 1))
        assertTrue(l.allow("map_failed", hour))
    }

    @Test fun `limiter caps all failures at ten an hour`() {
        val l = FailureLimiter()
        repeat(10) { assertTrue(l.allow("f$it", 1_000L + it)) }
        assertFalse(l.allow("another", 2_000))
        assertTrue(l.allow("another", 1_000L + hour))
    }

    @Test fun `failure names are the ones ending in failed error blocked or missing`() {
        listOf("map_failed", "offline_map_error", "help_notify_blocked", "venue_map_missing").forEach { assertTrue(it, MoshMonitor.isFailure(it)) }
        listOf("help_raised", "bt_off", "location_off", "report_sent", "report_not_sent", "battery_pct").forEach { assertFalse(it, MoshMonitor.isFailure(it)) }
    }

    @Test fun `scrubber drops exception text but keeps the type`() {
        val e = SentryEvent().apply {
            exceptions = listOf(SentryException().apply { type = "IllegalStateException"; value = "bad peer 3fa9c2 named Ana" })
        }
        val out = Scrubber.scrub(e)
        assertEquals("IllegalStateException", out.exceptions!![0].type)
        assertNull(out.exceptions!![0].value)
    }

    @Test fun `scrubber keeps event-name messages and drops free text`() {
        val named = Scrubber.scrub(SentryEvent().apply { message = Message().apply { formatted = "map_failed" } })
        assertEquals("map_failed", named.message?.formatted)
        val free = Scrubber.scrub(SentryEvent().apply { message = Message().apply { formatted = "can't reach Ana at gate 3" } })
        assertNull(free.message)
    }

    @Test fun `scrubber keeps the note in a problem report the person chose to send`() {
        val report = Scrubber.scrub(SentryEvent().apply {
            message = Message().apply { formatted = "Send did nothing" }
            fingerprints = listOf(Scrubber.USER_REPORT)
        })
        assertEquals("Send did nothing", report.message?.formatted)
    }

    @Test fun `scrubber keeps phone model and Android version but drops install id timezone and locale`() {
        val e = SentryEvent()
        e.contexts.setDevice(io.sentry.protocol.Device().apply {
            id = "17c2e292"; timezone = java.util.TimeZone.getTimeZone("Asia/Kolkata"); locale = "en_IN"
            manufacturer = "Google"; model = "Pixel 8"; batteryLevel = 50f; bootTime = java.util.Date(0)
        })
        e.contexts.setOperatingSystem(io.sentry.protocol.OperatingSystem().apply { name = "Android"; version = "16"; kernelVersion = "6.6"; build = "abc" })
        val out = Scrubber.scrub(e)
        val d = out.contexts.device!!
        assertEquals("Google", d.manufacturer); assertEquals("Pixel 8", d.model)
        assertNull(d.id); assertNull(d.timezone); assertNull(d.locale); assertNull(d.batteryLevel); assertNull(d.bootTime)
        val o = out.contexts.operatingSystem!!
        assertEquals("16", o.version); assertNull(o.kernelVersion); assertNull(o.build)
    }
}
