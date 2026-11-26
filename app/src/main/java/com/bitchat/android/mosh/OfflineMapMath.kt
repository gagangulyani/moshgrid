package com.bitchat.android.mosh

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

operator fun GeoBox.contains(p: Pair<Double, Double>): Boolean = p.first in south..north && p.second in west..east

val GeoBox.valid: Boolean get() = south < north && west < east && south >= -85.0 && north <= 85.0 && west >= -180.0 && east <= 180.0

/** Rough size in km (width at the middle latitude, height), for "about 12 × 9 km". */
fun GeoBox.sizeKm(): Pair<Double, Double> {
    val mid = (south + north) / 2 * PI / 180
    return (east - west) * 111.32 * cos(mid) to (north - south) * 110.57
}

/** How big an offline map is before it is saved. Street tiles stop at [MAX_ZOOM]; closer views reuse them. */
object OfflineMapMath {
    const val MIN_ZOOM = 0
    const val MAX_ZOOM = 14
    /** About a 90 km square at India's latitude: a whole city and its outskirts, tens of MB. */
    const val MAX_TILES = 1_500L

    fun tileCount(box: GeoBox, minZoom: Int = MIN_ZOOM, maxZoom: Int = MAX_ZOOM): Long =
        (minZoom..maxZoom).sumOf { z ->
            val (x1, y1) = tile(box.north, box.west, z)
            val (x2, y2) = tile(box.south, box.east, z)
            (x2 - x1 + 1).toLong() * (y2 - y1 + 1).toLong()
        }

    fun fits(box: GeoBox): Boolean = box.valid && tileCount(box) <= MAX_TILES

    /** Slippy-map tile x, y holding a point at zoom [z]. */
    fun tile(lat: Double, lon: Double, z: Int): Pair<Int, Int> {
        val n = 1 shl z
        val r = lat * PI / 180
        val x = floor((lon + 180) / 360 * n).toInt().coerceIn(0, n - 1)
        val y = floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n).toInt().coerceIn(0, n - 1)
        return x to y
    }
}
