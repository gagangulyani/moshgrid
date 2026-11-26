package com.bitchat.android.mosh

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Looper
import android.util.Log

/**
 * Shares this phone's location with friends while sharing is on. Runs inside the mesh foreground
 * service (typed `location`), so it keeps going with the screen off. Uses the platform
 * LocationManager, not Play Services, to stay GPL-clean. Locations are never logged.
 */
class LocationSharer(private val context: Context) : LocationListener {
    private var lastSent: LocationUpdate? = null
    @Volatile private var running = false
    private var deniedLogged = false

    /** Safe to call again and again: the ticker retries every minute until permission is granted. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start() {
        if (running) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        try {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.allProviders.contains(provider)) {
                    lm.requestLocationUpdates(provider, UPDATE_INTERVAL_MS, 0f, this, Looper.getMainLooper())
                }
            }
            running = true
            deniedLogged = false
            // A fresh fix can take minutes in a crowd. Start from the newest position Android already has
            // (its real age is kept, so Help widens its circle) instead of "location unknown".
            val now = System.currentTimeMillis()
            freshestRecent(
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                    .filter { lm.allProviders.contains(it) }
                    .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                    .map { it.time to it },
                now,
            )?.let { onLocationChanged(it) }
        } catch (e: SecurityException) {
            if (!deniedLogged) {
                Log.w(TAG, "Location permission missing; sharing paused")
                MoshMonitor.event(context, "permission_revoked")
                deniedLogged = true
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.removeUpdates(this)
        running = false
    }

    override fun onLocationChanged(location: Location) {
        val now = System.currentTimeMillis()
        val update = LocationUpdate(
            lat = location.latitude,
            lon = location.longitude,
            accuracyM = if (location.hasAccuracy()) location.accuracy else 50f,
            // When the fix was taken, not when it reached us: a cached fix must not look fresh.
            takenAtMs = location.time.takeIf { it in 1..now } ?: now,
            batteryPct = batteryPct(),
        )
        // A coarse network fix must not replace a better GPS fix from the last 30 seconds.
        MoshRuntime.myLocation.value?.let { cur ->
            if (update.accuracyM > cur.accuracyM && update.takenAtMs - cur.takenAtMs < 30_000L) return
        }
        MoshRuntime.updateMyLocation(update)
        if (!MoshRuntime.isInitialised || !MoshRuntime.prefs.shareLocation) return
        if (shouldSend(lastSent, update, now)) {
            lastSent = update
            MoshRuntime.sendToFriends(update)
        }
    }

    @Deprecated("Required on API < 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        // One provider (for example network location) can be off while GPS works: only record a real outage.
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        if (!androidx.core.location.LocationManagerCompat.isLocationEnabled(lm)) MoshMonitor.event(context, "location_off")
    }

    private fun batteryPct(): Int {
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else 0
    }

    companion object {
        private const val TAG = "LocationSharer"
        const val UPDATE_INTERVAL_MS = 15_000L
    }
}
