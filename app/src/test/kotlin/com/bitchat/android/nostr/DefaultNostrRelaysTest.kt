package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultNostrRelaysTest {
    @Test fun own_relay_comes_first_and_public_relays_stay_as_fallbacks() {
        val urls = DefaultNostrRelays.urls
        assertEquals("wss://relay.gagangulyani.com", urls.first())
        assertTrue(urls.containsAll(listOf("wss://relay.damus.io", "wss://relay.primal.net", "wss://offchain.pub", "wss://nostr21.com")))
        assertEquals(urls.size, urls.toSet().size)
    }

    @Test fun manager_defaults_use_the_same_list() {
        assertEquals(DefaultNostrRelays.urls, NostrRelayManager.defaultRelays())
    }
}
