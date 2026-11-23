package com.bitchat.android.mosh

import android.app.Application
import android.content.Context
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.TorMode
import com.bitchat.android.net.TorPreferenceManager
import com.bitchat.android.nostr.NostrRelayManager
import com.bitchat.android.ui.debug.DebugSettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Enforces the user's network checkboxes for the whole app, not only MoshGrid payloads.
 * Internet off: Nostr relays never connect and nothing is sent (chat, receipts, everything),
 * and Tor is stopped so the app keeps no connections to the Tor network either.
 * Bluetooth off: upstream's live BLE switch stops scanning, advertising and GATT.
 */
object MoshNetworkGate {
    @Volatile var internetAllowed: Boolean = true
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun allowsNostr(): Boolean = internetAllowed

    /** Apply the saved choices now. Called at start and whenever a checkbox changes. */
    fun apply(context: Context, prefs: MoshPrefs) {
        internetAllowed = prefs.internet
        runCatching { DebugSettingsManager.getInstance().setBleEnabled(prefs.bluetooth) }
        applyTor(context, prefs.internet)
        val relays = runCatching { NostrRelayManager.getInstance(context.applicationContext) }.getOrNull() ?: return
        if (prefs.internet) relays.connect() else relays.disconnect()
    }

    /** Internet off stops Tor without changing the saved Tor choice; Internet on restores it. */
    private fun applyTor(context: Context, internet: Boolean) {
        val app = context.applicationContext as? Application ?: return
        scope.launch {
            runCatching {
                val mode = if (internet) TorPreferenceManager.get(app) else TorMode.OFF
                ArtiTorManager.getInstance().applyMode(app, mode)
            }
        }
    }
}
