package com.bitchat.android.mosh.monitor

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrubberTest {
    @Test
    fun scrubber_removes_user_server_and_extras() {
        val e = SentryEvent()
        e.user = User().apply { id = "someone" }
        e.serverName = "host"
        e.setExtra("location", "0.1,0.2")
        e.setTag("peer", "abc")
        val out = Scrubber.scrub(e)
        assertNull(out.user)
        assertNull(out.serverName)
        assertTrue(out.extras.isNullOrEmpty())
        assertTrue(out.tags.isNullOrEmpty())
    }

    @Test
    fun scrubber_keeps_event_name_breadcrumbs_and_strips_their_data() {
        val b = Breadcrumb().apply { message = "packet_sent 3"; category = "mosh"; setData("peer", "x") }
        val kept = Scrubber.breadcrumb(b)!!
        assertEquals("packet_sent 3", kept.message)
        assertTrue(kept.data.isEmpty())
    }

    @Test
    fun scrubber_drops_breadcrumb_with_free_text() {
        assertNull(Scrubber.breadcrumb(Breadcrumb().apply { message = "msg to alice" }))
        assertNull(Scrubber.breadcrumb(Breadcrumb().apply { message = null }))
    }
}
