package com.bitchat.android.ui.mosh

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.drawscope.rotate as rotateDraw
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.bitchat.android.mosh.dotField
import com.bitchat.android.mosh.radarFraction
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
import androidx.compose.material.icons.filled.MoreVert
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

/** The three ways to look for friends, as tabs under the Find title. */
private enum class FindView(val label: String) { COMPASS("Compass"), LIST("List"), MAP("Map") }

@Composable
fun FindScreen(
    focusPeerID: String? = null,
    focusMeeting: Boolean = false,
    onAddFriend: () -> Unit = {},
    onMessage: (String) -> Unit = {},
    onMenu: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val context = LocalContext.current
    val venues = remember { loadVenues(context) }
    val me by MoshRuntime.myLocation.collectAsState()
    val locations by MoshRuntime.store.locations.collectAsState()
    val meeting by MoshRuntime.store.meetingPoint.collectAsState()
    val help by MoshRuntime.store.activeHelp.collectAsState()
    val lastHeard by MoshRuntime.store.lastHeardMs.collectAsState()
    var friends by remember { mutableStateOf(MoshRuntime.friends.friends()) }
    var selected by remember { mutableStateOf(if (focusMeeting) MEETING else focusPeerID) }
    var view by remember { mutableStateOf(FindView.COMPASS) }
    var longPress by remember { mutableStateOf<LatLng?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var locationOn by remember { mutableStateOf(locationUsable(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            friends = MoshRuntime.friends.friends(); locationOn = locationUsable(context)
            delay(3_000); now = System.currentTimeMillis()
        }
    }
    val turnOnLocation = rememberTurnOnLocation { locationOn = locationUsable(context) }

    val target = friends.firstOrNull { it.peerID == selected }
    val mp = meeting
    // Finding someone fills the screen; back returns to the view it was opened from.
    androidx.activity.compose.BackHandler(enabled = selected != null) { selected = null }
    if (selected == MEETING && mp != null) {
        FindingView("Meeting point", me, LocationUpdate(mp.lat, mp.lon, 0f, mp.setAtMs, 0), now, MoshColors.Acid,
            locationOn, turnOnLocation, place = true, onMessage = null) { selected = null }
        return
    }
    if (target != null) {
        FindingView("Finding ${target.displayName}", me, locations[target.peerID], now, avatarColor(target.peerID),
            locationOn, turnOnLocation, name = target.displayName, onMessage = { onMessage(target.peerID) }) { selected = null }
        return
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        MoshTitle("Find") {
            if (onMenu.isNotEmpty()) Box {
                var open by remember { mutableStateOf(false) }
                IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                }
                androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    onMenu.forEach { (label, go) ->
                        androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, onClick = { open = false; go() })
                    }
                }
            }
        }
        FindTabs(view) { view = it }
        when (view) {
            FindView.COMPASS -> CompassView(friends, locations, me, meeting, now, locationOn, turnOnLocation, onAddFriend) { selected = it }
            FindView.LIST -> Column(Modifier.fillMaxSize()) {
                if (!locationOn) WarnNote("Your location is off, so distances can't be shown.", "Turn on", turnOnLocation)
                else if (!MoshRuntime.prefs.shareLocation) WarnNote("You're not sharing your location, so friends can't find you.", null, null)
                meeting?.let { MeetingRow(it, me, onFind = { selected = MEETING }) }
                FriendList(friends, locations, lastHeard, me, now, onAddFriend) { selected = it }
            }
            FindView.MAP -> {
                val pins = buildPins(friends, locations, me, meeting, help, now, selected)
                // Draw the venue around me, else around a friend; with nobody located yet, preview the first venue.
                val venue = VenueRegistry.around(pins.sortedByDescending { it.color == CYAN }.map { it.lat to it.lon }, venues)
                    ?: venues.firstOrNull().takeIf { pins.isEmpty() }
                var fitRequest by remember { mutableIntStateOf(0) }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    VenueMap(venue = venue, pins = pins, fitRequest = fitRequest, onLongPress = { longPress = it })
                    if (venue != null) MapChip(venue.name, Modifier.align(Alignment.TopStart).padding(12.dp), bold = true)
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
                Text("Press and hold the map to set a meeting point.", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
            }
        }
    }

    longPress?.let { at ->
        var label by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { longPress = null },
            title = { Text("Set meeting point here?") },
            text = { OutlinedTextField(value = label, onValueChange = { label = it.take(40) }, label = { Text("Name it") },
                placeholder = { Text("e.g. Big tree by the bar") },
                supportingText = { Text("Something everyone can spot. Left blank, it's called Meeting point.") }) },
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

/** Location permission given and location switched on. */
private fun locationUsable(context: Context): Boolean {
    val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED ||
        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    val on = (context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager)
        ?.let { androidx.core.location.LocationManagerCompat.isLocationEnabled(it) } == true
    return granted && on
}

/** Asks for location permission if it is missing, else opens the phone's location switch. */
@Composable
private fun rememberTurnOnLocation(onResult: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { onResult() }
    return {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) ask.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
        else runCatching {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Segmented Compass | List | Map control from the design. */
@Composable
private fun FindTabs(view: FindView, onPick: (FindView) -> Unit) {
    Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp))
        .background(MaterialTheme.colorScheme.surface).padding(3.dp)) {
        FindView.entries.forEach { v ->
            val on = v == view
            Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp))
                .background(if (on) MaterialTheme.colorScheme.background else Color.Transparent)
                .selectable(selected = on, role = Role.Tab) { onPick(v) }, contentAlignment = Alignment.Center) {
                Text(v.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Amber note above the list, with an optional action. */
@Composable
private fun WarnNote(text: String, action: String?, onAction: (() -> Unit)?) {
    Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(MoshColors.Amber.copy(alpha = 0.18f)).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
        if (action != null && onAction != null) TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 44.dp)) {
            Text(action, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Full-width centred message with one acid action, for empty and off states. */
@Composable
private fun CentreCard(title: String, text: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(text, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.heightIn(min = 52.dp).clip(MoshShapes.Pill).background(MaterialTheme.colorScheme.primary)
            .clickable(role = Role.Button, onClick = onAction).padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
            Text(action, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

/** True when the phone's animator scale is off, so the sweep and pings stay still. */
@Composable
private fun reducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

private data class Blip(val id: String, val name: String, val color: Color, val distanceM: Double, val bearingDeg: Double,
                        val stale: Boolean, val place: Boolean)

/**
 * The design's compass: rings at 50, 200 and 600 m, a sweep, the way the phone faces at the top,
 * you in the middle and each friend placed by direction and distance. Tap someone for an arrow.
 */
@Composable
private fun CompassView(
    friends: List<Friend>, locations: Map<String, LocationUpdate>, me: LocationUpdate?, meeting: MeetingPoint?, now: Long,
    locationOn: Boolean, turnOnLocation: () -> Unit, onAddFriend: () -> Unit, onPick: (String) -> Unit,
) {
    val mates = friends.filter { it.status == FriendStatus.FRIEND }
    when {
        !locationOn -> {
            CentreCard("Your location is off", "The compass needs it to show which way your friends are.",
                "Turn on location", turnOnLocation, Modifier.fillMaxSize())
            return
        }
        friends.isEmpty() && meeting == null -> {
            CentreCard("Nobody to find yet", "Friends appear here once you've added each other.", "Add a friend", onAddFriend,
                Modifier.fillMaxSize())
            return
        }
        me == null -> {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text("Finding where you are…", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Stand in the open for a moment. The compass appears once your phone has a GPS fix.", fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            return
        }
    }
    val my = me!!
    val compass = rememberHeading()
    val heading = compass.headingDeg
    val blips = mates.mapNotNull { f ->
        val l = locations[f.peerID] ?: return@mapNotNull null
        Blip(f.peerID, f.displayName, avatarColor(f.peerID), distanceM(my.lat, my.lon, l.lat, l.lon),
            com.bitchat.android.mosh.bearingDeg(my.lat, my.lon, l.lat, l.lon), stale = now - l.takenAtMs > 600_000, place = false)
    } + listOfNotNull(meeting?.let {
        Blip(MEETING, "Meeting point", MoshColors.Acid, distanceM(my.lat, my.lon, it.lat, it.lon),
            com.bitchat.android.mosh.bearingDeg(my.lat, my.lon, it.lat, it.lon), stale = false, place = true)
    })
    val off = friends.filter { f -> f.status != FriendStatus.FRIEND || locations[f.peerID] == null }.map { f ->
        f.displayName + if (f.status != FriendStatus.FRIEND) " (hasn't added you yet)" else " (no location yet)"
    }
    // The compass shrinks when banners (a friend's Help, a warning) take room, so "Phone facing" stays in view.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
    val side = minOf(maxWidth - 40.dp, maxHeight - 96.dp, 330.dp).coerceAtLeast(180.dp)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Radar(blips, heading ?: 0.0, onPick, side)
        Spacer(Modifier.height(6.dp))
        Text(if (heading != null) "Phone facing ${compassWords(heading)}" else "Top of the circle is north",
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(
            when {
                heading != null -> "Turn with your phone. Tap someone to get an arrow to them."
                compass.available -> "Hold your phone flat to turn the compass with you. Tap someone to get an arrow to them."
                else -> "No compass on this phone. Tap someone to get an arrow to them."
            },
            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        if (compass.needsCalibration) {
            Text("Compass unsure: move your phone in a figure of 8.", fontSize = 14.sp, color = MoshColors.Amber,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
        if (off.isNotEmpty()) {
            Text("Not on the compass: ${off.joinToString(", ")}", fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(12.dp))
    }
    }
}

@Composable
private fun Radar(blips: List<Blip>, headingDeg: Double, onPick: (String) -> Unit, maxSide: androidx.compose.ui.unit.Dp = 330.dp) {
    val reduce = reducedMotion()
    val line = MaterialTheme.colorScheme.outlineVariant
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    val bg = MaterialTheme.colorScheme.background
    val accent = MaterialTheme.colorScheme.primary
    val spin = androidx.compose.animation.core.rememberInfiniteTransition(label = "sweep")
    val sweep by spin.animateFloat(0f, if (reduce) 0f else 360f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(4_000, easing = androidx.compose.animation.core.LinearEasing)),
        label = "sweepAngle")
    val ping by spin.animateFloat(0f, if (reduce) 0f else 1f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1_800, easing = androidx.compose.animation.core.LinearEasing)),
        label = "ping")
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxSide)
        val k = side.value / 330f
        Box(Modifier.size(side).semantics { contentDescription = "Compass: friends around you. The top is the way your phone is facing." }) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val c = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
                val outer = 150.dp.toPx() * k
                listOf(50.0, 200.0, 600.0).forEach { d ->
                    drawCircle(line, radius = (radarFraction(d) * outer).toFloat(), center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                }
                // Sweep: a soft acid tail behind the leading edge, turning once every four seconds.
                rotateDraw(sweep - 90f, c) {
                    drawCircle(androidx.compose.ui.graphics.Brush.sweepGradient(
                        0f to Color.Transparent, 290f / 360f to Color.Transparent, 1f to accent.copy(alpha = 0.33f), center = c),
                        radius = outer, center = c)
                }
                // Heading line from the top edge to you, with a pointer at the top.
                drawLine(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(accent, Color.Transparent), startY = 0f, endY = c.y),
                    androidx.compose.ui.geometry.Offset(c.x, 0f), c, strokeWidth = 2.dp.toPx())
                val tri = androidx.compose.ui.graphics.Path().apply {
                    moveTo(c.x, -2.dp.toPx()); lineTo(c.x + 8.dp.toPx(), 10.dp.toPx()); lineTo(c.x - 8.dp.toPx(), 10.dp.toPx()); close()
                }
                drawPath(tri, accent)
                // You: cyan dot with a dark ring and a halo.
                drawCircle(MoshColors.Cyan.copy(alpha = 0.22f), radius = 18.dp.toPx(), center = c)
                drawCircle(bg, radius = 10.dp.toPx(), center = c)
                drawCircle(MoshColors.Cyan, radius = 7.dp.toPx(), center = c)
            }
            listOf(50.0 to "50 m", 200.0 to "200 m", 600.0 to "600 m").forEach { (d, label) ->
                val r = (radarFraction(d) * 150 * k).toFloat()
                Text(label, fontSize = 12.sp, color = soft,
                    modifier = Modifier.offset(x = (172 * k).dp, y = (165 * k - r + 2).dp))
            }
            blips.forEach { b ->
                val r = radarFraction(b.distanceM) * 150 * k
                val a = Math.toRadians(b.bearingDeg - headingDeg)
                val x = 165 * k + r * Math.sin(a); val y = 165 * k - r * Math.cos(a)
                val dist = (if (b.stale) "~" else "") + metres(b.distanceM)
                Column(
                    Modifier.offset(x = (x - 60).dp, y = (y - 24).dp).width(120.dp)
                        .alpha(if (b.stale) 0.5f else 1f)
                        .clickable(role = Role.Button, onClickLabel = "Get an arrow") { onPick(b.id) }
                        .semantics(mergeDescendants = true) {
                            contentDescription = "${b.name}, $dist, " + directionWords(b.bearingDeg - headingDeg) +
                                (if (b.stale) ", old location" else "") + ". Get an arrow."
                        },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        val shape = if (b.place) RoundedCornerShape(8.dp) else CircleShape
                        if (!b.stale && !b.place) {
                            Box(Modifier.size(40.dp).graphicsLayer { scaleX = 1f + ping * 0.8f; scaleY = 1f + ping * 0.8f; alpha = 1f - ping }
                                .border(2.dp, b.color, shape))
                        }
                        Box(Modifier.size(40.dp).rotate(if (b.place) 45f else 0f).clip(shape).background(b.color).border(3.dp, bg, shape),
                            contentAlignment = Alignment.Center) {
                            Text(initials(if (b.place) "M P" else b.name), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                modifier = Modifier.rotate(if (b.place) -45f else 0f))
                        }
                    }
                    Text(if (b.place) "Meeting point" else "${b.name} · $dist", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 6.dp))
                }
            }
        }
    }
}

private fun initials(name: String) = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
    .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }.take(2)

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
private fun FriendList(friends: List<Friend>, locations: Map<String, LocationUpdate>, lastHeard: Map<String, Long>, me: LocationUpdate?,
                       now: Long, onAddFriend: () -> Unit, onPick: (String) -> Unit) {
    if (friends.isEmpty()) {
        CentreCard("Nobody to find yet", "Friends appear here once you've added each other.", "Add a friend", onAddFriend,
            Modifier.padding(vertical = 40.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
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
                if (f.status == FriendStatus.FRIEND) FindPill("Find") { onPick(f.peerID) }
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
private fun BigDistance(d: Double, approx: Boolean = false) {
    val (num, unit) = metres(d).split(" ").let { it[0] to it.getOrElse(1) { "" } }
    Text(buildAnnotatedString {
        append((if (approx) "~" else "") + num)
        withStyle(SpanStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(" $unit") }
    }, fontSize = 88.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 88.sp, letterSpacing = (-2).sp)
}

/**
 * Finding one person or the meeting point, as in the design: a dot-field arrow in their colour, the
 * distance huge, the direction in words, how old their location is, then Message and Buzz me at 20 m.
 */
@Composable
private fun FindingView(
    title: String, me: LocationUpdate?, target: LocationUpdate?, now: Long, color: Color,
    locationOn: Boolean, turnOnLocation: () -> Unit, name: String = "", place: Boolean = false,
    onMessage: (() -> Unit)?, onBack: () -> Unit,
) {
    val compass = rememberHeading()
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    val context = LocalContext.current
    var buzz by remember { mutableStateOf(false) }
    val stale = !place && target != null && now - target.takenAtMs > 600_000
    val g = if (me != null && target != null) guidance(me, target) else null
    val dist = when (g) { is Guidance.Arrow -> g.distanceM; Guidance.CloseLookAround -> 0.0; null -> null }
    val close = g == Guidance.CloseLookAround || (dist != null && dist <= 20)
    // Buzz once when they come within 20 m, then switch off.
    LaunchedEffect(buzz, close) {
        if (buzz && close && !stale) { vibrate(context); buzz = false }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
        }
        when {
            !locationOn -> CentreCard("Your location is off",
                "Turn it on so MoshGrid can point you to ${if (place) "the meeting point" else name}.",
                "Turn on location", turnOnLocation, Modifier.weight(1f))
            target == null -> CentreCard("No location from $name yet",
                "It appears once they're in reach and sharing. Send a message and it arrives when they are.",
                "Message $name", { onMessage?.invoke() }, Modifier.weight(1f))
            me == null -> Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text("Finding where you are…", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("The arrow appears once your phone has a GPS fix.", fontSize = 16.sp, color = soft, textAlign = TextAlign.Center)
            }
            else -> {
                val bearing = com.bitchat.android.mosh.bearingDeg(me.lat, me.lon, target.lat, target.lon)
                val heading = compass.headingDeg
                val rel = if (heading != null) bearing - heading else bearing
                // The arrow shrinks when banners (a friend's Help) take room, so distance and direction stay on screen.
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val arrowSize = (maxHeight - 210.dp).coerceIn(140.dp, 300.dp)
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    DotArrow(if (close) null else rel, color, dim = stale, side = arrowSize,
                        description = if (close) "Close by" else "Arrow pointing ${directionWords(rel)}, ${metres(dist ?: 0.0)}")
                    Spacer(Modifier.height(12.dp))
                    if (g == Guidance.CloseLookAround) {
                        Text("Close", fontSize = 88.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 88.sp)
                    } else BigDistance(dist ?: 0.0, approx = stale)
                    Text(
                        when {
                            stale -> "Last known direction"
                            close -> "Right around you"
                            heading != null -> directionWords(rel).replaceFirstChar { it.uppercase() }
                            else -> "To the ${compassWords(bearing)}"
                        },
                        fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        when {
                            place -> "Set on the map"
                            stale -> "$name's location is ${ago(now - target.takenAtMs).removeSuffix(" ago")} old. $name may have moved."
                            else -> "Location from ${ago(now - target.takenAtMs)}"
                        },
                        fontSize = 15.sp, color = if (stale) MoshColors.Amber else soft, textAlign = TextAlign.Center,
                    )
                    if (heading == null && !close) {
                        Text(if (compass.available) "Hold your phone flat to point the arrow the way you face."
                            else "No compass on this phone: the arrow points as if up is north.",
                            fontSize = 14.sp, color = soft, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                    } else if (compass.needsCalibration && !close) {
                        Text("Compass unsure: move your phone in a figure of 8.", fontSize = 14.sp, color = MoshColors.Amber,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                    }
                    if (close) {
                        Box(Modifier.padding(top = 16.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                            .padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(if (place) "You're there." else "Within 20 m. GPS can't tell the last few metres: look up and wave.",
                                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                        }
                    }
                }
                }
            }
        }
        if (locationOn && target != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onMessage != null) {
                    PillButton(if (stale) "Ask $name for an update" else "Message", MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.onSurface, Modifier.weight(1f), onMessage)
                }
                if (!stale && !close) {
                    PillButton(if (buzz) "Will buzz at 20 m" else "Buzz me at 20 m",
                        if (buzz) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        if (buzz) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                        Modifier.weight(1f).semantics { stateDescription = if (buzz) "On" else "Off" }) { buzz = !buzz }
                }
            }
        }
    }
}

@Composable
private fun PillButton(label: String, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(52.dp).clip(MoshShapes.Pill).background(bg).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, textAlign = TextAlign.Center)
    }
}

/** The 17 by 17 dot field; [relativeDeg] null lights only the centre (they are close). */
@Composable
private fun DotArrow(relativeDeg: Double?, color: Color, dim: Boolean, description: String, side: androidx.compose.ui.unit.Dp = 300.dp) {
    val dots = remember(relativeDeg?.let { Math.round(it / 2) }) { dotField(relativeDeg ?: 0.0) }
    val grey = Color(0xFF8A8A8A)
    val ink = MaterialTheme.colorScheme.onSurface
    androidx.compose.foundation.Canvas(Modifier.padding(top = 12.dp).size(side)
        .semantics { contentDescription = description }) {
        val cell = size.width / 17
        dots.forEachIndexed { i, d ->
            if (d.alpha == 0f) return@forEachIndexed
            val cx = (i % 17 + 0.5f) * cell; val cy = (i / 17 + 0.5f) * cell
            val (c, a) = when {
                d.centre -> ink to 1f
                relativeDeg != null && d.lit -> color to d.alpha * (if (dim) 0.4f else 1f)
                else -> grey to 0.22f
            }
            drawCircle(c.copy(alpha = a), radius = cell * (if (d.centre) 0.4f else 0.26f),
                center = androidx.compose.ui.geometry.Offset(cx, cy))
        }
    }
}

@Suppress("DEPRECATION")
private fun vibrate(context: Context) {
    val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
    if (android.os.Build.VERSION.SDK_INT >= 26) {
        v.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
    } else v.vibrate(longArrayOf(0, 300, 150, 300), -1)
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
