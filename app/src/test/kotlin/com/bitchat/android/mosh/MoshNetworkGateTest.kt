package com.bitchat.android.mosh

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoshNetworkGateTest {
    @After fun reset() { MoshNetworkGate.internetAllowed = true }

    @Test
    fun internet_off_closes_the_nostr_gate() {
        MoshNetworkGate.internetAllowed = false
        assertFalse(MoshNetworkGate.allowsNostr())
        MoshNetworkGate.internetAllowed = true
        assertTrue(MoshNetworkGate.allowsNostr())
    }
}
