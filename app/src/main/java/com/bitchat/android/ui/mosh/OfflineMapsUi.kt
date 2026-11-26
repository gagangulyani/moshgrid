package com.bitchat.android.ui.mosh

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bitchat.android.mosh.GeoBox
import com.bitchat.android.mosh.contains
import com.bitchat.android.mosh.sizeKm
import com.bitchat.android.mosh.MoshRuntime
import com.bitchat.android.mosh.OfflineMapMath
import com.bitchat.android.mosh.OfflineMaps
import com.bitchat.android.mosh.SavedMap
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.text.DateFormat
import java.util.Date

/**
 * Offline maps: move the street map to any place and save it, so Find can draw it with no signal.
 * Built-in venue maps need nothing; this is for everywhere else (the walk to the gig, the hotel).
 */
@Composable
fun OfflineMapsScreen() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val maps by OfflineMaps.maps.collectAsState()
    val internet = MoshRuntime.prefs.internet
    var box by remember { mutableStateOf<GeoBox?>(null) }
    var failed by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SavedMap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // A tap counter rides along so tapping the same map again still moves there.
    var goTo by remember { mutableStateOf<Pair<GeoBox, Int>?>(null) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            StreetMap(goTo = goTo, onFailed = { failed = true }, onMoved = { box = it; failed = false })
            val b = box
            val hint = when {
                !internet -> "Internet is off: only saved maps show"
                b == null -> null
                !OfflineMapMath.fits(b) -> "Too big to save: zoom in"
                else -> b.sizeKm().let { (w, h) -> "Area about %s × %s km".format(km(w), km(h)) }
            }
            hint?.let { MapChip(it, Modifier.align(Alignment.TopCenter).padding(12.dp), bold = true) }
            if (failed) {
                Text(if (internet) "The map couldn't load. Check your connection."
                    else "Turn Internet on (Network) to see and save maps of new places.",
                    fontSize = 14.sp, color = cs.onSurface,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp).clip(MoshShapes.Card)
                        .background(cs.surface.copy(alpha = 0.95f)).padding(16.dp))
            }
            Text("OpenFreeMap © OpenMapTiles © OpenStreetMap", fontSize = 11.sp, color = cs.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .background(cs.surface.copy(alpha = 0.9f), MoshShapes.Pill).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val canSave = internet && box?.let { OfflineMapMath.fits(it) } == true
            if (canSave) FindPill("Save this area") { naming = true }
            Text(if (internet) "Move and zoom the map to the place you need, then save it. Saving tells OpenFreeMap your internet address and the area; the map then stays on this phone and works with no signal."
                else "Saved maps work with Internet off. Turn it on in Network to save more.",
                fontSize = 13.sp, color = cs.onSurfaceVariant)
            error?.let { Text(it, fontSize = 14.sp, color = MoshColors.Red) }
            if (maps.isEmpty()) Text("No saved maps yet.", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            maps.forEach { m ->
                SavedMapRow(m, onOpen = { goTo = m.box to ((goTo?.second ?: 0) + 1) }, onResume = { OfflineMaps.resume(m.id) }.takeIf { internet },
                    onDelete = { deleting = m })
            }
        }
    }

    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Save this map?") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Name it") },
                placeholder = { Text("e.g. Hotel and the walk to the gig") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    naming = false
                    val b = box ?: return@TextButton
                    error = null
                    if (!OfflineMaps.save(context, name.ifBlank { "Saved map" }, b) { error = it }) error = "That area is too big. Zoom in and try again."
                }) { Text("Save map") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
    deleting?.let { m ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${m.name}?") },
            text = { Text("It's removed from this phone. You'd need signal to save it again.") },
            confirmButton = { TextButton(onClick = { OfflineMaps.delete(context, m.id); deleting = null }) { Text("Delete map") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep it") } },
        )
    }
}

@Composable
private fun SavedMapRow(m: SavedMap, onOpen: () -> Unit, onResume: (() -> Unit)?, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(MoshShapes.Card).background(cs.surface).clickable(onClick = onOpen)
        .padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(m.name, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            val size = mb(m.bytes)
            Text(when {
                m.percent == null -> "Saved · $size" + if (m.savedAtMs > 0) " · " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(m.savedAtMs)) else ""
                m.downloading -> "Saving… ${m.percent}% · $size"
                else -> "Stopped at ${m.percent}%"
            }, fontSize = 13.sp, color = cs.onSurfaceVariant)
        }
        if (m.percent != null && !m.downloading && onResume != null) TextButton(onClick = onResume) { Text("Continue") }
        IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Delete, contentDescription = "Delete ${m.name}")
        }
    }
}

/** The street map, reporting the area in view when it stops moving. */
@Composable
private fun StreetMap(goTo: Pair<GeoBox, Int>?, onFailed: () -> Unit, onMoved: (GeoBox) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        OfflineMaps.ensure(context)
        MapView(context).apply { onCreate(Bundle()) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        mapView.onStart(); mapView.onResume()
        onDispose { lifecycle.removeObserver(obs); mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
    }
    AndroidView(factory = {
        mapView.getMapAsync { m ->
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isAttributionEnabled = false
            m.uiSettings.isLogoEnabled = false
            // Start where it's useful: around me, else the newest saved map, else the whole country.
            val me = MoshRuntime.myLocation.value
            val first = OfflineMaps.maps.value.firstOrNull()?.box
            m.cameraPosition = when {
                me != null -> CameraPosition.Builder().target(LatLng(me.lat, me.lon)).zoom(12.0).build()
                first != null -> CameraPosition.Builder().target(LatLng((first.south + first.north) / 2, (first.west + first.east) / 2)).zoom(11.0).build()
                else -> CameraPosition.Builder().target(LatLng(22.5, 79.0)).zoom(3.5).build()
            }
            m.addOnCameraIdleListener {
                val b = m.projection.visibleRegion.latLngBounds
                onMoved(GeoBox(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast))
            }
            mapView.addOnDidFailLoadingMapListener {
                com.bitchat.android.mosh.MoshMonitor.event(context, "map_failed")
                onFailed()
            }
            m.setStyle(Style.Builder().fromUri(OfflineMaps.STREET_STYLE))
            map = m
        }
        mapView
    }, modifier = Modifier.fillMaxSize())

    LaunchedEffect(map, goTo) {
        val m = map ?: return@LaunchedEffect
        val b = goTo?.first ?: return@LaunchedEffect
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(
            LatLngBounds.from(b.north, b.east, b.south, b.west), (24 * context.resources.displayMetrics.density).toInt()))
    }
}

private fun km(v: Double) = if (v < 10) "%.1f".format(v) else v.toInt().toString()
private fun mb(bytes: Long) = if (bytes < 1_000_000) "${bytes / 1000} KB" else "%.1f MB".format(bytes / 1e6)
