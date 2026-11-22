package com.bitchat.android.mosh

import org.robolectric.RuntimeEnvironment
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MoshPrefsTest {
    private fun prefs() = MoshPrefs(
        RuntimeEnvironment.getApplication().getSharedPreferences("mosh-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    )

    @Test
    fun defaults_are_bluetooth_and_internet_on_monitoring_off() {
        val p = prefs()
        assertTrue(p.bluetooth); assertTrue(p.internet); assertFalse(p.monitoringOptIn); assertTrue(p.shareLocation)
        assertFalse(p.offlineOnly)
    }

    @Test
    fun internet_off_blocks_nostr_and_sentry() {
        val p = prefs(); p.internet = false; p.monitoringOptIn = true
        assertTrue(p.offlineOnly)
        assertFalse(nostrAllowed(p)); assertFalse(sentryAllowed(p))
    }

    @Test
    fun sentry_needs_opt_in_even_with_internet() {
        val p = prefs()
        assertFalse(sentryAllowed(p))
        p.monitoringOptIn = true
        assertTrue(sentryAllowed(p))
    }

    @Test
    fun paused_friends_round_trip() {
        val p = prefs()
        p.setPaused("a", true); p.setPaused("b", true); p.setPaused("a", false)
        assertEquals(setOf("b"), p.pausedFriends)
    }
}
