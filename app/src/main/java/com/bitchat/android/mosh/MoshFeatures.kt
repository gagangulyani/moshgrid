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
}
