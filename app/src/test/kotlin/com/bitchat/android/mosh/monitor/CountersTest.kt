package com.bitchat.android.mosh.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CountersTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun counts_are_flushed_as_event_lines_and_reset() {
        val c = Counters()
        repeat(3) { c.inc("packet_relayed") }
        c.inc("peer_seen")
        val log = FieldLog(tmp.root, { 5L })
        c.flushTo(log)
        val lines = File(tmp.root, FieldLog.FILE_NAME).readLines().toSet()
        assertEquals(setOf("5 packet_relayed 3", "5 peer_seen 1"), lines)
        c.flushTo(log)
        assertEquals(2, File(tmp.root, FieldLog.FILE_NAME).readLines().size) // nothing new after reset
    }

    @Test
    fun once_counts_a_key_only_the_first_time() {
        val c = Counters()
        assertTrue(c.once("not_confirmed", "msg-1"))
        assertEquals(false, c.once("not_confirmed", "msg-1"))
        assertTrue(c.once("not_confirmed", "msg-2"))
        assertEquals(2, c.snapshot()["not_confirmed"])
    }
}
