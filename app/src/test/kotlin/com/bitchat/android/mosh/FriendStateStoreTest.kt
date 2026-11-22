package com.bitchat.android.mosh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendStateStoreTest {
    private var now = 0L
    private val store = FriendStateStore { now }

    private fun loc(t: Long) = LocationUpdate(0.1, 0.2, 5f, t, 60)
    private fun mp(id: String, t: Long, cleared: Boolean = false) = MeetingPoint(id, 0.1, 0.2, "Tree", t, "x", cleared)

    @Test
    fun older_location_does_not_replace_newer() {
        store.apply("a", loc(2_000)); store.apply("a", loc(1_000))
        assertEquals(2_000L, store.locations.value["a"]?.takenAtMs)
    }

    @Test
    fun newer_meeting_point_replaces_older_even_from_other_friend() {
        store.apply("a", mp("one", 1_000)); store.apply("b", mp("two", 2_000)); store.apply("a", mp("old", 1_500))
        assertEquals("two", store.meetingPoint.value?.pointId)
    }

    @Test
    fun cleared_meeting_point_with_newer_time_clears() {
        store.apply("a", mp("one", 1_000)); store.apply("b", mp("one", 2_000, cleared = true))
        assertNull(store.meetingPoint.value)
        store.apply("a", mp("one", 1_500))
        assertNull(store.meetingPoint.value) // older set after a newer clear stays cleared
    }

    @Test
    fun help_cancel_removes_alert_only_if_ids_match() {
        store.apply("a", HelpAlert("h1", 0.1, 0.2, 5f, "", 1_000))
        store.apply("a", HelpCancel("other", 2_000))
        assertTrue(store.activeHelp.value.containsKey("a"))
        store.apply("a", HelpCancel("h1", 2_000))
        assertFalse(store.activeHelp.value.containsKey("a"))
    }

    @Test
    fun location_older_than_10_minutes_is_stale() {
        store.apply("a", loc(0)); now = 600_000
        assertFalse(store.isStale("a"))
        now = 600_001
        assertTrue(store.isStale("a"))
    }

    @Test
    fun friend_unheard_for_15_minutes_is_silent() {
        now = 1_000; store.apply("a", loc(1_000))
        now = 1_000 + 900_000
        assertFalse(store.isSilent("a"))
        now += 1
        assertTrue(store.isSilent("a"))
        assertTrue(store.isSilent("never-heard"))
    }
}
