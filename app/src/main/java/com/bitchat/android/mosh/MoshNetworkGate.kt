package com.bitchat.android.mosh

import android.content.Context
import com.bitchat.android.nostr.NostrRelayManager
import com.bitchat.android.ui.debug.DebugSettingsManager

/**
 * Enforces the user's network checkboxes for the whole app, not only MoshGrid payloads.
 * Internet off: Nostr relays never connect and nothing is sent (chat, receipts, everything).
 * Bluetooth off: upstream's live BLE switch stops scanning, advertising and GATT.
 */
object MoshNetworkGate {
    @Volatile var internetAllowed: Boolean = true

    fun allowsNostr(): Boolean = internetAllowed

    /** Apply the saved choices now. Called at start and whenever a checkbox changes. */
    fun apply(context: Context, prefs: MoshPrefs) {
        internetAllowed = prefs.internet
        runCatching { DebugSettingsManager.getInstance().setBleEnabled(prefs.bluetooth) }
        runCatching { OfflineMaps.applyInternet() }
        val relays = runCatching { NostrRelayManager.getInstance(context.applicationContext) }.getOrNull() ?: return
        if (prefs.internet) relays.connect() else relays.disconnect()
        watchNetwork(context.applicationContext, relays)
    }

    @Volatile private var watching = false

    /** When signal comes back (after a dead zone, or Wi-Fi to mobile data), reconnect at once, not after the backoff. */
    private fun watchNetwork(context: Context, relays: NostrRelayManager) {
        if (watching) return
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        watching = runCatching {
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) = relays.onNetworkAvailable()
            })
        }.isSuccess
    }
}
