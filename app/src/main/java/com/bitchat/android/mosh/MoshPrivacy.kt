package com.bitchat.android.mosh

/**
 * What MoshGrid puts in packets that travel in clear.
 *
 * Upstream announces the nickname to everyone nearby. MoshGrid is friends-only: friends learn
 * each other's names from the in-person QR exchange, so clear-text packets carry no name.
 * The decoder requires the nickname TLV to be present, so it is sent with length 0.
 */
object MoshPrivacy {
    const val ANNOUNCED_NICKNAME = ""

    fun leavePayload(): ByteArray = ByteArray(0)
}
