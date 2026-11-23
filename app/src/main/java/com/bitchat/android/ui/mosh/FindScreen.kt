package com.bitchat.android.ui.mosh

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bitchat.android.mosh.Friend
import com.bitchat.android.mosh.FriendStatus
import com.bitchat.android.mosh.Guidance
import com.bitchat.android.mosh.HelpRepeater
import com.bitchat.android.mosh.LocationUpdate
import com.bitchat.android.mosh.MeetingPoint
import com.bitchat.android.mosh.MoshRuntime
import com.bitchat.android.mosh.Venue
import com.bitchat.android.mosh.VenueRegistry
import com.bitchat.android.mosh.directionWords
import com.bitchat.android.mosh.distanceM
import com.bitchat.android.mosh.guidance
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.util.UUID

private const val MEETING = "__meeting__"
private const val ACID = "#C6FF3D"
private const val CYAN = "#3DF2FF"
private const val PINK = "#FF5CDB"
private const val RED = "#E8222B"
private const val GREY = "#6E6E6E"

/** Background-only style: venue features come from the bundled GeoJSON, so no tiles or glyphs are needed. */
private const val BASE_STYLE = """{"version":8,"sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#050505"}}]}"""

@Composable
fun FindScreen(focusPeerID: String? = null, focusMeeting: Boolean = false) {
    val context = LocalContext.current
    val venues = remember { loadVenues(context) }
    val me by MoshRuntime.myLocation.collectAsState()
    val locations by MoshRuntime.store.locations.collectAsState()
    val meeting by MoshRuntime.store.meetingPoint.collectAsState()
    val help by MoshRuntime.store.activeHelp.collectAsState()
    val lastHeard by MoshRuntime.store.lastHeardMs.collectAsState()
    var friends by remember { mutableStateOf(MoshRuntime.friends.friends()) }
    var selected by remember { mutableStateOf(if (focusMeeting) MEETING else focusPeerID) }
    var longPress by remember { mutableStateOf<LatLng?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { delay(5_000); now = System.currentTimeMillis(); friends = MoshRuntime.friends.friends() }
    }
    val venue = remember(me == null) { me?.let { VenueRegistry.nearest(it.lat, it.lon, venues) } ?: venues.firstOrNull() }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            VenueMap(
                venue = venue,
                pins = buildPins(friends, locations, me, meeting, help, now),
                onLongPress = { longPress = it },
            )
            Text(
                "© OpenStreetMap contributors",
                fontSize = 10.sp, color = Color(0xFF9E9E9E),
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
            )
            if (venue != null) {
                Text(venue.name, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        meeting?.let { MeetingBanner(it, me, onFind = { selected = MEETING }) }
        val target = friends.firstOrNull { it.peerID == selected }
        val mp = meeting
        if (selected == MEETING && mp != null) {
            GuidancePanel("Going to ${mp.label}", me, LocationUpdate(mp.lat, mp.lon, 0f, mp.setAtMs, 0), now, showAge = false) { selected = null }
        } else if (target != null) {
            GuidancePanel("Finding ${target.displayName}", me, locations[target.peerID], now) { selected = null }
        } else {
            FriendList(friends, locations, lastHeard, me, now) { selected = it }
        }
    }

    longPress?.let { at ->
        var label by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { longPress = null },
            title = { Text("Set meeting point here?") },
            text = { OutlinedTextField(value = label, onValueChange = { label = it.take(40) }, label = { Text("Name it (e.g. big tree by the bar)") }) },
            confirmButton = {
                TextButton(onClick = {
                    MoshRuntime.setMeetingPoint(MeetingPoint(UUID.randomUUID().toString().take(12), at.latitude, at.longitude,
                        label.ifBlank { "Meeting point" }, System.currentTimeMillis(), "me", cleared = false))
                    longPress = null
                }) { Text("Set for all friends") }
            },
            dismissButton = { TextButton(onClick = { longPress = null }) { Text("Cancel") } },
        )
    }
}

private data class Pin(val lat: Double, val lon: Double, val color: String, val radius: Float)

private fun buildPins(
    friends: List<Friend>, locations: Map<String, LocationUpdate>, me: LocationUpdate?,
    meeting: MeetingPoint?, help: Map<String, com.bitchat.android.mosh.HelpAlert>, now: Long,
): List<Pin> {
    val pins = mutableListOf<Pin>()
    meeting?.let { pins += Pin(it.lat, it.lon, ACID, 11f) }
    friends.forEach { f ->
        locations[f.peerID]?.let { l ->
            val stale = now - l.takenAtMs > 600_000
            pins += Pin(l.lat, l.lon, if (stale) GREY else PINK, 8f)
        }
    }
    help.values.filterNot { HelpRepeater.isLocationUnknown(it) }.forEach { pins += Pin(it.lat, it.lon, RED, 13f) }
    me?.let { pins += Pin(it.lat, it.lon, CYAN, 7f) }
    return pins
}

@Composable
private fun VenueMap(venue: Venue?, pins: List<Pin>, onLongPress: (LatLng) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(Bundle()) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf<Style?>(null) }

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
            map = m
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isAttributionEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.addOnMapLongClickListener { onLongPress(it); true }
            m.setStyle(Style.Builder().fromJson(BASE_STYLE)) { style ->
                addVenueLayers(context, style, venue)
                style.addSource(GeoJsonSource("pins"))
                style.addLayer(CircleLayer("pins", "pins").withProperties(
                    PropertyFactory.circleColor(Expression.get("color")),
                    PropertyFactory.circleRadius(Expression.get("radius")),
                    PropertyFactory.circleStrokeColor("#000000"),
                    PropertyFactory.circleStrokeWidth(2f),
                ))
                venue?.let { v ->
                    m.moveCamera(CameraUpdateFactory.newLatLngBounds(
                        LatLngBounds.Builder().include(LatLng(v.bbox[1], v.bbox[0])).include(LatLng(v.bbox[3], v.bbox[2])).build(), 0))
                    m.moveCamera(CameraUpdateFactory.zoomBy(1.5))
                }
                styleReady = style
            }
        }
        mapView
    }, modifier = Modifier.fillMaxSize())

    LaunchedEffect(styleReady, pins) {
        val style = styleReady ?: return@LaunchedEffect
        val fc = FeatureCollection.fromFeatures(pins.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
                addStringProperty("color", p.color); addNumberProperty("radius", p.radius)
            }
        })
        (style.getSource("pins") as? GeoJsonSource)?.setGeoJson(fc)
    }
}

private fun addVenueLayers(context: Context, style: Style, venue: Venue?) {
    venue ?: return
    val json = runCatching { context.assets.open(venue.asset).bufferedReader().use { it.readText() } }.getOrNull() ?: return
    style.addSource(GeoJsonSource("venue", json))
    fun kind(k: String) = Expression.eq(Expression.get("kind"), Expression.literal(k))
    style.addLayer(FillLayer("green", "venue").withFilter(kind("green")).withProperties(PropertyFactory.fillColor("#12301A")))
    style.addLayer(FillLayer("water", "venue").withFilter(kind("water")).withProperties(PropertyFactory.fillColor("#0B2A3D")))
    style.addLayer(FillLayer("building", "venue").withFilter(kind("building")).withProperties(PropertyFactory.fillColor("#1F1F1F")))
    style.addLayer(FillLayer("amenity", "venue").withFilter(kind("amenity")).withProperties(PropertyFactory.fillColor("#3A1214")))
    style.addLayer(LineLayer("path", "venue").withFilter(kind("path")).withProperties(
        PropertyFactory.lineColor("#3A3A3A"), PropertyFactory.lineWidth(1f)))
    style.addLayer(LineLayer("road_minor", "venue").withFilter(kind("road_minor")).withProperties(
        PropertyFactory.lineColor("#4A4A4A"), PropertyFactory.lineWidth(1.5f)))
    style.addLayer(LineLayer("road_major", "venue").withFilter(kind("road_major")).withProperties(
        PropertyFactory.lineColor("#7A7A7A"), PropertyFactory.lineWidth(3f)))
}

private fun loadVenues(context: Context): List<Venue> = runCatching {
    VenueRegistry.load(context.assets.open("venues/venues.json").bufferedReader().use { it.readText() })
}.getOrDefault(emptyList())

private fun ago(ms: Long): String = when {
    ms < 60_000 -> "just now"
    ms < 3_600_000 -> "${ms / 60_000} min ago"
    else -> "${ms / 3_600_000} h ago"
}

private fun metres(d: Double): String = if (d < 1000) "${d.toInt()} m" else "%.1f km".format(d / 1000)

@Composable
private fun FriendList(friends: List<Friend>, locations: Map<String, LocationUpdate>, lastHeard: Map<String, Long>, me: LocationUpdate?, now: Long, onPick: (String) -> Unit) {
    if (friends.isEmpty()) {
        Text("No friends yet. Add them in person from the Friends tab.", modifier = Modifier.padding(20.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
        items(friends, key = { it.peerID }) { f ->
            val l = locations[f.peerID]
            val heard = lastHeard[f.peerID]
            val line = when {
                f.status == FriendStatus.WAITING_FOR_THEM -> "Waiting for them to scan you"
                l == null -> "No location yet"
                else -> {
                    val dist = me?.let { metres(distanceM(it.lat, it.lon, l.lat, l.lon)) } ?: "?"
                    val stale = now - l.takenAtMs > 600_000
                    "$dist · ±${l.accuracyM.toInt()} m · location ${ago(now - l.takenAtMs)}" + if (stale) " (old)" else ""
                }
            }
            val silent = heard == null || now - heard > 900_000
            Row(Modifier.fillMaxWidth().clickable { onPick(f.peerID) }.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.displayName, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(line, fontSize = 14.sp, color = if (l != null && now - l.takenAtMs > 600_000) Color(0xFFFFC266) else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (silent && f.status == FriendStatus.FRIEND) {
                        Text(if (heard == null) "Not heard from yet" else "Last heard ${ago(now - heard)}", fontSize = 12.sp, color = Color(0xFFFFC266))
                    }
                }
                Text("Find", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MeetingBanner(mp: MeetingPoint, me: LocationUpdate?, onFind: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF2C3A0E)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Meeting point: ${mp.label}", fontWeight = FontWeight.Bold)
            me?.let { Text(metres(distanceM(it.lat, it.lon, mp.lat, mp.lon)) + " away", fontSize = 13.sp) }
        }
        TextButton(onClick = onFind) { Text("Find") }
        TextButton(onClick = {
            MoshRuntime.setMeetingPoint(mp.copy(setAtMs = System.currentTimeMillis(), setBy = "me", cleared = true))
        }) { Text("Clear") }
    }
}

@Composable
private fun GuidancePanel(title: String, me: LocationUpdate?, target: LocationUpdate?, now: Long, showAge: Boolean = true, onClose: () -> Unit) {
    val azimuth = rememberAzimuth()
    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        when {
            target == null -> Text("No location from them yet.")
            me == null -> Text("Waiting for your GPS…")
            else -> when (val g = guidance(me, target)) {
                Guidance.CloseLookAround -> {
                    Text("Close", fontSize = 56.sp, fontWeight = FontWeight.ExtraBold)
                    Text("GPS can't tell the last few metres. Look around, or call out.")
                }
                is Guidance.Arrow -> {
                    val rel = g.bearingDeg - azimuth
                    Text("↑", fontSize = 72.sp, color = Color(0xFFFF5CDB), modifier = Modifier.rotate(rel.toFloat()))
                    Text(metres(g.distanceM), fontSize = 48.sp, fontWeight = FontWeight.ExtraBold)
                    Text(directionWords(rel).replaceFirstChar { it.uppercase() }, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (showAge) target?.let { Text("Their location: ${ago(now - it.takenAtMs)}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        TextButton(onClick = onClose) { Text("Back to list") }
    }
}

/** Compass heading in degrees (0 = north), from the rotation-vector sensor. */
@Composable
private fun rememberAzimuth(): Double {
    val context = LocalContext.current
    var azimuth by remember { mutableDoubleStateOf(0.0) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val rot = FloatArray(9); val orient = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, orient)
                azimuth = (Math.toDegrees(orient[0].toDouble()) + 360) % 360
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(listener) }
    }
    return azimuth
}
