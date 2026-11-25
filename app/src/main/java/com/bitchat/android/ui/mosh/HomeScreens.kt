package com.bitchat.android.ui.mosh

import androidx.compose.foundation.background
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

/** The three places in the design's bottom bar: Chats, the big Find pill in the middle, and You. */
enum class HomeTab { CHATS, FIND, YOU }

/** Design bottom bar: Chats on the left, a raised acid Find pill in the middle, You on the right. */
@Composable
fun MoshHomeNav(selected: HomeTab, onSelect: (HomeTab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.navigationBarsPadding().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 16.dp).fillMaxWidth()
            .height(72.dp).clip(RoundedCornerShape(36.dp)).background(cs.surface).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically,
    ) {
        SideTab("Chats", Icons.Outlined.ChatBubbleOutline, selected == HomeTab.CHATS) { onSelect(HomeTab.CHATS) }
        val findOn = selected == HomeTab.FIND
        Row(
            Modifier.height(56.dp).clip(RoundedCornerShape(28.dp)).background(cs.primary)
                .then(if (findOn) Modifier.border(2.dp, cs.onSurface, RoundedCornerShape(28.dp)) else Modifier)
                .clickable(role = Role.Tab) { onSelect(HomeTab.FIND) }
                .semantics { this.selected = findOn }
                .padding(horizontal = 26.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.Explore, contentDescription = null, tint = cs.onPrimary)
            Text("Find", color = cs.onPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
        }
        SideTab("You", Icons.Outlined.Person, selected == HomeTab.YOU) { onSelect(HomeTab.YOU) }
    }
}

@Composable
private fun SideTab(label: String, icon: ImageVector, on: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.widthIn(min = 76.dp).heightIn(min = 56.dp).clip(RoundedCornerShape(28.dp))
            .clickable(role = Role.Tab, onClick = onClick).semantics { selected = on },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (on) cs.primary else cs.onSurfaceVariant)
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (on) cs.onSurface else cs.onSurfaceVariant)
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
    banners: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val conversations by chatViewModel.conversations.collectAsState()
    var friends by remember { mutableStateOf(MoshRuntime.friends.friends()) }
    LaunchedEffect(Unit) { while (true) { delay(5_000); friends = MoshRuntime.friends.friends() } }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }

    val rows = remember(conversations, friends) { buildRows(friends, conversations) }
    val shown = rows.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }

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
            items(shown, key = { it.peerID }) { r -> ChatRowItem(r) { onOpenChat(r.peerID) } }
            if (rows.isNotEmpty() && shown.isEmpty()) {
                item {
                    Text("No chats match \"$query\"", color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(32.dp))
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

@Composable
private fun ChatRowItem(r: ChatRow, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        MoshAvatar(r.name, r.peerID, 52.dp)
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
                    r.preview == null -> if (r.mutual) "Say hi" else "Waiting for them to scan you"
                    r.outgoing -> "You: ${r.preview}"
                    else -> r.preview
                }
                Text(text, fontSize = 15.sp, color = if (r.preview == null && !r.mutual) MoshColors.Amber else cs.onSurfaceVariant,
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
