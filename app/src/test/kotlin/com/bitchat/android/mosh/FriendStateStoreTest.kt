package com.bitchat.android.mosh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendStateStoreTest {
    private var now = 0L
    private val store = FriendStateStore(clock = { now })

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

    @Test fun wipe_clears_friends_state_and_saved_help() {
        val saved = object : ReceivedHelpPersistence {
            var r: ReceivedHelp? = null
            override fun load() = r
            override fun save(r: ReceivedHelp) { this.r = r }
        }
        val s = FriendStateStore(clock = { 1_000L }, helpPersistence = saved)
        s.apply("ana", LocationUpdate(0.001, 0.001, 8f, 900L, 80))
        s.apply("ana", HelpAlert("h-1", 0.001, 0.001, 8f, "", 900L))
        s.apply("ben", HelpCancel("h-2", 900L))
        s.setMeetingRecord(MeetingPoint("m-1", 0.002, 0.002, "", 900L, "me", false))
        s.wipe()
        assertTrue(s.locations.value.isEmpty()); assertTrue(s.activeHelp.value.isEmpty())
        assertTrue(s.lastHeardMs.value.isEmpty()); assertNull(s.meetingPoint.value)
        assertTrue(FriendStateStore(clock = { 1_000L }, helpPersistence = saved).activeHelp.value.isEmpty())
        // Cancel memory is gone too, and an older meeting point is accepted again.
        s.apply("ana", HelpAlert("h-2", 0.001, 0.001, 8f, "", 950L))
        assertEquals("h-2", s.activeHelp.value["ana"]?.alertId)
        s.setMeetingRecord(MeetingPoint("m-0", 0.002, 0.002, "", 100L, "me", false))
        assertEquals("m-0", s.meetingPoint.value?.pointId)
    }

    private class MemSnapshot : FriendSnapshotPersistence {
        var s: FriendSnapshot? = null
        override fun load() = s
        override fun save(s: FriendSnapshot) { this.s = s }
    }

    @Test
    fun restart_keeps_friends_last_location_and_meeting_point() {
        val disk = MemSnapshot(); var t = 1_000L
        val s = FriendStateStore(clock = { t }, snapshotPersistence = disk)
        s.apply("ana", LocationUpdate(0.001, 0.001, 8f, 900L, 80))
        s.apply("ana", MeetingPoint("m-1", 0.002, 0.002, "gate", 950L, "ana", false))
        t = 2 * 3_600_000L // two hours later, app restarted
        val again = FriendStateStore(clock = { t }, snapshotPersistence = disk)
        assertEquals(0.001, again.locations.value["ana"]!!.lat, 0.0)
        assertEquals("m-1", again.meetingPoint.value?.pointId)
        assertTrue(again.lastHeardMs.value.containsKey("ana"))
    }

    @Test
    fun saved_state_older_than_12_hours_is_dropped() {
        val disk = MemSnapshot(); var t = 1_000L
        FriendStateStore(clock = { t }, snapshotPersistence = disk).apply("ana", LocationUpdate(0.001, 0.001, 8f, 900L, 80))
        t = 13 * 3_600_000L
        val again = FriendStateStore(clock = { t }, snapshotPersistence = disk)
        assertTrue(again.locations.value.isEmpty()); assertTrue(again.lastHeardMs.value.isEmpty())
    }

    @Test
    fun forget_removes_a_friend_everywhere() {
        val disk = MemSnapshot()
        val s = FriendStateStore(clock = { 1_000L }, snapshotPersistence = disk)
        s.apply("ana", LocationUpdate(0.001, 0.001, 8f, 900L, 80))
        s.apply("ana", HelpAlert("h-1", 0.001, 0.001, 8f, "", 900L))
        s.forget("ana")
        assertTrue(s.locations.value.isEmpty()); assertTrue(s.activeHelp.value.isEmpty())
        assertTrue(disk.s!!.locations.isEmpty())
    }

    @Test
    fun apply_reports_what_changed_so_only_real_alerts_ring() {
        var t = 1_000L
        val s = FriendStateStore(clock = { t })
        val h = HelpAlert("h-1", 0.001, 0.001, 8f, "", 900L)
        assertEquals(HelpChange.NEW, s.apply("ana", h))
        t = 2_000L
        assertEquals(HelpChange.UPDATED, s.apply("ana", h.copy(raisedAtMs = 1_900L)))
        assertEquals(HelpChange.NONE, s.apply("ana", h.copy(raisedAtMs = 1_000L))) // older repeat
        assertEquals(HelpChange.CANCELLED, s.apply("ana", HelpCancel("h-1", 1_950L)))
        // Relays replay the alert after the cancel (restart): it must not ring again.
        assertEquals(HelpChange.NONE, s.apply("ana", h.copy(raisedAtMs = 1_990L)))
        // A late cancel for an old alert does not hide a newer one.
        assertEquals(HelpChange.NEW, s.apply("ana", HelpAlert("h-2", 0.001, 0.001, 8f, "", 1_995L)))
        assertEquals(HelpChange.NONE, s.apply("ana", HelpCancel("h-1", 1_950L)))
        assertEquals("h-2", s.activeHelp.value["ana"]?.alertId)
    }

    @Test
    fun cancel_is_remembered_longer_than_relays_replay() {
        assertTrue(FriendStateStore.CANCEL_MEMORY_MS >= 72 * 3_600_000L)
    }

    @Test
    fun payload_from_the_future_is_dropped() {
        val s = FriendStateStore(clock = { 1_000_000L })
        val ahead = 1_000_000L + FriendStateStore.MAX_FUTURE_MS + 1
        assertEquals(HelpChange.NONE, s.apply("ana", MeetingPoint("m-x", 0.002, 0.002, "", ahead, "ana", false)))
        assertNull(s.meetingPoint.value)
        s.apply("ana", LocationUpdate(0.001, 0.001, 8f, ahead, 80))
        assertTrue(s.locations.value.isEmpty()); assertTrue(s.lastHeardMs.value.isEmpty())
    }

    @Test
    fun replayed_old_payload_does_not_make_a_silent_friend_look_heard() {
        var t = 10 * 3_600_000L
        val s = FriendStateStore(clock = { t })
        s.apply("ana", LocationUpdate(0.001, 0.001, 8f, 1_000L, 80)) // made hours ago, delivered now
        assertEquals(1_000L, s.lastHeardMs.value["ana"])
        assertTrue(s.isSilent("ana"))
    }

    @Test
    fun repeat_without_position_keeps_the_last_known_position() {
        val s = FriendStateStore(clock = { 2_000L })
        s.apply("ana", HelpAlert("h-1", 0.001, 0.002, 8f, "", 1_000L))
        s.apply("ana", HelpAlert("h-1", 0.0, 0.0, HelpRepeater.UNKNOWN_ACCURACY_M, "", 1_500L))
        val h = s.activeHelp.value["ana"]!!
        assertEquals(0.002, h.lon, 0.0); assertEquals(1_500L, h.raisedAtMs)
    }

    @Test
    fun only_our_own_meeting_point_is_offered_for_resending() {
        val s = FriendStateStore(clock = { 2_000L })
        s.apply("ana", MeetingPoint("m-a", 0.002, 0.002, "", 1_000L, "x", false))
        assertNull(s.ownLatestMeeting)
        s.setMeetingRecord(MeetingPoint("m-me", 0.002, 0.002, "", 1_500L, "me", false), own = true)
        assertEquals("m-me", s.ownLatestMeeting?.pointId)
        s.apply("ana", MeetingPoint("m-a2", 0.002, 0.002, "", 1_800L, "x", false))
        assertNull(s.ownLatestMeeting)
    }

    @Test
    fun expired_entries_are_not_written_back() {
        val disk = MemSnapshot(); var t = 1_000L
        val s = FriendStateStore(clock = { t }, snapshotPersistence = disk)
        s.apply("ana", LocationUpdate(0.001, 0.001, 8f, 900L, 80))
        t = 13 * 3_600_000L
        s.apply("ben", LocationUpdate(0.001, 0.001, 8f, t, 80))
        assertEquals(setOf("ben"), disk.s!!.locations.keys)
    }
}
