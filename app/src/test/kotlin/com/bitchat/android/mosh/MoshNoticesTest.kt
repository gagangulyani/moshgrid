package com.bitchat.android.mosh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MoshNoticesTest {
    @Test fun `friend notices are in plain words`() {
        assertEquals("Ana added you back. You're friends now.", MoshNotices.friendChange("Ana", added = true, mutual = true))
        assertEquals("Ana added you. Add them back to be friends.", MoshNotices.friendChange("Ana", added = true, mutual = false))
        assertEquals("Ana removed you as a friend.", MoshNotices.friendChange("Ana", added = false, mutual = true))
    }

    @Test fun `no upstream jargon`() {
        for (added in listOf(true, false)) for (mutual in listOf(true, false)) {
            val s = MoshNotices.friendChange("Ana", added, mutual).lowercase()
            listOf("nostr", "mesh", "favorite", "dm").forEach { assertFalse("$it in '$s'", Regex("\\b$it").containsMatchIn(s)) }
        }
    }
}
