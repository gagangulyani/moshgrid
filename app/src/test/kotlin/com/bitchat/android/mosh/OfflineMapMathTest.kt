package com.bitchat.android.mosh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic boxes only. */
class OfflineMapMathTest {
    @Test fun tile_numbers_match_the_slippy_map_scheme() {
        assertEquals(0 to 0, OfflineMapMath.tile(10.0, 10.0, 0))
        assertEquals(1 to 0, OfflineMapMath.tile(10.0, 10.0, 1))
        assertEquals(0 to 1, OfflineMapMath.tile(-10.0, -10.0, 1))
        // Edges stay inside the grid.
        assertEquals(3 to 3, OfflineMapMath.tile(-85.0, 180.0, 2))
    }

    @Test fun one_tile_per_zoom_for_a_tiny_box() {
        val box = GeoBox(10.0001, 10.0001, 10.0002, 10.0002)
        assertEquals(15L, OfflineMapMath.tileCount(box))
    }

    @Test fun a_city_fits_and_a_country_does_not() {
        val city = GeoBox(20.0, 70.0, 20.4, 70.4)
        assertTrue(OfflineMapMath.tileCount(city) < OfflineMapMath.MAX_TILES)
        assertTrue(OfflineMapMath.fits(city))
        val country = GeoBox(10.0, 70.0, 30.0, 90.0)
        assertFalse(OfflineMapMath.fits(country))
    }

    @Test fun broken_boxes_never_fit() {
        assertFalse(OfflineMapMath.fits(GeoBox(20.4, 70.0, 20.0, 70.4)))
        assertFalse(OfflineMapMath.fits(GeoBox(84.9, 0.0, 89.0, 0.1)))
    }

    @Test fun contains_and_size() {
        val box = GeoBox(0.0, 0.0, 1.0, 1.0)
        assertTrue((0.5 to 0.5) in box)
        assertFalse((1.5 to 0.5) in box)
        val (w, h) = box.sizeKm()
        assertEquals(111.3, w, 0.5)
        assertEquals(110.6, h, 0.5)
    }
}
