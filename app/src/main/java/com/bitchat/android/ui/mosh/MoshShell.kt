package com.bitchat.android.ui.mosh

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.material.icons.filled.Close
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

/** Pages opened from home (⋮ menu, network button, You), shown full screen with a back arrow. */
private enum class Page { FRIENDS, NETWORK, LOCATION, MAPS, PROBLEMS, REPORT, ABOUT, LINKS, MET }

/** How loud an in-app banner is: Help is solid red, problems are amber, the rest are plain tiles. */
private enum class Tone { HELP, WARN, INFO }

/**
 * MoshGrid's top level: first-run setup, then Chats as home, and a bottom bar of four equal tabs
 * (Chats, Find, Share, You). Find has the Ask for help bar above the bottom bar.
 */
@Composable
fun MoshShell(chatViewModel: ChatViewModel, openFind: Pair<String, Long>? = null, openGroup: Pair<String, Long>? = null,
              openFriends: Long? = null) {
    val prefs = MoshRuntime.prefs
    val wipes by MoshRuntime.wipes.collectAsState()
    var onboarded by remember(wipes) { mutableStateOf(prefs.onboardingDone) }
    if (!onboarded) {
        MoshOnboarding(prefs, chatViewModel.nickname.collectAsState().value, onName = { chatViewModel.setNickname(it) }) { onboarded = true }
        return
    }
    var tab by remember { mutableStateOf(HomeTab.CHATS) }
    var page by remember { mutableStateOf<Page?>(null) }
    var friendOpen by remember { mutableStateOf<String?>(null) }
    // Groups: the open chat, its info page, and the new-group page.
    var groupOpen by remember { mutableStateOf<String?>(null) }
    var groupInfo by remember { mutableStateOf(false) }
    var newGroup by remember { mutableStateOf(false) }
    LaunchedEffect(openFriends) { if (openFriends != null) { groupOpen = null; friendOpen = null; page = Page.FRIENDS } }
    LaunchedEffect(openGroup) { if (openGroup != null) { groupOpen = openGroup.first; groupInfo = false; page = null } }
    var showHelp by remember { mutableStateOf(false) }
    // Friend to focus on Find, from a tapped Help notification. Cleared when Find is opened by hand.
    var findFocus by remember { mutableStateOf(openFind?.first) }
    // Bumped whenever a banner asks Find to open on someone, so Find refocuses even if already open.
    var findRequest by remember { mutableStateOf(0) }
    LaunchedEffect(openFind) {
        if (openFind != null) { findFocus = openFind.first; showHelp = false; page = null; tab = HomeTab.FIND }
    }
    var findMeeting by remember { mutableStateOf(false) }
    // The app icon shortcut and socials links (https://moshgrid.vercel.app/s#…) open the Share tab.
    val shareRequest by com.bitchat.android.mosh.MoshSocials.shareRequests.collectAsState()
    val openedCard by com.bitchat.android.mosh.MoshSocials.opened.collectAsState()
    val goShare = { showHelp = false; page = null; groupOpen = null; tab = HomeTab.SHARE }
    LaunchedEffect(shareRequest) { if (shareRequest != 0L) goShare() }
    LaunchedEffect(openedCard) { if (openedCard != null) goShare() }
    val help by MoshRuntime.store.activeHelp.collectAsState()
    var hiddenHelp by remember { mutableStateOf(emptyMap<String, Long>()) }
    val context = LocalContext.current
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
        // Hidden alerts come back as soon as that friend sends a newer one (their Help keeps updating).
        val help = help.filter { (peer, h) -> hiddenHelp[peer] != h.raisedAtMs }
        if (help.isNotEmpty()) {
            val names = help.keys.joinToString { MoshRuntime.friends.displayName(it) ?: "A friend" }
            // Help is never cleared by time: a phone that stops sending may be the emergency. Show the age instead.
            val newest = help.values.maxOf { it.raisedAtMs }
            val mins = ((clockNow - newest).coerceAtLeast(0) / 60_000)
            val age = if (mins < 3) null else "Updated $mins min ago"
            val note = help.values.maxByOrNull { it.raisedAtMs }?.note?.takeIf { it.isNotBlank() }?.let { "Look for: $it" }
            val line = listOfNotNull(note, age).joinToString(" · ").ifEmpty { null }
            Banner("$names asked for help", line, Tone.HELP, action = "Find",
                onClose = { hiddenHelp = hiddenHelp + help.mapValues { it.value.raisedAtMs } }) {
                goFind(help.maxByOrNull { it.value.raisedAtMs }?.key, false)
            }
        }
        val requests by com.bitchat.android.mosh.MoshInvites.requests.collectAsState()
        if (requests.isNotEmpty() && page != Page.FRIENDS) {
            val names = requests.joinToString { it.name }
            Banner(if (requests.size == 1) "$names wants to add you" else "${requests.size} friend requests", names.takeIf { requests.size > 1 },
                Tone.INFO, action = "View") { groupOpen = null; friendOpen = null; page = Page.FRIENDS }
        }
        when (val u = com.bitchat.android.mosh.MoshUpdater.state.collectAsState().value) {
            is com.bitchat.android.mosh.MoshUpdater.State.Available ->
                Banner("MoshGrid ${u.update.versionName} is out", "Fixes and new things. Takes a minute.", Tone.INFO, action = "Update") {
                    com.bitchat.android.mosh.MoshUpdater.start(context)
                }
            is com.bitchat.android.mosh.MoshUpdater.State.Downloading ->
                Banner("Downloading MoshGrid ${u.update.versionName}", "${u.percent}%", Tone.INFO, action = "OK") {}
            is com.bitchat.android.mosh.MoshUpdater.State.Failed ->
                Banner("Update didn't finish", u.reason, Tone.WARN, action = "Try again") { com.bitchat.android.mosh.MoshUpdater.start(context) }
            else -> Unit
        }
        RadioBanners()
    }

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        OpenedInviteDialog()
        when {
            showHelp -> HelpScreen { showHelp = false }
            newGroup -> NewGroupScreen(chatViewModel.nickname.collectAsState().value, onBack = { newGroup = false }) { id ->
                newGroup = false; groupOpen = id
            }
            groupOpen != null && groupInfo -> GroupInfoScreen(groupOpen!!, onBack = { groupInfo = false },
                onFind = { peer -> groupInfo = false; groupOpen = null; goFind(peer, false) },
                onDeleted = { groupInfo = false; groupOpen = null })
            groupOpen != null -> GroupThreadScreen(groupOpen!!, onBack = { groupOpen = null }, onInfo = { groupInfo = true },
                onFind = { peer -> groupOpen = null; goFind(peer, false) })
            page == Page.FRIENDS -> MoshPage("Friends", { if (friendOpen != null) friendOpen = null else page = null }) {
                FriendsScreen(onAddFriend = { chatViewModel.showVerificationSheet() },
                    onChat = { peer -> chatViewModel.showPrivateChatSheet(peer) },
                    onFind = { peer -> friendOpen = null; goFind(peer, false) },
                    open = friendOpen, onOpen = { friendOpen = it })
            }
            page == Page.NETWORK -> MoshPage("Network", { page = null }) { NetworkScreen(chatViewModel) }
            page == Page.LOCATION -> MoshPage("Location", { page = null }) { LocationPage() }
            page == Page.MAPS -> MoshPage("Offline maps", { page = null }) { OfflineMapsScreen() }
            page == Page.PROBLEMS -> MoshPage("Crash reports", { page = null }) { ProblemsPage() }
            page == Page.REPORT -> MoshPage("Report a problem", { page = null }) { ReportPage() }
            page == Page.ABOUT -> MoshPage("About", { page = null }) { AboutPage() }
            page == Page.LINKS -> MoshPage("My links", { page = null }) { EditLinksPage() }
            page == Page.MET -> MoshPage("People I met", { page = null }) { PeopleMetPage() }
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
                            onOpenGroup = { groupOpen = it },
                            onNewGroup = { newGroup = true },
                            banners = { Column { banners() } },
                        )
                        HomeTab.FIND -> Column {
                            banners()
                            Box(Modifier.weight(1f)) {
                                androidx.compose.runtime.key(openFind?.second, findRequest) {
                                    FindScreen(focusPeerID = findFocus, focusMeeting = findMeeting,
                                        onAddFriend = { chatViewModel.showVerificationSheet() },
                                        onMessage = { peer -> chatViewModel.showPrivateChatSheet(peer) },
                                        onOfflineMaps = { page = Page.MAPS },
                                        onMenu = listOf(
                                            "Add a friend" to { chatViewModel.showVerificationSheet() },
                                            "Friends" to { page = Page.FRIENDS },
                                            "Offline maps" to { page = Page.MAPS },
                                            "Network" to { page = Page.NETWORK },
                                            "Settings" to { tab = HomeTab.YOU },
                                        ))
                                }
                            }
                        }
                        HomeTab.SHARE -> ShareTab(chatViewModel, onEditLinks = { page = Page.LINKS }, onPeopleMet = { page = Page.MET },
                            banners = { Column { banners() } })
                        HomeTab.YOU -> YouScreen(
                            name = chatViewModel.nickname.collectAsState().value,
                            onMyCode = { chatViewModel.showVerificationSheet() },
                            onFriends = { page = Page.FRIENDS },
                            onLocation = { page = Page.LOCATION },
                            onMaps = { page = Page.MAPS },
                            onNetwork = { page = Page.NETWORK },
                            onProblems = { page = Page.PROBLEMS },
                            onReport = { page = Page.REPORT },
                            onAbout = { page = Page.ABOUT },
                            onErase = { chatViewModel.panicClearAllData() },
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
    openedCard?.let { card ->
        SocialsCardSheet(card, savedAt = null) { com.bitchat.android.mosh.MoshSocials.closeOpened() }
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
    name: String, onMyCode: () -> Unit, onFriends: () -> Unit, onLocation: () -> Unit, onMaps: () -> Unit,
    onNetwork: () -> Unit, onProblems: () -> Unit, onReport: () -> Unit, onAbout: () -> Unit, onErase: () -> Unit,
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
                MyPhotoAvatar(name, 56.dp)
                Column(Modifier.weight(1f)) {
                    Text(name.ifBlank { "No name yet" }, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Text(if (com.bitchat.android.mosh.ProfilePhotos.images.collectAsState().value.containsKey("me")) "Your name and friend code" else "Tap the circle to add a photo", fontSize = 14.sp, color = cs.onSurfaceVariant)
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
                val mapsContext = LocalContext.current
                LaunchedEffect(Unit) { com.bitchat.android.mosh.OfflineMaps.ensure(mapsContext) }
                val saved = com.bitchat.android.mosh.OfflineMaps.maps.collectAsState().value.size
                MoshActionRow("Offline maps", if (saved == 0) "Save a street map for when there's no signal"
                    else "$saved saved on this phone", Icons.Outlined.Map, onMaps)
                val net = rememberNetState()
                MoshActionRow("Network", listOf(
                    "Bluetooth " + if (prefs.bluetooth) "on" else "off",
                    when (net) { NetState.OFF -> "Internet off"; NetState.OFFLINE -> "No internet"; NetState.CONNECTING -> "Connecting…"
                        else -> "Relay connected" },
                ).joinToString(" · "), Icons.Outlined.SignalCellularAlt, onNetwork)
                MoshActionRow("Crash reports", if (prefs.monitoringOptIn) "On" else "Off", Icons.Outlined.BugReport, onProblems)
                MoshActionRow("Report a problem", "Tell us what didn't work", Icons.Outlined.ChatBubbleOutline, onReport)
                MoshActionRow("About", "Version ${appVersion(LocalContext.current)} · open source", Icons.Outlined.Info, onAbout)
            }
            EraseRow(onErase)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Panic wipe: erases everything MoshGrid keeps on this phone, after one confirmation. */
@Composable
private fun EraseRow(onErase: () -> Unit) {
    var ask by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MoshShapes.Tile)
        .clickable(role = androidx.compose.ui.semantics.Role.Button) { ask = true }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Erase everything on this phone", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
    if (ask) androidx.compose.material3.AlertDialog(onDismissRequest = { ask = false },
        title = { Text("Erase everything?") },
        text = { Text("Deletes your friends, chats, photos, saved maps, socials links, people you met and your MoshGrid identity, " +
            "then starts setup again. If your Help is on, friends get one last message that it's off. This can't be undone.") },
        confirmButton = { TextButton(onClick = { ask = false; onErase() }) { Text("Erase everything", color = Color(0xFFFF6B6B)) } },
        dismissButton = { TextButton(onClick = { ask = false }) { Text("Cancel") } })
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
            "What went wrong in the app (a crash, or something that failed without crashing), plus your phone model and Android version.",
            "Never your location, messages, names or friends.",
            "Goes to MoshGrid's own server, only when Internet is on.",
            "Turning it off stops it straight away.",
        ))
        MoshActionRow("Privacy policy", "What MoshGrid collects and why", Icons.Outlined.Info) { openWeb(context, PRIVACY_URL) }
        MoshActionRow("Share diagnostics", "Event counts only. You choose where to send it.", Icons.Outlined.Share) {
            // Recording the share also creates the log, so the button works on a fresh install or after a wipe.
            MoshMonitor.event(context, "diagnostics_shared")
            val file = MoshMonitor.fieldLog(context).file
            if (file.exists()) {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { context.startActivity(Intent.createChooser(send, "Share diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { com.bitchat.android.mosh.MoshMonitor.failed("share_failed", it) }
            }
        }
    }
}

/** The version people see, e.g. "1.1.0" (the build's "-moshgrid" suffix is for Android, not for reading). */
private fun appVersion(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        ?.removeSuffix("-moshgrid") ?: ""

/** Version, how it works in three lines, and the licence. */
@Composable
private fun AboutPage() {
    val context = LocalContext.current
    val version = remember { appVersion(context) }
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("MoshGrid", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Text("Version $version", fontSize = 15.sp, color = cs.onSurfaceVariant)
        UpdateRow()
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
            }.onFailure { com.bitchat.android.mosh.MoshMonitor.failed("open_link_failed", it) }
        }
        MoshActionRow("Privacy policy", "What MoshGrid collects and why", Icons.Outlined.Info) { openWeb(context, PRIVACY_URL) }
        MoshActionRow("Terms of use", "The rules for using MoshGrid", Icons.Outlined.Info) { openWeb(context, TERMS_URL) }
        Text("Made by Gagan Deep Singh", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 12.dp))
        Text("Product engineer and indie hacker, based in Noida. I built MoshGrid so friends can find each other at gigs and festivals, even when the phone network gives up.",
            fontSize = 15.sp, color = cs.onSurfaceVariant)
        MoshActionRow("LinkedIn", "linkedin.com/in/gagan-gulyani", null) { openWeb(context, "https://www.linkedin.com/in/gagan-gulyani") }
        MoshActionRow("X", "x.com/GaganGulyani", null) { openWeb(context, "https://x.com/GaganGulyani") }
        MoshActionRow("Instagram", "instagram.com/life_of_gulyani", null) { openWeb(context, "https://www.instagram.com/life_of_gulyani") }
        MoshActionRow("Email", "gagangulyanig@gmail.com", null) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:gagangulyanig@gmail.com"))
                    .putExtra(Intent.EXTRA_SUBJECT, "MoshGrid").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { com.bitchat.android.mosh.MoshMonitor.failed("open_link_failed", it) }
        }
    }
}

/** "Check for updates": checks now instead of waiting for the automatic check, and updates at once. */
@Composable
private fun UpdateRow() {
    val context = LocalContext.current
    if (com.bitchat.android.BuildConfig.PLAY_STORE) {
        MoshActionRow("Updates", "Google Play keeps MoshGrid up to date", Icons.Outlined.Refresh) {
            openWeb(context, "https://play.google.com/store/apps/details?id=" + context.packageName)
        }
        return
    }
    val updater = com.bitchat.android.mosh.MoshUpdater
    val state = updater.state.collectAsState().value
    val check = updater.check.collectAsState().value
    val line = when {
        state is com.bitchat.android.mosh.MoshUpdater.State.Downloading -> "Downloading ${state.update.versionName}… ${state.percent}%"
        state is com.bitchat.android.mosh.MoshUpdater.State.Installing -> "Installing ${state.update.versionName}…"
        state is com.bitchat.android.mosh.MoshUpdater.State.Available -> "Version ${state.update.versionName} is ready. Tap to update."
        state is com.bitchat.android.mosh.MoshUpdater.State.Failed -> state.reason + " Tap to try again."
        check is com.bitchat.android.mosh.MoshUpdater.Check.Checking -> "Checking…"
        check is com.bitchat.android.mosh.MoshUpdater.Check.UpToDate -> "You have the newest version."
        check is com.bitchat.android.mosh.MoshUpdater.Check.Error -> check.reason
        else -> "MoshGrid checks by itself every few hours. Tap to check now."
    }
    MoshActionRow("Check for updates", line, Icons.Outlined.Refresh) {
        when (state) {
            is com.bitchat.android.mosh.MoshUpdater.State.Available, is com.bitchat.android.mosh.MoshUpdater.State.Failed -> updater.start(context)
            is com.bitchat.android.mosh.MoshUpdater.State.Downloading, is com.bitchat.android.mosh.MoshUpdater.State.Installing -> Unit
            else -> updater.checkNow(context)
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
        .onFailure { com.bitchat.android.mosh.MoshMonitor.failed("settings_open_failed", it) }
    if (!btOn) Banner("Bluetooth is off", "Friends nearby can't reach you, or you them.", Tone.WARN, action = "Turn on") {
        open(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
    }
    if (!locOn) Banner("Location is off", "Friends can't see you, and Help has no position.", Tone.WARN, action = "Turn on") {
        open(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
    }
}

/** In-app banner as a rounded tile: bold title, at most one short line, and one pill action. */
@Composable
private fun Banner(title: String, line: String?, tone: Tone, action: String = "OK", onClose: (() -> Unit)? = null, onTap: () -> Unit) {
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
        if (onClose != null) {
            androidx.compose.material3.IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                androidx.compose.material3.Icon(Icons.Filled.Close, contentDescription = "Hide this alert", tint = fg)
            }
        }
    }
}
