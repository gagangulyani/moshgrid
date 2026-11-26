package com.bitchat.android.ui.mosh

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.mosh.MoshMonitor
import com.bitchat.android.mosh.MoshRuntime
import com.bitchat.android.mosh.monitor.Monitoring

/** Where the privacy policy and terms live (the same site the app updates from). */
internal const val PRIVACY_URL = "https://moshgrid.vercel.app/privacy"
internal const val TERMS_URL = "https://moshgrid.vercel.app/terms"

internal fun openWeb(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        MoshMonitor.failed("open_link_failed", it)
        android.widget.Toast.makeText(context, "No browser found. The page is at $url", android.widget.Toast.LENGTH_LONG).show()
    }
}

/**
 * "Report a problem": the person writes what went wrong and sends it, with the names and counts of
 * recent app events. It goes to MoshGrid's own server only on this tap, even if crash reports are off.
 */
@Composable
fun ReportPage() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var note by rememberSaveable { mutableStateOf("") }
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    val internet = MoshRuntime.prefs.internet
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Tell us what didn't work: what you tapped, what you expected, and what happened instead.",
            fontSize = 16.sp, color = cs.onSurfaceVariant)
        OutlinedTextField(value = note, onValueChange = { note = it.take(1000); result = null },
            label = { Text("What went wrong") },
            placeholder = { Text("e.g. I tapped Send in the group and nothing happened") },
            supportingText = { Text("${note.length} / 1000") },
            minLines = 4, shape = MoshShapes.Card, modifier = Modifier.fillMaxWidth())
        Why("What gets sent?", listOf(
            "Your note, exactly as you wrote it. Leave out names, numbers and places.",
            "Which app events happened in the last day, as names and counts (like map_failed 2).",
            "Your phone model, Android version and MoshGrid version.",
            "Never your location, messages, friends or photos.",
        ))
        Button(onClick = {
            val sent = Monitoring.userReport(note.trim(), MoshMonitor.recent(context))
            result = when {
                !internet -> "Internet is off for MoshGrid. Turn it on in Network, then send again."
                !sent -> "Couldn't send it. Try again in a moment."
                else -> { note = ""; "Sent. Thank you. If there's no signal right now, it goes when there is." }
            }
            MoshMonitor.event(context, if (sent) "report_sent" else "report_not_sent")
        }, enabled = note.isNotBlank() && internet, shape = MoshShapes.Pill,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text("Send report", fontWeight = FontWeight.Bold)
        }
        if (!internet) Text("Internet is off for MoshGrid, so reports can't be sent. Turn it on in Network.",
            fontSize = 14.sp, color = cs.onSurfaceVariant)
        result?.let { Text(it, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        MoshActionRow("Privacy policy", "What MoshGrid collects and why", null) { openWeb(context, PRIVACY_URL) }
    }
}
