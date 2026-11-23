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
        val relays = runCatching { NostrRelayManager.getInstance(context.applicationContext) }.getOrNull() ?: return
        if (prefs.internet) relays.connect() else relays.disconnect()
    }
}
