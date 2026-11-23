package com.bitchat.android.mosh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Synthetic coordinates and venues only.
class FindLogicTest {
    private fun at(lat: Double, lon: Double, acc: Float = 3f) = LocationUpdate(lat, lon, acc, 0, 50)

    @Test fun bearing_due_east_is_90() = assertEquals(90.0, bearingDeg(0.0, 0.0, 0.0, 0.01), 0.5)
    @Test fun bearing_due_north_is_0() = assertEquals(0.0, bearingDeg(0.0, 0.0, 0.01, 0.0), 0.5)
    @Test fun bearing_due_west_is_270() = assertEquals(270.0, bearingDeg(0.0, 0.0, 0.0, -0.01), 0.5)

    @Test
    fun accuracy_worse_than_distance_is_close_look_around() {
        // about 20 m apart, accuracies 15 + 10
        assertEquals(Guidance.CloseLookAround, guidance(at(0.0, 0.0, 15f), at(0.00018, 0.0, 10f)))
    }

    @Test
    fun accurate_fixes_give_arrow() {
        val g = guidance(at(0.0, 0.0), at(0.0009, 0.0))
        assertTrue(g is Guidance.Arrow)
        assertEquals(100.0, (g as Guidance.Arrow).distanceM, 2.0)
    }

    @Test
    fun direction_words_cover_the_compass() {
        assertEquals("ahead", directionWords(0.0))
        assertEquals("to your right", directionWords(90.0))
        assertEquals("behind you", directionWords(180.0))
        assertEquals("to your left", directionWords(270.0))
        assertEquals("ahead, slightly right", directionWords(30.0))
    }

    private val json = """
        {"venues":[
          {"id":"test-a","name":"Test Ground A","city":"Alpha","dates":["2026-12-05"],"bbox":[0.0,0.0,0.03,0.03],"asset":"venues/a.pmtiles"},
          {"id":"test-b","name":"Test Ground B","city":"Beta","dates":["2026-12-08","2026-12-09"],"bbox":[1.0,1.0,1.03,1.03],"asset":"venues/b.pmtiles"}
        ]}
    """.trimIndent()

    @Test
    fun venue_json_parses_both_venues() {
        val v = VenueRegistry.load(json)
        assertEquals(listOf("test-a", "test-b"), v.map { it.id })
        assertEquals(listOf("2026-12-08", "2026-12-09"), v[1].dates)
    }

    @Test
    fun nearest_venue_is_the_one_containing_the_point() {
        val v = VenueRegistry.load(json)
        assertEquals("test-b", VenueRegistry.nearest(1.01, 1.01, v)?.id)
        assertNull(VenueRegistry.nearest(5.0, 5.0, v))
    }
}
