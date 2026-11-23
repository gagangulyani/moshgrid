package com.bitchat.android.ui.mosh

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.mosh.FriendStatus
import com.bitchat.android.mosh.MoshMonitor
import com.bitchat.android.mosh.MoshRuntime
import kotlinx.coroutines.delay

private val HelpRed = Color(0xFFE8222B)
const val HOLD_SECONDS = 3

/** Hold for 3 seconds to alert friends; repeats every 2 minutes until cancelled. */
@Composable
fun HelpScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var note by remember { mutableStateOf("") }
    var holding by remember { mutableStateOf(false) }
    var left by remember { mutableIntStateOf(HOLD_SECONDS) }
    var active by remember { mutableStateOf(MoshRuntime.help.activeAlertId != null) }

    LaunchedEffect(holding) {
        left = HOLD_SECONDS
        while (holding && left > 0) { delay(1_000); if (holding) left-- }
        if (holding && left == 0) {
            MoshRuntime.help.raise(note)
            MoshMonitor.event(context, "help_raised")
            active = true; holding = false
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Ask for help", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Close") }
        }
        if (active) {
            Text("Your friends have been alerted.", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Sending your position again every 2 minutes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { MoshRuntime.help.cancel(); active = false },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text("I'm OK now, cancel", color = MaterialTheme.colorScheme.onSurface)
            }
        } else {
            OutlinedTextField(value = note, onValueChange = { note = it.take(120) },
                label = { Text("What to look for (optional)") }, modifier = Modifier.fillMaxWidth())
            Text("Hold for $HOLD_SECONDS seconds. Let go to stop.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(
                Modifier.size(220.dp).background(HelpRed, CircleShape).pointerInput(Unit) {
                    detectTapGestures(onPress = { holding = true; tryAwaitRelease(); holding = false })
                },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (holding) "$left" else "HELP", fontSize = if (holding) 80.sp else 44.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    if (holding) Text("Keep holding", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            Text("Who will see where you are: only your friends.", fontWeight = FontWeight.Bold)
        }
        Text("This is not an emergency service. In danger, call 112.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }) { Text("Call 112") }
    }
}

/** Friends list with status, location pause, and the way to add someone in person. */
@Composable
fun FriendsScreen(onAddFriend: () -> Unit) {
    var friends by remember { mutableStateOf(MoshRuntime.friends.friends()) }
    var paused by remember { mutableStateOf(MoshRuntime.prefs.pausedFriends) }
    LaunchedEffect(Unit) { while (true) { delay(5_000); friends = MoshRuntime.friends.friends() } }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Friends", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Button(onClick = onAddFriend, modifier = Modifier.fillMaxWidth()) { Text("Add a friend in person (QR)") }
        Text("Stand next to each other and scan each other's code. Both of you need to scan.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn {
            items(friends, key = { it.peerID }) { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.displayName, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(if (f.status == FriendStatus.WAITING_FOR_THEM) "Waiting for them to scan you" else "Friends",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Share location", fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
                    Switch(checked = f.peerID !in paused, onCheckedChange = { on ->
                        MoshRuntime.prefs.setPaused(f.peerID, !on); paused = MoshRuntime.prefs.pausedFriends
                    })
                }
            }
        }
    }
}
