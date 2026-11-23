package com.bitchat.android.ui.mosh

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.mosh.BatteryGuide
import com.bitchat.android.mosh.MoshPrefs

/** One checklist screen: networks, what Internet means, battery steps, Help alarm access, monitoring. */
@Composable
fun MoshOnboarding(prefs: MoshPrefs, onDone: () -> Unit) {
    val context = LocalContext.current
    var bluetooth by remember { mutableStateOf(prefs.bluetooth) }
    var internet by remember { mutableStateOf(prefs.internet) }
    var monitoring by remember { mutableStateOf(prefs.monitoringOptIn) }
    var share by remember { mutableStateOf(prefs.shareLocation) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Welcome to MoshGrid", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            "Find your friends at concerts and festivals, even when the phone network dies.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Section("How should MoshGrid reach your friends?")
        CheckRow("Bluetooth: phone to phone, works with no signal", bluetooth) { bluetooth = it }
        CheckRow("Internet: when there is signal", internet) { internet = it }
        Text(
            if (internet) "Internet uses public Nostr relays. Messages are encrypted, but relays can see that you are sending and when."
            else "Offline only: nothing leaves your phone except over Bluetooth to nearby phones.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Without signal, MoshGrid reaches friends near you, and catches up when you get close again. Always agree a meeting point.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Section("Location")
        CheckRow("Share my location with my friends", share) { share = it }
        Text("Only friends you added in person see it. You can pause anyone later.", fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Section("Keep MoshGrid alive in your pocket")
        BatteryGuide.stepsFor(Build.MANUFACTURER).forEach { Text("• $it", fontSize = 14.sp) }
        OutlinedButton(onClick = {
            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }) { Text("Open battery settings") }

        Section("Let Help alerts ring on silent")
        Text("When a friend asks for help, MoshGrid can ring even in Do Not Disturb. Allow it here.", fontSize = 14.sp)
        OutlinedButton(onClick = {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm?.isNotificationPolicyAccessGranted != true) {
                runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }) { Text("Allow Do Not Disturb access") }

        Section("Help us fix problems")
        CheckRow("Send crash and problem reports", monitoring) { monitoring = it }
        Text("Reports never include your location, messages, names or friends. Never sent in offline-only mode.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text("This is not an emergency service. In danger, call 112.", fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error)

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                prefs.bluetooth = bluetooth; prefs.internet = internet
                prefs.monitoringOptIn = monitoring; prefs.shareLocation = share
                prefs.onboardingDone = true
                com.bitchat.android.mosh.MoshNetworkGate.apply(context, prefs)
                onDone()
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Start", fontWeight = FontWeight.ExtraBold) }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
internal fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, fontSize = 15.sp)
    }
}
