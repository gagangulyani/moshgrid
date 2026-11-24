package com.bitchat.android.ui.mosh

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.bitchat.android.mosh.MoshMonitor
import com.bitchat.android.mosh.MoshRuntime
import com.bitchat.android.ui.ChatScreen
import com.bitchat.android.ui.ChatViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Tab(val label: String) { FIND("Find"), FRIENDS("Friends"), CHATS("Chats"), SETTINGS("Settings") }

/** MoshGrid's top level: first-run setup, then Chats (upstream), Find, Friends, Settings, and Help. */
@Composable
fun MoshShell(chatViewModel: ChatViewModel, openFind: Pair<String, Long>? = null) {
    val prefs = MoshRuntime.prefs
    val wipes by MoshRuntime.wipes.collectAsState()
    var onboarded by remember(wipes) { mutableStateOf(prefs.onboardingDone) }
    if (!onboarded) {
        MoshOnboarding(prefs, chatViewModel.nickname.value, onName = { chatViewModel.setNickname(it) }) { onboarded = true }
        return
    }
    // Find first: where your friends are is what matters at a concert.
    var tab by remember { mutableStateOf(Tab.FIND) }
    var showHelp by remember { mutableStateOf(false) }
    // Friend to focus on Find, from a tapped Help notification. Cleared when Find is opened by hand.
    var findFocus by remember { mutableStateOf(openFind?.first) }
    // Bumped whenever a banner asks Find to open on someone, so Find refocuses even if already open.
    var findRequest by remember { mutableStateOf(0) }
    LaunchedEffect(openFind) {
        if (openFind != null) { findFocus = openFind.first; showHelp = false; tab = Tab.FIND }
    }
    var findMeeting by remember { mutableStateOf(false) }
    val meeting by MoshRuntime.store.meetingPoint.collectAsState()
    val me by MoshRuntime.myLocation.collectAsState()
    val stop by MoshRuntime.lastStop.collectAsState()
    val help by MoshRuntime.store.activeHelp.collectAsState()
    var clockNow by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); clockNow = System.currentTimeMillis() } }

    // Back closes the Help screen, then returns to Find, and only then leaves the app.
    androidx.activity.compose.BackHandler(enabled = showHelp || tab != Tab.FIND) {
        if (showHelp) showHelp = false else { findFocus = null; findMeeting = false; tab = Tab.FIND }
    }

    // Our own Help: easy to forget it is still on after closing the Help screen.
    var ownHelpOn by remember { mutableStateOf(MoshRuntime.help.activeAlertId != null) }
    LaunchedEffect(showHelp) { while (true) { ownHelpOn = MoshRuntime.help.activeAlertId != null; kotlinx.coroutines.delay(2_000) } }

    if (showHelp) { HelpScreen { showHelp = false }; return }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("MoshGrid", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Button(onClick = { showHelp = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE8222B))) {
                Text(if (ownHelpOn) "Help ON" else "Help", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        if (ownHelpOn) {
            Banner("Your Help is ON. Friends are being alerted every minute or two.", Color(0xFF7A0F14), action = "Cancel") {
                MoshRuntime.help.cancel(); ownHelpOn = false
            }
        }
        if (help.isNotEmpty()) {
            val names = help.keys.joinToString { MoshRuntime.friends.displayName(it) ?: "A friend" }
            // Help is never cleared by time: a phone that stops sending may be the emergency. Show the age instead.
            val newest = help.values.maxOf { it.raisedAtMs }
            val mins = ((clockNow - newest).coerceAtLeast(0) / 60_000)
            val age = if (mins < 3) "" else " Last update $mins min ago."
            val note = help.values.maxByOrNull { it.raisedAtMs }?.note?.takeIf { it.isNotBlank() }?.let { " Look for: $it." } ?: ""
            Banner("$names asked for help.$note$age", Color(0xFF3A1214), action = "Find") {
                findFocus = help.maxByOrNull { it.value.raisedAtMs }?.key; findMeeting = false; findRequest++; tab = Tab.FIND
            }
        }
        RadioBanners()
        stop?.let { s ->
            val at = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(s.stoppedAtMs))
            Banner("MoshGrid was stopped at $at (${s.reason}). You were unreachable for ${s.unreachableMinutes} min. Check battery settings.",
                Color(0xFF3D2A00)) { MoshRuntime.reportStop(null) }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.CHATS -> Column {
                    Text("MoshGrid chats are friends-only. Open a friend's chat from the Friends tab.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    // Meeting point from the chat: see it, go to it, or set one at your spot.
                    val mp = meeting
                    if (mp != null) {
                        val dist = me?.let { " · " + com.bitchat.android.mosh.distanceM(it.lat, it.lon, mp.lat, mp.lon).toInt() + " m" } ?: ""
                        Banner("Meeting point: ${mp.label}$dist", Color(0xFF2C3A0E), action = "Find") { findMeeting = true; findFocus = null; findRequest++; tab = Tab.FIND }
                    } else if (me != null) {
                        TextButton(onClick = {
                            val here = me ?: return@TextButton
                            MoshRuntime.setMeetingPoint(com.bitchat.android.mosh.MeetingPoint(
                                java.util.UUID.randomUUID().toString().take(12), here.lat, here.lon, "Where I am now",
                                System.currentTimeMillis(), "me", cleared = false))
                        }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Set meeting point where I am") }
                    }
                    ChatScreen(viewModel = chatViewModel)
                }
                Tab.FIND -> androidx.compose.runtime.key(openFind?.second, findRequest) {
                    FindScreen(focusPeerID = findFocus, focusMeeting = findMeeting)
                }
                Tab.FRIENDS -> FriendsScreen(
                    onAddFriend = { chatViewModel.showVerificationSheet(); tab = Tab.CHATS },
                    onChat = { peer -> chatViewModel.showPrivateChatSheet(peer); tab = Tab.CHATS },
                )
                Tab.SETTINGS -> SettingsScreen()
            }
        }
        NavigationBar {
            Tab.entries.forEach { t ->
                NavigationBarItem(selected = tab == t, onClick = { if (t != Tab.FIND) findMeeting = false; findFocus = null; tab = t }, icon = {}, label = { Text(t.label, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) })
            }
        }
    }
}

/** Bluetooth or Location switched off: say what it costs and offer to turn it on, without blocking. */
@Composable
private fun RadioBanners() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var btOn by remember { mutableStateOf(true) }
    var locOn by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            btOn = (context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter?.isEnabled != false
            locOn = (context.getSystemService(android.content.Context.LOCATION_SERVICE) as? android.location.LocationManager)
                ?.let { androidx.core.location.LocationManagerCompat.isLocationEnabled(it) } != false
            kotlinx.coroutines.delay(3_000)
        }
    }
    fun open(action: String) = runCatching { context.startActivity(android.content.Intent(action).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    if (!btOn) Banner("Bluetooth is off: friends nearby cannot reach you, and you cannot reach them.", Color(0xFF3D2A00), action = "Turn on") {
        open(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
    }
    if (!locOn) Banner("Location is off: friends cannot see where you are, and Help has no position.", Color(0xFF3D2A00), action = "Turn on") {
        open(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
    }
}

@Composable
private fun Banner(text: String, color: Color, action: String = "OK", onTap: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).background(color, RoundedCornerShape(12.dp))
        .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        // Banner backgrounds are always dark, so the text is fixed light: readable in light and dark theme.
        Text(text, fontSize = 14.sp, color = Color.White, modifier = Modifier.weight(1f))
        TextButton(onClick = onTap) { Text(action, color = Color(0xFFC6FF3D), fontWeight = FontWeight.Bold) }
    }
}

/** Network checkboxes, sharing, monitoring, and sharing the field log by hand. */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = MoshRuntime.prefs
    var bluetooth by remember { mutableStateOf(prefs.bluetooth) }
    var internet by remember { mutableStateOf(prefs.internet) }
    var share by remember { mutableStateOf(prefs.shareLocation) }
    var monitoring by remember { mutableStateOf(prefs.monitoringOptIn) }
    // Scrolls so large text sizes never hide the last settings.
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp)) {
        Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Text("Networks", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        CheckRow("Bluetooth (phone to phone)", bluetooth) { bluetooth = it; prefs.bluetooth = it; com.bitchat.android.mosh.MoshNetworkGate.apply(context, prefs) }
        CheckRow("Internet (Nostr relays)", internet) { internet = it; prefs.internet = it; com.bitchat.android.mosh.MoshNetworkGate.apply(context, prefs) }
        if (!internet) Text("Offline only: nothing leaves your phone except over Bluetooth.", fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Location", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        CheckRow("Share my location with friends", share) { share = it; prefs.shareLocation = it }
        Text("Problems", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        CheckRow("Send crash and problem reports (no personal data)", monitoring) { monitoring = it; prefs.monitoringOptIn = it }
        Text("Changes to reporting apply after restarting the app.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = {
            // Recording the share also creates the log, so the button works on a fresh install or after a wipe.
            MoshMonitor.event(context, "diagnostics_shared")
            val file = MoshMonitor.fieldLog(context).file
            if (file.exists()) {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { context.startActivity(Intent.createChooser(send, "Share diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }) { Text("Share diagnostics (event counts only)") }
        Text("MoshGrid is open source (GPL-3.0), based on bitchat-android.", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
    }
}
