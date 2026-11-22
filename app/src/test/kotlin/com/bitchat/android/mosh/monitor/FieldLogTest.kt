package com.bitchat.android.mosh.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FieldLogTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000L

    @Test
    fun writes_event_name_and_count() {
        val log = FieldLog(tmp.root, { now })
        log.event("packet_sent", 3)
        val line = File(tmp.root, FieldLog.FILE_NAME).readLines().single()
        assertEquals("1000 packet_sent 3", line)
    }

    @Test
    fun field_log_rejects_free_text_event_names() {
        val log = FieldLog(tmp.root, { now })
        assertFalse(log.event("msg to alice"))
        assertFalse(log.event("lat 28.1"))
        assertTrue(log.event("bt_off"))
    }

    @Test
    fun field_log_rotates_at_2mb() {
        val log = FieldLog(tmp.root, { now }, maxBytes = 200)
        repeat(30) { log.event("peer_seen") }
        assertTrue(File(tmp.root, FieldLog.FILE_NAME + ".1").exists())
        assertTrue(File(tmp.root, FieldLog.FILE_NAME).length() <= 200)
    }

    @Test
    fun default_cap_is_2mb() = assertEquals(2L * 1024 * 1024, FieldLog.DEFAULT_MAX_BYTES)
}
