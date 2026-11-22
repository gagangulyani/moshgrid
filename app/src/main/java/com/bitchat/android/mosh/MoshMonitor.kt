package com.bitchat.android.mosh

import android.content.Context
import com.bitchat.android.mosh.monitor.FieldLog
import java.io.File

/** Facade for silent-failure events: field log always, Sentry breadcrumb when allowed. */
object MoshMonitor {
    @Volatile private var log: FieldLog? = null

    fun fieldLog(context: Context): FieldLog =
        log ?: synchronized(this) { log ?: FieldLog(File(context.applicationContext.filesDir, "diagnostics")).also { log = it } }

    fun event(context: Context, name: String, count: Int = 1) {
        fieldLog(context).event(name, count)
        com.bitchat.android.mosh.monitor.Monitoring.breadcrumb(name, count)
    }
}
