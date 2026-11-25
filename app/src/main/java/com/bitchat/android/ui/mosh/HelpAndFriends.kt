package com.bitchat.android.ui.mosh

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.mosh.FriendStatus
import com.bitchat.android.mosh.MoshMonitor
import com.bitchat.android.mosh.MoshRuntime
import kotlinx.coroutines.delay

const val HOLD_SECONDS = 3

/** Hold for 3 seconds to alert friends; repeats every 2 minutes until cancelled. */
@Composable
fun HelpScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var note by remember { mutableStateOf("") }
    var holding by remember { mutableStateOf(false) }
    var left by remember { mutableIntStateOf(HOLD_SECONDS) }
    var active by remember { mutableStateOf(MoshRuntime.help.activeAlertId != null) }
    val ring = remember { Animatable(0f) }

    LaunchedEffect(holding) {
        left = HOLD_SECONDS
        while (holding && left > 0) { delay(1_000); if (holding) left-- }
        if (holding && left == 0) {
            MoshRuntime.help.raise(note)
            MoshMonitor.event(context, "help_raised")
            active = true; holding = false
        }
    }
    // The ring only shows progress; the countdown above decides when Help is sent.
    LaunchedEffect(holding) {
        if (holding) ring.animateTo(1f, tween(HOLD_SECONDS * 1_000, easing = LinearEasing)) else ring.snapTo(0f)
    }

    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (active) "Close" else "Cancel", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        val friendCount = remember { MoshRuntime.mutualFriends().size }
        if (active) {
            // Honest status: who it went to and how. A Bluetooth send has no receipt, so say "sent", not "received".
            var routes by remember { mutableStateOf(MoshRuntime.help.lastRoutes) }
            LaunchedEffect(Unit) { while (true) { routes = MoshRuntime.help.lastRoutes; delay(2_000) } }
            val status = helpStatus(routes)
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(MoshColors.Red.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        Text("!", color = MoshColors.Red, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                    }
                    Text(status.headline, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold,
                        color = if (status.reachedAnyone) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                }
                status.lines.forEach { Text(it, fontSize = 17.sp) }
                Text("Trying again every minute or two", color = soft)
                Spacer(Modifier.height(24.dp))
                Button(onClick = { MoshRuntime.help.cancel(); active = false },
                    modifier = Modifier.fillMaxWidth().height(56.dp), shape = MoshShapes.Pill,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.background)) {
                    Text("I'm OK now, cancel", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                EmergencyLine("Not an emergency service. In danger, call 112.")
            }
        } else {
            Text("Ask for help", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            Text("Hold for $HOLD_SECONDS seconds. Let go to stop.", fontSize = 17.sp, color = soft)
            if (friendCount == 0) {
                Text("No friend has scanned you back yet, so nobody will get this. In danger, call 112.",
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f)).padding(horizontal = 14.dp, vertical = 12.dp))
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 290.dp), contentAlignment = Alignment.Center) {
                val track = MoshColors.Red.copy(alpha = 0.25f)
                Box(
                    Modifier.size(250.dp)
                        .drawBehind {
                            drawCircle(track)
                            drawArc(MoshColors.Red, startAngle = -90f, sweepAngle = 360f * ring.value, useCenter = true)
                        }
                        .semantics { role = androidx.compose.ui.semantics.Role.Button; contentDescription = "Help. Hold for $HOLD_SECONDS seconds." }
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = { holding = true; tryAwaitRelease(); holding = false })
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(Modifier.size(216.dp).clip(CircleShape).background(MoshColors.Red),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(if (holding) "$left" else "Hold", fontSize = if (holding) 96.sp else 34.sp,
                            fontWeight = FontWeight.ExtraBold, color = Color.White, lineHeight = if (holding) 96.sp else 34.sp)
                        Text(if (holding) "Keep holding" else "for $HOLD_SECONDS seconds", fontSize = 15.sp, color = Color.White)
                    }
                }
            }
            OutlinedTextField(value = note, onValueChange = { note = it.take(120) },
                label = { Text("What to look for (optional)") }, shape = MoshShapes.Card, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text("Who will see where you are", fontSize = 14.sp, color = soft)
                Text("Only your friends", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
            Spacer(Modifier.height(12.dp))
            EmergencyLine("In danger? Call 112.")
        }
    }
}

/** Small centred 112 line with the dial action. */
@Composable
private fun EmergencyLine(text: String) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        TextButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Call 112", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error) }
    }
}

internal data class HelpStatus(val headline: String, val lines: List<String>, val reachedAnyone: Boolean)

/** Turns per-friend routes into plain words. Names come from the friend list. */
internal fun helpStatus(routes: Map<String, com.bitchat.android.mosh.MoshRoute>,
                        name: (String) -> String = { MoshRuntime.friends.displayName(it) ?: "A friend" }): HelpStatus {
    if (routes.isEmpty()) return HelpStatus("Nobody to send to: no friend has scanned you back yet.", emptyList(), false)
    fun how(r: com.bitchat.android.mosh.MoshRoute) = when (r) {
        com.bitchat.android.mosh.MoshRoute.MESH -> "Bluetooth"
        com.bitchat.android.mosh.MoshRoute.MESH_AND_NOSTR -> "Bluetooth and internet"
        com.bitchat.android.mosh.MoshRoute.NOSTR -> "internet"
        else -> null
    }
    val sent = routes.filter { how(it.value) != null }
    val waiting = routes.filter { it.value == com.bitchat.android.mosh.MoshRoute.QUEUED }
    val missed = routes.keys - sent.keys - waiting.keys
    val lines = buildList {
        if (sent.isNotEmpty()) add("Sent to " + sent.entries.joinToString { "${name(it.key)} (${how(it.value)})" })
        if (waiting.isNotEmpty()) add("Waiting for signal, sends as soon as you are online: " + waiting.keys.joinToString { name(it) })
        if (missed.isNotEmpty()) add("Not reachable right now: " + missed.joinToString { name(it) })
    }
    return when {
        sent.isNotEmpty() -> HelpStatus("Help sent", lines, true)
        waiting.isNotEmpty() -> HelpStatus("Not sent yet: no signal and no friend nearby.", lines, false)
        else -> HelpStatus("Not sent yet: no friend nearby and no internet.", lines, false)
    }
}

/** Friends list with status, location pause, and the way to add someone in person. */
@Composable
fun FriendsScreen(onAddFriend: () -> Unit, onChat: (String) -> Unit) {
    var friends by remember { mutableStateOf(MoshRuntime.friends.friends()) }
    var paused by remember { mutableStateOf(MoshRuntime.prefs.pausedFriends) }
    var removing by remember { mutableStateOf<com.bitchat.android.mosh.Friend?>(null) }
    val context = LocalContext.current
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    LaunchedEffect(Unit) { while (true) { delay(5_000); friends = MoshRuntime.friends.friends() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { MoshTitle("Friends") }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onAddFriend, modifier = Modifier.fillMaxWidth().height(56.dp), shape = MoshShapes.Pill,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary)) {
                    Text("Add a friend in person", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                if (friends.isEmpty()) {
                    Text("No friends yet. Help reaches nobody.", fontSize = 16.sp, color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                } else {
                    val mutual = friends.count { it.status == FriendStatus.FRIEND }
                    Text("Help reaches $mutual of ${friends.size}", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = if (mutual == 0) MaterialTheme.colorScheme.error else soft, modifier = Modifier.padding(top = 6.dp))
                }
                Why("How do I add someone?", listOf(
                    "Stand next to each other.",
                    "Scan each other's code. Both of you need to scan.",
                ))
            }
        }
        items(friends, key = { it.peerID }) { f ->
            // Avatar and name on top, controls below: stays readable at large text sizes.
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    MoshAvatar(f.displayName, f.peerID)
                    Column(Modifier.weight(1f)) {
                        Text(f.displayName, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(if (f.status == FriendStatus.WAITING_FOR_THEM) "Waiting for them to scan you" else "Friends",
                            fontSize = 14.sp, color = soft)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onChat(f.peerID) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Chat", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    TextButton(onClick = { removing = f }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.weight(1f))
                    val sharing = f.peerID !in paused
                    Row(Modifier.heightIn(min = 48.dp).toggleable(value = sharing, role = androidx.compose.ui.semantics.Role.Switch) { on ->
                        MoshRuntime.prefs.setPaused(f.peerID, !on); paused = MoshRuntime.prefs.pausedFriends
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Text("Share location", fontSize = 13.sp, color = soft, modifier = Modifier.padding(end = 8.dp))
                        Switch(checked = sharing, onCheckedChange = null)
                    }
                }
                HorizontalDivider(Modifier.padding(start = 64.dp, top = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
    removing?.let { f ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${f.displayName}?") },
            text = { Text("They stop getting your location, Help and meeting points. To add them again, scan each other in person.") },
            confirmButton = {
                TextButton(onClick = {
                    MoshRuntime.removeFriend(context, f)
                    friends = MoshRuntime.friends.friends(); removing = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}
