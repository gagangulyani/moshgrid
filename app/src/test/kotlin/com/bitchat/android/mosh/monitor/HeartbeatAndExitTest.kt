package com.bitchat.android.mosh.monitor

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HeartbeatAndExitTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 0L

    @Test
    fun heartbeat_gap_over_3_minutes_is_reported() {
        val f = File(tmp.root, "hb")
        now = 1_000; Heartbeat(f) { now }.beat()
        now = 1_000 + 180_001
        assertEquals(180_001L, Heartbeat(f) { now }.gapOnStartMs())
    }

    @Test
    fun heartbeat_gap_under_3_minutes_is_ignored() {
        val f = File(tmp.root, "hb")
        now = 1_000; Heartbeat(f) { now }.beat()
        now = 1_000 + 179_000
        assertNull(Heartbeat(f) { now }.gapOnStartMs())
        assertNull(Heartbeat(File(tmp.root, "none")) { now }.gapOnStartMs())
    }

    @Test
    fun exit_reasons_are_described() {
        assertEquals("low memory", describeExit(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("crash", describeExit(ApplicationExitInfo.REASON_CRASH))
        assertEquals("app not responding", describeExit(ApplicationExitInfo.REASON_ANR))
        assertEquals("stopped by user", describeExit(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertEquals("killed by system", describeExit(ApplicationExitInfo.REASON_OTHER))
        assertEquals("other", describeExit(9999))
    }
}
