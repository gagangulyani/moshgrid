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
    fun defaults_are_bluetooth_internet_and_crash_reports_on() {
        val p = prefs()
        assertTrue(p.bluetooth); assertTrue(p.internet); assertTrue(p.monitoringOptIn); assertTrue(p.shareLocation)
        assertFalse(p.offlineOnly)
    }

    @Test
    fun internet_off_blocks_nostr_and_sentry() {
        val p = prefs(); p.internet = false; p.monitoringOptIn = true
        assertTrue(p.offlineOnly)
        assertFalse(nostrAllowed(p)); assertFalse(sentryAllowed(p))
    }

    @Test
    fun sentry_waits_for_setup_and_stops_when_switched_off() {
        val p = prefs()
        // On by default, but nothing is sent before the user has seen the switch during setup.
        assertFalse(sentryAllowed(p))
        p.onboardingDone = true
        assertTrue(sentryAllowed(p))
        p.monitoringOptIn = false
        assertFalse(sentryAllowed(p))
    }

    @Test
    fun paused_friends_round_trip() {
        val p = prefs()
        p.setPaused("a", true); p.setPaused("b", true); p.setPaused("a", false)
        assertEquals(setOf("b"), p.pausedFriends)
    }
}
