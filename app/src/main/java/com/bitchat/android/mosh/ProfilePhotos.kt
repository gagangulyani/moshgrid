package com.bitchat.android.mosh

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/** Profile photos on this phone: picking mine, sending it to friends, showing theirs. */
object ProfilePhotos {
    /** Sent and shown at this size (pixels, square). Small enough for a few Bluetooth packets. */
    const val SIDE_PX = 160
    private const val TARGET_BYTES = 10 * 1024

    @Volatile private var book: ProfilePhotoBook? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _images = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    /** For avatars: [ProfilePhotoBook.ME], each friend's Noise key and their mesh peer ID, to the decoded photo. */
    val images: StateFlow<Map<String, ImageBitmap>> = _images.asStateFlow()

    fun init(securePrefs: SharedPreferences) {
        if (book != null) return
        val b = ProfilePhotoBook(System::currentTimeMillis, ::decodes, PrefsPhotos(securePrefs))
        book = b
        scope.launch { b.photos.collect { publish(it) } }
    }

    fun mine(): ProfilePhoto? = book?.mine()?.takeIf { it.jpeg.isNotEmpty() }

    /** Picks up a photo from the photo picker. False if it could not be read. */
    suspend fun setMine(context: Context, uri: Uri): Boolean {
        val jpeg = runCatching { prepare(context, uri) }.onFailure { MoshMonitor.failed("photo_read_failed", it) }.getOrNull() ?: return false
        return setMine(jpeg)
    }

    fun setMine(jpeg: ByteArray): Boolean {
        val b = book ?: return false
        b.setMine(jpeg) ?: return false
        scope.launch { runCatching { tick() }.onFailure { MoshMonitor.failed("tick_failed", it) } }
        return true
    }

    fun removeMine() {
        book?.removeMine() ?: return
        scope.launch { runCatching { tick() }.onFailure { MoshMonitor.failed("tick_failed", it) } }
    }

    /** From a friend (MoshInbox has already checked they are one). */
    fun onReceived(fromPeerID: String, p: ProfilePhoto) {
        val friend = MoshRuntime.friendFor(fromPeerID) ?: return
        book?.applyFriend(friend.noiseKeyHex, p)
    }

    /** Every minute from the ticker, and right after a change: my photo to friends who don't have it yet. */
    fun tick() {
        val b = book ?: return
        if (!MoshRuntime.isInitialised) return
        val photo = b.mine() ?: return
        val friends = MoshRuntime.mutualFriends()
        val due = b.due(friends.map { it.noiseKeyHex }).toSet()
        friends.filter { it.noiseKeyHex in due }.forEach { f ->
            val route = MoshRuntime.sender.send(photo, f.peerID)
            if (route == MoshRoute.MESH || route == MoshRoute.MESH_AND_NOSTR || route == MoshRoute.NOSTR || route == MoshRoute.QUEUED) {
                b.markSent(f.noiseKeyHex, photo.updatedAtMs)
            }
        }
    }

    fun forget(noiseKeyHex: String) { book?.forget(noiseKeyHex) }

    fun wipe() { book?.wipe() }

    private fun publish(photos: Map<String, ByteArray>) {
        val out = HashMap<String, ImageBitmap>()
        photos.forEach { (key, jpeg) ->
            val img = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap() ?: return@forEach
            out[key] = img
            if (key != ProfilePhotoBook.ME) {
                com.bitchat.android.services.ContactIdentityResolver.bytesFromHex(key)
                    ?.let { com.bitchat.android.noise.NoisePeerIdentity.derivePeerID(it) }
                    ?.let { out[it] = img }
            }
        }
        _images.value = out
    }

    /** A JPEG we accept: square, 16 to 512 pixels a side, and it decodes. */
    private fun decodes(jpeg: ByteArray): Boolean {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
        if (o.outWidth != o.outHeight || o.outWidth !in 16..512) return false
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) != null
    }

    /** Reads the picked image, turns it upright, crops the middle square and makes a small JPEG. */
    private fun prepare(context: Context, uri: Uri): ByteArray? {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIDE_PX * 2) sample *= 2
        val raw = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = runCatching {
            cr.openInputStream(uri)?.use { androidx.exifinterface.media.ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)
        val side = minOf(raw.width, raw.height)
        val m = Matrix().apply {
            postRotate(rotation.toFloat())
            val s = SIDE_PX.toFloat() / side
            postScale(s, s)
        }
        val square = Bitmap.createBitmap(raw, (raw.width - side) / 2, (raw.height - side) / 2, side, side, m, true)
        val sized = if (square.width == SIDE_PX && square.height == SIDE_PX) square
            else Bitmap.createScaledBitmap(square, SIDE_PX, SIDE_PX, true)
        return compress(sized)
    }

    /** Lowers JPEG quality until it fits. */
    fun compress(bitmap: Bitmap): ByteArray? {
        for (q in intArrayOf(82, 72, 62, 50, 40)) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, q, out)
            if (out.size() <= TARGET_BYTES) return out.toByteArray()
        }
        return null
    }
}

/** Photos sit in the encrypted MoshGrid preferences, next to Help and friends' locations. */
private class PrefsPhotos(private val prefs: SharedPreferences) : PhotoPersistence {
    override fun loadPhotos(): Map<String, ProfilePhoto> = prefs.all.mapNotNull { (k, v) ->
        if (!k.startsWith(PHOTO) || v !is String) return@mapNotNull null
        val at = v.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
        val jpeg = runCatching { Base64.decode(v.substringAfter('|'), Base64.NO_WRAP) }.getOrNull() ?: return@mapNotNull null
        k.removePrefix(PHOTO) to ProfilePhoto(at, jpeg)
    }.toMap()

    override fun savePhoto(key: String, p: ProfilePhoto?) {
        prefs.edit().apply {
            if (p == null) remove(PHOTO + key)
            else putString(PHOTO + key, "${p.updatedAtMs}|" + Base64.encodeToString(p.jpeg, Base64.NO_WRAP))
        }.apply()
    }

    override fun loadSent(): Map<String, Pair<Long, Long>> = prefs.all.mapNotNull { (k, v) ->
        if (!k.startsWith(SENT) || v !is String) return@mapNotNull null
        val ver = v.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
        val at = v.substringAfter('|').toLongOrNull() ?: return@mapNotNull null
        k.removePrefix(SENT) to (ver to at)
    }.toMap()

    override fun saveSent(key: String, sent: Pair<Long, Long>?) {
        prefs.edit().apply { if (sent == null) remove(SENT + key) else putString(SENT + key, "${sent.first}|${sent.second}") }.apply()
    }

    private companion object {
        const val PHOTO = "photo|"
        const val SENT = "photo_sent|"
    }
}
