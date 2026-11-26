package com.bitchat.android.ui.mosh

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.MainActivity
import com.bitchat.android.mosh.MoshSocials
import com.bitchat.android.mosh.SocialsCode
import com.bitchat.android.ui.theme.BitchatTheme

/**
 * My socials code on its own, shown from the Quick Settings tile, also over the lock screen.
 * Over the lock screen it shows the code and nothing else: no editing, no way into the app
 * without unlocking. The code only holds public links, which is why it may show while locked.
 */
class ShareCodeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val book = MoshSocials.of(this)
        val name = getSharedPreferences("bitchat_prefs", MODE_PRIVATE).getString("nickname", null).orEmpty()
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        setContent {
            BitchatTheme(darkTheme = true) {
                val links by book.links.collectAsState()
                val hidden by book.hidden.collectAsState()
                val shared = links.filter { it !in hidden }
                Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (locked) {
                            Icon(Icons.Outlined.Lock, null, tint = Color(0xFFBDBDBD), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Locked", color = Color(0xFFBDBDBD), fontSize = 15.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { finish() }) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
                    }
                    Spacer(Modifier.weight(1f))
                    if (shared.isEmpty()) {
                        Text("No links to share yet", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Add links to your profiles on MoshGrid's Share tab first.", color = Color(0xFFBDBDBD), fontSize = 16.sp)
                        TextButton(onClick = ::openApp) { Text("Open MoshGrid", fontWeight = FontWeight.Bold, fontSize = 17.sp) }
                    } else {
                        CodeCard(SocialsCode.encode(name, shared), name, shared, codeSize = 280.dp)
                        Text(if (locked) "Showing over the lock screen: only this code. Unlock to change links or scan."
                            else "Any phone camera opens this code.", color = Color(0xFFBDBDBD), fontSize = 14.sp,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.weight(1f))
                    if (!locked && shared.isNotEmpty()) {
                        TextButton(onClick = ::openApp, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Text("Open the Share tab", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }

    /** Asks to unlock first when locked, then opens the Share tab. */
    private fun openApp() {
        val go = {
            startActivity(Intent(this, MainActivity::class.java).setAction(MoshSocials.ACTION_SHOW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { go() }
        }) else go()
    }
}
