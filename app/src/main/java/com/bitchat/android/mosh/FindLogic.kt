package com.bitchat.android.mosh

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Initial great-circle bearing from A to B, 0..360, 0 = north. */
fun bearingDeg(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
    val p1 = Math.toRadians(fromLat); val p2 = Math.toRadians(toLat)
    val dl = Math.toRadians(toLon - fromLon)
    val y = sin(dl) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

sealed interface Guidance {
    data class Arrow(val bearingDeg: Double, val distanceM: Double) : Guidance
    object CloseLookAround : Guidance
}

/** When GPS error is as big as the distance, an arrow would spin, so say "close, look around". */
fun guidance(me: LocationUpdate, target: LocationUpdate): Guidance {
    val d = distanceM(me.lat, me.lon, target.lat, target.lon)
    if (me.accuracyM + target.accuracyM >= d) return Guidance.CloseLookAround
    return Guidance.Arrow(bearingDeg(me.lat, me.lon, target.lat, target.lon), d)
}

/** Words for a direction relative to where the phone points (0 = straight ahead). */
fun directionWords(relativeDeg: Double): String {
    val a = ((relativeDeg % 360) + 360) % 360
    return when {
        a < 15 || a >= 345 -> "ahead"
        a < 60 -> "ahead, slightly right"
        a < 120 -> "to your right"
        a < 165 -> "behind you, to the right"
        a < 195 -> "behind you"
        a < 240 -> "behind you, to the left"
        a < 300 -> "to your left"
        else -> "ahead, slightly left"
    }
}

data class Venue(
    // Names are pinned: Gson reads venues.json by field name, and R8 renames fields in release builds.
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("city") val city: String,
    @SerializedName("dates") val dates: List<String>,
    /** west, south, east, north */
    @SerializedName("bbox") val bbox: List<Double>,
    @SerializedName("asset") val asset: String,
) {
    val centerLat: Double get() = (bbox[1] + bbox[3]) / 2
    val centerLon: Double get() = (bbox[0] + bbox[2]) / 2
    fun contains(lat: Double, lon: Double) = lon in bbox[0]..bbox[2] && lat in bbox[1]..bbox[3]
}

object VenueRegistry {
    private class VenueFile(@SerializedName("venues") val venues: List<Venue>?)

    fun load(json: String): List<Venue> {
        val venues = Gson().fromJson(json, VenueFile::class.java)?.venues ?: emptyList()
        venues.forEach { require(it.bbox.size == 4) { "bbox needs 4 numbers: ${it.id}" } }
        return venues
    }

    fun nearest(lat: Double, lon: Double, venues: List<Venue>): Venue? = venues.firstOrNull { it.contains(lat, lon) }

    /** Venue to draw: the one around the first point that is inside a venue (pass yourself first). */
    fun around(points: List<Pair<Double, Double>>, venues: List<Venue>): Venue? =
        points.firstNotNullOfOrNull { (lat, lon) -> nearest(lat, lon, venues) }
}

/**
 * Direction the user faces, 0..360 (0 = north), from a 3x3 rotation matrix (device to world,
 * world X east, Y north, Z up). Uses the phone's top edge plus its camera direction, so the answer
 * holds whether the phone lies flat or is held upright in front of you; Android's
 * getOrientation() azimuth breaks down when the phone is upright. Null when the phone points
 * straight up or down and no direction can be read.
 */
fun headingFromRotationMatrix(r: FloatArray): Double? {
    val east = r[1] - r[2]
    val north = r[4] - r[5]
    if (kotlin.math.hypot(east.toDouble(), north.toDouble()) < 0.1) return null
    return (Math.toDegrees(atan2(east.toDouble(), north.toDouble())) + 360.0) % 360.0
}

/** Eight-point compass words, for phones without a compass. */
fun compassWords(bearingDeg: Double): String {
    val names = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")
    val a = ((bearingDeg % 360) + 360) % 360
    return names[((a + 22.5) / 45).toInt() % 8]
}

/** South-west and north-east corners of what the map should show. */
data class GeoBox(val south: Double, val west: Double, val north: Double, val east: Double)

/**
 * Box around every point, grown to at least [minSpanM] each way so a single pin, or two pins
 * metres apart, still show their surroundings. Null when there are no points.
 */
fun fitBox(points: List<Pair<Double, Double>>, minSpanM: Double = 250.0): GeoBox? {
    if (points.isEmpty()) return null
    var s = points.minOf { it.first }; var n = points.maxOf { it.first }
    var w = points.minOf { it.second }; var e = points.maxOf { it.second }
    val midLat = (s + n) / 2
    val minLat = minSpanM / 111_320.0
    val minLon = minSpanM / (111_320.0 * cos(Math.toRadians(midLat)).coerceAtLeast(0.01))
    if (n - s < minLat) { val c = (s + n) / 2; s = c - minLat / 2; n = c + minLat / 2 }
    if (e - w < minLon) { val c = (w + e) / 2; w = c - minLon / 2; e = c + minLon / 2 }
    return GeoBox(s, w, n, e)
}
