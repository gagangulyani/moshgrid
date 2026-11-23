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

private enum class Tab(val label: String) { CHATS("Chats"), FIND("Find"), FRIENDS("Friends"), SETTINGS("Settings") }

/** MoshGrid's top level: first-run setup, then Chats (upstream), Find, Friends, Settings, and Help. */
@Composable
fun MoshShell(chatViewModel: ChatViewModel, openFindFor: String? = null) {
    val prefs = MoshRuntime.prefs
    var onboarded by remember { mutableStateOf(prefs.onboardingDone) }
    if (!onboarded) {
        MoshOnboarding(prefs) { onboarded = true }
        return
    }
    var tab by remember { mutableStateOf(if (openFindFor != null) Tab.FIND else Tab.CHATS) }
    var showHelp by remember { mutableStateOf(false) }
    var findMeeting by remember { mutableStateOf(false) }
    val meeting by MoshRuntime.store.meetingPoint.collectAsState()
    val me by MoshRuntime.myLocation.collectAsState()
    val stop by MoshRuntime.lastStop.collectAsState()
    val help by MoshRuntime.store.activeHelp.collectAsState()
    var clockNow by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); clockNow = System.currentTimeMillis() } }

    if (showHelp) { HelpScreen { showHelp = false }; return }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("MoshGrid", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Button(onClick = { showHelp = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE8222B))) {
                Text("Help", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        if (help.isNotEmpty()) {
            val names = help.keys.joinToString { MoshRuntime.friends.displayName(it) ?: "A friend" }
            // Help is never cleared by time: a phone that stops sending may be the emergency. Show the age instead.
            val newest = help.values.maxOf { it.raisedAtMs }
            val mins = ((clockNow - newest).coerceAtLeast(0) / 60_000)
            val age = if (mins < 3) "" else " Last update $mins min ago."
            Banner("$names asked for help.$age Tap Find.", Color(0xFF3A1214)) { tab = Tab.FIND }
        }
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
                        Banner("Meeting point: ${mp.label}$dist", Color(0xFF2C3A0E), action = "Find") { findMeeting = true; tab = Tab.FIND }
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
                Tab.FIND -> FindScreen(focusPeerID = openFindFor, focusMeeting = findMeeting)
                Tab.FRIENDS -> FriendsScreen(
                    onAddFriend = { chatViewModel.showVerificationSheet(); tab = Tab.CHATS },
                    onChat = { peer -> chatViewModel.showPrivateChatSheet(peer); tab = Tab.CHATS },
                )
                Tab.SETTINGS -> SettingsScreen()
            }
        }
        NavigationBar {
            Tab.entries.forEach { t ->
                NavigationBarItem(selected = tab == t, onClick = { if (t != Tab.FIND) findMeeting = false; tab = t }, icon = {}, label = { Text(t.label, fontWeight = FontWeight.Bold) })
            }
        }
    }
}

@Composable
private fun Banner(text: String, color: Color, action: String = "OK", onTap: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).background(color, RoundedCornerShape(12.dp))
        .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onTap) { Text(action) }
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
    Column(Modifier.fillMaxSize().padding(16.dp)) {
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
