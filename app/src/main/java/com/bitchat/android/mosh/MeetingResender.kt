package com.bitchat.android.mosh

/**
 * Keeps the group's meeting point moving. A meeting point is set once, but a friend out of range
 * with no signal at that moment would never get it. Every phone that knows the latest record
 * (set or cleared) re-sends it every [RESEND_MS] for [RESEND_FOR_MS] after it was set, so it spreads
 * through the group as people meet. Receivers keep the latest by time, so repeats and gossip are
 * harmless. Re-sends go through [QuietResend]: Bluetooth, and the internet once per record.
 */
class MeetingResender(
    private val clock: () -> Long,
    private val latest: () -> MeetingPoint?,
    private val resend: QuietResend,
) {
    private var lastSentMs = 0L
    private var lastSentId: Pair<String, Long>? = null

    @Synchronized
    fun tick() {
        val record = latest() ?: return
        val now = clock()
        if (now - record.setAtMs > RESEND_FOR_MS) return
        val id = record.pointId to record.setAtMs
        if (id == lastSentId && now - lastSentMs < RESEND_MS) return
        lastSentId = id; lastSentMs = now
        resend.send(record, "m|${record.pointId}|${record.setAtMs}")
    }

    companion object {
        const val RESEND_MS = 180_000L
        const val RESEND_FOR_MS = 3 * 3_600_000L
    }
}
