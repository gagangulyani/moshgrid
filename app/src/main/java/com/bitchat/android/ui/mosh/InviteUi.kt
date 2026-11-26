package com.bitchat.android.ui.mosh

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.mosh.InviteLink
import com.bitchat.android.mosh.InviteRequest
import com.bitchat.android.mosh.MoshInvites
import com.bitchat.android.mosh.MoshRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun inviteText(link: InviteLink) =
    "Add me on MoshGrid so we can find each other at the gig, even with no signal: ${link.url()}"

/** Builds my link off the main thread (it needs the Keystore), then hands it over. */
private suspend fun myLinkOrToast(context: Context, fresh: Boolean = false): InviteLink? {
    val link = withContext(Dispatchers.IO) { runCatching { MoshInvites.myLink(context, fresh) }.onFailure { com.bitchat.android.mosh.MoshMonitor.failed("invite_link_failed", it) }.getOrNull() }
    if (link == null) Toast.makeText(context, "MoshGrid is still starting. Try again in a moment.", Toast.LENGTH_SHORT).show()
    return link
}

/**
 * Friend requests waiting for me, and the two ways to invite someone who isn't next to you:
 * share a link to any app, or pick one contact and text it to them. Contacts never leave the phone.
 */
@Composable
fun InviteSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requests by MoshInvites.requests.collectAsState()
    val cs = MaterialTheme.colorScheme
    var contact by remember { mutableStateOf<Pair<String, String>?>(null) }
    var askNew by remember { mutableStateOf(false) }
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data
        if (res.resultCode == Activity.RESULT_OK && uri != null) contact = readPhone(context, uri)
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        requests.forEach { r -> RequestRow(r) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InviteButton("Invite with a link", Icons.Outlined.Link, Modifier.weight(1f)) {
                scope.launch {
                    val link = myLinkOrToast(context) ?: return@launch
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, inviteText(link)), "Send your invite"))
                }
            }
            InviteButton("Send to a contact", Icons.Outlined.Contacts, Modifier.weight(1f)) {
                runCatching { pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }
                    .onFailure { com.bitchat.android.mosh.MoshMonitor.failed("contact_pick_failed", it); Toast.makeText(context, "No contacts app found.", Toast.LENGTH_SHORT).show() }
            }
        }
        Text("Anyone with your link can ask to add you; nobody is added until you accept. Links last 7 days. " +
            if (!MoshRuntime.prefs.internet) "Invites need Internet on, for both of you." else "",
            fontSize = 13.sp, color = cs.onSurfaceVariant)
        Text("Stop old links working", fontSize = 13.sp, color = cs.primary, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(MoshShapes.Pill).clickable(role = Role.Button) { askNew = true }.padding(vertical = 6.dp))
    }

    contact?.let { (name, number) ->
        AlertDialog(
            onDismissRequest = { contact = null },
            title = { Text("Invite $name") },
            text = { Text("Your invite link goes in a message to $number. MoshGrid doesn't keep or send your contacts anywhere.") },
            confirmButton = {
                TextButton(onClick = {
                    contact = null
                    scope.launch {
                        val link = myLinkOrToast(context) ?: return@launch
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
                                .putExtra("sms_body", inviteText(link)))
                        }.onFailure { com.bitchat.android.mosh.MoshMonitor.failed("share_failed", it); Toast.makeText(context, "No messaging app found.", Toast.LENGTH_SHORT).show() }
                    }
                }) { Text("Text message") }
            },
            dismissButton = {
                TextButton(onClick = {
                    contact = null
                    scope.launch {
                        val link = myLinkOrToast(context) ?: return@launch
                        val digits = number.filter { it.isDigit() }
                        // WhatsApp's link needs the full international number; otherwise let the person choose the app.
                        val intent = if (number.trim().startsWith("+") && digits.length >= 8)
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=" + Uri.encode(inviteText(link))))
                        else Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, inviteText(link)), "Send your invite")
                        runCatching { context.startActivity(intent) }.onFailure {
                            com.bitchat.android.mosh.MoshMonitor.failed("share_failed", it)
                            Toast.makeText(context, "No app found to send it with.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("WhatsApp or other") }
            },
        )
    }

    if (askNew) {
        AlertDialog(
            onDismissRequest = { askNew = false },
            title = { Text("Stop old links working?") },
            text = { Text("Links you already sent stop working. Use this if a link went somewhere it shouldn't. Friends you already have stay friends.") },
            confirmButton = {
                TextButton(onClick = {
                    askNew = false
                    scope.launch { if (myLinkOrToast(context, fresh = true) != null) Toast.makeText(context, "Old links no longer work.", Toast.LENGTH_SHORT).show() }
                }) { Text("Stop old links") }
            },
            dismissButton = { TextButton(onClick = { askNew = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RequestRow(r: InviteRequest) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(MoshShapes.Card).background(cs.surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MoshAvatar(r.name, r.noiseKeyHex, 44.dp)
            Column(Modifier.weight(1f)) {
                Text(r.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("Used your invite link. Accept and they see where you are and get your Help alerts.", fontSize = 13.sp, color = cs.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(MoshShapes.Pill).background(cs.primary)
                .clickable(role = Role.Button) { scope.launch { withContext(Dispatchers.IO) { MoshInvites.accept(context, r) } } },
                contentAlignment = Alignment.Center) { Text("Accept", color = cs.onPrimary, fontWeight = FontWeight.Bold) }
            Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(MoshShapes.Pill).background(cs.surfaceVariant)
                .clickable(role = Role.Button) { MoshInvites.decline(context, r) },
                contentAlignment = Alignment.Center) { Text("Decline", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun InviteButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(modifier.heightIn(min = 52.dp).clip(MoshShapes.Pill).background(cs.surface).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = cs.primary, modifier = Modifier.size(20.dp))
        Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
    }
}

/** The tapped contact's name and number, read through the picker's one-off grant (no contacts permission). */
private fun readPhone(context: Context, uri: Uri): Pair<String, String>? = runCatching {
    context.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
        if (!c.moveToFirst()) null else (c.getString(0) ?: "them") to (c.getString(1) ?: return@use null)
    }
}.getOrNull()

/** Shown after tapping someone's invite link: confirm, then the request goes to them. */
@Composable
fun OpenedInviteDialog() {
    val link by MoshInvites.opened.collectAsState()
    val l = link ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val expired = l.expiresAtSec * 1000 < System.currentTimeMillis()
    AlertDialog(
        onDismissRequest = { MoshInvites.dismissOpened() },
        title = { Text(if (expired) "This invite has expired" else "Add ${l.name}?") },
        text = {
            Text(when {
                expired -> "Ask ${l.name} to send you a new link."
                !MoshRuntime.prefs.internet -> "Invites travel over the internet. Turn Internet on in Network, then open the link again."
                else -> "${l.name} gets your request. Once they accept, you're friends: you can find each other and get each other's Help alerts."
            })
        },
        confirmButton = {
            if (!expired && MoshRuntime.prefs.internet) TextButton(onClick = {
                scope.launch {
                    val r = withContext(Dispatchers.IO) { MoshInvites.sendRequest(context, l) }
                    Toast.makeText(context, when (r) {
                        MoshInvites.SendResult.Sent -> "Request sent. ${l.name} shows as waiting until they accept."
                        MoshInvites.SendResult.AlreadyFriends -> "You're already friends."
                        MoshInvites.SendResult.Mine -> "That's your own invite link."
                        MoshInvites.SendResult.Expired -> "This invite has expired."
                        MoshInvites.SendResult.NotReady -> "Couldn't send yet. Check your connection and try again."
                    }, Toast.LENGTH_LONG).show()
                    if (r != MoshInvites.SendResult.NotReady) MoshInvites.dismissOpened()
                }
            }) { Text("Send request") }
        },
        dismissButton = { TextButton(onClick = { MoshInvites.dismissOpened() }) { Text(if (expired) "OK" else "Cancel") } },
    )
}
