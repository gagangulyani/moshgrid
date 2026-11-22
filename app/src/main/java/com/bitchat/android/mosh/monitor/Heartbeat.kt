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
