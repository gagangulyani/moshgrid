package com.bitchat.android.ui.mosh

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Person
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.mosh.FriendStatus
import com.bitchat.android.mosh.MoshRuntime
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.ui.ChatViewModel
import com.bitchat.android.ui.ConversationSummary
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The four places in the bottom bar, all the same size: Chats, Find, Share and You. */
enum class HomeTab { CHATS, FIND, SHARE, YOU }

/**
 * Bottom bar with four equal tabs. The open tab is one green block, icon and label together,
 * filled icon on top; the others are outlines in grey. Nothing in the bar is louder than the rest.
 */
@Composable
fun MoshHomeNav(selected: HomeTab, onSelect: (HomeTab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 14.dp).fillMaxWidth()
            .height(72.dp).clip(RoundedCornerShape(24.dp)).background(cs.surface).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavTab("Chats", Icons.Filled.ChatBubble, Icons.Outlined.ChatBubbleOutline, selected == HomeTab.CHATS) { onSelect(HomeTab.CHATS) }
        NavTab("Find", Icons.Filled.Explore, Icons.Outlined.Explore, selected == HomeTab.FIND) { onSelect(HomeTab.FIND) }
        NavTab("Share", Icons.Filled.QrCode, Icons.Outlined.QrCode, selected == HomeTab.SHARE) { onSelect(HomeTab.SHARE) }
        NavTab("You", Icons.Filled.Person, Icons.Outlined.Person, selected == HomeTab.YOU) { onSelect(HomeTab.YOU) }
    }
}

@Composable
private fun RowScope.NavTab(label: String, on: ImageVector, off: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.weight(1f).fillMaxHeight().padding(horizontal = 6.dp, vertical = 9.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) cs.primary else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick).semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(if (selected) on else off, contentDescription = null, tint = if (selected) cs.onPrimary else cs.onSurfaceVariant)
        Text(label, fontSize = 12.sp, maxLines = 1, fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Bold,
            color = if (selected) cs.onPrimary else cs.onSurfaceVariant)
    }
}

/** Red "Ask for help" bar above the bottom bar on Find, as in the design. Says when your Help is on. */
@Composable
fun HelpBar(helpOn: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp).fillMaxWidth().heightIn(min = 60.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (helpOn) MoshColors.Red else MoshColors.Red.copy(alpha = 0.16f))
            .border(2.dp, MoshColors.Red, RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (helpOn) "Your help alert is on. Open it." else "Ask for help" }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(if (helpOn) Color.White else MoshColors.Red), contentAlignment = Alignment.Center) {
            Text("!", color = if (helpOn) MoshColors.Red else Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(if (helpOn) "Your help alert is on" else "Ask for help", fontWeight = FontWeight.Bold,
                color = if (helpOn) Color.White else Color(0xFFFF5A5F))
            Text(if (helpOn) "Friends are being alerted. Tap to see." else "Hold for 3 seconds", fontSize = 14.sp,
                color = if (helpOn) Color.White else cs.onSurface)
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = if (helpOn) Color.White else cs.onSurface)
    }
}

/** One row of the home list: a friend, and their latest message if you have talked. */
private data class ChatRow(
    val peerID: String,
    val conversationID: String,
    val name: String,
    val mutual: Boolean,
    val preview: String?,
    val outgoing: Boolean,
    val status: DeliveryStatus?,
    val at: Long,
    val unread: Int,
    /** A group chat: [peerID] holds the group id. */
    val isGroup: Boolean = false,
    val sender: String? = null,
)

/** Home: MoshGrid title, network button, ⋮ menu, search, one row per friend, and the add-friend button. */
@Composable
fun ChatsHome(
    chatViewModel: ChatViewModel,
    onOpenChat: (String) -> Unit,
    onAddFriend: () -> Unit,
    onNetwork: () -> Unit,
    onFriends: () -> Unit,
    onSettings: () -> Unit,
    onOpenGroup: (String) -> Unit = {},
    onNewGroup: () -> Unit = {},
    banners: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val conversations by chatViewModel.conversations.collectAsState()
    var friends by remember { mutableStateOf(MoshRuntime.friends.friends()) }
    LaunchedEffect(Unit) { while (true) { delay(5_000); friends = MoshRuntime.friends.friends() } }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }

    val groups by com.bitchat.android.mosh.MoshGroups.store.groups.collectAsState()
    val rows = remember(conversations, friends, groups) {
        (buildRows(friends, conversations) + groupRows(groups)).sortedWith(compareByDescending<ChatRow> { it.at }.thenBy { it.name.lowercase() })
    }
    var chip by remember { mutableStateOf(0) }
    val locations by MoshRuntime.store.locations.collectAsState()
    val me by MoshRuntime.myLocation.collectAsState()
    // Close by: a fresh location (under 10 min) within 200 m of you.
    fun isClose(peer: String): Boolean {
        val l = locations[peer] ?: return false; val m = me ?: return false
        return System.currentTimeMillis() - l.takenAtMs < 600_000 && com.bitchat.android.mosh.distanceM(m.lat, m.lon, l.lat, l.lon) <= 200
    }
    val shown = rows.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        .filter { when (chip) { 1 -> it.unread > 0; 2 -> !it.isGroup && isClose(it.peerID); 3 -> it.isGroup; else -> true } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("MoshGrid", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    Box(Modifier.size(48.dp).clip(CircleShape).background(cs.surface).clickable(role = Role.Button, onClick = onNetwork)
                        .semantics { contentDescription = "Network" }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.SignalCellularAlt, contentDescription = null)
                    }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("New group") }, onClick = { menu = false; onNewGroup() })
                            DropdownMenuItem(text = { Text("Add a friend") }, onClick = { menu = false; onAddFriend() })
                            DropdownMenuItem(text = { Text("Friends") }, onClick = { menu = false; onFriends() })
                            DropdownMenuItem(text = { Text("Network") }, onClick = { menu = false; onNetwork() })
                            DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; onSettings() })
                        }
                    }
                }
            }
            item { banners() }
            if (rows.isNotEmpty()) {
                item {
                    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp).fillMaxWidth().height(48.dp)
                        .clip(RoundedCornerShape(24.dp)).background(cs.surface).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.Search, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) Text("Search chats", color = cs.onSurfaceVariant, fontSize = 16.sp)
                            BasicTextField(query, { query = it }, singleLine = true,
                                textStyle = TextStyle(color = cs.onSurface, fontSize = 16.sp), cursorBrush = SolidColor(cs.primary),
                                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search chats" })
                        }
                    }
                }
            }
            if (rows.isNotEmpty()) {
                item {
                    Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("All", "Unread", "Close by", "Groups").forEachIndexed { i, label ->
                            val on = chip == i
                            Box(Modifier.height(40.dp).clip(RoundedCornerShape(20.dp))
                                .background(if (on) cs.primary.copy(alpha = 0.16f) else cs.surface)
                                .selectable(selected = on, role = Role.RadioButton) { chip = i }.padding(horizontal = 16.dp),
                                contentAlignment = Alignment.Center) {
                                Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) cs.primary else cs.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            items(shown, key = { (if (it.isGroup) "g:" else "p:") + it.peerID }) { r ->
                ChatRowItem(r) { if (r.isGroup) onOpenGroup(r.peerID) else onOpenChat(r.peerID) }
            }
            if (rows.isNotEmpty() && shown.isEmpty()) {
                item {
                    Text(when {
                        query.isNotBlank() -> "No chats match \"$query\""
                        chip == 1 -> "No unread messages."
                        chip == 3 -> "No groups yet. Make one from ⋮ → New group."
                        else -> "Nobody within 200 m right now."
                    }, color = cs.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(32.dp))
                }
            }
            if (rows.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(72.dp).clip(CircleShape).background(cs.surface), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.PersonAdd, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(34.dp))
                        }
                        Text("No friends yet", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Add friends before you get separated. Stand within a few metres of each other.",
                            color = cs.onSurfaceVariant, fontSize = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Box(Modifier.padding(top = 8.dp).height(52.dp).clip(RoundedCornerShape(26.dp)).background(cs.primary)
                            .clickable(role = Role.Button, onClick = onAddFriend).padding(horizontal = 28.dp),
                            contentAlignment = Alignment.Center) {
                            Text("Add a friend", color = cs.onPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        }
                    }
                }
            }
        }
        if (rows.isNotEmpty()) {
            Box(Modifier.align(Alignment.BottomEnd).padding(16.dp).size(60.dp).clip(RoundedCornerShape(18.dp))
                .background(cs.primary).clickable(role = Role.Button, onClick = onAddFriend)
                .semantics { contentDescription = "Add a friend" }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.PersonAdd, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(26.dp))
            }
        }
    }
}

private fun buildRows(friends: List<com.bitchat.android.mosh.Friend>, conversations: List<ConversationSummary>): List<ChatRow> {
    val rows = friends.map { f ->
        val canonical = runCatching { ContactDirectory.canonicalConversationId(f.peerID) }.getOrDefault(f.peerID)
        val c = conversations.firstOrNull { it.conversationID == canonical || it.conversationID == f.peerID || f.peerID in it.identityAliases }
        ChatRow(f.peerID, c?.conversationID ?: canonical, f.displayName, f.status == FriendStatus.FRIEND,
            c?.latestMessagePreview, c?.latestMessageIsOutgoing == true, c?.latestDeliveryStatus, c?.latestMessageAt ?: 0L, c?.unreadCount ?: 0)
    }
    // Talked-to friends first, newest on top; the rest by name.
    return rows.sortedWith(compareByDescending<ChatRow> { it.at }.thenBy { it.name.lowercase() })
}

private fun groupRows(groups: List<com.bitchat.android.mosh.Group>): List<ChatRow> {
    val me = com.bitchat.android.mosh.MoshGroups.myKeyHex()
    return groups.map { g ->
        val last = g.messages.lastOrNull { it.kind == com.bitchat.android.mosh.GroupMessage.TEXT }
        val mine = last != null && last.fromKeyHex == me
        val who = last?.let { m -> if (mine) "You" else g.roster.members.firstOrNull { it.noiseKeyHex == m.fromKeyHex }?.name ?: "Someone" }
        val status = if (mine) when (com.bitchat.android.mosh.overallDelivery(last!!.status)) {
            com.bitchat.android.mosh.GroupDelivery.READ -> DeliveryStatus.Read("group", Date(last.sentAtMs))
            com.bitchat.android.mosh.GroupDelivery.DELIVERED -> DeliveryStatus.Delivered("group", Date(last.sentAtMs))
            com.bitchat.android.mosh.GroupDelivery.SENT -> DeliveryStatus.Sent
            com.bitchat.android.mosh.GroupDelivery.WAITING -> DeliveryStatus.Sending
        } else null
        ChatRow(g.id, g.id, g.roster.name, mutual = !g.closed, preview = last?.text, outgoing = mine, status = status,
            at = g.lastAtMs, unread = g.unread, isGroup = true, sender = who)
    }
}

/** Square-ish avatar for a group, so groups and people are told apart at a glance. */
@Composable
fun GroupAvatar(name: String, key: String, size: androidx.compose.ui.unit.Dp = 52.dp) {
    val initials = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "#" }
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(avatarColor(key)), contentAlignment = Alignment.Center) {
        Text(initials, color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.3f).sp)
    }
}

@Composable
private fun ChatRowItem(r: ChatRow, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (r.isGroup) GroupAvatar(r.name, r.peerID) else MoshAvatar(r.name, r.peerID, 52.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                if (r.at > 0) Text(timeLabel(r.at), fontSize = 13.sp,
                    color = if (r.unread > 0) cs.primary else cs.onSurfaceVariant,
                    fontWeight = if (r.unread > 0) FontWeight.Bold else FontWeight.Normal)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (r.outgoing && r.status != null) StatusIcon(r.status)
                val text = when {
                    r.isGroup && r.preview == null -> if (r.mutual) "Group made. Say hi." else "You're no longer in this group"
                    r.isGroup -> "${r.sender}: ${r.preview}"
                    r.preview == null -> if (r.mutual) "Say hi" else "Waiting for them to add you"
                    r.outgoing -> "You: ${r.preview}"
                    else -> r.preview
                }
                Text(text, fontSize = 15.sp, color = if (r.preview == null && !r.mutual && !r.isGroup) MoshColors.Amber else cs.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (r.unread > 0) {
                    Box(Modifier.heightIn(min = 22.dp).widthIn(min = 22.dp).clip(RoundedCornerShape(11.dp)).background(cs.secondary)
                        .padding(horizontal = 7.dp), contentAlignment = Alignment.Center) {
                        Text("${r.unread}", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** Message status as the design shows it: clock waiting, one tick sent, two delivered, two in cyan seen. */
@Composable
private fun StatusIcon(s: DeliveryStatus) {
    val cs = MaterialTheme.colorScheme
    val (icon, tint, label) = when (s) {
        is DeliveryStatus.Sending -> Triple(Icons.Filled.Schedule, cs.onSurfaceVariant, "Waiting to send")
        is DeliveryStatus.Sent -> Triple(Icons.Filled.Done, cs.onSurfaceVariant, "Sent")
        is DeliveryStatus.Delivered -> Triple(Icons.Filled.DoneAll, cs.onSurfaceVariant, "Delivered")
        is DeliveryStatus.Read -> Triple(Icons.Filled.DoneAll, cs.secondary, "Seen")
        is DeliveryStatus.Failed -> Triple(Icons.Filled.ErrorOutline, cs.error, "Not sent")
        else -> Triple(Icons.Filled.Done, cs.onSurfaceVariant, "Sent")
    }
    Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
}

private fun timeLabel(at: Long): String {
    val now = Calendar.getInstance(); val then = Calendar.getInstance().apply { timeInMillis = at }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return SimpleDateFormat(if (sameDay) "HH:mm" else "d MMM", Locale.getDefault()).format(Date(at))
}

/** Full-screen page with a back arrow and a title, for Friends, Network and Settings opened from home. */
@Composable
fun MoshPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

/** What MoshGrid's internet route is doing right now, from the phone's network and the relay connections. */
enum class NetState(val title: String, val detail: String, val good: Boolean) {
    OFF("Internet off for MoshGrid", "Only Bluetooth is used. Turn Internet on below to reach friends who are far away.", false),
    OFFLINE("No internet on this phone", "No Wi-Fi or mobile data right now. Bluetooth still works; messages wait and go when signal returns.", false),
    CONNECTING("Connecting to the relay…", "The phone is online but MoshGrid hasn't reached a relay yet. Messages wait until it does.", false),
    BACKUP("Connected through a backup relay", "MoshGrid's own relay isn't reachable, so a public relay carries your locked messages.", true),
    CONNECTED("Connected to MoshGrid's relay", "Messages to friends far away go through now, locked so only they can read them.", true),
}

@Composable
fun rememberNetState(internetSwitch: Boolean? = null): NetState {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val relay = remember { runCatching { com.bitchat.android.nostr.NostrRelayManager.getInstance(context) }.getOrNull() }
    val relays by (relay?.relays ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())).collectAsState()
    var online by remember { mutableStateOf(true) }
    var internetOn by remember { mutableStateOf(MoshRuntime.prefs.internet) }
    LaunchedEffect(Unit) {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        while (true) {
            online = cm?.getNetworkCapabilities(cm.activeNetwork)
                ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            internetOn = MoshRuntime.prefs.internet
            kotlinx.coroutines.delay(2_000)
        }
    }
    val up = relays.filter { it.isConnected }
    return when {
        !(internetSwitch ?: internetOn) -> NetState.OFF
        !online -> NetState.OFFLINE
        up.any { it.url.contains(OWN_RELAY_HOST) } -> NetState.CONNECTED
        up.isNotEmpty() -> NetState.BACKUP
        else -> NetState.CONNECTING
    }
}

private const val OWN_RELAY_HOST = "relay.gagangulyani.com"

@Composable
private fun InternetStatus(internet: Boolean) {
    val s = rememberNetState(internet)
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(MoshShapes.Card).background(cs.surface).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 6.dp).size(12.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (s.good) MoshColors.Acid else if (s == NetState.CONNECTING) MoshColors.Amber else cs.outline))
        Column {
            Text(s.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(s.detail, fontSize = 13.sp, color = cs.onSurfaceVariant)
        }
    }
}

/** Network: how messages are getting through right now, from what the app knows. */
@Composable
fun NetworkScreen(chatViewModel: ChatViewModel) {
    val cs = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = MoshRuntime.prefs
    val nearby by chatViewModel.connectedPeers.collectAsState()
    val relay = remember { runCatching { com.bitchat.android.nostr.NostrRelayManager.getInstance(context.applicationContext) }.getOrNull() }
    val relayUp by (relay?.isConnected ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
    var bluetooth by remember { mutableStateOf(prefs.bluetooth) }
    var internet by remember { mutableStateOf(prefs.internet) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MoshTile(if (internet && relayUp) "Internet connected" else "No internet route", Modifier.fillMaxWidth().heightIn(min = 140.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${nearby.size}", fontSize = 64.sp, fontWeight = FontWeight.ExtraBold, color = cs.secondary, lineHeight = 64.sp)
                Text(if (nearby.size == 1) "phone nearby" else "phones nearby", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(bottom = 10.dp))
            }
            Text(if (nearby.isEmpty()) "No MoshGrid phones in Bluetooth range right now." else "They pass your messages along.",
                fontSize = 13.sp, color = cs.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MoshTile("Bluetooth", Modifier.weight(1f)) {
                Text(if (bluetooth) "On" else "Off", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            }
            MoshTile("Internet", Modifier.weight(1f)) {
                Text(when { !internet -> "Off"; relayUp -> "On"; else -> "No signal" }, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        InternetStatus(internet)
        Spacer(Modifier.height(4.dp))
        MoshSwitchRow("Bluetooth", "Phone to phone. No signal needed.", bluetooth) {
            bluetooth = it; prefs.bluetooth = it; com.bitchat.android.mosh.MoshNetworkGate.apply(context, prefs)
        }
        MoshSwitchRow("Internet", if (internet) "Whenever there's signal." else "Off: nothing leaves your phone except over Bluetooth.", internet) {
            internet = it; prefs.internet = it; com.bitchat.android.mosh.MoshNetworkGate.apply(context, prefs)
        }
        Why("How do messages get through?", listOf(
            "Over Bluetooth, each MoshGrid phone passes messages on to the next, so they hop across the crowd.",
            "With signal, they also go over the internet, locked so only your friend can read them.",
            "Anything that can't get through yet waits and is sent when a route appears.",
        ))
    }
}
