package com.bitchat.android.mosh

import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** MoshGrid payloads. They travel inside the Noise envelope, so relays only see ciphertext. */
sealed interface MoshPayload

data class LocationUpdate(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val takenAtMs: Long,
    val batteryPct: Int,
) : MoshPayload

data class MeetingPoint(
    val pointId: String,
    val lat: Double,
    val lon: Double,
    val label: String,
    val setAtMs: Long,
    val setBy: String,
    val cleared: Boolean,
) : MoshPayload

data class HelpAlert(
    val alertId: String,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val note: String,
    val raisedAtMs: Long,
) : MoshPayload

data class HelpCancel(val alertId: String, val cancelledAtMs: Long) : MoshPayload

/** One person in a group: their Noise static key, their Nostr key (empty if unknown) and the name the creator knows them by. */
data class GroupMember(val noiseKeyHex: String, val nostrPubHex: String, val name: String)

/** Who is in a group. Only the creator sends it; the newest [updatedAtMs] wins. The creator is always a member. */
data class GroupRoster(
    val groupId: String,
    val name: String,
    val creatorKeyHex: String,
    val updatedAtMs: Long,
    val members: List<GroupMember>,
) : MoshPayload

/** A group message. [kind] [GroupMessage.TEXT] carries [text]; [GroupMessage.LEFT] says the sender left. */
data class GroupMessage(
    val groupId: String,
    val messageId: String,
    val kind: Int,
    val text: String,
    val sentAtMs: Long,
) : MoshPayload {
    companion object { const val TEXT = 0; const val LEFT = 1 }
}

/** Tells the sender of [messageId] that it arrived ([read] false) or was seen ([read] true). */
data class GroupReceipt(val groupId: String, val messageId: String, val read: Boolean, val atMs: Long) : MoshPayload

/**
 * My profile photo, for friends only: a small square JPEG ([MoshCodec.MAX_PHOTO_BYTES] at most).
 * Empty [jpeg] means I removed it. The newest [updatedAtMs] from a friend wins.
 */
class ProfilePhoto(val updatedAtMs: Long, val jpeg: ByteArray) : MoshPayload {
    override fun equals(other: Any?) = other is ProfilePhoto && other.updatedAtMs == updatedAtMs && other.jpeg.contentEquals(jpeg)
    override fun hashCode() = 31 * updatedAtMs.hashCode() + jpeg.contentHashCode()
    override fun toString() = "ProfilePhoto(updatedAtMs=$updatedAtMs, ${jpeg.size} bytes)"
}

/**
 * Adding a friend by invite link, over Nostr only. [REQUEST]: someone with my link asks to be added
 * (their keys and name, my link's token). [ACCEPT]: the link's owner said yes (their keys and name, same token).
 */
class FriendInvite(
    val kind: Int,
    val token: ByteArray,
    val noiseKeyHex: String,
    val nostrPubHex: String,
    val name: String,
    val atMs: Long,
) : MoshPayload {
    override fun equals(other: Any?) = other is FriendInvite && other.kind == kind && other.token.contentEquals(token) &&
        other.noiseKeyHex == noiseKeyHex && other.nostrPubHex == nostrPubHex && other.name == name && other.atMs == atMs
    override fun hashCode() = token.contentHashCode() * 31 + noiseKeyHex.hashCode()
    override fun toString() = "FriendInvite(kind=$kind, name=$name)"
    companion object { const val REQUEST = 0; const val ACCEPT = 1; const val TOKEN_BYTES = 16 }
}

/**
 * Wire format: version byte (1), then fixed fields in order. Doubles and longs big-endian,
 * strings as u8 length + UTF-8. Decoding is fail-closed: anything malformed or out of range is null.
 */
object MoshCodec {
    private const val VERSION: Int = 1
    const val MAX_ID_BYTES = 64
    const val MAX_LABEL_BYTES = 64
    const val MAX_NOTE_BYTES = 140
    const val MAX_GROUP_TEXT_BYTES = 500
    const val MAX_GROUP_MEMBERS = 12
    const val MAX_PHOTO_BYTES = 12 * 1024

    fun encode(p: MoshPayload): NoisePayload {
        require(isValid(p)) { "invalid MoshGrid payload: $p" }
        return encodeUnchecked(p)
    }

    /** Encodes without validation. Tests use it to feed the decoder bad values. */
    internal fun encodeUnchecked(p: MoshPayload): NoisePayload {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeByte(VERSION)
        val type = when (p) {
            is LocationUpdate -> {
                out.writeDouble(p.lat); out.writeDouble(p.lon); out.writeFloat(p.accuracyM)
                out.writeLong(p.takenAtMs); out.writeByte(p.batteryPct)
                NoisePayloadType.LOCATION
            }
            is MeetingPoint -> {
                out.str(p.pointId); out.writeDouble(p.lat); out.writeDouble(p.lon); out.str(p.label)
                out.writeLong(p.setAtMs); out.str(p.setBy); out.writeBoolean(p.cleared)
                NoisePayloadType.MEETING_POINT
            }
            is HelpAlert -> {
                out.str(p.alertId); out.writeDouble(p.lat); out.writeDouble(p.lon); out.writeFloat(p.accuracyM)
                out.str(p.note); out.writeLong(p.raisedAtMs)
                NoisePayloadType.HELP
            }
            is HelpCancel -> {
                out.str(p.alertId); out.writeLong(p.cancelledAtMs)
                NoisePayloadType.HELP_CANCEL
            }
            is GroupRoster -> {
                out.str(p.groupId); out.str(p.name); out.key(p.creatorKeyHex); out.writeLong(p.updatedAtMs)
                out.writeByte(p.members.size)
                p.members.forEach { m -> out.key(m.noiseKeyHex); out.optKey(m.nostrPubHex); out.str(m.name) }
                NoisePayloadType.GROUP_ROSTER
            }
            is GroupMessage -> {
                out.str(p.groupId); out.str(p.messageId); out.writeByte(p.kind)
                val b = p.text.toByteArray(Charsets.UTF_8); out.writeShort(b.size); out.write(b)
                out.writeLong(p.sentAtMs)
                NoisePayloadType.GROUP_MESSAGE
            }
            is GroupReceipt -> {
                out.str(p.groupId); out.str(p.messageId); out.writeBoolean(p.read); out.writeLong(p.atMs)
                NoisePayloadType.GROUP_RECEIPT
            }
            is FriendInvite -> {
                out.writeByte(p.kind); out.write(p.token); out.key(p.noiseKeyHex); out.key(p.nostrPubHex); out.str(p.name)
                out.writeLong(p.atMs)
                NoisePayloadType.FRIEND_INVITE
            }
            is ProfilePhoto -> {
                out.writeLong(p.updatedAtMs); out.writeShort(p.jpeg.size); out.write(p.jpeg)
                NoisePayloadType.PROFILE_PHOTO
            }
        }
        out.flush()
        return NoisePayload(type, bytes.toByteArray())
    }

    fun decode(payload: NoisePayload): MoshPayload? = try {
        val input = DataInputStream(ByteArrayInputStream(payload.data))
        if (input.readUnsignedByte() != VERSION) {
            null
        } else {
            val p: MoshPayload? = when (payload.type) {
                NoisePayloadType.LOCATION -> LocationUpdate(
                    input.readDouble(), input.readDouble(), input.readFloat(), input.readLong(), input.readUnsignedByte()
                )
                NoisePayloadType.MEETING_POINT -> MeetingPoint(
                    input.str(), input.readDouble(), input.readDouble(), input.str(), input.readLong(), input.str(), input.readBoolean()
                )
                NoisePayloadType.HELP -> HelpAlert(
                    input.str(), input.readDouble(), input.readDouble(), input.readFloat(), input.str(), input.readLong()
                )
                NoisePayloadType.HELP_CANCEL -> HelpCancel(input.str(), input.readLong())
                NoisePayloadType.GROUP_ROSTER -> {
                    val id = input.str(); val name = input.str(); val creator = input.key(); val at = input.readLong()
                    val n = input.readUnsignedByte()
                    if (n > MAX_GROUP_MEMBERS) null
                    else GroupRoster(id, name, creator, at, List(n) { GroupMember(input.key(), input.optKey(), input.str()) })
                }
                NoisePayloadType.GROUP_MESSAGE -> {
                    val id = input.str(); val msg = input.str(); val kind = input.readUnsignedByte()
                    val len = input.readUnsignedShort()
                    if (len > MAX_GROUP_TEXT_BYTES) null
                    else { val b = ByteArray(len); input.readFully(b); GroupMessage(id, msg, kind, String(b, Charsets.UTF_8), input.readLong()) }
                }
                NoisePayloadType.GROUP_RECEIPT -> GroupReceipt(input.str(), input.str(), input.readBoolean(), input.readLong())
                NoisePayloadType.FRIEND_INVITE -> {
                    val kind = input.readUnsignedByte(); val t = ByteArray(FriendInvite.TOKEN_BYTES); input.readFully(t)
                    FriendInvite(kind, t, input.key(), input.key(), input.str(), input.readLong())
                }
                NoisePayloadType.PROFILE_PHOTO -> {
                    val at = input.readLong(); val len = input.readUnsignedShort()
                    if (len > MAX_PHOTO_BYTES) null else { val b = ByteArray(len); input.readFully(b); ProfilePhoto(at, b) }
                }
                else -> null
            }
            p?.takeIf { input.available() == 0 && isValid(it) }
        }
    } catch (_: IOException) {
        null
    }

    fun isMoshType(type: NoisePayloadType): Boolean = type == NoisePayloadType.LOCATION ||
        type == NoisePayloadType.MEETING_POINT || type == NoisePayloadType.HELP || type == NoisePayloadType.HELP_CANCEL ||
        type == NoisePayloadType.PROFILE_PHOTO || type == NoisePayloadType.FRIEND_INVITE || isGroupType(type)

    fun isGroupType(type: NoisePayloadType): Boolean = type == NoisePayloadType.GROUP_ROSTER ||
        type == NoisePayloadType.GROUP_MESSAGE || type == NoisePayloadType.GROUP_RECEIPT

    private fun validCoord(lat: Double, lon: Double) = lat in -90.0..90.0 && lon in -180.0..180.0
    private fun fits(s: String, max: Int) = s.toByteArray(Charsets.UTF_8).size <= max
    private fun validAccuracy(a: Float) = a.isFinite() && a >= 0f
    private fun validId(s: String) = s.isNotEmpty() && fits(s, MAX_ID_BYTES)
    private val HEX_KEY = Regex("^[0-9a-f]{64}$")

    private fun isValid(p: MoshPayload): Boolean = when (p) {
        is LocationUpdate -> validCoord(p.lat, p.lon) && validAccuracy(p.accuracyM) && p.batteryPct in 0..100
        is MeetingPoint -> validCoord(p.lat, p.lon) && fits(p.pointId, MAX_ID_BYTES) && p.pointId.isNotEmpty() &&
            fits(p.label, MAX_LABEL_BYTES) && fits(p.setBy, MAX_ID_BYTES)
        is HelpAlert -> validCoord(p.lat, p.lon) && validAccuracy(p.accuracyM) && fits(p.alertId, MAX_ID_BYTES) &&
            p.alertId.isNotEmpty() && fits(p.note, MAX_NOTE_BYTES)
        is HelpCancel -> fits(p.alertId, MAX_ID_BYTES) && p.alertId.isNotEmpty()
        is GroupRoster -> validId(p.groupId) && p.name.isNotBlank() && fits(p.name, MAX_LABEL_BYTES) &&
            HEX_KEY.matches(p.creatorKeyHex) && p.members.size in 2..MAX_GROUP_MEMBERS &&
            p.members.all { HEX_KEY.matches(it.noiseKeyHex) && (it.nostrPubHex.isEmpty() || HEX_KEY.matches(it.nostrPubHex)) && fits(it.name, MAX_LABEL_BYTES) } &&
            p.members.map { it.noiseKeyHex }.toSet().size == p.members.size &&
            p.members.any { it.noiseKeyHex == p.creatorKeyHex }
        is GroupMessage -> validId(p.groupId) && validId(p.messageId) && fits(p.text, MAX_GROUP_TEXT_BYTES) &&
            when (p.kind) { GroupMessage.TEXT -> p.text.isNotBlank(); GroupMessage.LEFT -> p.text.isEmpty(); else -> false }
        is GroupReceipt -> validId(p.groupId) && validId(p.messageId)
        is FriendInvite -> (p.kind == FriendInvite.REQUEST || p.kind == FriendInvite.ACCEPT) &&
            p.token.size == FriendInvite.TOKEN_BYTES && HEX_KEY.matches(p.noiseKeyHex) && HEX_KEY.matches(p.nostrPubHex) &&
            p.name.isNotBlank() && fits(p.name, MAX_LABEL_BYTES) && p.atMs > 0
        // A JPEG starts FF D8 FF. Whether it really decodes to a small square is checked before it is kept.
        is ProfilePhoto -> p.updatedAtMs > 0 && p.jpeg.size <= MAX_PHOTO_BYTES && (p.jpeg.isEmpty() ||
            (p.jpeg.size >= 3 && p.jpeg[0] == 0xFF.toByte() && p.jpeg[1] == 0xD8.toByte() && p.jpeg[2] == 0xFF.toByte()))
    }

    /** A 32-byte key, written raw. Hex in memory is lowercase. */
    private fun DataOutputStream.key(hex: String) { write(hexBytes(hex)) }
    private fun DataInputStream.key(): String { val b = ByteArray(32); readFully(b); return b.joinToString("") { "%02x".format(it) } }
    /** Optional key: one byte 0 (absent) or 32, then the key. */
    private fun DataOutputStream.optKey(hex: String) { if (hex.isEmpty()) writeByte(0) else { writeByte(32); key(hex) } }
    private fun DataInputStream.optKey(): String = when (readUnsignedByte()) { 0 -> ""; 32 -> key(); else -> throw IOException("bad key length") }
    private fun hexBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun DataOutputStream.str(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        writeByte(b.size)
        write(b)
    }

    private fun DataInputStream.str(): String {
        val n = readUnsignedByte()
        val b = ByteArray(n)
        readFully(b)
        return String(b, Charsets.UTF_8)
    }
}

/**
 * Entry point for received MoshGrid payloads from mesh or Nostr.
 * Drops anything not from a QR-verified friend before decoding.
 */
object MoshInbox {
    @Volatile var friends: FriendRegistry? = null
    @Volatile var sink: ((fromPeerID: String, payload: MoshPayload) -> Unit)? = null
    /** True when [fromPeerID] is in one of my groups. Lets group payloads from non-friends through; the group store checks which group. */
    @Volatile var groupMember: ((fromPeerID: String) -> Boolean)? = null

    fun receive(fromPeerID: String, payload: NoisePayload, friends: FriendRegistry? = this.friends,
                sink: ((String, MoshPayload) -> Unit)? = this.sink): Boolean {
        val friend = friends != null && friends.isFriend(fromPeerID)
        val member = MoshCodec.isGroupType(payload.type) && groupMember?.invoke(fromPeerID) == true
        if (!friend && !member) return false
        val decoded = MoshCodec.decode(payload) ?: return false
        sink?.invoke(fromPeerID, decoded)
        return true
    }
}
