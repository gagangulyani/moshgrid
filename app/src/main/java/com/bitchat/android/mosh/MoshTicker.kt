package com.bitchat.android.mosh

import android.bluetooth.BluetoothManager
import android.content.Context
import com.bitchat.android.mosh.monitor.Heartbeat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Runs inside the mesh foreground service: location sharing, heartbeat, Help repeats, health checks. */
class MoshTicker(private val context: Context, private val scope: CoroutineScope) {
    private val sharer = LocationSharer(context)
    private var job: Job? = null

    fun start() {
        if (job != null) return
        if (!MoshRuntime.isInitialised) MoshRuntime.init(context)
        if (MoshRuntime.prefs.shareLocation) sharer.start()
        val heartbeat = MoshStartup.heartbeat(context)
        job = scope.launch {
            var minutes = 0
            var btWasOn = true
            while (isActive) {
                heartbeat.beat()
                MoshRuntime.help.tick()
                val btOn = bluetoothOn()
                if (btWasOn && !btOn) MoshMonitor.event(context, "bt_off")
                btWasOn = btOn
                if (minutes % 60 == 0) MoshMonitor.event(context, "battery_pct", sharerBattery())
                minutes++
                delay(Heartbeat.BEAT_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        sharer.stop()
    }

    private fun bluetoothOn(): Boolean =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true

    private fun sharerBattery(): Int = MoshRuntime.myLocation.value?.batteryPct ?: 0
}

data class StopNotice(val stoppedAtMs: Long, val unreachableMinutes: Long, val reason: String)

/** On app start: work out whether MoshGrid was stopped or frozen while the user thought it was on. */
object MoshStartup {
    fun heartbeat(context: Context) = Heartbeat(File(context.applicationContext.filesDir, "diagnostics/heartbeat"))

    fun onAppStart(context: Context) {
        val hb = heartbeat(context)
        val last = hb.lastBeatMs()
        val gap = hb.gapOnStartMs() ?: return
        val reason = lastExitReason(context)
        val minutes = gap / 60_000
        MoshRuntime.reportStop(StopNotice(last ?: 0L, minutes, reason))
        MoshMonitor.event(context, "app_stopped", minutes.toInt())
        if (MoshRuntime.isInitialised && sentryAllowed(MoshRuntime.prefs)) {
            com.bitchat.android.mosh.monitor.Monitoring.report("app_stopped", minutes.toInt())
        }
    }

    private fun lastExitReason(context: Context): String {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return "unknown"
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return "unknown"
        val info = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() }.getOrNull()
        return info?.let { com.bitchat.android.mosh.monitor.describeExit(it.reason) } ?: "unknown"
    }
}
