package com.bitchat.android.mosh

import com.bitchat.android.ui.mosh.helpStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpRepeaterTest {
    private var now = 0L
    private var pos: LocationUpdate? = LocationUpdate(0.1, 0.2, 5f, 0, 80)
    private val sent = mutableListOf<MoshPayload>()
    private val r = HelpRepeater({ now }, { pos }, { sent += it; mapOf("ana" to MoshRoute.MESH) })

    @Test fun raise_sends_immediately() {
        r.raise("red cap"); assertEquals(1, sent.size); assertTrue(sent[0] is HelpAlert)
    }

    @Test fun tick_before_2_minutes_does_not_resend() {
        r.raise(""); now = 119_999; r.tick(); assertEquals(1, sent.size)
    }

    @Test fun tick_after_2_minutes_resends_with_same_alert_id_and_new_position() {
        val id = r.raise(""); now = 120_000; pos = LocationUpdate(0.3, 0.4, 5f, now, 80); r.tick()
        val second = sent[1] as HelpAlert
        assertEquals(id, second.alertId); assertEquals(0.3, second.lat, 0.0)
    }

    @Test fun cancel_sends_help_cancel_and_repeats_it_then_stops() {
        val id = r.raise(""); r.cancel()
        assertEquals(HelpCancel(id, 0), sent[1])
        now = 120_000; r.tick()
        assertEquals(HelpCancel(id, 0), sent[2])
        now = HelpRepeater.CANCEL_REPEAT_FOR_MS + 1; r.tick()
        val count = sent.size
        now += 10 * 60_000; r.tick()
        assertEquals(count, sent.size)
        assertTrue(sent.none { it is HelpAlert && sent.indexOf(it) > 0 })
    }

    @Test fun retries_every_minute_while_nobody_was_reached_over_bluetooth() {
        val r2 = HelpRepeater({ now }, { pos }, { sent += it; mapOf("ana" to MoshRoute.NOSTR) })
        r2.raise(""); now = HelpRepeater.FAST_REPEAT_MS; r2.tick()
        assertEquals(2, sent.size)
    }

    @Test fun no_position_yet_sends_unknown_location() {
        val r2 = HelpRepeater({ now }, { null }, { sent += it; emptyMap() })
        r2.raise("")
        assertTrue(HelpRepeater.isLocationUnknown(sent.last() as HelpAlert))
    }

    @Test fun fresh_fix_keeps_its_accuracy_and_old_fix_grows_by_walking_distance() {
        val fix = LocationUpdate(0.1, 0.2, 5f, 0, 80)
        assertEquals(5f, HelpRepeater.accuracyFor(fix, 30_000), 0f)
        assertEquals(5f + 600 * HelpRepeater.WALK_SPEED_MPS, HelpRepeater.accuracyFor(fix, 600_000), 0.01f)
    }

    @Test fun restored_help_resends_on_first_tick() {
        val store = object : HelpOutboxPersistence {
            var o: HelpOutbox? = null
            override fun load() = o
            override fun save(o: HelpOutbox?) { this.o = o }
        }
        val first = HelpRepeater({ now }, { pos }, { sent += it; emptyMap() }, store)
        val id = first.raise("")
        val restarted = HelpRepeater({ now }, { pos }, { sent += it; emptyMap() }, store)
        assertEquals(id, restarted.activeAlertId)
        now = 1_000; restarted.tick()
        assertEquals(2, sent.size)
    }

    @Test fun status_with_no_friends_says_nobody() {
        val s = helpStatus(emptyMap()) { it }
        assertFalse(s.reachedAnyone); assertTrue(s.headline.startsWith("Nobody to send to"))
    }

    @Test fun status_with_nobody_reachable_does_not_claim_success() {
        val s = helpStatus(mapOf("ana" to MoshRoute.NONE)) { it }
        assertFalse(s.reachedAnyone); assertTrue(s.lines.single().contains("ana"))
    }

    @Test fun status_lists_how_each_friend_was_reached() {
        val s = helpStatus(mapOf("ana" to MoshRoute.MESH_AND_NOSTR, "ben" to MoshRoute.NONE)) { it }
        assertTrue(s.reachedAnyone)
        assertEquals(listOf("Sent to ana (Bluetooth and internet)", "Not reachable right now: ben"), s.lines)
    }

    @Test fun wipe_tells_friends_once_then_forgets_everything() {
        val store = object : HelpOutboxPersistence {
            var saved: HelpOutbox? = null
            override fun load() = saved
            override fun save(o: HelpOutbox?) { saved = o }
        }
        val w = HelpRepeater({ now }, { pos }, { sent += it; mapOf("ana" to MoshRoute.MESH) }, store)
        val id = w.raise("")
        w.wipe()
        assertEquals(HelpCancel(id, 0), sent.last())
        assertNull(w.activeAlertId); assertNull(store.saved); assertTrue(w.lastRoutes.isEmpty())
        val count = sent.size
        now = 10 * 60_000; w.tick()
        assertEquals(count, sent.size)
        assertNull(HelpRepeater({ now }, { pos }, { emptyMap() }, store).activeAlertId)
    }

    @Test fun cancel_keeps_repeating_over_bluetooth_for_3_hours() {
        val mesh = mutableListOf<MoshPayload>()
        val w = HelpRepeater({ now }, { pos }, { sent += it; mapOf("ana" to MoshRoute.NONE) }, null,
            QuietResend({ listOf("ana") }) { p, _, meshOnly -> if (meshOnly) { mesh += p; MoshRoute.MESH } else MoshRoute.NONE })
        val id = w.raise(""); w.cancel()
        val fullSends = sent.size
        now = HelpRepeater.CANCEL_REPEAT_FOR_MS + 1; w.tick()
        assertEquals("late repeats must not use the internet", fullSends, sent.size)
        assertEquals(HelpCancel(id, 0), mesh.last())
        now += HelpRepeater.LATE_CANCEL_REPEAT_MS; w.tick(); assertEquals(2, mesh.size)
        now = HelpRepeater.CANCEL_MESH_REPEAT_FOR_MS + 1; w.tick()
        val n = mesh.size
        now += 10 * HelpRepeater.LATE_CANCEL_REPEAT_MS; w.tick(); assertEquals(n, mesh.size)
    }
}
