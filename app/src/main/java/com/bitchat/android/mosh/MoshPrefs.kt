package com.bitchat.android.mosh

import android.content.Context
import android.content.SharedPreferences

/** User choices for MoshGrid: which networks to use, sharing, monitoring. Asked in onboarding. */
class MoshPrefs(private val prefs: SharedPreferences) {
    var bluetooth: Boolean
        get() = prefs.getBoolean(KEY_BLUETOOTH, true)
        set(v) = prefs.edit().putBoolean(KEY_BLUETOOTH, v).apply()

    /** Internet path (Nostr relays). Unticked means fully offline. */
    var internet: Boolean
        get() = prefs.getBoolean(KEY_INTERNET, true)
        set(v) = prefs.edit().putBoolean(KEY_INTERNET, v).apply()

    var monitoringOptIn: Boolean
        get() = prefs.getBoolean(KEY_MONITORING, false)
        set(v) = prefs.edit().putBoolean(KEY_MONITORING, v).apply()

    var shareLocation: Boolean
        get() = prefs.getBoolean(KEY_SHARE_LOCATION, true)
        set(v) = prefs.edit().putBoolean(KEY_SHARE_LOCATION, v).apply()

    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(v) = prefs.edit().putBoolean(KEY_ONBOARDING_DONE, v).apply()

    val offlineOnly: Boolean get() = !internet

    val pausedFriends: Set<String> get() = prefs.getStringSet(KEY_PAUSED, emptySet())?.toSet() ?: emptySet()

    fun setPaused(peerID: String, paused: Boolean) {
        val next = if (paused) pausedFriends + peerID else pausedFriends - peerID
        prefs.edit().putStringSet(KEY_PAUSED, next).apply()
    }

    companion object {
        private const val FILE = "moshgrid_prefs"
        private const val KEY_BLUETOOTH = "bluetooth"
        private const val KEY_INTERNET = "internet"
        private const val KEY_MONITORING = "monitoring_opt_in"
        private const val KEY_SHARE_LOCATION = "share_location"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_PAUSED = "paused_friends"

        fun of(context: Context) = MoshPrefs(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}

fun nostrAllowed(p: MoshPrefs): Boolean = p.internet
fun sentryAllowed(p: MoshPrefs): Boolean = p.internet && p.monitoringOptIn
