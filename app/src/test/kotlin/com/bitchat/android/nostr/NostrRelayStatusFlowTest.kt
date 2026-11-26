package com.bitchat.android.nostr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The UI watches [NostrRelayManager.relays]; every status change must reach it as a new, unequal value. */
@RunWith(RobolectricTestRunner::class)
class NostrRelayStatusFlowTest {
    private fun setStatus(manager: NostrRelayManager, url: String, up: Boolean) {
        NostrRelayManager::class.java.getDeclaredMethod("updateRelayStatus", String::class.java, Boolean::class.java, Throwable::class.java)
            .apply { isAccessible = true }
            .invoke(manager, url, up, null)
    }

    @Test fun relay_going_up_and_down_is_published() {
        val manager = NostrRelayManager::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val url = manager.relays.value.first().url

        val before = manager.relays.value
        setStatus(manager, url, true)
        val up = manager.relays.value
        assertNotEquals(before, up)
        assertTrue(up.first { it.url == url }.isConnected)
        assertFalse("an earlier snapshot must not change under the UI", before.first { it.url == url }.isConnected)

        setStatus(manager, url, false)
        val down = manager.relays.value
        assertNotEquals(up, down)
        assertFalse(down.first { it.url == url }.isConnected)
        assertTrue(up.first { it.url == url }.isConnected)
    }
}
