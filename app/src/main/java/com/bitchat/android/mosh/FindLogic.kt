package com.bitchat.android.mosh

import com.google.gson.Gson
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
    val id: String,
    val name: String,
    val city: String,
    val dates: List<String>,
    /** west, south, east, north */
    val bbox: List<Double>,
    val asset: String,
) {
    val centerLat: Double get() = (bbox[1] + bbox[3]) / 2
    val centerLon: Double get() = (bbox[0] + bbox[2]) / 2
    fun contains(lat: Double, lon: Double) = lon in bbox[0]..bbox[2] && lat in bbox[1]..bbox[3]
}

object VenueRegistry {
    private class VenueFile(val venues: List<Venue>?)

    fun load(json: String): List<Venue> {
        val venues = Gson().fromJson(json, VenueFile::class.java)?.venues ?: emptyList()
        venues.forEach { require(it.bbox.size == 4) { "bbox needs 4 numbers: ${it.id}" } }
        return venues
    }

    fun nearest(lat: Double, lon: Double, venues: List<Venue>): Venue? = venues.firstOrNull { it.contains(lat, lon) }
}
