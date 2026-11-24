package com.bitchat.android.ui

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** MoshGrid announces no name in clear; blank nicknames must not hide the friend's QR name. */
class ChatStateNicknamesTest {
    @Test fun blank_nicknames_are_left_out() = runTest {
        val state = ChatState(backgroundScope)
        state.setPeerNicknames(mapOf("aa11" to "", "bb22" to "  ", "cc33" to "Priya"))
        assertEquals(mapOf("cc33" to "Priya"), state.peerNicknames.value)
    }
}
