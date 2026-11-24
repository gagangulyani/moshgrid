package com.bitchat.android.mosh

/**
 * Upstream features MoshGrid V1 does not use. Off means hidden, and for public chat also not
 * sent and not shown: MoshGrid is friends-only. Phones still relay other MoshGrid packets.
 */
object MoshFeatures {
    const val PUBLIC_CHAT = false
    const val VOICE = false
    const val MEDIA = false
    const val GLOBE = false
    const val LOCATION_NOTES = false
    const val CASHU = false
    const val GEOHASH = false
    /** Upstream About sheet: describes bitchat features MoshGrid hides. Triple-tap wipe still works. */
    const val ABOUT = false
    /** Favourite star in a chat: in MoshGrid it would add or remove a friend with one tap. */
    const val FAVORITE_STAR = false
    /**
     * Upstream blocks the whole app with a setup screen when Bluetooth or Location is off, also when
     * coming back to the app. MoshGrid keeps the app (and the Help button) open and shows a banner.
     */
    const val BLOCK_ON_RADIO_OFF = false
}
