package com.bitchat.android.net

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object TorPreferenceManager {
    /**
     * MoshGrid: Tor is off unless chosen. Help over the internet must not wait for Tor to bootstrap on
     * jammed festival data. Content stays end-to-end encrypted; relays can see the phone's IP address.
     */
    private val DEFAULT_MODE = TorMode.OFF
    private const val PREFS_NAME = "bitchat_settings"
    private const val KEY_TOR_MODE = "tor_mode"

    private val _modeFlow = MutableStateFlow(DEFAULT_MODE)
    val modeFlow: StateFlow<TorMode> = _modeFlow

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_TOR_MODE, DEFAULT_MODE.name)
        val mode = runCatching { TorMode.valueOf(saved ?: DEFAULT_MODE.name) }.getOrDefault(DEFAULT_MODE)
        _modeFlow.value = mode
    }

    fun set(context: Context, mode: TorMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_TOR_MODE, mode.name).apply()
        _modeFlow.value = mode
    }

    fun get(context: Context): TorMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_TOR_MODE, DEFAULT_MODE.name)
        return runCatching { TorMode.valueOf(saved ?: DEFAULT_MODE.name) }.getOrDefault(DEFAULT_MODE)
    }
}

