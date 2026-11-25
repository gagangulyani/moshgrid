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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
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
import androidx.compose.runtime.mutableIntStateOf
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
import com.bitchat.android.mosh.compassWords
import com.bitchat.android.mosh.fitBox
import com.bitchat.android.mosh.headingFromRotationMatrix
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
    val pins = buildPins(friends, locations, me, meeting, help, now, selected)
    // Draw the venue around me, else around a friend; with nobody located yet, preview the first venue.
    val venue = VenueRegistry.around(pins.sortedByDescending { it.color == CYAN }.map { it.lat to it.lon }, venues)
        ?: venues.firstOrNull().takeIf { pins.isEmpty() }
    var fitRequest by remember { mutableIntStateOf(0) }

    val target = friends.firstOrNull { it.peerID == selected }
    val mp = meeting
    val finding = (selected == MEETING && mp != null) || target != null

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (!finding) MoshTitle("Find")
        // The map stays in place while finding someone, so MapLibre is not torn down and rebuilt.
        Box(Modifier.fillMaxWidth().weight(1f)) {
            VenueMap(
                venue = venue,
                pins = pins,
                fitRequest = fitRequest,
                onLongPress = { longPress = it },
            )
            if (venue != null) {
                MapChip(venue.name, Modifier.align(Alignment.TopStart).padding(12.dp), bold = true)
            }
            if (pins.isNotEmpty()) {
                MapChip("Show everyone", Modifier.align(Alignment.TopEnd).padding(12.dp), bold = true, onClick = { fitRequest++ })
            }
            MapLegend(pins, Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 34.dp))
            Text(
                "© OpenStreetMap contributors",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (selected == MEETING && mp != null) {
            GuidancePanel("Going to ${mp.label}", me, LocationUpdate(mp.lat, mp.lon, 0f, mp.setAtMs, 0), now,
                arrowColor = MoshColors.Acid, showAge = false) { selected = null }
        } else if (target != null) {
            GuidancePanel("Finding ${target.displayName}", me, locations[target.peerID], now,
                arrowColor = MoshColors.Pink) { selected = null }
        } else {
            meeting?.let { MeetingRow(it, me, onFind = { selected = MEETING }) }
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

/** [stroke] is white for whoever is being found, so they stand out among several friends. */
private data class Pin(val lat: Double, val lon: Double, val color: String, val radius: Float, val stroke: String = "#000000")

/** Rounded overlay on the map; tappable when [onClick] is given. */
@Composable
private fun MapChip(text: String, modifier: Modifier, bold: Boolean = false, onClick: (() -> Unit)? = null) {
    val base = modifier.clip(MoshShapes.Pill).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
    val m = if (onClick != null) base.clickable(onClick = onClick).heightIn(min = 48.dp) else base.heightIn(min = 36.dp)
    Box(m.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 14.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/** What each colour means, for the colours on the map right now. */
@Composable
private fun MapLegend(pins: List<Pin>, modifier: Modifier) {
    val shown = pins.map { it.color }.toSet()
    val entries = listOf(CYAN to "You", PINK to "Friend", GREY to "Old location", RED to "Help", ACID to "Meeting point")
        .filter { it.first in shown }
    if (entries.isEmpty()) return
    Row(modifier.clip(MoshShapes.Pill).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
        .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        entries.forEachIndexed { i, (color, label) ->
            if (i > 0) Spacer(Modifier.width(4.dp))
            Box(Modifier.size(10.dp).background(Color(android.graphics.Color.parseColor(color)), CircleShape))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
    }
}

private fun buildPins(
    friends: List<Friend>, locations: Map<String, LocationUpdate>, me: LocationUpdate?,
    meeting: MeetingPoint?, help: Map<String, com.bitchat.android.mosh.HelpAlert>, now: Long, selected: String?,
): List<Pin> {
    val pins = mutableListOf<Pin>()
    fun stroke(isSelected: Boolean) = if (isSelected) "#FFFFFF" else "#000000"
    meeting?.let { pins += Pin(it.lat, it.lon, ACID, if (selected == MEETING) 14f else 11f, stroke(selected == MEETING)) }
    friends.forEach { f ->
        locations[f.peerID]?.let { l ->
            val stale = now - l.takenAtMs > 600_000
            pins += Pin(l.lat, l.lon, if (stale) GREY else PINK, if (f.peerID == selected) 11f else 8f, stroke(f.peerID == selected))
        }
    }
    help.filterValues { !HelpRepeater.isLocationUnknown(it) }.forEach { (peer, h) -> pins += Pin(h.lat, h.lon, RED, 13f, stroke(peer == selected)) }
    me?.let { pins += Pin(it.lat, it.lon, CYAN, 7f) }
    return pins
}

@Composable
private fun VenueMap(venue: Venue?, pins: List<Pin>, fitRequest: Int, onLongPress: (LatLng) -> Unit) {
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
                style.addSource(GeoJsonSource("pins"))
                style.addLayer(CircleLayer("pins", "pins").withProperties(
                    PropertyFactory.circleColor(Expression.get("color")),
                    PropertyFactory.circleRadius(Expression.get("radius")),
                    PropertyFactory.circleStrokeColor(Expression.get("stroke")),
                    PropertyFactory.circleStrokeWidth(2.5f),
                ))
                styleReady = style
            }
        }
        mapView
    }, modifier = Modifier.fillMaxSize())

    // Venue drawing follows the venue (it changes when your first fix arrives or you walk to another one).
    LaunchedEffect(styleReady, venue?.id) {
        val style = styleReady ?: return@LaunchedEffect
        replaceVenueLayers(context, style, venue)
    }

    // Frame everyone: when the venue changes, someone appears or disappears, or "Show everyone" is tapped.
    // Position updates alone do not move the camera, so it never fights the user's panning.
    LaunchedEffect(styleReady, venue?.id, pins.size, fitRequest) {
        val m = map ?: return@LaunchedEffect
        styleReady ?: return@LaunchedEffect
        val box = fitBox(pins.map { it.lat to it.lon })
        if (box != null) {
            val bounds = LatLngBounds.Builder().include(LatLng(box.south, box.west)).include(LatLng(box.north, box.east)).build()
            val pad = (48 * context.resources.displayMetrics.density).toInt()
            m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad))
        } else if (venue != null) {
            m.moveCamera(CameraUpdateFactory.newLatLngBounds(
                LatLngBounds.Builder().include(LatLng(venue.bbox[1], venue.bbox[0])).include(LatLng(venue.bbox[3], venue.bbox[2])).build(), 0))
            m.moveCamera(CameraUpdateFactory.zoomBy(1.5))
        }
    }

    LaunchedEffect(styleReady, pins) {
        val style = styleReady ?: return@LaunchedEffect
        val fc = FeatureCollection.fromFeatures(pins.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
                addStringProperty("color", p.color); addNumberProperty("radius", p.radius); addStringProperty("stroke", p.stroke)
            }
        })
        (style.getSource("pins") as? GeoJsonSource)?.setGeoJson(fc)
    }
}

private val VENUE_LAYERS = listOf("green", "water", "building", "amenity", "path", "road_minor", "road_major")

/** Swap the drawn venue. Venue layers sit below the pins so pins are never hidden. */
private fun replaceVenueLayers(context: Context, style: Style, venue: Venue?) {
    VENUE_LAYERS.forEach { style.removeLayer(it) }
    style.removeSource("venue")
    venue ?: return
    val json = runCatching { context.assets.open(venue.asset).bufferedReader().use { it.readText() } }.getOrNull() ?: return
    style.addSource(GeoJsonSource("venue", json))
    fun kind(k: String) = Expression.eq(Expression.get("kind"), Expression.literal(k))
    fun add(layer: org.maplibre.android.style.layers.Layer) = style.addLayerBelow(layer, "pins")
    add(FillLayer("green", "venue").withFilter(kind("green")).withProperties(PropertyFactory.fillColor("#12301A")))
    add(FillLayer("water", "venue").withFilter(kind("water")).withProperties(PropertyFactory.fillColor("#0B2A3D")))
    add(FillLayer("building", "venue").withFilter(kind("building")).withProperties(PropertyFactory.fillColor("#1F1F1F")))
    add(FillLayer("amenity", "venue").withFilter(kind("amenity")).withProperties(PropertyFactory.fillColor("#3A1214")))
    add(LineLayer("path", "venue").withFilter(kind("path")).withProperties(
        PropertyFactory.lineColor("#3A3A3A"), PropertyFactory.lineWidth(1f)))
    add(LineLayer("road_minor", "venue").withFilter(kind("road_minor")).withProperties(
        PropertyFactory.lineColor("#4A4A4A"), PropertyFactory.lineWidth(1.5f)))
    add(LineLayer("road_major", "venue").withFilter(kind("road_major")).withProperties(
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

/** Small acid pill, the design's "Find" button. */
@Composable
private fun FindPill(label: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).clip(MoshShapes.Pill).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
        .clickable(onClick = onClick).padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
        Text(label, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
private fun FriendList(friends: List<Friend>, locations: Map<String, LocationUpdate>, lastHeard: Map<String, Long>, me: LocationUpdate?, now: Long, onPick: (String) -> Unit) {
    if (friends.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Nobody to find yet", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("Add friends from Chats with the + button.", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
        items(friends, key = { it.peerID }) { f ->
            val l = locations[f.peerID]
            val heard = lastHeard[f.peerID]
            val stale = l != null && now - l.takenAtMs > 600_000
            val line = when {
                f.status == FriendStatus.WAITING_FOR_THEM -> "Waiting for them to scan you"
                l == null -> "No location yet"
                else -> {
                    val dist = me?.let { metres(distanceM(it.lat, it.lon, l.lat, l.lon)) + " · " } ?: ""
                    dist + (if (stale) "old location, " else "") + ago(now - l.takenAtMs)
                }
            }
            val silent = heard == null || now - heard > 900_000
            Row(Modifier.fillMaxWidth().clickable { onPick(f.peerID) }.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MoshAvatar(f.displayName, f.peerID, size = 48.dp)
                Column(Modifier.weight(1f)) {
                    Text(f.displayName, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1)
                    Text(line, fontSize = 14.sp, color = if (stale) MoshColors.Amber else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (silent && f.status == FriendStatus.FRIEND) {
                        Text(if (heard == null) "Not heard from yet" else "Last heard ${ago(now - heard)}", fontSize = 13.sp, color = MoshColors.Amber)
                    }
                }
                FindPill("Find") { onPick(f.peerID) }
            }
        }
    }
}

@Composable
private fun MeetingRow(mp: MeetingPoint, me: LocationUpdate?, onFind: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center) {
            Text("◆", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp)
        }
        Column(Modifier.weight(1f)) {
            Text("Meeting point", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            val away = me?.let { " · " + metres(distanceM(it.lat, it.lon, mp.lat, mp.lon)) + " away" } ?: ""
            Text(mp.label + away, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        var confirm by remember { mutableStateOf(false) }
        TextButton(onClick = { confirm = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Clear", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
        }
        FindPill("Find", onFind)
        if (confirm) AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Clear the meeting point?") },
            text = { Text("It disappears for all your friends.") },
            confirmButton = {
                TextButton(onClick = {
                    MoshRuntime.setMeetingPoint(mp.copy(setAtMs = System.currentTimeMillis(), setBy = "me", cleared = true))
                    confirm = false
                }) { Text("Clear for everyone") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep it") } },
        )
    }
}

/** The design's huge distance: number in 88sp ExtraBold, unit smaller and grey. */
@Composable
private fun BigDistance(d: Double) {
    val (num, unit) = metres(d).split(" ").let { it[0] to it.getOrElse(1) { "" } }
    Text(buildAnnotatedString {
        append(num)
        withStyle(SpanStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(" $unit") }
    }, fontSize = 88.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 88.sp, letterSpacing = (-2).sp)
}

@Composable
private fun GuidancePanel(title: String, me: LocationUpdate?, target: LocationUpdate?, now: Long, arrowColor: Color,
                          showAge: Boolean = true, onClose: () -> Unit) {
    val compass = rememberHeading()
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to list") }
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                target == null -> Text("No location from them yet.", fontSize = 17.sp, color = soft)
                me == null -> Text("Waiting for your GPS…", fontSize = 17.sp, color = soft)
                else -> when (val g = guidance(me, target)) {
                    Guidance.CloseLookAround -> {
                        Text("Close", fontSize = 88.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 88.sp)
                        Box(Modifier.clip(MoshShapes.Card).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                            .padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text("GPS can't tell the last few metres. Look around, or call out.", fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                        }
                    }
                    is Guidance.Arrow -> {
                        val heading = compass.headingDeg
                        if (heading != null) {
                            val rel = g.bearingDeg - heading
                            Text("↑", fontSize = 72.sp, fontWeight = FontWeight.ExtraBold, color = arrowColor, modifier = Modifier.rotate(rel.toFloat()))
                            BigDistance(g.distanceM)
                            Text(directionWords(rel).replaceFirstChar { it.uppercase() }, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            if (compass.needsCalibration) {
                                Text("Compass unsure: move your phone in a figure of 8.", fontSize = 14.sp, color = MoshColors.Amber,
                                    textAlign = TextAlign.Center)
                            }
                        } else {
                            // No compass: the arrow is relative to north, and the words say so.
                            Text("↑", fontSize = 72.sp, fontWeight = FontWeight.ExtraBold, color = arrowColor, modifier = Modifier.rotate(g.bearingDeg.toFloat()))
                            BigDistance(g.distanceM)
                            Text("To the ${compassWords(g.bearingDeg)}", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text(if (compass.available) "Hold your phone still to point the arrow." else "No compass on this phone: the arrow points as if up is north.",
                                fontSize = 14.sp, color = soft, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
        if (showAge && target != null) {
            val stale = now - target.takenAtMs > 600_000
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MoshTile("Their location", Modifier.weight(1f)) {
                    Text(ago(now - target.takenAtMs).replaceFirstChar { it.uppercase() }, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                        color = if (stale) MoshColors.Amber else MaterialTheme.colorScheme.onSurface)
                    if (stale) Text("They may have moved", fontSize = 13.sp, color = MoshColors.Amber)
                }
                MoshTile("Accurate to", Modifier.weight(1f)) {
                    Text("±${target.accuracyM.toInt()} m", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 48.dp).clip(MoshShapes.Pill)
            .background(MaterialTheme.colorScheme.surface).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            Text("Back to list", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
    }
}

private data class Compass(val headingDeg: Double?, val available: Boolean, val needsCalibration: Boolean)

/**
 * Where the phone points (0 = north). Rotation vector first, then the magnetometer-only rotation
 * vector, then accelerometer plus magnetometer (budget phones often have no gyroscope). Smoothed so
 * the arrow does not jitter. [Compass.headingDeg] is null with no compass, or while the phone points
 * straight up or down.
 */
@Composable
private fun rememberHeading(): Compass {
    val context = LocalContext.current
    var compass by remember { mutableStateOf(Compass(null, available = false, needsCalibration = false)) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val rotation = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnet = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val available = rotation != null || (accel != null && magnet != null)
        compass = Compass(null, available, needsCalibration = false)
        val r = FloatArray(9); val gravity = FloatArray(3); val field = FloatArray(3)
        var haveGravity = false; var unreliable = false
        var sx = 0.0; var sy = 0.0; var smoothed = false
        fun update() {
            val h = headingFromRotationMatrix(r)
            if (h == null) { compass = compass.copy(headingDeg = null); return }
            val x = Math.cos(Math.toRadians(h)); val y = Math.sin(Math.toRadians(h))
            if (!smoothed) { sx = x; sy = y; smoothed = true } else { sx += 0.25 * (x - sx); sy += 0.25 * (y - sy) }
            compass = Compass((Math.toDegrees(Math.atan2(sy, sx)) + 360) % 360, available, unreliable)
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(r, e.values); update()
                    }
                    Sensor.TYPE_ACCELEROMETER -> { e.values.copyInto(gravity, 0, 0, 3); haveGravity = true }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        e.values.copyInto(field, 0, 0, 3)
                        if (rotation == null && haveGravity && SensorManager.getRotationMatrix(r, null, gravity, field)) update()
                    }
                }
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {
                if (s?.type == Sensor.TYPE_MAGNETIC_FIELD || s?.type == rotation?.type) {
                    unreliable = accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
                }
            }
        }
        if (rotation != null) sm?.registerListener(listener, rotation, SensorManager.SENSOR_DELAY_UI)
        // The magnetometer's accuracy tells us when the compass needs calibrating, whichever source is used.
        if (magnet != null) sm?.registerListener(listener, magnet, SensorManager.SENSOR_DELAY_UI)
        if (rotation == null && accel != null) sm?.registerListener(listener, accel, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(listener) }
    }
    return compass
}
