package com.bitchat.android.net

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** MoshGrid: Help over the internet must not wait for Tor, so a fresh install has Tor off. */
@RunWith(RobolectricTestRunner::class)
class TorDefaultTest {
    @Test fun fresh_install_has_tor_off() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("bitchat_settings", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(TorMode.OFF, TorPreferenceManager.get(context))
    }

    @Test fun a_user_choice_is_kept() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        TorPreferenceManager.set(context, TorMode.ON)
        assertEquals(TorMode.ON, TorPreferenceManager.get(context))
        TorPreferenceManager.set(context, TorMode.OFF)
    }
}
