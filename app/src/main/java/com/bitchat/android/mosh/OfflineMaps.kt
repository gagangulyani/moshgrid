package com.bitchat.android.mosh

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/** A street map saved on the phone. [percent] is null once it is complete. */
data class SavedMap(
    val id: Long,
    val name: String,
    val savedAtMs: Long,
    val box: GeoBox,
    val bytes: Long,
    val percent: Int?,
    val downloading: Boolean,
)

/**
 * Street maps saved for offline use (MapLibre offline regions in the app's private storage).
 * Saving one sends the area to OpenFreeMap, like looking at it does; once saved, the map draws
 * from the phone with no signal. With MoshGrid's Internet switch off, maps never touch the network.
 * All calls on the main thread (MapLibre's callbacks arrive there too).
 */
object OfflineMaps {
    const val STREET_STYLE = "https://tiles.openfreemap.org/styles/dark"

    private val _maps = MutableStateFlow<List<SavedMap>>(emptyList())
    val maps: StateFlow<List<SavedMap>> = _maps.asStateFlow()

    private val regions = LinkedHashMap<Long, OfflineRegion>()
    private val info = LinkedHashMap<Long, SavedMap>()
    private var app: Context? = null
    private val ready get() = app != null

    /** Loads MapLibre and applies the Internet switch. Call before showing any map. */
    fun ensure(context: Context) {
        MapLibre.getInstance(context.applicationContext)
        app = context.applicationContext
        applyInternet()
        if (regions.isEmpty()) refresh(context)
    }

    /** Internet off for MoshGrid: maps draw only from what is saved and make no requests. */
    fun applyInternet() {
        if (!ready || !MoshRuntime.isInitialised) return
        MapLibre.setConnected(if (MoshRuntime.prefs.internet) null else false)
    }

    /** True if a saved map covers this point, so the street map can be drawn without signal. */
    fun covers(lat: Double, lon: Double): Boolean = _maps.value.any { (lat to lon) in it.box }

    fun refresh(context: Context) {
        OfflineManager.getInstance(context.applicationContext).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                regions.clear(); info.clear()
                offlineRegions.orEmpty().forEach { r -> track(r, downloading = false) }
                publish()
            }
            override fun onError(error: String) { MoshMonitor.event(context, "offline_map_error") }
        })
    }

    /** Starts saving [box] as [name]. False if it is too big or broken (the UI checks first). */
    fun save(context: Context, name: String, box: GeoBox, onError: (String) -> Unit): Boolean {
        if (!OfflineMapMath.fits(box)) return false
        val bounds = LatLngBounds.from(box.north, box.east, box.south, box.west)
        val density = context.resources.displayMetrics.density.coerceIn(1f, 3f)
        val def = OfflineTilePyramidRegionDefinition(STREET_STYLE, bounds,
            OfflineMapMath.MIN_ZOOM.toDouble(), OfflineMapMath.MAX_ZOOM.toDouble(), density)
        val meta = JSONObject().put("name", name.take(40)).put("at", System.currentTimeMillis()).toString().toByteArray()
        OfflineManager.getInstance(context.applicationContext).createOfflineRegion(def, meta, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                track(offlineRegion, downloading = true)
                publish()
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }
            override fun onError(error: String) {
                MoshMonitor.event(context, "offline_map_error")
                onError("Couldn't start saving this map.")
            }
        })
        return true
    }

    /** Continue a map that stopped part way (the app was closed, or signal dropped). */
    fun resume(id: Long) {
        val r = regions[id] ?: return
        info[id]?.let { info[id] = it.copy(downloading = true) }
        publish()
        r.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    fun delete(context: Context, id: Long) {
        val r = regions[id] ?: return
        r.setDownloadState(OfflineRegion.STATE_INACTIVE)
        r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() { regions.remove(id); info.remove(id); publish() }
            override fun onError(error: String) { MoshMonitor.event(context, "offline_map_error") }
        })
    }

    /** Panic wipe: every saved map and the map cache go, even if no map was opened since the app started. */
    fun wipe(context: Context) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                MapLibre.getInstance(context.applicationContext)
                regions.clear(); info.clear(); publish()
                OfflineManager.getInstance(context.applicationContext).resetDatabase(object : OfflineManager.FileSourceCallback {
                    override fun onSuccess() = Unit
                    override fun onError(message: String) { MoshMonitor.event(context, "offline_map_error") }
                })
            }
        }
    }

    private fun track(r: OfflineRegion, downloading: Boolean) {
        val meta = runCatching { JSONObject(String(r.metadata)) }.getOrNull()
        val b = r.definition.bounds ?: return
        val box = GeoBox(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast)
        regions[r.id] = r
        info[r.id] = SavedMap(r.id, meta?.optString("name").orEmpty().ifBlank { "Saved map" }, meta?.optLong("at") ?: 0L,
            box, 0L, if (downloading) 0 else null, downloading)
        r.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) = update(r.id, status)
            override fun onError(error: OfflineRegionError) { app?.let { MoshMonitor.event(it, "offline_map_error") } }
            override fun mapboxTileCountLimitExceeded(limit: Long) = Unit
        })
        r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(status: OfflineRegionStatus?) { status?.let { update(r.id, it) } }
            override fun onError(error: String?) = Unit
        })
    }

    private fun update(id: Long, s: OfflineRegionStatus) {
        val old = info[id] ?: return
        val pct = when {
            s.isComplete -> null
            s.requiredResourceCount > 0 -> (s.completedResourceCount * 100 / s.requiredResourceCount).toInt().coerceIn(0, 99)
            else -> 0
        }
        val downloading = !s.isComplete && s.downloadState == OfflineRegion.STATE_ACTIVE
        if (s.isComplete && s.downloadState == OfflineRegion.STATE_ACTIVE) regions[id]?.setDownloadState(OfflineRegion.STATE_INACTIVE)
        val next = old.copy(bytes = s.completedResourceSize, percent = pct, downloading = downloading)
        // Progress arrives per tile: only publish when something the user sees changes.
        if (next.percent != old.percent || next.downloading != old.downloading || next.bytes / 500_000 != old.bytes / 500_000) {
            info[id] = next
            publish()
        } else info[id] = next
    }

    private fun publish() { _maps.value = info.values.sortedByDescending { it.savedAtMs } }
}
