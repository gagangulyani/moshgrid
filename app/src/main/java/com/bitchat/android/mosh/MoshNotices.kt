package com.bitchat.android.mosh

/** In-chat notices shown in MoshGrid's words (upstream's mention favorites, Nostr and mesh). */
object MoshNotices {
    /** Someone added or removed me as a friend. [mutual]: I have them as a friend too. */
    fun friendChange(name: String, added: Boolean, mutual: Boolean): String = when {
        !added -> "$name removed you as a friend."
        mutual -> "$name added you back. You're friends now."
        else -> "$name added you. Add them back to be friends."
    }
}
