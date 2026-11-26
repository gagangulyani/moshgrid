package com.bitchat.android.mosh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

/** What an invite link carries. Anyone who sees the link sees this: name and public keys, never a location. */
class InviteLink(
    val noiseKeyHex: String,
    val nostrPubHex: String,
    val token: ByteArray,
    val expiresAtSec: Long,
    val name: String,
) {
    fun url(): String = PREFIX + encode()

    fun encode(): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeByte(VERSION); write(hex(noiseKeyHex)); write(hex(nostrPubHex)); write(token); writeLong(expiresAtSec)
            val n = name.toByteArray(Charsets.UTF_8); writeByte(n.size); write(n)
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray())
    }

    override fun equals(other: Any?) = other is InviteLink && other.noiseKeyHex == noiseKeyHex && other.nostrPubHex == nostrPubHex &&
        other.token.contentEquals(token) && other.expiresAtSec == expiresAtSec && other.name == name
    override fun hashCode() = noiseKeyHex.hashCode() * 31 + token.contentHashCode()

    companion object {
        const val HOST = "moshgrid.vercel.app"
        const val PREFIX = "https://$HOST/add#"
        private const val VERSION = 1
        private val HEX_KEY = Regex("^[0-9a-f]{64}$")

        /**
         * Reads a link: the https form, the moshgrid:// form, or the bare code. Fail-closed:
         * anything malformed is null. Expiry is checked by the caller (it needs a clock).
         */
        fun parse(text: String): InviteLink? = try {
            val code = text.trim().substringAfter('#', text.trim()).trim()
            val b = Base64.getUrlDecoder().decode(code)
            val input = DataInputStream(ByteArrayInputStream(b))
            if (input.readUnsignedByte() != VERSION) null else {
                val noise = ByteArray(32).also { input.readFully(it) }
                val nostr = ByteArray(32).also { input.readFully(it) }
                val token = ByteArray(FriendInvite.TOKEN_BYTES).also { input.readFully(it) }
                val exp = input.readLong()
                val n = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                val name = String(n, Charsets.UTF_8)
                val link = InviteLink(hexOf(noise), hexOf(nostr), token, exp, name)
                link.takeIf {
                    input.available() == 0 && HEX_KEY.matches(it.noiseKeyHex) && HEX_KEY.matches(it.nostrPubHex) &&
                        name.isNotBlank() && n.size <= MoshCodec.MAX_LABEL_BYTES && exp > 0 && !noise.all { x -> x == 0.toByte() }
                }
            }
        } catch (_: Exception) {
            null
        }

        private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        private fun hexOf(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}

/** Someone who used my link and is waiting for me to say yes. */
class InviteRequest(val noiseKeyHex: String, val nostrPubHex: String, val name: String, val atMs: Long, val token: ByteArray) {
    override fun equals(other: Any?) = other is InviteRequest && other.noiseKeyHex == noiseKeyHex && other.nostrPubHex == nostrPubHex &&
        other.name == name && other.atMs == atMs && other.token.contentEquals(token)
    override fun hashCode() = noiseKeyHex.hashCode()
}

/** A request I sent with someone's link, waiting for their yes. */
data class SentInvite(val link: InviteLink, val sentAtMs: Long)

interface InvitePersistence {
    fun loadToken(): Pair<ByteArray, Long>?
    fun saveToken(token: ByteArray, expiresAtSec: Long)
    fun loadRequests(): List<InviteRequest>
    fun saveRequests(r: List<InviteRequest>)
    fun loadSent(): List<SentInvite>
    fun saveSent(s: List<SentInvite>)
}

/**
 * Invite links and requests. See docs/MOSH_INVITES.md.
 * - My link holds a random token, valid [LINK_VALID_MS]; a new link replaces it and old links stop working.
 * - A request counts only if its Nostr key is the one that signed it ([senderNostrHex]), it carries
 *   my current, unexpired token, and it is not from me or from someone already a friend.
 * - An accept counts only if it answers a request I sent with that person's link.
 */
class InviteBook(
    private val clock: () -> Long,
    private val random: (Int) -> ByteArray,
    private val persistence: InvitePersistence? = null,
) {
    private var token: Pair<ByteArray, Long>? = persistence?.loadToken()
    private val _requests = MutableStateFlow(persistence?.loadRequests().orEmpty())
    val requests: StateFlow<List<InviteRequest>> = _requests.asStateFlow()
    private val _sent = MutableStateFlow(persistence?.loadSent().orEmpty())
    val sent: StateFlow<List<SentInvite>> = _sent.asStateFlow()

    /** My current link's token and expiry (seconds), made fresh if there is none or it ran out. */
    @Synchronized
    fun currentToken(): Pair<ByteArray, Long> {
        val t = token
        if (t != null && t.second * 1000 > clock() + MIN_LEFT_MS) return t
        return newToken()
    }

    @Synchronized
    fun newToken(): Pair<ByteArray, Long> {
        val t = random(FriendInvite.TOKEN_BYTES) to (clock() + LINK_VALID_MS) / 1000
        token = t
        persistence?.saveToken(t.first, t.second)
        return t
    }

    /** True if it was kept as a new request (show it; maybe notify). */
    @Synchronized
    fun onRequest(senderNostrHex: String, p: FriendInvite, myNoiseKeyHex: String?, isFriend: (String) -> Boolean): Boolean {
        val t = token ?: return false
        if (p.kind != FriendInvite.REQUEST || p.nostrPubHex != senderNostrHex) return false
        if (!p.token.contentEquals(t.first) || t.second * 1000 < clock()) return false
        if (p.noiseKeyHex == myNoiseKeyHex || isFriend(p.noiseKeyHex)) return false
        val others = _requests.value.filterNot { it.noiseKeyHex == p.noiseKeyHex }
        if (others.size >= MAX_REQUESTS) return false
        val isNew = others.size == _requests.value.size
        set(others + InviteRequest(p.noiseKeyHex, p.nostrPubHex, p.name.trim(), clock(), p.token))
        return isNew
    }

    @Synchronized
    fun removeRequest(noiseKeyHex: String) = set(_requests.value.filterNot { it.noiseKeyHex == noiseKeyHex })

    /** Remembers a request I am sending with [link]. Null if the link is expired or mine. */
    @Synchronized
    fun send(link: InviteLink, myNoiseKeyHex: String?): SentInvite? {
        if (link.expiresAtSec * 1000 < clock() || link.noiseKeyHex == myNoiseKeyHex) return null
        val s = SentInvite(link, clock())
        _sent.value = _sent.value.filterNot { it.link.noiseKeyHex == link.noiseKeyHex }.filter { clock() - it.sentAtMs < LINK_VALID_MS } + s
        persistence?.saveSent(_sent.value)
        return s
    }

    /** The request this accept answers, or null if it answers nothing I sent. The match is removed. */
    @Synchronized
    fun onAccept(senderNostrHex: String, p: FriendInvite): SentInvite? {
        if (p.kind != FriendInvite.ACCEPT || p.nostrPubHex != senderNostrHex) return null
        val match = _sent.value.firstOrNull {
            it.link.nostrPubHex == senderNostrHex && it.link.noiseKeyHex == p.noiseKeyHex &&
                it.link.token.contentEquals(p.token) && clock() - it.sentAtMs < LINK_VALID_MS
        } ?: return null
        _sent.value = _sent.value - match
        persistence?.saveSent(_sent.value)
        return match
    }

    @Synchronized
    fun wipe() {
        token = null
        set(emptyList())
        _sent.value = emptyList(); persistence?.saveSent(emptyList())
        persistence?.saveToken(ByteArray(0), 0)
    }

    private fun set(r: List<InviteRequest>) { _requests.value = r; persistence?.saveRequests(r) }

    companion object {
        const val LINK_VALID_MS = 7 * 24 * 60 * 60_000L
        /** A link handed out should work for at least a day, so an old token is replaced early. */
        const val MIN_LEFT_MS = 24 * 60 * 60_000L
        const val MAX_REQUESTS = 20
    }
}
