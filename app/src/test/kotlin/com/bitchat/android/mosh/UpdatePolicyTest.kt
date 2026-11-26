package com.bitchat.android.mosh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    private val now = 10 * 60 * 60_000L
    private val hour = UpdatePolicy.IDLE_MS

    @Test fun `wifi installs even while the app is open`() =
        assertTrue(UpdatePolicy.autoInstall(onWifi = true, helpLive = false, inUse = true, lastUsedMs = now, nowMs = now))

    @Test fun `mobile data waits while the app is open`() =
        assertFalse(UpdatePolicy.autoInstall(onWifi = false, helpLive = false, inUse = true, lastUsedMs = now - 5 * hour, nowMs = now))

    @Test fun `mobile data waits until an hour after the app was last open`() {
        assertFalse(UpdatePolicy.autoInstall(false, false, false, lastUsedMs = now - hour + 1, nowMs = now))
        assertTrue(UpdatePolicy.autoInstall(false, false, false, lastUsedMs = now - hour, nowMs = now))
    }

    @Test fun `never while Help is live`() {
        assertFalse(UpdatePolicy.autoInstall(true, helpLive = true, inUse = false, lastUsedMs = 0, nowMs = now))
        assertFalse(UpdatePolicy.autoInstall(false, helpLive = true, inUse = false, lastUsedMs = 0, nowMs = now))
    }
}
