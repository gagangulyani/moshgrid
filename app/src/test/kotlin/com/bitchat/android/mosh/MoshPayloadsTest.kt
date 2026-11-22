package com.bitchat.android.mosh

import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Synthetic coordinates only (AGENTS.md privacy rules).
class MoshPayloadsTest {

    @Test
    fun location_round_trips() {
        val p = LocationUpdate(0.5, 0.25, 12f, 1_000L, 80)
        val encoded = MoshCodec.encode(p)
        assertEquals(NoisePayloadType.LOCATION, encoded.type)
        assertEquals(p, MoshCodec.decode(encoded))
    }

    @Test
    fun meeting_point_round_trips() {
        val p = MeetingPoint("mp-1", 0.1, -0.2, "Big tree by the bar", 2_000L, "peer-a", cleared = false)
        assertEquals(p, MoshCodec.decode(MoshCodec.encode(p)))
    }

    @Test
    fun help_and_cancel_round_trip() {
        val h = HelpAlert("h-1", 0.3, 0.4, 8f, "red cap, near left screen", 3_000L)
        val c = HelpCancel("h-1", 4_000L)
        assertEquals(h, MoshCodec.decode(MoshCodec.encode(h)))
        assertEquals(c, MoshCodec.decode(MoshCodec.encode(c)))
    }

    @Test
    fun latitude_out_of_range_decodes_to_null() {
        // encodeUnchecked skips validation so the decoder's own check is what is tested.
        val bad = MoshCodec.encodeUnchecked(LocationUpdate(91.0, 0.25, 12f, 1_000L, 80))
        assertNull(MoshCodec.decode(bad))
    }

    @Test
    fun truncated_payload_decodes_to_null() {
        val ok = MoshCodec.encode(HelpAlert("h-1", 0.3, 0.4, 8f, "note", 3_000L))
        val cut = NoisePayload(ok.type, ok.data.copyOf(ok.data.size - 3))
        assertNull(MoshCodec.decode(cut))
    }

    @Test(expected = IllegalArgumentException::class)
    fun label_over_64_bytes_is_rejected_on_encode() {
        MoshCodec.encode(MeetingPoint("mp", 0.0, 0.0, "x".repeat(65), 1L, "p", false))
    }

    @Test(expected = IllegalArgumentException::class)
    fun note_over_140_bytes_is_rejected_on_encode() {
        MoshCodec.encode(HelpAlert("h", 0.0, 0.0, 1f, "x".repeat(141), 1L))
    }

    @Test
    fun non_mosh_payload_decodes_to_null() {
        assertNull(MoshCodec.decode(NoisePayload(NoisePayloadType.PRIVATE_MESSAGE, byteArrayOf(1, 2, 3))))
    }
}
