package com.bitchat.android.mosh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where photos and "who has my current photo" are kept. Keys are [ProfilePhotoBook.ME] or a friend's Noise key. */
interface PhotoPersistence {
    fun loadPhotos(): Map<String, ProfilePhoto>
    fun savePhoto(key: String, p: ProfilePhoto?)
    /** Friend's Noise key to (version of my photo sent, when). */
    fun loadSent(): Map<String, Pair<Long, Long>>
    fun saveSent(key: String, sent: Pair<Long, Long>?)
}

/**
 * Profile photos: mine, and the newest one each friend sent me. See docs/MOSH_PHOTOS.md.
 * - A friend's photo is kept only if it is newer than the one I hold and [decodes] (small, square JPEG).
 *   A removal (empty JPEG) is kept as a marker so an older photo arriving late cannot bring it back.
 * - My photo goes to each mutual friend once per change, and again every [RESEND_MS] in case a copy was lost.
 */
class ProfilePhotoBook(
    private val clock: () -> Long,
    private val decodes: (ByteArray) -> Boolean,
    private val persistence: PhotoPersistence? = null,
) {
    private val all = HashMap<String, ProfilePhoto>()
    private val sent = HashMap<String, Pair<Long, Long>>()
    private val _photos = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    /** Photos to show: key to JPEG. Removed photos are not in it. */
    val photos: StateFlow<Map<String, ByteArray>> = _photos.asStateFlow()

    init {
        persistence?.let { all += it.loadPhotos(); sent += it.loadSent() }
        publish()
    }

    @Synchronized fun mine(): ProfilePhoto? = all[ME]

    /** Sets my photo; returns what to send. */
    @Synchronized
    fun setMine(jpeg: ByteArray): ProfilePhoto? {
        val p = ProfilePhoto(stamp(), jpeg)
        if (jpeg.isEmpty() || !valid(p) || !decodes(jpeg)) return null
        put(ME, p)
        return p
    }

    /** Removes my photo; friends get a removal so theirs goes too. Null if I had none. */
    @Synchronized
    fun removeMine(): ProfilePhoto? {
        val old = all[ME] ?: return null
        if (old.jpeg.isEmpty()) return null
        val p = ProfilePhoto(stamp(), ByteArray(0))
        put(ME, p)
        return p
    }

    /** A photo from a friend (already friend-gated). True if it changed what I show. */
    @Synchronized
    fun applyFriend(noiseKeyHex: String, p: ProfilePhoto): Boolean {
        if (noiseKeyHex == ME || !valid(p) || p.updatedAtMs > clock() + MAX_FUTURE_MS) return false
        val old = all[noiseKeyHex]
        if (old != null && p.updatedAtMs <= old.updatedAtMs) return false
        if (p.jpeg.isNotEmpty() && !decodes(p.jpeg)) return false
        put(noiseKeyHex, p)
        return true
    }

    /** Friends (Noise keys) who should get my photo now. */
    @Synchronized
    fun due(friendKeys: List<String>): List<String> {
        val m = mine() ?: return emptyList()
        val now = clock()
        return friendKeys.filter { k ->
            val s = sent[k]
            s == null || s.first != m.updatedAtMs || now - s.second >= RESEND_MS
        }
    }

    @Synchronized
    fun markSent(friendKey: String, version: Long) {
        val s = version to clock()
        sent[friendKey] = s
        persistence?.saveSent(friendKey, s)
    }

    /** A removed friend: forget their photo, and that they had mine. */
    @Synchronized
    fun forget(noiseKeyHex: String) {
        if (all.remove(noiseKeyHex) != null) persistence?.savePhoto(noiseKeyHex, null)
        if (sent.remove(noiseKeyHex) != null) persistence?.saveSent(noiseKeyHex, null)
        publish()
    }

    @Synchronized
    fun wipe() {
        all.keys.toList().forEach { persistence?.savePhoto(it, null) }
        sent.keys.toList().forEach { persistence?.saveSent(it, null) }
        all.clear(); sent.clear(); publish()
    }

    /** Later than my last change even if the clock went back, so friends always take it. */
    private fun stamp(): Long = maxOf(clock(), (all[ME]?.updatedAtMs ?: 0L) + 1)

    private fun valid(p: ProfilePhoto) = runCatching { MoshCodec.encode(p) }.isSuccess

    private fun put(key: String, p: ProfilePhoto) {
        all[key] = p
        persistence?.savePhoto(key, p)
        publish()
    }

    private fun publish() { _photos.value = all.filterValues { it.jpeg.isNotEmpty() }.mapValues { it.value.jpeg } }

    companion object {
        const val ME = "me"
        const val RESEND_MS = 12 * 60 * 60_000L
        /** A friend's phone clock may run ahead, but not by a day. */
        const val MAX_FUTURE_MS = 24 * 60 * 60_000L
    }
}
