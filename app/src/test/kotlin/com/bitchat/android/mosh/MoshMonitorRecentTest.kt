package com.bitchat.android.mosh

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MoshMonitorRecentTest {
    @Test fun `recent sums the last day's events by name and leaves out older ones`() {
        val c = ApplicationProvider.getApplicationContext<Context>()
        MoshMonitor.wipe(c)
        val f = MoshMonitor.fieldLog(c).file
        f.parentFile!!.mkdirs()
        val now = 100L * 24 * 60 * 60_000L
        f.writeText("${now - 25 * 60 * 60_000L} map_failed 9\n${now - 1000} map_failed 1\n${now - 500} map_failed 2\n${now - 10} bt_off 1\n")
        assertEquals("bt_off 1\nmap_failed 3", MoshMonitor.recent(c, nowMs = now))
    }
}
