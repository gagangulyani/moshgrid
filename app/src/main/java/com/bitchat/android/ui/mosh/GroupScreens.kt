package com.bitchat.android.ui.mosh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.mosh.Friend
import com.bitchat.android.mosh.FriendStatus
import com.bitchat.android.mosh.Group
import com.bitchat.android.mosh.GroupDelivery
import com.bitchat.android.mosh.GroupMember
import com.bitchat.android.mosh.GroupMessage
import com.bitchat.android.mosh.MoshCodec
import com.bitchat.android.mosh.MoshGroups
import com.bitchat.android.mosh.MoshRuntime
import com.bitchat.android.mosh.StoredGroupMessage
import com.bitchat.android.mosh.overallDelivery
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun hhmm(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

/** Header used by the group pages: back arrow, then whatever goes in the middle. */
@Composable
private fun GroupTopBar(onBack: () -> Unit, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        content()
    }
}

/** Friends who scanned us back: the only people who can be put in a group. */
private fun pickableFriends(): List<Friend> = MoshRuntime.friends.friends().filter { it.status == FriendStatus.FRIEND }

/** A friend you can tick, used when making a group and when adding people to one. */
@Composable
private fun PickRow(f: Friend, picked: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = picked, enabled = enabled, role = Role.Checkbox, onValueChange = onToggle)
        .padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        MoshAvatar(f.displayName, f.peerID, 44.dp)
        Text(f.displayName, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
        Checkbox(checked = picked, onCheckedChange = null, enabled = enabled)
    }
}

/** Big acid pill at the bottom of a page. */
@Composable
private fun BottomAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.navigationBarsPadding().padding(16.dp).fillMaxWidth().height(56.dp).clip(MoshShapes.Pill)
        .background(if (enabled) cs.primary else cs.surface).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = if (enabled) cs.onPrimary else cs.onSurfaceVariant)
    }
}

@Composable
private fun NameField(value: String, placeholder: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
                      example: String = "e.g. Camp crew") {
    val cs = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp)).background(cs.surface).padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text("$placeholder, $example", color = cs.onSurfaceVariant, fontSize = 17.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        BasicTextField(value, { v -> if (v.toByteArray().size <= 32) onChange(v) }, singleLine = true,
            textStyle = TextStyle(color = cs.onSurface, fontSize = 17.sp), cursorBrush = SolidColor(cs.primary),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder })
    }
}

/** Make a group: a name, then tick friends. Everyone in it must have added you back. */
@Composable
fun NewGroupScreen(myName: String, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val friends = remember { pickableFriends() }
    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    val max = MoshCodec.MAX_GROUP_MEMBERS - 1
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GroupTopBar(onBack) { Text("New group", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        LazyColumn(Modifier.weight(1f)) {
            item { NameField(name, "Group name", { name = it }, Modifier.padding(horizontal = 16.dp)) }
            item {
                Text(if (friends.isEmpty()) "Add friends first. Only friends who added you back can be in a group."
                    else "Who's in it? ${picked.size} of up to $max picked.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp))
            }
            items(friends, key = { it.peerID }) { f ->
                val on = f.peerID in picked
                PickRow(f, on, enabled = on || picked.size < max) { picked = if (it) picked + f.peerID else picked - f.peerID }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp)) } }
        }
        BottomAction("Make group", enabled = name.isNotBlank() && picked.isNotEmpty()) {
            val id = runCatching { MoshGroups.create(name, myName, friends.filter { it.peerID in picked }) }
                .onFailure { com.bitchat.android.mosh.MoshMonitor.failed("group_create_failed", it) }.getOrNull()
            if (id != null) onCreated(id) else error = "Couldn't make the group yet. Try again in a moment."
        }
    }
}

/** A group's chat. Your messages show WhatsApp-style ticks; tap one to see who has it and who read it. */
@Composable
fun GroupThreadScreen(groupId: String, onBack: () -> Unit, onInfo: () -> Unit, onFind: (String) -> Unit = {}, onAddPeople: () -> Unit = onInfo) {
    val groups by MoshGroups.store.groups.collectAsState()
    val g = groups.firstOrNull { it.id == groupId }
    DisposableEffect(groupId) {
        MoshGroups.setOpen(groupId)
        onDispose { MoshGroups.setOpen(null) }
    }
    // New messages that land while the chat is open are read straight away.
    LaunchedEffect(g?.messages?.size) { if (g != null) MoshGroups.setOpen(groupId) }
    androidx.activity.compose.BackHandler(onBack = onBack)
    if (g == null) { LaunchedEffect(Unit) { onBack() }; return }
    val me = MoshGroups.myKeyHex()
    val cs = MaterialTheme.colorScheme
    var info by remember { mutableStateOf<StoredGroupMessage?>(null) }
    var draft by remember(groupId) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var findPeople by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) } // "clear" or "leave"
    val list = rememberLazyListState()
    LaunchedEffect(g.messages.size) { if (g.messages.isNotEmpty()) list.animateScrollToItem(g.messages.size - 1) }

    Column(Modifier.fillMaxSize().background(cs.background).imePadding()) {
        GroupTopBar(onBack) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Group info", onClick = onInfo).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GroupAvatar(g.roster.name, g.id, 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(g.roster.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(memberLine(g, me), fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!g.closed) IconButton(onClick = { findPeople = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Explore, contentDescription = "Find people in this group")
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                }
                androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    @Composable fun item(label: String, color: Color? = null, go: () -> Unit) = androidx.compose.material3.DropdownMenuItem(
                        text = { Text(label, color = color ?: cs.onSurface) }, onClick = { menu = false; go() })
                    item("Group info", go = onInfo)
                    if (!g.closed) item("Find people") { findPeople = true }
                    if (!g.closed && g.roster.creatorKeyHex == me) item("Add people", go = onAddPeople)
                    item(if (g.muted) "Unmute notifications" else "Mute notifications") { MoshGroups.store.setMuted(groupId, !g.muted) }
                    item("Clear chat") { confirm = "clear" }
                    if (g.closed) item("Delete chat", cs.error) { confirm = "delete" } else item("Exit group", cs.error) { confirm = "leave" }
                }
            }
        }
        if (g.muted) Text("Notifications are muted for this group. Help alerts still ring.", fontSize = 13.sp,
            color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (g.messages.isEmpty()) item {
                Text(if (g.roster.creatorKeyHex == me) "You made this group. Say hi." else "No messages here. Say hi.",
                    color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 40.dp))
            }
            items(g.messages, key = { it.fromKeyHex + it.id }) { m ->
                val name = g.roster.members.firstOrNull { it.noiseKeyHex == m.fromKeyHex }?.name ?: "Someone"
                when {
                    m.kind == GroupMessage.LEFT -> Text("$name left", fontSize = 13.sp, color = cs.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    m.fromKeyHex == me -> Bubble(m, null, mine = true) { info = m }
                    else -> Bubble(m, name to avatarColor(m.fromKeyHex), mine = false, onTap = null)
                }
            }
        }
        if (g.closed) {
            Text("You're not in this group any more.", color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp))
        } else {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(cs.surface)
                    .padding(horizontal = 18.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                    if (draft.isEmpty()) Text("Message", color = cs.onSurfaceVariant, fontSize = 16.sp)
                    BasicTextField(draft, { v -> if (v.toByteArray().size <= MoshCodec.MAX_GROUP_TEXT_BYTES) draft = v },
                        textStyle = TextStyle(color = cs.onSurface, fontSize = 16.sp), cursorBrush = SolidColor(cs.primary), maxLines = 5,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Message" })
                }
                val canSend = draft.isNotBlank()
                Box(Modifier.size(48.dp).clip(CircleShape).background(if (canSend) cs.primary else cs.surface)
                    .clickable(enabled = canSend, role = Role.Button) { if (MoshGroups.sendText(groupId, draft)) draft = "" }
                    .semantics { contentDescription = "Send" }, contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = if (canSend) cs.onPrimary else cs.onSurfaceVariant,
                        modifier = Modifier.size(22.dp))
                }
            }
        }
    }
    info?.let { m -> ReceiptSheet(g, g.messages.firstOrNull { it.id == m.id && it.fromKeyHex == m.fromKeyHex } ?: m) { info = null } }
    if (findPeople) FindPeopleSheet(g, me, onFind = { findPeople = false; onFind(it) }) { findPeople = false }
    confirm?.let { what ->
        val (title, body, ok) = when (what) {
            "clear" -> Triple("Clear this chat?", "Messages are deleted from this phone only. Everyone else keeps theirs.", "Clear chat")
            "delete" -> Triple("Delete this chat?", "It's removed from this phone. You're already out of the group.", "Delete chat")
            else -> Triple("Exit ${g.roster.name}?", "You stop getting its messages and can't send to it. The others are told you left.", "Exit group")
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) }, text = { Text(body) },
            confirmButton = {
                TextButton(onClick = {
                    when (what) {
                        "clear" -> MoshGroups.store.clearMessages(groupId)
                        "delete" -> { MoshGroups.store.delete(groupId); onBack() }
                        else -> MoshGroups.leave(groupId)
                    }
                    confirm = null
                }) { Text(ok, color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

/** Everyone in the group with how far away they are; Find for friends (only friends share their location). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FindPeopleSheet(g: Group, me: String?, onFind: (String) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val friends = remember { MoshRuntime.friends.friends() }
    val locations by MoshRuntime.store.locations.collectAsState()
    val my by MoshRuntime.myLocation.collectAsState()
    com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(top = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Find people", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Only friends who added you share where they are.", fontSize = 14.sp, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp))
            g.roster.members.filter { it.noiseKeyHex != me && it.noiseKeyHex !in g.left }.forEach { m ->
                val f = friends.firstOrNull { it.noiseKeyHex == m.noiseKeyHex && it.status == FriendStatus.FRIEND }
                val l = f?.let { locations[it.peerID] }
                val line = when {
                    f == null -> "Not your friend, so no location"
                    l == null -> "No location yet"
                    else -> {
                        val mins = ((System.currentTimeMillis() - l.takenAtMs).coerceAtLeast(0) / 60_000)
                        val age = if (mins < 1) "just now" else "$mins min ago"
                        my?.let { me2 ->
                            val d = com.bitchat.android.mosh.distanceM(me2.lat, me2.lon, l.lat, l.lon)
                            (if (d < 1000) "${d.toInt()} m" else "%.1f km".format(d / 1000)) + " away · $age"
                        } ?: "Location from $age"
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MoshAvatar(m.name, m.noiseKeyHex, 44.dp)
                    Column(Modifier.weight(1f)) {
                        Text(m.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(line, fontSize = 14.sp, color = cs.onSurfaceVariant, maxLines = 1)
                    }
                    if (f != null && l != null) Box(Modifier.heightIn(min = 44.dp).clip(MoshShapes.Pill).background(cs.primary.copy(alpha = 0.16f))
                        .clickable { onFind(f.peerID) }.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                        Text("Find", color = cs.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun memberLine(g: Group, me: String?): String {
    val others = g.roster.members.filter { it.noiseKeyHex != me && it.noiseKeyHex !in g.left }.map { it.name }
    return (others + "You").joinToString(", ")
}

@Composable
private fun Bubble(m: StoredGroupMessage, from: Pair<String, Color>?, mine: Boolean, onTap: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val bg = if (mine) androidx.compose.ui.graphics.lerp(cs.background, cs.primary, 0.16f) else cs.surface
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom) {
        if (from != null) {
            MoshAvatar(from.first, m.fromKeyHex, 30.dp)
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(18.dp)).background(bg)
            .then(if (onTap != null) Modifier.clickable(onClickLabel = "Who has it", onClick = onTap) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp)) {
            if (from != null) Text(from.first, color = from.second, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(m.text, fontSize = 16.sp, color = cs.onSurface)
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(hhmm(m.sentAtMs), fontSize = 11.sp, color = cs.onSurfaceVariant)
                if (mine) Ticks(overallDelivery(m.status))
            }
        }
    }
}

@Composable
private fun Ticks(d: GroupDelivery, size: Int = 16) {
    val cs = MaterialTheme.colorScheme
    val (icon, tint, label) = when (d) {
        GroupDelivery.WAITING -> Triple(Icons.Filled.Schedule, cs.onSurfaceVariant, "Waiting to send")
        GroupDelivery.SENT -> Triple(Icons.Filled.Done, cs.onSurfaceVariant, "Sent")
        GroupDelivery.DELIVERED -> Triple(Icons.Filled.DoneAll, cs.onSurfaceVariant, "Delivered to everyone")
        GroupDelivery.READ -> Triple(Icons.Filled.DoneAll, MoshColors.Cyan, "Read by everyone")
    }
    Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(size.dp))
}

/** Message info: who read it and when, who has it, and who it is still on its way to. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiptSheet(g: Group, m: StoredGroupMessage, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(top = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Message info", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(m.text, fontSize = 15.sp, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 8.dp))
            val byState = m.status.entries.groupBy { it.value.state }
            listOf(
                GroupDelivery.READ to "Read",
                GroupDelivery.DELIVERED to "Delivered",
                GroupDelivery.SENT to "Sent, not on their phone yet",
                GroupDelivery.WAITING to "Waiting to send",
            ).forEach { (state, title) ->
                val rows = byState[state].orEmpty()
                if (rows.isEmpty()) return@forEach
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 10.dp)) {
                    Ticks(state, 18)
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                rows.sortedBy { it.value.atMs }.forEach { (key, st) ->
                    val member = g.roster.members.firstOrNull { it.noiseKeyHex == key }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MoshAvatar(member?.name ?: "?", key, 36.dp)
                        Text(member?.name ?: "Someone", fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(
                            when {
                                key in g.left -> "Left"
                                state >= GroupDelivery.DELIVERED -> hhmm(st.atMs)
                                state == GroupDelivery.SENT -> if (st.sentOverInternet) "Over internet" else "Over Bluetooth"
                                else -> "Not in reach"
                            },
                            fontSize = 14.sp, color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
            Text("Messages keep trying over Bluetooth for 12 hours until they arrive.", fontSize = 13.sp, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp))
        }
    }
}

/** Group info: members (with Find for friends), and for the creator renaming and adding or removing people. */
@Composable
fun GroupInfoScreen(groupId: String, onBack: () -> Unit, onFind: (String) -> Unit, onDeleted: () -> Unit) {
    val groups by MoshGroups.store.groups.collectAsState()
    val g = groups.firstOrNull { it.id == groupId } ?: run { LaunchedEffect(Unit) { onDeleted() }; return }
    val me = MoshGroups.myKeyHex()
    val cs = MaterialTheme.colorScheme
    val isCreator = g.roster.creatorKeyHex == me && !g.closed
    val friends = remember { MoshRuntime.friends.friends() }
    var renaming by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<GroupMember?>(null) }
    var leaving by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(onBack = onBack)

    if (adding) {
        val inIt = g.roster.members.map { it.noiseKeyHex }.toSet()
        val choices = pickableFriends().filter { it.noiseKeyHex !in inIt }
        var picked by remember { mutableStateOf(setOf<String>()) }
        val room = MoshCodec.MAX_GROUP_MEMBERS - g.roster.members.size
        androidx.activity.compose.BackHandler { adding = false }
        Column(Modifier.fillMaxSize().background(cs.background)) {
            GroupTopBar({ adding = false }) { Text("Add people", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            LazyColumn(Modifier.weight(1f)) {
                item {
                    Text(if (choices.isEmpty()) "Everyone you can add is already in the group." else "Room for $room more.",
                        fontSize = 14.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
                items(choices, key = { it.peerID }) { f ->
                    val on = f.peerID in picked
                    PickRow(f, on, enabled = on || picked.size < room) { picked = if (it) picked + f.peerID else picked - f.peerID }
                }
            }
            BottomAction("Add", enabled = picked.isNotEmpty()) {
                MoshGroups.edit(groupId, g.roster.name, g.roster.members, choices.filter { it.peerID in picked })
                adding = false
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().background(cs.background)) {
        GroupTopBar(onBack) { Text("Group info", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GroupAvatar(g.roster.name, g.id, 88.dp)
                    Text(g.roster.name, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
                    Text("${g.roster.members.size} people · made by " +
                        (if (g.roster.creatorKeyHex == me) "you" else g.roster.members.firstOrNull { it.noiseKeyHex == g.roster.creatorKeyHex }?.name ?: "a friend"),
                        fontSize = 14.sp, color = cs.onSurfaceVariant)
                    if (isCreator) TextButton(onClick = { renaming = true }) { Text("Rename", color = cs.primary, fontWeight = FontWeight.Bold) }
                }
            }
            if (isCreator && g.roster.members.size < MoshCodec.MAX_GROUP_MEMBERS) item {
                MoshActionRow("Add people", "Friends who added you back", Icons.Outlined.PersonAdd) { adding = true }
            }
            items(g.roster.members.sortedBy { it.noiseKeyHex != me }, key = { it.noiseKeyHex }) { m ->
                val friend = friends.firstOrNull { it.noiseKeyHex == m.noiseKeyHex }
                val line = when {
                    m.noiseKeyHex == me -> "You"
                    m.noiseKeyHex in g.left -> "Left the group"
                    friend != null -> if (m.noiseKeyHex == g.roster.creatorKeyHex) "Friend · made the group" else "Friend"
                    else -> "Not your friend · added by " + (g.roster.members.firstOrNull { it.noiseKeyHex == g.roster.creatorKeyHex }?.name ?: "the creator")
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MoshAvatar(m.name, m.noiseKeyHex, 44.dp)
                    Column(Modifier.weight(1f)) {
                        Text(if (m.noiseKeyHex == me) "You" else m.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(line, fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 1)
                    }
                    if (friend != null && friend.status == FriendStatus.FRIEND && m.noiseKeyHex !in g.left) {
                        Box(Modifier.heightIn(min = 40.dp).clip(MoshShapes.Pill).background(cs.primary.copy(alpha = 0.16f))
                            .clickable { onFind(friend.peerID) }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                            Text("Find", color = cs.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (isCreator && m.noiseKeyHex != me) {
                        TextButton(onClick = { removing = m }) { Text("Remove", color = cs.error) }
                    }
                }
            }
            item {
                Spacer(Modifier.height(16.dp))
                if (g.closed) TextButton(onClick = { MoshGroups.store.delete(groupId); onDeleted() }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Delete this chat", color = cs.error, fontWeight = FontWeight.SemiBold)
                } else TextButton(onClick = { leaving = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Leave group", color = cs.error, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(g.roster.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename group") },
            text = { NameField(name, "Group name", { name = it }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    MoshGroups.edit(groupId, name, g.roster.members, emptyList()); renaming = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    removing?.let { m ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${m.name}?") },
            text = { Text("They stop getting this group's messages. Their copy of the chat stays on their phone.") },
            confirmButton = {
                TextButton(onClick = {
                    if (g.roster.members.size <= 2) MoshGroups.leave(groupId)
                    else MoshGroups.edit(groupId, g.roster.name, g.roster.members.filter { it.noiseKeyHex != m.noiseKeyHex }, emptyList())
                    removing = null
                }) { Text("Remove", color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("Leave ${g.roster.name}?") },
            text = { Text("You stop getting its messages and can't send to it. The chat stays here until you delete it.") },
            confirmButton = { TextButton(onClick = { MoshGroups.leave(groupId); leaving = false }) { Text("Leave", color = cs.error) } },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text("Stay") } },
        )
    }
}
