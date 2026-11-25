package com.bitchat.android.ui.mosh

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.bitchat.android.mosh.MoshMonitor
import com.bitchat.android.mosh.MoshRuntime
import com.bitchat.android.ui.ChatViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pages opened from home (⋮ menu, network button, You), shown full screen with a back arrow. */
private enum class Page { FRIENDS, NETWORK, LOCATION, PROBLEMS, ABOUT }

/** How loud an in-app banner is: Help is solid red, problems are amber, the rest are plain tiles. */
private enum class Tone { HELP, WARN, INFO }

/**
 * MoshGrid's top level, laid out as in the design: first-run setup, then Chats as home, a big Find
 * pill in the middle of the bottom bar (with the Ask for help bar above it), and You on the right.
 */
@Composable
fun MoshShell(chatViewModel: ChatViewModel, openFind: Pair<String, Long>? = null) {
    val prefs = MoshRuntime.prefs
    val wipes by MoshRuntime.wipes.collectAsState()
    var onboarded by remember(wipes) { mutableStateOf(prefs.onboardingDone) }
    if (!onboarded) {
        MoshOnboarding(prefs, chatViewModel.nickname.value, onName = { chatViewModel.setNickname(it) }) { onboarded = true }
        return
    }
    var tab by remember { mutableStateOf(HomeTab.CHATS) }
    var page by remember { mutableStateOf<Page?>(null) }
    var friendOpen by remember { mutableStateOf<String?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    // Friend to focus on Find, from a tapped Help notification. Cleared when Find is opened by hand.
    var findFocus by remember { mutableStateOf(openFind?.first) }
    // Bumped whenever a banner asks Find to open on someone, so Find refocuses even if already open.
    var findRequest by remember { mutableStateOf(0) }
    LaunchedEffect(openFind) {
        if (openFind != null) { findFocus = openFind.first; showHelp = false; page = null; tab = HomeTab.FIND }
    }
    var findMeeting by remember { mutableStateOf(false) }
    val help by MoshRuntime.store.activeHelp.collectAsState()
    val stop by MoshRuntime.lastStop.collectAsState()
    var clockNow by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); clockNow = System.currentTimeMillis() } }
    val showVerification by chatViewModel.showVerificationSheet.collectAsState()
    val chatPeer by chatViewModel.privateChatSheetPeer.collectAsState()

    // Back closes Help, then a page, then returns to Chats, and only then leaves the app.
    androidx.activity.compose.BackHandler(enabled = showHelp || page != null || tab != HomeTab.CHATS) {
        when {
            showHelp -> showHelp = false
            page != null -> page = null
            else -> { findFocus = null; findMeeting = false; tab = HomeTab.CHATS }
        }
    }

    // Our own Help: easy to forget it is still on after closing the Help screen.
    var ownHelpOn by remember { mutableStateOf(MoshRuntime.help.activeAlertId != null) }
    LaunchedEffect(showHelp) { while (true) { ownHelpOn = MoshRuntime.help.activeAlertId != null; kotlinx.coroutines.delay(2_000) } }

    val goFind: (String?, Boolean) -> Unit = { peer, meeting ->
        findFocus = peer; findMeeting = meeting; findRequest++; page = null; tab = HomeTab.FIND
    }
    val banners: @Composable () -> Unit = {
        if (ownHelpOn && tab != HomeTab.FIND) {
            Banner("Your Help is on", "Friends get an alert every minute or two.", Tone.HELP, action = "Open") { showHelp = true }
        }
        if (help.isNotEmpty()) {
            val names = help.keys.joinToString { MoshRuntime.friends.displayName(it) ?: "A friend" }
            // Help is never cleared by time: a phone that stops sending may be the emergency. Show the age instead.
            val newest = help.values.maxOf { it.raisedAtMs }
            val mins = ((clockNow - newest).coerceAtLeast(0) / 60_000)
            val age = if (mins < 3) null else "Updated $mins min ago"
            val note = help.values.maxByOrNull { it.raisedAtMs }?.note?.takeIf { it.isNotBlank() }?.let { "Look for: $it" }
            val line = listOfNotNull(note, age).joinToString(" · ").ifEmpty { null }
            Banner("$names asked for help", line, Tone.HELP, action = "Find") {
                goFind(help.maxByOrNull { it.value.raisedAtMs }?.key, false)
            }
        }
        RadioBanners()
        stop?.let { s ->
            val at = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(s.stoppedAtMs))
            Banner("MoshGrid stopped at $at", "${s.reason}. Unreachable for ${s.unreachableMinutes} min. Check battery settings.",
                Tone.WARN) { MoshRuntime.reportStop(null) }
        }
    }

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        when {
            showHelp -> HelpScreen { showHelp = false }
            page == Page.FRIENDS -> MoshPage("Friends", { if (friendOpen != null) friendOpen = null else page = null }) {
                FriendsScreen(onAddFriend = { chatViewModel.showVerificationSheet() },
                    onChat = { peer -> chatViewModel.showPrivateChatSheet(peer) },
                    onFind = { peer -> friendOpen = null; goFind(peer, false) },
                    open = friendOpen, onOpen = { friendOpen = it })
            }
            page == Page.NETWORK -> MoshPage("Network", { page = null }) { NetworkScreen(chatViewModel) }
            page == Page.LOCATION -> MoshPage("Location", { page = null }) { LocationPage() }
            page == Page.PROBLEMS -> MoshPage("Crash reports", { page = null }) { ProblemsPage() }
            page == Page.ABOUT -> MoshPage("About", { page = null }) { AboutPage() }
            else -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        HomeTab.CHATS -> ChatsHome(
                            chatViewModel = chatViewModel,
                            onOpenChat = { peer -> chatViewModel.showPrivateChatSheet(peer) },
                            onAddFriend = { chatViewModel.showVerificationSheet() },
                            onNetwork = { page = Page.NETWORK },
                            onFriends = { page = Page.FRIENDS },
                            onSettings = { tab = HomeTab.YOU },
                            banners = { Column { banners() } },
                        )
                        HomeTab.FIND -> Column {
                            banners()
                            Box(Modifier.weight(1f)) {
                                androidx.compose.runtime.key(openFind?.second, findRequest) {
                                    FindScreen(focusPeerID = findFocus, focusMeeting = findMeeting,
                                        onAddFriend = { chatViewModel.showVerificationSheet() },
                                        onMessage = { peer -> chatViewModel.showPrivateChatSheet(peer) },
                                        onMenu = listOf(
                                            "Add a friend" to { chatViewModel.showVerificationSheet() },
                                            "Friends" to { page = Page.FRIENDS },
                                            "Network" to { page = Page.NETWORK },
                                            "Settings" to { tab = HomeTab.YOU },
                                        ))
                                }
                            }
                        }
                        HomeTab.YOU -> YouScreen(
                            name = chatViewModel.nickname.collectAsState().value,
                            onMyCode = { chatViewModel.showVerificationSheet() },
                            onFriends = { page = Page.FRIENDS },
                            onLocation = { page = Page.LOCATION },
                            onNetwork = { page = Page.NETWORK },
                            onProblems = { page = Page.PROBLEMS },
                            onAbout = { page = Page.ABOUT },
                        )
                    }
                }
                if (tab == HomeTab.FIND) HelpBar(ownHelpOn) { showHelp = true }
                MoshHomeNav(tab) { t -> if (t != HomeTab.FIND) { findMeeting = false; findFocus = null }; tab = t }
            }
        }
    }
    // Sheets that open over any screen: adding a friend by QR code, and a private chat.
    if (showVerification) {
        com.bitchat.android.ui.VerificationSheet(isPresented = true, onDismiss = chatViewModel::hideVerificationSheet, viewModel = chatViewModel)
    }
    chatPeer?.let { peer ->
        com.bitchat.android.ui.PrivateChatSheet(isPresented = true, peerID = peer, viewModel = chatViewModel, onDismiss = {
            chatViewModel.hidePrivateChatSheet(); chatViewModel.endPrivateChat()
        }, onFind = { friend ->
            chatViewModel.hidePrivateChatSheet(); chatViewModel.endPrivateChat(); goFind(friend, false)
        })
    }
}

/**
 * The You tab, laid out like the design's Settings: you at the top (tap for your code), then one row
 * per topic with its current state in a single grey line. Switches live on each topic's page.
 */
@Composable
private fun YouScreen(
    name: String, onMyCode: () -> Unit, onFriends: () -> Unit, onLocation: () -> Unit,
    onNetwork: () -> Unit, onProblems: () -> Unit, onAbout: () -> Unit,
) {
    val prefs = MoshRuntime.prefs
    val cs = MaterialTheme.colorScheme
    var friends by remember { mutableStateOf(MoshRuntime.friends.friends()) }
    LaunchedEffect(Unit) { while (true) { friends = MoshRuntime.friends.friends(); kotlinx.coroutines.delay(3_000) } }
    val mutual = friends.count { it.status == com.bitchat.android.mosh.FriendStatus.FRIEND }
    val waiting = friends.size - mutual
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
        MoshTitle("You")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().clip(MoshShapes.Tile).background(cs.surface)
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onMyCode).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MoshAvatar(name.ifBlank { "?" }, "me", 56.dp)
                Column(Modifier.weight(1f)) {
                    Text(name.ifBlank { "No name yet" }, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Your name and code", fontSize = 14.sp, color = cs.onSurfaceVariant)
                }
                androidx.compose.material3.Icon(Icons.Outlined.QrCode2, contentDescription = null, tint = cs.primary,
                    modifier = Modifier.size(28.dp))
            }
            Column(Modifier.fillMaxWidth().clip(MoshShapes.Tile).background(cs.surface).padding(horizontal = 16.dp, vertical = 4.dp)) {
                MoshActionRow("Friends", when {
                    friends.isEmpty() -> "Nobody yet"
                    waiting == 0 -> "$mutual ${if (mutual == 1) "friend" else "friends"}"
                    else -> "$mutual ${if (mutual == 1) "friend" else "friends"} · $waiting waiting"
                }, Icons.Outlined.Group, onFriends)
                MoshActionRow("Location", if (prefs.shareLocation) "Friends can see where you are" else "Not sharing",
                    Icons.Outlined.LocationOn, onLocation)
                MoshActionRow("Network", listOf(
                    "Bluetooth " + if (prefs.bluetooth) "on" else "off",
                    "Internet " + if (prefs.internet) "on" else "off",
                ).joinToString(" · "), Icons.Outlined.SignalCellularAlt, onNetwork)
                MoshActionRow("Crash reports", if (prefs.monitoringOptIn) "On" else "Off", Icons.Outlined.BugReport, onProblems)
                MoshActionRow("About", "How MoshGrid works · open source", Icons.Outlined.Info, onAbout)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Location sharing: one switch and what it means. */
@Composable
private fun LocationPage() {
    val prefs = MoshRuntime.prefs
    var share by remember { mutableStateOf(prefs.shareLocation) }
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MoshSwitchRow("Share my location", if (share) "Friends you added see where you are." else "Friends can't find you on the compass or map.", share) {
            share = it; prefs.shareLocation = it
        }
        Why("Who sees my location?", listOf(
            "Only friends you added by scanning codes. Nobody else, not even MoshGrid.",
            "It's locked so only they can open it.",
            "Help alerts always include where you are, even with sharing off.",
        ))
    }
}

/** Crash reports and sharing the field log by hand. */
@Composable
private fun ProblemsPage() {
    val context = LocalContext.current
    val prefs = MoshRuntime.prefs
    var monitoring by remember { mutableStateOf(prefs.monitoringOptIn) }
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MoshSwitchRow("Send crash reports",
            if (prefs.internet) "Helps fix MoshGrid. No personal data." else "Only sent when Internet is on.", monitoring) {
            monitoring = it; prefs.monitoringOptIn = it
            com.bitchat.android.mosh.monitor.Monitoring.init(context.applicationContext as android.app.Application, prefs)
        }
        Why("What's in a crash report?", listOf(
            "What went wrong in the app, plus your phone model and Android version.",
            "Never your location, messages, names or friends.",
            "Goes to MoshGrid's own server, only when Internet is on.",
            "Turning it off stops it straight away.",
        ))
        MoshActionRow("Share diagnostics", "Event counts only. You choose where to send it.", Icons.Outlined.Share) {
            // Recording the share also creates the log, so the button works on a fresh install or after a wipe.
            MoshMonitor.event(context, "diagnostics_shared")
            val file = MoshMonitor.fieldLog(context).file
            if (file.exists()) {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { context.startActivity(Intent.createChooser(send, "Share diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }
}

/** Version, how it works in three lines, and the licence. */
@Composable
private fun AboutPage() {
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("MoshGrid $version", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        listOf(
            "Phones pass messages to each other over Bluetooth, so it works with no signal.",
            "With signal, messages also go over the internet to friends out of range.",
            "Everything is locked so only the friend it's for can open it.",
        ).forEach { Text(it, fontSize = 16.sp, color = cs.onSurfaceVariant) }
        Text("Open source (GPL-3.0), based on bitchat-android.", fontSize = 14.sp, color = cs.onSurfaceVariant)
        MoshActionRow("Source code", "On GitHub", Icons.Outlined.Info) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/gagangulyani/moshgrid"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
    if (!btOn) Banner("Bluetooth is off", "Friends nearby can't reach you, or you them.", Tone.WARN, action = "Turn on") {
        open(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
    }
    if (!locOn) Banner("Location is off", "Friends can't see you, and Help has no position.", Tone.WARN, action = "Turn on") {
        open(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
    }
}

/** In-app banner as a rounded tile: bold title, at most one short line, and one pill action. */
@Composable
private fun Banner(title: String, line: String?, tone: Tone, action: String = "OK", onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg = when (tone) {
        Tone.HELP -> MoshColors.Red
        Tone.WARN -> MoshColors.Amber.copy(alpha = 0.18f).compositeOver(cs.surface)
        Tone.INFO -> cs.surface
    }
    // Red Help tiles always carry white text; the others follow the theme so both themes stay readable.
    val fg = if (tone == Tone.HELP) Color.White else cs.onSurface
    val soft = if (tone == Tone.HELP) Color.White.copy(alpha = 0.85f) else cs.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(MoshShapes.Card).background(bg)
        .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = fg)
            if (line != null) Text(line, fontSize = 14.sp, color = soft)
        }
        Button(onClick = onTap, modifier = Modifier.heightIn(min = 48.dp), shape = MoshShapes.Pill,
            colors = if (tone == Tone.HELP) ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
            else ButtonDefaults.buttonColors(containerColor = cs.primary, contentColor = cs.onPrimary)) {
            Text(action, fontWeight = FontWeight.Bold)
        }
    }
}
