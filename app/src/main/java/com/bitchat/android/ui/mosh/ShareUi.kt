package com.bitchat.android.ui.mosh

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.mosh.MetPerson
import com.bitchat.android.mosh.MoshInvites
import com.bitchat.android.mosh.MoshSocials
import com.bitchat.android.mosh.SocialsCard
import com.bitchat.android.mosh.SocialsCode
import com.bitchat.android.services.VerificationService
import com.bitchat.android.ui.ChatViewModel
import com.bitchat.android.ui.ScannerView
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The Share tab: my socials code (any phone camera opens it) and a scanner for other people's codes.
 * Made for strangers: the code holds only the links I tick, never my location or a way to message me.
 * See docs/MOSH_SOCIALS.md.
 */
@Composable
fun ShareTab(chatViewModel: ChatViewModel, onEditLinks: () -> Unit, onPeopleMet: () -> Unit, banners: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val book = remember { MoshSocials.of(context) }
    val links by book.links.collectAsState()
    var scanning by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        MoshTitle("Share")
        banners()
        if (links.isEmpty() && !scanning) {
            ShareEmpty(onAdd = onEditLinks, onScan = { scanning = true })
            return@Column
        }
        TwoWay("My code", "Scan", scanning) { scanning = it }
        Spacer(Modifier.height(12.dp))
        if (scanning) ScanSocials(chatViewModel) { scanning = false }
        else MyCode(chatViewModel.nickname.collectAsState().value, onEditLinks, onPeopleMet)
    }
}

@Composable
private fun ShareEmpty(onAdd: () -> Unit, onScan: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Give people you meet your links", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 30.sp)
        Text("Paste links to your Instagram, Spotify or anything else. People you meet scan your code with any phone camera. " +
            "They don't need MoshGrid.", fontSize = 16.sp, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        BigButton("Add my links", Icons.Filled.Add, primary = true, onClick = onAdd)
        BigButton("Scan someone's code", Icons.Outlined.QrCodeScanner, primary = false, onClick = onScan)
    }
}

/** My code, the links it holds (tap to leave one out), and the two socials pages. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MyCode(name: String, onEditLinks: () -> Unit, onPeopleMet: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val book = remember { MoshSocials.of(context) }
    val links by book.links.collectAsState()
    val hidden by book.hidden.collectAsState()
    val met by book.met.collectAsState()
    val shared = links.filter { it !in hidden }
    val code = remember(name, shared) { SocialsCode.encode(name, shared) }
    FullBrightness()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CodeCard(code, name, shared)
        Text("This code shares", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val labels = links.map(SocialsCode::label)
            links.forEachIndexed { i, link ->
                val on = link !in hidden
                // Two links on the same site show their addresses, so the chips can be told apart.
                val text = if (labels.count { it == labels[i] } > 1) SocialsCode.shown(link) else labels[i]
                Row(Modifier.heightIn(min = 44.dp).clip(MoshShapes.Pill)
                    .background(if (on) cs.primary else Color.Transparent)
                    .border(1.5.dp, if (on) cs.primary else cs.outline, MoshShapes.Pill)
                    .clickable(role = Role.Checkbox) { book.setShown(link, !on) }
                    .semantics { selected = on; contentDescription = "$text, ${if (on) "in your code" else "left out"}" }
                    .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (on) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = cs.onPrimary)
                    Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (on) cs.onPrimary else cs.onSurface, maxLines = 1)
                }
            }
        }
        Column(Modifier.fillMaxWidth().clip(MoshShapes.Tile).background(cs.surface).padding(horizontal = 16.dp, vertical = 4.dp)) {
            MoshActionRow("Edit my links", "${links.size} of ${SocialsCode.MAX_LINKS} links", null, onEditLinks)
            MoshActionRow("People I met", if (met.isEmpty()) "Nobody saved yet" else "${met.size} saved", null, onPeopleMet)
        }
        LockNote("Any phone camera opens this code. It holds only the links ticked here, never your location or a way to message you. Nothing is uploaded.")
        Spacer(Modifier.height(8.dp))
    }
}

/** The white card with the code, my name and what it holds. Also shown over the lock screen. */
@Composable
fun CodeCard(code: String?, name: String, shared: List<String>, codeSize: androidx.compose.ui.unit.Dp = 240.dp) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color.White).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val bitmap = remember(code) { code?.let(::qrBitmap) }
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = "Your socials code", filterQuality = FilterQuality.None,
                modifier = Modifier.size(codeSize))
        } else {
            Box(Modifier.size(codeSize), contentAlignment = Alignment.Center) {
                Text("Tick at least one link below to make your code.", color = Color(0xFF3A3A3A), fontSize = 16.sp,
                    textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
            }
        }
        Text(name.ifBlank { "Your socials" }, color = Color.Black, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        if (shared.isNotEmpty()) Text(shared.map(SocialsCode::label).distinct().joinToString(", "), color = Color(0xFF3A3A3A), fontSize = 15.sp,
            textAlign = TextAlign.Center)
    }
}

/** One module per pixel; drawn scaled up without smoothing so the code stays sharp. */
internal fun qrBitmap(text: String): Bitmap? = runCatching {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    val px = IntArray(m.width * m.height) { if (m[it % m.width, it / m.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
}.getOrNull()

/** Screen at full brightness and kept on while the code is showing, so it scans in a dark venue. */
@Composable
fun FullBrightness() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        val w = activity.window
        val before = w.attributes.screenBrightness
        w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            w.attributes = w.attributes.apply { screenBrightness = before }
            w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private sealed interface Scan {
    data class Socials(val card: SocialsCard) : Scan
    data class Friend(val qr: VerificationService.VerificationQR) : Scan
    data class Problem(val title: String, val line: String) : Scan
}

/** Scanner for socials codes; also takes friend codes and invite links, so one scanner does everything. */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun ScanSocials(chatViewModel: ChatViewModel, onShowMine: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val camera = rememberPermissionState(android.Manifest.permission.CAMERA)
    var result by remember { mutableStateOf<Scan?>(null) }
    var round by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val myNoiseKey = remember {
        VerificationService.VerificationQR.fromUrlString(
            chatViewModel.buildMyQRString(chatViewModel.nickname.value, chatViewModel.getCurrentNpub()))?.noiseKeyHex
    }
    val onScan: (String) -> Unit = { text ->
        if (result == null) result = when {
            SocialsCode.decode(text) != null -> Scan.Socials(SocialsCode.decode(text)!!)
            text.trim().startsWith(SocialsCode.PREFIX) -> Scan.Problem("This socials code is broken",
                "Part of it is missing or it holds something that isn't a web link. Ask them to show it again.")
            VerificationService.verifyScannedQR(text) != null -> {
                val qr = VerificationService.verifyScannedQR(text)!!
                if (myNoiseKey != null && qr.noiseKeyHex.equals(myNoiseKey, ignoreCase = true))
                    Scan.Problem("That's your own friend code", "Scan someone else's code.")
                else Scan.Friend(qr)
            }
            MoshInvites.open(text) -> null // The invite dialog takes it from here.
            else -> Scan.Problem("That isn't a MoshGrid code", "Point at a socials code or a friend code from MoshGrid.")
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (camera.status.isGranted) {
            Box(Modifier.widthIn(max = 340.dp).fillMaxWidth().aspectRatio(1f).clip(MoshShapes.Tile).background(Color.Black),
                contentAlignment = Alignment.Center) {
                key(round) { ScannerView(onScan = onScan) }
                Box(Modifier.fillMaxSize(0.68f).border(3.dp, cs.primary, RoundedCornerShape(18.dp)))
            }
            Text("Point at someone's socials code", fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("Friend codes and invite codes work here too.", fontSize = 15.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
        } else {
            Spacer(Modifier.height(16.dp))
            Text("Camera not allowed", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text("MoshGrid needs the camera to scan codes. Nothing it sees is saved or sent.", fontSize = 16.sp,
                color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            BigButton("Allow camera", null, primary = true) { camera.launchPermissionRequest() }
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Open phone settings", fontWeight = FontWeight.Bold) }
            TextButton(onClick = onShowMine) { Text("Show my code instead", fontWeight = FontWeight.Bold) }
        }
    }
    val done: () -> Unit = { result = null; round += 1 }
    when (val r = result) {
        is Scan.Socials -> SocialsCardSheet(r.card, savedAt = null, onDismiss = done)
        is Scan.Friend -> AlertDialog(onDismissRequest = done,
            title = { Text("Add ${r.qr.nickname.ifBlank { "them" }} as a friend?") },
            text = { Text("That's a friend code, not a socials code. Friends can see where you are and get your Help alerts once you both accept.") },
            confirmButton = { TextButton(onClick = {
                val ok = chatViewModel.beginQRVerification(r.qr)
                Toast.makeText(context, if (ok) "Friend request sent to ${r.qr.nickname}." else
                    "${r.qr.nickname} isn't nearby. Stand close together and scan again.", Toast.LENGTH_LONG).show()
                done()
            }) { Text("Add friend") } },
            dismissButton = { TextButton(onClick = done) { Text("Cancel") } })
        is Scan.Problem -> AlertDialog(onDismissRequest = done, title = { Text(r.title) }, text = { Text(r.line) },
            confirmButton = { TextButton(onClick = done) { Text("Scan again") } })
        null -> Unit
    }
}

/**
 * Someone's socials: full addresses, nothing opens until tapped. [savedAt] is set when it comes from
 * People I met, which adds Forget.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SocialsCardSheet(card: SocialsCard, savedAt: MetPerson?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val book = remember { MoshSocials.of(context) }
    val met by book.met.collectAsState()
    val saved = met.any { it.card == card }
    val who = card.name.ifBlank { "Someone" }
    com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)
            .navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MoshAvatar(who, card.name + card.links.firstOrNull().orEmpty(), 60.dp)
                Column(Modifier.weight(1f)) {
                    Text(who, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                    Text(if (savedAt != null) "Saved ${dayLabel(savedAt.atMs)}. Not a friend." else "Scanned just now. Not a friend.",
                        fontSize = 14.sp, color = cs.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surface).padding(horizontal = 16.dp, vertical = 4.dp)) {
                card.links.forEach { link ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(SocialsCode.label(link), fontSize = 13.sp, color = cs.onSurfaceVariant)
                            Text(SocialsCode.shown(link), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(Modifier.heightIn(min = 44.dp).clip(MoshShapes.Pill).background(cs.surfaceVariant)
                            .clickable(role = Role.Button) { openLink(context, link) }
                            .semantics { contentDescription = "Open ${SocialsCode.shown(link)}" }.padding(horizontal = 18.dp),
                            contentAlignment = Alignment.Center) { Text("Open", fontWeight = FontWeight.Bold) }
                    }
                }
            }
            Text("Links from someone you just met: check the address before you open one. $who can't see your location or message you in MoshGrid.",
                fontSize = 13.sp, color = cs.onSurfaceVariant)
            if (savedAt != null) {
                BigButton("Forget $who", null, primary = false) { book.forget(savedAt); onDismiss() }
            } else {
                BigButton(if (saved) "Saved to People I met" else "Save to People I met", if (saved) Icons.Filled.Check else null,
                    primary = !saved) { if (!saved) book.remember(card, System.currentTimeMillis()) }
            }
        }
    }
}

private fun openLink(context: Context, link: String) {
    // Checked again here: only a link that passes the code rules is ever handed to the browser.
    val ok = SocialsCode.cleanLink(link) ?: return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ok)).addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Toast.makeText(context, "No browser found to open this link.", Toast.LENGTH_SHORT).show() }
}

/** Edit my links: paste profile links; the name of each comes from its site. Saved as you type. */
@Composable
fun EditLinksPage() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val book = remember { MoshSocials.of(context) }
    val drafts = remember { mutableStateListOf<String>().apply { addAll(book.links.value); if (size < SocialsCode.MAX_LINKS) add("") } }
    fun save() { book.setLinks(drafts.toList()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Paste links to the profiles you're happy to give people you meet. Copy each one from the app's Share profile button.",
            fontSize = 16.sp, color = cs.onSurfaceVariant)
        Column(Modifier.fillMaxWidth().clip(MoshShapes.Tile).background(cs.surface).padding(horizontal = 16.dp, vertical = 6.dp)) {
            drafts.forEachIndexed { i, value ->
                val clean = SocialsCode.cleanLink(value)
                val (label, bad) = when {
                    value.isBlank() -> "New link" to false
                    clean != null && drafts.take(i).any { SocialsCode.cleanLink(it) == clean } -> "Already added above" to true
                    clean != null -> SocialsCode.label(clean) to false
                    "://" in value && !value.trim().startsWith("https://") -> "Needs to start with https://" to true
                    else -> "That isn't a link MoshGrid can use" to true
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (bad) Color(0xFFFF8A80) else cs.onSurfaceVariant)
                        BasicTextField(value = value, onValueChange = { drafts[i] = it.replace("\n", ""); save() }, singleLine = true,
                            textStyle = TextStyle(color = cs.onSurface, fontSize = 16.sp), cursorBrush = SolidColor(cs.primary),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).semantics { contentDescription = "Link ${i + 1}" },
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (value.isEmpty()) Text("https://", color = cs.onSurfaceVariant, fontSize = 16.sp)
                                    inner()
                                }
                            })
                    }
                    IconButton(onClick = { drafts.removeAt(i); if (drafts.isEmpty()) drafts.add(""); save() },
                        modifier = Modifier.size(44.dp).clip(CircleShape).background(cs.surfaceVariant)) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove link ${i + 1}", modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (drafts.size < SocialsCode.MAX_LINKS) {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) { drafts.add("") },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Add, null, tint = cs.primary)
                    Text("Add a link", color = cs.primary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
        LockNote("Only https web links, up to ${SocialsCode.MAX_LINKS}. They stay on this phone until you show your code, and panic wipe clears them.")
    }
}

/** People I met: everyone whose code I saved, newest first, grouped by day. Only on this phone. */
@Composable
fun PeopleMetPage() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val book = remember { MoshSocials.of(context) }
    val met by book.met.collectAsState()
    var open by remember { mutableStateOf<MetPerson?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (met.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("Nobody saved yet", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text("Scan someone's socials code on the Share tab, then tap Save to People I met. Their links wait here for when you have signal.",
                fontSize = 16.sp, color = cs.onSurfaceVariant)
        }
        met.groupBy { dayLabel(it.atMs) }.forEach { (day, people) ->
            Text(day, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            Column(Modifier.fillMaxWidth().clip(MoshShapes.Tile).background(cs.surface).padding(horizontal = 16.dp, vertical = 4.dp)) {
                people.forEach { p ->
                    val who = p.card.name.ifBlank { "Someone" }
                    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(role = Role.Button) { open = p },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        MoshAvatar(who, p.card.name + p.card.links.firstOrNull().orEmpty(), 44.dp)
                        Column(Modifier.weight(1f)) {
                            Text(who, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text(p.card.links.map(SocialsCode::label).distinct().joinToString(", "), fontSize = 14.sp, color = cs.onSurfaceVariant)
                        }
                        Icon(Icons.Outlined.ChevronRight, null, tint = cs.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        LockNote("Only on this phone. Open someone and tap Forget to remove them; panic wipe clears the list.")
    }
    open?.let { p -> SocialsCardSheet(p.card, savedAt = p) { open = null } }
}

private fun dayLabel(atMs: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = atMs }
    val now = Calendar.getInstance()
    val sameDay = c.get(Calendar.YEAR) == now.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    now.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = c.get(Calendar.YEAR) == now.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay -> "Today"
        yesterday -> "Yesterday"
        else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(atMs))
    }
}

@Composable
private fun TwoWay(left: String, right: String, rightOn: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(52.dp).clip(MoshShapes.Pill).background(cs.surface).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(left to false, right to true).forEach { (label, isRight) ->
            val on = rightOn == isRight
            Box(Modifier.weight(1f).fillMaxSize().clip(MoshShapes.Pill).background(if (on) cs.onSurface else Color.Transparent)
                .clickable(role = Role.Tab) { onChange(isRight) }.semantics { selected = on },
                contentAlignment = Alignment.Center) {
                Text(label, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = if (on) cs.surface else cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BigButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, primary: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(56.dp).clip(MoshShapes.Pill)
        .background(if (primary) cs.primary else Color.Transparent)
        .then(if (primary) Modifier else Modifier.border(1.5.dp, cs.outline, MoshShapes.Pill))
        .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = if (primary) cs.onPrimary else cs.onSurface); Spacer(Modifier.size(8.dp)) }
        Text(label, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = if (primary) cs.onPrimary else cs.onSurface)
    }
}

@Composable
private fun ColumnScope.LockNote(text: String) {
    val cs = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.Lock, null, tint = MoshColors.Cyan, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Text(text, fontSize = 13.sp, color = cs.onSurfaceVariant, lineHeight = 18.sp)
    }
}
