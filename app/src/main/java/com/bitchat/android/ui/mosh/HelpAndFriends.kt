package com.bitchat.android.ui.mosh

import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
                label = { Text("What to look for (optional)") },
                placeholder = { Text("e.g. Red cap, near the sound desk") },
                supportingText = { Text("What you're wearing or a landmark, so friends spot you fast.") },
                shape = MoshShapes.Card, modifier = Modifier.fillMaxWidth())
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

/**
 * Friends, as in the design: one plain row per friend (tap for their page) and one way to add
 * someone. Chat, Find, location sharing and Remove live on the friend's own page.
 */
@Composable
fun FriendsScreen(onAddFriend: () -> Unit, onChat: (String) -> Unit, onFind: (String) -> Unit = {},
                  open: String? = null, onOpen: (String?) -> Unit = {}) {
    var friends by remember { mutableStateOf(MoshRuntime.friends.friends()) }
    var paused by remember { mutableStateOf(MoshRuntime.prefs.pausedFriends) }
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    LaunchedEffect(Unit) { while (true) { delay(3_000); friends = MoshRuntime.friends.friends(); paused = MoshRuntime.prefs.pausedFriends } }
    val current = friends.firstOrNull { it.peerID == open }
    if (current != null) {
        androidx.activity.compose.BackHandler { onOpen(null) }
        FriendProfile(current, sharing = current.peerID !in paused,
            onShare = { on -> MoshRuntime.prefs.setPaused(current.peerID, !on); paused = MoshRuntime.prefs.pausedFriends },
            onChat = { onChat(current.peerID) }, onFind = { onFind(current.peerID) },
            onRemoved = { friends = MoshRuntime.friends.friends(); onOpen(null) })
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (friends.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("No friends yet", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Help reaches nobody until you add someone. Stand next to each other and scan each other's code.",
                        fontSize = 16.sp, color = soft, textAlign = TextAlign.Center)
                }
            }
        } else {
            val mutual = friends.count { it.status == FriendStatus.FRIEND }
            item {
                Text("Help reaches $mutual of ${friends.size}", fontSize = 14.sp, color = if (mutual == 0) MaterialTheme.colorScheme.error else soft,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
        }
        items(friends, key = { it.peerID }) { f ->
            val waiting = f.status == FriendStatus.WAITING_FOR_THEM
            Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Open") { onOpen(f.peerID) }.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MoshAvatar(f.displayName, f.peerID, 48.dp)
                Column(Modifier.weight(1f)) {
                    Text(f.displayName, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1)
                    Text(when {
                        waiting -> "Waiting for them to scan you"
                        f.peerID in paused -> "Not sharing your location"
                        else -> "Sees your location"
                    }, fontSize = 14.sp, color = if (waiting) MoshColors.Amber else soft, maxLines = 1)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = soft)
            }
        }
        item {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 16.dp).fillMaxWidth().height(52.dp).clip(MoshShapes.Pill)
                .background(MaterialTheme.colorScheme.primary).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onAddFriend),
                contentAlignment = Alignment.Center) {
                Text("Add a friend", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

/** One friend's page, as in the design's profile: who, Message and Find, sharing, and Remove at the bottom. */
@Composable
private fun FriendProfile(
    f: com.bitchat.android.mosh.Friend, sharing: Boolean, onShare: (Boolean) -> Unit,
    onChat: () -> Unit, onFind: () -> Unit, onRemoved: () -> Unit,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var removing by remember { mutableStateOf(false) }
    val mutual = f.status == FriendStatus.FRIEND
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(8.dp))
        MoshAvatar(f.displayName, f.peerID, 88.dp)
        Text(f.displayName, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Text(if (mutual) "Friends" else "Waiting for them to scan you", fontSize = 15.sp,
            color = if (mutual) cs.onSurfaceVariant else MoshColors.Amber)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).height(52.dp).clip(MoshShapes.Pill).background(cs.surface).clickable(onClick = onChat),
                contentAlignment = Alignment.Center) { Text("Message", fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
            if (mutual) Box(Modifier.weight(1f).height(52.dp).clip(MoshShapes.Pill).background(cs.primary).clickable(onClick = onFind),
                contentAlignment = Alignment.Center) { Text("Find", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = cs.onPrimary) }
        }
        Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(MoshShapes.Tile).background(cs.surface).padding(horizontal = 16.dp)) {
            MoshSwitchRow("Share my location with ${f.displayName}",
                if (sharing) "They see where you are." else "They can't see where you are. Help alerts still include it.", sharing, onChange = onShare)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { removing = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Remove ${f.displayName}", color = cs.error, fontWeight = FontWeight.SemiBold)
        }
    }
    if (removing) androidx.compose.material3.AlertDialog(
        onDismissRequest = { removing = false },
        title = { Text("Remove ${f.displayName}?") },
        text = { Text("They stop getting your location, Help and meeting points. To add them again, scan each other in person.") },
        confirmButton = {
            TextButton(onClick = { MoshRuntime.removeFriend(context, f); removing = false; onRemoved() }) {
                Text("Remove", color = cs.error)
            }
        },
        dismissButton = { TextButton(onClick = { removing = false }) { Text("Cancel") } },
    )
}
