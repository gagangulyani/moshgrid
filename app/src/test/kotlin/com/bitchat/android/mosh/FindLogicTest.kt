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

    @Test
    fun venue_around_uses_first_point_inside_a_venue() {
        val v = VenueRegistry.load(json)
        // I am outside every venue, my friend is inside B: draw B.
        assertEquals("test-b", VenueRegistry.around(listOf(5.0 to 5.0, 1.01 to 1.01), v)?.id)
        assertEquals("test-a", VenueRegistry.around(listOf(0.01 to 0.01, 1.01 to 1.01), v)?.id)
        assertNull(VenueRegistry.around(listOf(5.0 to 5.0), v))
    }

    // Rotation matrix, row-major, columns = the phone's X (right edge), Y (top edge), Z (out of the
    // screen) in world coordinates (east, north, up).
    private fun rot(x: List<Double>, y: List<Double>, z: List<Double>) = floatArrayOf(
        x[0].toFloat(), y[0].toFloat(), z[0].toFloat(),
        x[1].toFloat(), y[1].toFloat(), z[1].toFloat(),
        x[2].toFloat(), y[2].toFloat(), z[2].toFloat(),
    )

    @Test fun heading_flat_top_edge_north_is_0() =
        assertEquals(0.0, headingFromRotationMatrix(rot(listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0)))!!, 0.5)

    @Test fun heading_flat_top_edge_east_is_90() =
        assertEquals(90.0, headingFromRotationMatrix(rot(listOf(0.0, -1.0, 0.0), listOf(1.0, 0.0, 0.0), listOf(0.0, 0.0, 1.0)))!!, 0.5)

    @Test fun heading_upright_facing_north_is_0() =
        // Held in front of you: top edge up, screen towards you (south), camera north.
        assertEquals(0.0, headingFromRotationMatrix(rot(listOf(1.0, 0.0, 0.0), listOf(0.0, 0.0, 1.0), listOf(0.0, -1.0, 0.0)))!!, 0.5)

    @Test fun heading_upright_facing_west_is_270() =
        assertEquals(270.0, headingFromRotationMatrix(rot(listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0), listOf(1.0, 0.0, 0.0)))!!, 0.5)

    @Test fun heading_tilted_45_facing_south_is_180() {
        val c = Math.sqrt(0.5)
        assertEquals(180.0, headingFromRotationMatrix(rot(listOf(-1.0, 0.0, 0.0), listOf(0.0, -c, c), listOf(0.0, c, c)))!!, 0.5)
    }

    @Test fun compass_words_cover_eight_points() {
        assertEquals("north", compassWords(0.0)); assertEquals("north", compassWords(350.0))
        assertEquals("north-east", compassWords(40.0)); assertEquals("east", compassWords(90.0))
        assertEquals("south-west", compassWords(225.0)); assertEquals("north-west", compassWords(320.0))
    }

    @Test fun fit_box_single_point_shows_surroundings() {
        val b = fitBox(listOf(0.25 to 0.5))!!
        assertTrue(distanceM(b.south, 0.5, b.north, 0.5) >= 249.0)
        assertTrue(distanceM(0.25, b.west, 0.25, b.east) >= 249.0)
        assertTrue(0.25 in b.south..b.north && 0.5 in b.west..b.east)
    }

    @Test fun fit_box_contains_far_apart_points() {
        val b = fitBox(listOf(0.25 to 0.5, 0.27 to 0.48))!!
        assertEquals(0.25, b.south, 1e-9); assertEquals(0.27, b.north, 1e-9)
        assertEquals(0.48, b.west, 1e-9); assertEquals(0.5, b.east, 1e-9)
        assertNull(fitBox(emptyList()))
    }

    @Test
    fun radar_radius_grows_with_distance_and_stays_inside() {
        assertEquals(26.0 / 150, radarFraction(0.0), 1e-9)
        assertEquals(1.0, radarFraction(800.0), 1e-9)
        assertEquals(1.0, radarFraction(5_000.0), 1e-9)
        assertTrue(radarFraction(50.0) < radarFraction(200.0))
        assertTrue(radarFraction(200.0) < radarFraction(600.0))
    }

    @Test
    fun dot_field_lights_dots_toward_the_target() {
        val ahead = dotField(0.0)
        assertEquals(17 * 17, ahead.size)
        assertTrue(ahead[8 * 17 + 8].centre)
        // Straight up from the centre is lit, straight down is not.
        assertTrue(ahead[2 * 17 + 8].lit)
        assertFalse(ahead[14 * 17 + 8].lit)
        // Pointing right lights the right side instead.
        val right = dotField(90.0)
        assertTrue(right[8 * 17 + 14].lit)
        assertFalse(right[2 * 17 + 8].lit)
        // Corners are outside the round field.
        assertEquals(0f, ahead[0].alpha)
    }
}
