package com.bitchat.android.mosh

import java.util.UUID

/**
 * Help to friends: sends at once, then repeats every 2 minutes with the latest position until
 * cancelled. One alert at a time; the alert ID stays the same across repeats so receivers update
 * one pin instead of stacking alerts.
 */
class HelpRepeater(
    private val clock: () -> Long,
    private val position: () -> Triple<Double, Double, Float>?,
    private val send: (MoshPayload) -> Unit,
) {
    private var alertId: String? = null
    private var note: String = ""
    private var lastSentMs = 0L

    val activeAlertId: String? get() = alertId

    @Synchronized
    fun raise(note: String): String {
        val id = alertId ?: UUID.randomUUID().toString().take(16)
        alertId = id
        this.note = note.take(MoshCodec.MAX_NOTE_BYTES)
        sendNow()
        return id
    }

    @Synchronized
    fun tick() {
        if (alertId != null && clock() - lastSentMs >= REPEAT_MS) sendNow()
    }

    @Synchronized
    fun cancel() {
        val id = alertId ?: return
        alertId = null
        send(HelpCancel(id, clock()))
    }

    private fun sendNow() {
        val id = alertId ?: return
        val now = clock()
        val pos = position()
        val alert = if (pos != null) {
            HelpAlert(id, pos.first, pos.second, pos.third, safeNote(), now)
        } else {
            HelpAlert(id, 0.0, 0.0, UNKNOWN_ACCURACY_M, safeNote(), now)
        }
        lastSentMs = now
        send(alert)
    }

    private fun safeNote(): String {
        var n = note
        while (n.toByteArray(Charsets.UTF_8).size > MoshCodec.MAX_NOTE_BYTES) n = n.dropLast(1)
        return n
    }

    companion object {
        const val REPEAT_MS = 120_000L
        /** Accuracy used when the phone has no fix yet. Receivers show "location unknown". */
        const val UNKNOWN_ACCURACY_M = 1_000_000f
        fun isLocationUnknown(h: HelpAlert) = h.accuracyM >= UNKNOWN_ACCURACY_M
    }
}
