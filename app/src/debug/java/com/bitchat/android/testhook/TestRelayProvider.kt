package com.bitchat.android.testhook

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Debug only. Runs before the Application starts and, when a test relay was set with the
 * `test_relay` hook, points Nostr at it instead of the public relays.
 */
class TestRelayProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val url = context?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)?.getString(KEY_URL, null)
        if (!url.isNullOrBlank()) com.bitchat.android.nostr.NostrTestRelays.override = listOf(url)
        return true
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    companion object {
        const val PREFS = "moshgrid_test_relay"
        const val KEY_URL = "url"
    }
}
