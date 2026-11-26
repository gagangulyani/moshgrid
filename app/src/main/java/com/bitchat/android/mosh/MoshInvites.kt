package com.bitchat.android.mosh

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bitchat.android.MainActivity
import com.bitchat.android.R
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrTransport
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.services.VerificationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Adding friends with invite links, over the internet (Nostr). See docs/MOSH_INVITES.md. */
object MoshInvites {
    const val EXTRA_OPEN_REQUESTS = "mosh_open_invites"
    private const val CHANNEL_ID = "mosh_invites"

    @Volatile private var book: InviteBook? = null
    private val emptyRequests = MutableStateFlow<List<InviteRequest>>(emptyList())
    private val emptySent = MutableStateFlow<List<SentInvite>>(emptyList())
    val requests: StateFlow<List<InviteRequest>> get() = book?.requests ?: emptyRequests
    val sent: StateFlow<List<SentInvite>> get() = book?.sent ?: emptySent

    private val _opened = MutableStateFlow<InviteLink?>(null)
    /** A link the user just tapped, waiting for them to confirm "Send request". */
    val opened: StateFlow<InviteLink?> = _opened.asStateFlow()

    fun init(securePrefs: SharedPreferences) {
        if (book != null) return
        val rng = java.security.SecureRandom()
        book = InviteBook(System::currentTimeMillis, { n -> ByteArray(n).also { rng.nextBytes(it) } }, PrefsInvites(securePrefs))
    }

    /** From a tapped link (https://…/add#… or moshgrid://add#…). False if it is not a valid invite. */
    fun open(text: String): Boolean {
        val link = InviteLink.parse(text) ?: return false
        _opened.value = link
        return true
    }

    fun dismissOpened() { _opened.value = null }

    /** My link. Needs the Keystore, so call it off the main thread. Null until my keys are ready. */
    fun myLink(context: Context, fresh: Boolean = false): InviteLink? {
        val b = book ?: return null
        val noise = VerificationService.myNoiseKeyHex() ?: return null
        val nostr = NostrIdentityBridge.getCurrentNostrIdentity(context)?.publicKeyHex?.lowercase() ?: return null
        val (token, exp) = if (fresh) b.newToken() else b.currentToken()
        return InviteLink(noise, nostr, token, exp, myName(context))
    }

    sealed interface SendResult { object Sent : SendResult; object Expired : SendResult; object NotReady : SendResult; object AlreadyFriends : SendResult; object Mine : SendResult }

    /** "Send request" on a tapped link. They show as waiting until they accept. Off the main thread. */
    fun sendRequest(context: Context, link: InviteLink): SendResult {
        val b = book ?: return SendResult.NotReady
        val me = VerificationService.myNoiseKeyHex() ?: return SendResult.NotReady
        val myNostr = NostrIdentityBridge.getCurrentNostrIdentity(context)?.publicKeyHex?.lowercase() ?: return SendResult.NotReady
        if (link.noiseKeyHex == me) return SendResult.Mine
        if (MoshRuntime.isInitialised && MoshRuntime.mutualFriends().any { it.noiseKeyHex == link.noiseKeyHex }) return SendResult.AlreadyFriends
        val sent = b.send(link, me) ?: return SendResult.Expired
        val p = FriendInvite(FriendInvite.REQUEST, link.token, me, myNostr, myName(context), sent.sentAtMs)
        befriend(context, link.noiseKeyHex, link.nostrPubHex, link.name, theyAddedUs = false)
        val ok = NostrTransport.getInstance(context).sendMoshPayload(MoshCodec.encode(p), link.noiseKeyHex, link.nostrPubHex)
        _opened.value = null
        return if (ok) SendResult.Sent else SendResult.NotReady
    }

    /** A FRIEND_INVITE from Nostr. [senderNostrHex] is the key that signed it. */
    fun onReceived(context: Context, senderNostrHex: String, p: FriendInvite) {
        val b = book ?: return
        val sender = senderNostrHex.lowercase()
        when (p.kind) {
            FriendInvite.REQUEST -> {
                val me = VerificationService.myNoiseKeyHex()
                val isNew = b.onRequest(sender, p, me) { k ->
                    MoshRuntime.isInitialised && MoshRuntime.mutualFriends().any { it.noiseKeyHex == k }
                }
                if (isNew) notify(context, "${p.name.trim()} wants to add you", "Open MoshGrid to accept or decline.")
            }
            FriendInvite.ACCEPT -> {
                val match = b.onAccept(sender, p) ?: return
                befriend(context, match.link.noiseKeyHex, match.link.nostrPubHex, p.name.trim(), theyAddedUs = true)
                notify(context, "${p.name.trim()} added you", "You're friends now.")
            }
        }
    }

    /** Yes to a request: they become a full friend, and they hear back. Off the main thread. */
    fun accept(context: Context, r: InviteRequest): Boolean {
        val b = book ?: return false
        val me = VerificationService.myNoiseKeyHex() ?: return false
        val myNostr = NostrIdentityBridge.getCurrentNostrIdentity(context)?.publicKeyHex?.lowercase() ?: return false
        befriend(context, r.noiseKeyHex, r.nostrPubHex, r.name, theyAddedUs = true)
        val p = FriendInvite(FriendInvite.ACCEPT, r.token, me, myNostr, myName(context), System.currentTimeMillis())
        NostrTransport.getInstance(context).sendMoshPayload(MoshCodec.encode(p), r.noiseKeyHex, r.nostrPubHex)
        b.removeRequest(r.noiseKeyHex)
        cancelNotification(context)
        return true
    }

    fun decline(context: Context, r: InviteRequest) {
        book?.removeRequest(r.noiseKeyHex)
        if (requests.value.isEmpty()) cancelNotification(context)
    }

    fun wipe() { book?.wipe(); _opened.value = null }

    /** Same records as a QR scan: favourite, verified fingerprint, their Nostr key. */
    private fun befriend(context: Context, noiseKeyHex: String, nostrPubHex: String, name: String, theyAddedUs: Boolean) {
        val key = ContactIdentityResolver.bytesFromHex(noiseKeyHex) ?: return
        FavoritesPersistenceService.initialize(context.applicationContext)
        val favs = FavoritesPersistenceService.shared
        favs.updateFavoriteStatus(key, name.take(32), true)
        ContactIdentityResolver.npubFromHex(nostrPubHex)?.let { favs.updateNostrPublicKey(key, it) }
        if (theyAddedUs) favs.updatePeerFavoritedUs(key, true)
        val identity = SecureIdentityStateManager(context.applicationContext)
        identity.setVerifiedFingerprint(identity.generateFingerprint(key), true)
    }

    private fun myName(context: Context): String =
        context.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE).getString("nickname", null)
            ?.trim()?.take(32)?.ifBlank { null } ?: "A friend"

    private fun notify(context: Context, title: String, text: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm?.getNotificationChannel(CHANNEL_ID) == null) {
                nm?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Friend requests", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val open = PendingIntent.getActivity(context, NOTIFICATION_ID,
                Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_REQUESTS, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(text)
                .setContentIntent(open).setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Friend request").build())
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
        }
    }

    private fun cancelNotification(context: Context) { runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) } }

    private const val NOTIFICATION_ID = 0x48001
}

/** Invites sit in the encrypted MoshGrid preferences, one line per entry, fields separated by '|'. */
private class PrefsInvites(private val prefs: SharedPreferences) : InvitePersistence {
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = runCatching { Base64.decode(s, Base64.NO_WRAP) }.getOrNull()

    override fun loadToken(): Pair<ByteArray, Long>? {
        val v = prefs.getString("invite_token", null) ?: return null
        val t = unb64(v.substringBefore('|')) ?: return null
        val exp = v.substringAfter('|').toLongOrNull() ?: return null
        return (t to exp).takeIf { t.size == FriendInvite.TOKEN_BYTES }
    }

    override fun saveToken(token: ByteArray, expiresAtSec: Long) {
        prefs.edit().apply {
            if (token.isEmpty()) remove("invite_token") else putString("invite_token", b64(token) + "|" + expiresAtSec)
        }.apply()
    }

    override fun loadRequests(): List<InviteRequest> = prefs.getString("invite_requests", null).orEmpty().lines().mapNotNull { line ->
        val f = line.split('|')
        if (f.size != 5) return@mapNotNull null
        val name = unb64(f[2])?.toString(Charsets.UTF_8) ?: return@mapNotNull null
        val token = unb64(f[4]) ?: return@mapNotNull null
        InviteRequest(f[0], f[1], name, f[3].toLongOrNull() ?: return@mapNotNull null, token)
    }

    override fun saveRequests(r: List<InviteRequest>) {
        prefs.edit().putString("invite_requests", r.joinToString("\n") {
            listOf(it.noiseKeyHex, it.nostrPubHex, b64(it.name.toByteArray(Charsets.UTF_8)), it.atMs.toString(), b64(it.token)).joinToString("|")
        }).apply()
    }

    override fun loadSent(): List<SentInvite> = prefs.getString("invite_sent", null).orEmpty().lines().mapNotNull { line ->
        val link = InviteLink.parse(line.substringBefore('|')) ?: return@mapNotNull null
        SentInvite(link, line.substringAfter('|').toLongOrNull() ?: return@mapNotNull null)
    }

    override fun saveSent(s: List<SentInvite>) {
        prefs.edit().putString("invite_sent", s.joinToString("\n") { it.link.encode() + "|" + it.sentAtMs }).apply()
    }
}
