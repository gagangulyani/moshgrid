package com.bitchat.android.mosh

import java.util.UUID

/**
 * Help to friends: sends at once, then repeats every 2 minutes with the latest position until
 * cancelled. One alert at a time; the alert ID stays the same across repeats so receivers update
 * one pin instead of stacking alerts.
 *
 * Survives the app being killed ([persistence]): on restart the next tick resends straight away.
 * A cancel is repeated for [CANCEL_REPEAT_FOR_MS], because a cancel sent with no signal would
 * otherwise leave friends looking for someone who is fine.
 */
class HelpRepeater(
    private val clock: () -> Long,
    private val position: () -> LocationUpdate?,
    private val send: (MoshPayload) -> Map<String, MoshRoute>,
    private val persistence: HelpOutboxPersistence? = null,
    /**
     * Bluetooth-only send for the long tail of cancel repeats. A friend who got the Help over
     * Bluetooth and then drifted away may only be reachable again hours later; without the cancel
     * they would keep searching for someone who is fine. Null keeps the 15-minute window only.
     */
    private val lateResend: QuietResend? = null,
) {
    private var alertId: String? = null
    private var note: String = ""
    private var cancelledId: String? = null
    private var cancelledAtMs = 0L
    private var lastSentMs = 0L
    /** Set when state was restored after a restart: send on the next tick instead of waiting. */
    private var resumeDue = false

    /** Route per friend for the most recent send. Empty means there was nobody to send to. */
    @Volatile var lastRoutes: Map<String, MoshRoute> = emptyMap()
        private set
    @Volatile var lastSentAtMs: Long = 0L
        private set

    val activeAlertId: String? get() = alertId

    init {
        persistence?.load()?.let {
            alertId = it.alertId; note = it.note; cancelledId = it.cancelledId; cancelledAtMs = it.cancelledAtMs
            resumeDue = true
        }
    }

    @Synchronized
    fun raise(note: String): String {
        val id = alertId ?: UUID.randomUUID().toString().take(16)
        alertId = id
        cancelledId = null
        this.note = note.take(MoshCodec.MAX_NOTE_BYTES)
        save()
        sendNow()
        return id
    }

    @Synchronized
    fun tick() {
        val now = clock()
        // Until a send has gone out over Bluetooth to someone, retry on every tick: after a restart or
        // out of range, the mesh session may come back any moment and Help should not wait 2 minutes.
        val viaMesh = lastRoutes.values.any { it == MoshRoute.MESH || it == MoshRoute.MESH_AND_NOSTR }
        val interval = if (viaMesh) REPEAT_MS else FAST_REPEAT_MS
        val due = resumeDue || now - lastSentMs >= interval
        if (alertId != null) {
            if (due) sendNow()
            return
        }
        val cancelled = cancelledId ?: return
        val age = now - cancelledAtMs
        val quiet = lateResend
        val late = age > CANCEL_REPEAT_FOR_MS
        if ((late && quiet == null) || age > CANCEL_MESH_REPEAT_FOR_MS) {
            cancelledId = null; save(); return
        }
        if (late && quiet != null) {
            // After 15 minutes: Bluetooth every few minutes, the internet once, so no traffic piles up.
            if (resumeDue || now - lastSentMs >= LATE_CANCEL_REPEAT_MS) {
                resumeDue = false
                lastSentMs = now
                quiet.send(HelpCancel(cancelled, cancelledAtMs), "c|$cancelled")
            }
            return
        }
        if (due) {
            resumeDue = false
            lastSentMs = now
            lastRoutes = send(HelpCancel(cancelled, cancelledAtMs))
        }
    }

    @Synchronized
    fun cancel() {
        val id = alertId ?: return
        val now = clock()
        alertId = null
        cancelledId = id
        cancelledAtMs = now
        save()
        lastSentMs = now
        lastRoutes = send(HelpCancel(id, now))
    }

    /**
     * Panic wipe: one last cancel so friends stop looking (best effort, the identity is about to
     * go), then forget the alert, its cancel and the saved copy.
     */
    @Synchronized
    fun wipe() {
        alertId?.let { send(HelpCancel(it, clock())) }
        alertId = null; note = ""; cancelledId = null; cancelledAtMs = 0L
        resumeDue = false; lastRoutes = emptyMap(); lastSentAtMs = 0L
        persistence?.save(null)
    }

    private fun save() {
        persistence?.save(if (alertId == null && cancelledId == null) null else HelpOutbox(alertId, note, cancelledId, cancelledAtMs))
    }

    private fun sendNow() {
        val id = alertId ?: return
        val now = clock()
        val fix = position()
        val alert = if (fix != null) {
            HelpAlert(id, fix.lat, fix.lon, accuracyFor(fix, now), safeNote(), now)
        } else {
            HelpAlert(id, 0.0, 0.0, UNKNOWN_ACCURACY_M, safeNote(), now)
        }
        resumeDue = false
        lastSentMs = now
        lastSentAtMs = now
        lastRoutes = send(alert)
    }

    private fun safeNote(): String {
        var n = note
        while (n.toByteArray(Charsets.UTF_8).size > MoshCodec.MAX_NOTE_BYTES) n = n.dropLast(1)
        return n
    }

    companion object {
        const val REPEAT_MS = 120_000L
        const val FAST_REPEAT_MS = 60_000L
        const val CANCEL_REPEAT_FOR_MS = 15 * 60_000L
        /** Bluetooth-only cancel repeats continue this long after a cancel. */
        const val CANCEL_MESH_REPEAT_FOR_MS = 3 * 3_600_000L
        const val LATE_CANCEL_REPEAT_MS = 180_000L
        /** Accuracy used when the phone has no fix yet. Receivers show "location unknown". */
        const val UNKNOWN_ACCURACY_M = 1_000_000f
        /** How far someone may have walked since an old fix, for the uncertainty circle. */
        const val WALK_SPEED_MPS = 1.4f
        fun isLocationUnknown(h: HelpAlert) = h.accuracyM >= UNKNOWN_ACCURACY_M

        /** An old fix is still useful, but must not look exact: grow the circle by walking distance. */
        fun accuracyFor(fix: LocationUpdate, now: Long): Float {
            val ageS = ((now - fix.takenAtMs).coerceAtLeast(0)) / 1000f
            val grown = if (ageS > 60f) fix.accuracyM + ageS * WALK_SPEED_MPS else fix.accuracyM
            return grown.coerceAtMost(UNKNOWN_ACCURACY_M - 1f)
        }
    }
}
