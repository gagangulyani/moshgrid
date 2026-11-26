package com.bitchat.android.mosh

import android.content.Context
import com.bitchat.android.mosh.monitor.FieldLog
import com.bitchat.android.mosh.monitor.Monitoring
import java.io.File

/**
 * Facade for silent-failure events. Every event goes to the on-phone field log and, while crash
 * reports are on, a breadcrumb. Failures (names ending in _failed, _error, _blocked or _missing)
 * are also reported to GlitchTip on their own, at most once per name per hour.
 */
object MoshMonitor {
    @Volatile private var log: FieldLog? = null
    @Volatile private var app: Context? = null
    private val FAILURE = Regex("_(failed|error|blocked|missing)$")

    fun isFailure(name: String) = FAILURE.containsMatchIn(name)

    /** At app start, so code without a Context can record failures with [failed]. */
    fun init(context: Context) { app = context.applicationContext }

    fun fieldLog(context: Context): FieldLog =
        log ?: synchronized(this) { log ?: FieldLog(File(context.applicationContext.filesDir, "diagnostics")).also { log = it } }

    /** Panic wipe: delete the diagnostics folder (field log and heartbeat) and reports not sent yet. */
    fun wipe(context: Context) {
        synchronized(this) {
            File(context.applicationContext.filesDir, "diagnostics").deleteRecursively()
            // Reports waiting for signal (the reporting library's queue), including any problem report.
            File(context.applicationContext.cacheDir, "sentry").deleteRecursively()
            log = null
        }
    }

    fun event(context: Context, name: String, count: Int = 1, e: Throwable? = null) {
        fieldLog(context).event(name, count)
        Monitoring.breadcrumb(name, count)
        if (isFailure(name)) Monitoring.failure(name, e)
    }

    /** Something that should have worked didn't (a button that would otherwise do nothing, a load that failed). */
    fun failed(name: String, e: Throwable? = null) {
        val c = app ?: return
        runCatching { event(c, name, 1, e) }
    }

    /** Event names and counts from the last [hours], newest last, for "Report a problem". */
    fun recent(context: Context, hours: Int = 24, nowMs: Long = System.currentTimeMillis()): String {
        val f = fieldLog(context).file
        if (!f.exists()) return "(none)"
        val since = nowMs - hours * 60 * 60_000L
        val counts = sortedMapOf<String, Int>()
        f.readLines().forEach { line ->
            val p = line.split(' ')
            if (p.size == 3 && (p[0].toLongOrNull() ?: 0) >= since) counts[p[1]] = (counts[p[1]] ?: 0) + (p[2].toIntOrNull() ?: 0)
        }
        return if (counts.isEmpty()) "(none)" else counts.entries.joinToString("\n") { "${it.key} ${it.value}" }
    }
}
