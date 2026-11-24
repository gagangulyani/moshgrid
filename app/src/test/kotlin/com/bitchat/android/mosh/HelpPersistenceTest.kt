package com.bitchat.android.mosh

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HelpPersistenceTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("help_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test fun outbox_round_trips_and_clears() {
        val p = PrefsHelpOutbox(prefs)
        assertNull(p.load())
        val o = HelpOutbox("h-1", "red cap", "h-0", 42L)
        p.save(o)
        assertEquals(o, PrefsHelpOutbox(prefs).load())
        p.save(null)
        assertNull(PrefsHelpOutbox(prefs).load())
    }

    @Test fun received_help_round_trips_including_unicode_note_and_cancel_ids() {
        val p = PrefsReceivedHelp(prefs)
        val r = ReceivedHelp(
            active = mapOf("aa11" to HelpAlert("h-9", 0.001, -0.002, 12f, "near the stage ✨", 1_000L)),
            cancelled = mapOf("h-1 with space" to 5L),
        )
        p.save(r)
        assertEquals(r, PrefsReceivedHelp(prefs).load())
    }

    @Test fun corrupt_received_help_loads_as_nothing() {
        prefs.edit().putString("help_in_active", "peer not-base64!!").commit()
        assertNull(PrefsReceivedHelp(prefs).load())
    }

    @Test fun friend_snapshot_round_trips() {
        val p = PrefsFriendSnapshot(prefs)
        val s = FriendSnapshot(
            locations = mapOf("aa11" to LocationUpdate(0.001, -0.002, 9f, 1_000L, 40)),
            meeting = MeetingPoint("m-1", 0.003, 0.004, "big tree ✨", 2_000L, "aa11", false),
            lastHeard = mapOf("aa11" to 3_000L),
        )
        p.save(s)
        assertEquals(s, PrefsFriendSnapshot(prefs).load())
    }
}
