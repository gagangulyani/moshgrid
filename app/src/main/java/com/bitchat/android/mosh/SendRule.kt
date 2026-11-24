package com.bitchat.android.mosh

import com.bitchat.android.model.DeliveryStatus
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

const val SEND_MIN_MOVE_M = 15.0
const val SEND_MAX_INTERVAL_MS = 120_000L
private const val EARTH_RADIUS_M = 6_371_000.0

/** Great-circle distance in metres (haversine). */
fun distanceM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val dLat = Math.toRadians(bLat - aLat)
    val dLon = Math.toRadians(bLon - aLon)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/** Last-known fixes older than this are not used to seed our position on start. */
const val LAST_KNOWN_MAX_AGE_MS = 10 * 60_000L

/** The newest of (fix time, fix) pairs that is not in the future and at most [LAST_KNOWN_MAX_AGE_MS] old. */
fun <T> freshestRecent(fixes: List<Pair<Long, T>>, nowMs: Long): T? =
    fixes.filter { (t, _) -> t <= nowMs && nowMs - t <= LAST_KNOWN_MAX_AGE_MS }.maxByOrNull { it.first }?.second

/** Share a new location when we moved more than 15 m, or 2 minutes passed since the last share. */
fun shouldSend(last: LocationUpdate?, current: LocationUpdate, nowMs: Long): Boolean {
    if (last == null) return true
    if (nowMs - last.takenAtMs >= SEND_MAX_INTERVAL_MS) return true
    return distanceM(last.lat, last.lon, current.lat, current.lon) > SEND_MIN_MOVE_M
}

enum class Tick { CLOCK, ONE_GREY, TWO_GREY, TWO_BLUE, NOT_CONFIRMED }

const val NOT_CONFIRMED_AFTER_MS = 600_000L

/** Message status icon. "Sent" that never got a receipt within 10 minutes shows as Not confirmed. */
fun tickFor(status: DeliveryStatus?, sentAtMs: Long, nowMs: Long): Tick = when (status) {
    null, is DeliveryStatus.Sending -> Tick.CLOCK
    is DeliveryStatus.Sent -> if (nowMs - sentAtMs > NOT_CONFIRMED_AFTER_MS) Tick.NOT_CONFIRMED else Tick.ONE_GREY
    is DeliveryStatus.Delivered, is DeliveryStatus.PartiallyDelivered -> Tick.TWO_GREY
    is DeliveryStatus.Read -> Tick.TWO_BLUE
    is DeliveryStatus.Failed -> Tick.NOT_CONFIRMED
}
