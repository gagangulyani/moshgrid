package com.bitchat.android.ui.mosh

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Sos
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bitchat.android.mosh.BatteryGuide
import com.bitchat.android.mosh.MoshPrefs

private const val STEPS = 5

/**
 * First-run setup: a welcome screen, then one question per step with a progress bar and Back.
 * Each step says one line; the detail sits behind a "Why?" row for people who want it.
 * Choices are saved together when the user presses Start.
 */
@Composable
fun MoshOnboarding(prefs: MoshPrefs, currentName: String = "", onName: (String) -> Unit = {}, onDone: () -> Unit) {
    val context = LocalContext.current
    // 0 is the welcome screen, 1..STEPS the steps.
    var step by rememberSaveable { mutableIntStateOf(0) }
    // Friends see this name (it travels in the QR code). Upstream's random "anon1234" helps nobody.
    var name by rememberSaveable { mutableStateOf(currentName.takeUnless { it.isBlank() || it.startsWith("anon") }.orEmpty()) }
    var bluetooth by rememberSaveable { mutableStateOf(prefs.bluetooth) }
    var internet by rememberSaveable { mutableStateOf(prefs.internet) }
    var share by rememberSaveable { mutableStateOf(prefs.shareLocation) }
    var monitoring by rememberSaveable { mutableStateOf(prefs.monitoringOptIn) }

    BackHandler(enabled = step > 0) { step-- }
    // Each permission is asked where it is needed; the answer doesn't block moving on.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val next: () -> Unit = {
        if (step != 1 || name.isNotBlank()) {
            when (step) {
                2 -> if (bluetooth) askMissing(context, bluetoothPermissions(), ask::launch)
                3 -> askMissing(context, listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), ask::launch)
            }
            step++
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        if (step == 0) {
            Welcome(onStart = { step = 1 })
            return@Column
        }
        StepHeader(step, onBack = { step-- })
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
            },
            modifier = Modifier.weight(1f),
            label = "onboarding step",
        ) { s ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                when (s) {
                    1 -> NameStep(name, onName = { name = it.take(24) }, onDone = next)
                    2 -> NetworkStep(bluetooth, { bluetooth = it }, internet, { internet = it })
                    3 -> LocationStep(share) { share = it }
                    4 -> PocketStep()
                    else -> ReportsStep(monitoring) { monitoring = it }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 20.dp, top = 8.dp)) {
            val last = step == STEPS
            Button(
                enabled = step != 1 || name.isNotBlank(),
                onClick = {
                    if (!last) next() else {
                        onName(name.trim())
                        prefs.bluetooth = bluetooth; prefs.internet = internet
                        prefs.monitoringOptIn = monitoring; prefs.shareLocation = share
                        prefs.onboardingDone = true
                        // Battery was handled in step 4; don't show the older battery screen again.
                        com.bitchat.android.onboarding.BatteryOptimizationPreferenceManager.setSkipped(context, true)
                        com.bitchat.android.mosh.MoshNetworkGate.apply(context, prefs)
                        com.bitchat.android.mosh.monitor.Monitoring.init(context.applicationContext as android.app.Application, prefs)
                        onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(if (last) "Start MoshGrid" else "Next", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp) }
            if (step == 4) {
                TextButton(onClick = next, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Do this later", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Welcome(onStart: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Find your friends in the crowd.", fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.semantics { heading() })
                Text("Even when the network dies.", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Feature(Icons.Outlined.LocationOn, MaterialTheme.colorScheme.secondary, "See where your friends are")
                Feature(Icons.Outlined.Sos, MaterialTheme.colorScheme.error, "Call them for help with one press")
                Feature(Icons.Outlined.Bluetooth, MaterialTheme.colorScheme.primary, "Works over Bluetooth, no signal needed")
            }
        }
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Set it up", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        IconBubble(icon, tint)
        Text(text, fontSize = 17.sp)
    }
}

@Composable
internal fun IconBubble(icon: ImageVector, tint: Color) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun StepHeader(step: Int, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 20.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Row(
            Modifier.weight(1f).padding(horizontal = 8.dp).clearAndSetSemantics { contentDescription = "Step $step of $STEPS" },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(STEPS) { i ->
                Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(
                    if (i < step) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant))
            }
        }
        Text("$step of $STEPS", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics { })
    }
}

@Composable
private fun StepTitle(title: String, lead: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.semantics { heading() })
        Text(lead, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NameStep(name: String, onName: (String) -> Unit, onDone: () -> Unit) {
    val focus = LocalFocusManager.current
    StepTitle("What do your friends call you?", "They'll see it when you ask for help.")
    OutlinedTextField(
        value = name, onValueChange = onName, singleLine = true,
        label = { Text("Your name") }, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focus.clearFocus(); onDone() }),
    )
    Why("Why now?", listOf(
        "Your name travels inside your QR code, so set it before you and your friends scan each other.",
        "Friends see it next to your spot on the map, in chats and on your Help alerts.",
    ))
}

@Composable
private fun NetworkStep(bluetooth: Boolean, onBluetooth: (Boolean) -> Unit, internet: Boolean, onInternet: (Boolean) -> Unit) {
    StepTitle("How should messages travel?", "Keep both on for the best chance.")
    SwitchCard(Icons.Outlined.Bluetooth, "Bluetooth", "Phone to phone. No signal needed.", bluetooth, onBluetooth)
    SwitchCard(Icons.Outlined.Language, "Internet", "Whenever there's signal.", internet, onInternet)
    Why("What goes over the internet?", listOf(
        "Messages are encrypted. Only your friends can read them.",
        "They pass through MoshGrid's own relay, with public relays as backup. Relays see your internet address and when you send, not what you send.",
        "Turn Internet off and nothing leaves your phone except over Bluetooth to nearby phones.",
    ))
    Why("What if there's no signal at all?", listOf(
        "Bluetooth reaches friends near you, and messages hop through other MoshGrid phones in between.",
        "Anything that couldn't get through goes when you get close again.",
        "Agree a meeting point before the show, just in case.",
    ))
}

@Composable
private fun LocationStep(share: Boolean, onShare: (Boolean) -> Unit) {
    StepTitle("Show friends where you are?", "Only people you scan in person.")
    SwitchCard(Icons.Outlined.LocationOn, "Share my location", null, share, onShare)
    Why("Who can see it?", listOf(
        "Only friends you added by scanning each other's QR codes. Nobody else, and not MoshGrid.",
        "You can stop sharing with one friend in Friends, or with everyone in Settings.",
        "When you press Help, your location goes to your friends even if sharing is off.",
    ))
}

@Composable
private fun PocketStep() {
    val context = LocalContext.current
    // Re-read both settings whenever the user comes back from the system screen.
    var checks by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) checks++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val unrestricted = remember(checks) { batteryUnrestricted(context) }
    val dnd = remember(checks) { context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true }

    StepTitle("Keep it awake in your pocket", "Your phone may stop MoshGrid to save battery.")
    ActionCard(Icons.Outlined.BatteryChargingFull, "Battery: unrestricted", "So it keeps running all show", unrestricted, "Allow") {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        runCatching { context.startActivity(direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { context.startActivity(list.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }
    ActionCard(Icons.Outlined.NotificationsActive, "Ring Help on silent", "Even in Do Not Disturb", dnd, "Allow") {
        runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    if (Build.VERSION.SDK_INT >= 33) {
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { checks++ }
        val granted = remember(checks) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
        ActionCard(Icons.Outlined.Sos, "Show Help alerts", "So a friend's Help reaches you", granted, "Allow") {
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    val steps = BatteryGuide.stepsFor(Build.MANUFACTURER)
    Why(if (steps.size > 2) "Extra steps for $maker phones" else "More battery tips", steps)
}

private fun bluetoothPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    else emptyList()

private fun askMissing(context: Context, permissions: List<String>, launch: (Array<String>) -> Unit) {
    val missing = permissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
    if (missing.isNotEmpty()) launch(missing.toTypedArray())
}

private fun batteryUnrestricted(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

@Composable
private fun ReportsStep(monitoring: Boolean, onMonitoring: (Boolean) -> Unit) {
    StepTitle("Help us fix crashes?", "On unless you turn it off. Never your location or messages.")
    SwitchCard(null, "Send crash reports", null, monitoring, onMonitoring)
    Why("What's in a report?", listOf(
        "What went wrong in the app, plus your phone model and Android version.",
        "Never your location, messages, name or friends.",
        "Reports go to MoshGrid's own server, and only when Internet is on.",
        "Turn this off any time in Settings. It stops straight away.",
    ))
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
        Text("Not an emergency service. In danger, call 112.", fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

/** A card the whole of which toggles a switch; screen readers read the title with the state. */
@Composable
internal fun SwitchCard(icon: ImageVector?, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    MoshCard {
        Row(
            Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (icon != null) IconBubble(icon, MaterialTheme.colorScheme.secondary)
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (subtitle != null) Text(subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

/** A setting the user changes in a system screen: a button until it is done, then a tick. */
@Composable
internal fun ActionCard(icon: ImageVector, title: String, subtitle: String, done: Boolean, action: String, onAction: () -> Unit) {
    MoshCard {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (done) IconBubble(Icons.Filled.Check, MaterialTheme.colorScheme.primary) else IconBubble(icon, MaterialTheme.colorScheme.secondary)
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(if (done) "Done" else subtitle, fontSize = 14.sp,
                    color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!done) OutlinedButton(onClick = onAction) { Text(action, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
internal fun MoshCard(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp)),
    ) { content() }
}

/** "Why?" row: closed by default, opens to a few short points. */
@Composable
internal fun Why(question: String, points: List<String>) {
    var open by rememberSaveable(question) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (open) MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f) else Color.Transparent),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = if (open) "Hide" else "Show") { open = !open }
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val tint = if (open) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
            Icon(Icons.Outlined.Info, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Text(question, fontSize = 16.sp, color = tint, fontWeight = if (open) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = tint)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(start = 42.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                points.forEach { Text("• $it", fontSize = 15.sp) }
            }
        }
    }
}

@Composable
internal fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row toggles, and screen readers read the label with the box.
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, fontSize = 15.sp)
    }
}
