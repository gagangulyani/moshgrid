package com.bitchat.android.mosh.monitor

import android.app.ApplicationExitInfo
import java.io.File

/**
 * The mesh service writes a timestamp every minute. On the next start, a gap longer than
 * [GAP_THRESHOLD_MS] means the service was frozen or killed: the user was unreachable.
 */
class Heartbeat(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    fun beat() {
        file.parentFile?.mkdirs()
        file.writeText(clock().toString())
    }

    fun lastBeatMs(): Long? = runCatching { file.readText().trim().toLong() }.getOrNull()

    fun gapOnStartMs(): Long? {
        val last = lastBeatMs() ?: return null
        val gap = clock() - last
        return gap.takeIf { it > GAP_THRESHOLD_MS }
    }

    companion object {
        const val GAP_THRESHOLD_MS = 180_000L
        const val BEAT_INTERVAL_MS = 60_000L
    }
}

/** Plain words for why Android last stopped the app (ApplicationExitInfo, Android 11+). */
fun describeExit(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
    ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash"
    ApplicationExitInfo.REASON_ANR -> "app not responding"
    ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> "stopped by user"
    ApplicationExitInfo.REASON_OTHER, ApplicationExitInfo.REASON_SIGNALED,
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ApplicationExitInfo.REASON_FREEZER -> "killed by system"
    else -> "other"
}

/** Why MoshGrid was not running, as the app explains it. [ours] means it is our bug: offer to report it. */
enum class StopCause(val ours: Boolean) {
    PHONE_RESTARTED(false), UPDATED(false), BATTERY(false), FORCE_STOPPED(false), PERMISSION_CHANGED(false),
    CRASHED(true), FROZE(true), UNKNOWN(true),
}

/**
 * The cause of a gap in the heartbeat. A phone restart leaves no exit record for that gap, so it is
 * detected from the boot time: the phone booted after our last beat. [exitReason] is the latest
 * ApplicationExitInfo reason (Android 11+), null when Android gives none.
 */
fun stopCause(exitReason: Int?, lastBeatMs: Long?, bootedAtMs: Long): StopCause = when {
    lastBeatMs != null && bootedAtMs > lastBeatMs -> StopCause.PHONE_RESTARTED
    exitReason == null -> StopCause.UNKNOWN
    else -> when (exitReason) {
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> StopCause.CRASHED
        ApplicationExitInfo.REASON_ANR -> StopCause.FROZE
        ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_FREEZER, ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_OTHER -> StopCause.BATTERY
        ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> StopCause.FORCE_STOPPED
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> StopCause.PERMISSION_CHANGED
        ApplicationExitInfo.REASON_PACKAGE_UPDATED, ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> StopCause.UPDATED
        else -> StopCause.UNKNOWN
    }
}
