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

/**
 * Wire format: version byte (1), then fixed fields in order. Doubles and longs big-endian,
 * strings as u8 length + UTF-8. Decoding is fail-closed: anything malformed or out of range is null.
 */
object MoshCodec {
    private const val VERSION: Int = 1
    const val MAX_ID_BYTES = 64
    const val MAX_LABEL_BYTES = 64
    const val MAX_NOTE_BYTES = 140

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
                else -> null
            }
            p?.takeIf { input.available() == 0 && isValid(it) }
        }
    } catch (_: IOException) {
        null
    }

    fun isMoshType(type: NoisePayloadType): Boolean = type == NoisePayloadType.LOCATION ||
        type == NoisePayloadType.MEETING_POINT || type == NoisePayloadType.HELP || type == NoisePayloadType.HELP_CANCEL

    private fun validCoord(lat: Double, lon: Double) = lat in -90.0..90.0 && lon in -180.0..180.0
    private fun fits(s: String, max: Int) = s.toByteArray(Charsets.UTF_8).size <= max
    private fun validAccuracy(a: Float) = a.isFinite() && a >= 0f

    private fun isValid(p: MoshPayload): Boolean = when (p) {
        is LocationUpdate -> validCoord(p.lat, p.lon) && validAccuracy(p.accuracyM) && p.batteryPct in 0..100
        is MeetingPoint -> validCoord(p.lat, p.lon) && fits(p.pointId, MAX_ID_BYTES) && p.pointId.isNotEmpty() &&
            fits(p.label, MAX_LABEL_BYTES) && fits(p.setBy, MAX_ID_BYTES)
        is HelpAlert -> validCoord(p.lat, p.lon) && validAccuracy(p.accuracyM) && fits(p.alertId, MAX_ID_BYTES) &&
            p.alertId.isNotEmpty() && fits(p.note, MAX_NOTE_BYTES)
        is HelpCancel -> fits(p.alertId, MAX_ID_BYTES) && p.alertId.isNotEmpty()
    }

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

    fun receive(fromPeerID: String, payload: NoisePayload, friends: FriendRegistry? = this.friends,
                sink: ((String, MoshPayload) -> Unit)? = this.sink): Boolean {
        if (friends == null || !friends.isFriend(fromPeerID)) return false
        val decoded = MoshCodec.decode(payload) ?: return false
        sink?.invoke(fromPeerID, decoded)
        return true
    }
}
