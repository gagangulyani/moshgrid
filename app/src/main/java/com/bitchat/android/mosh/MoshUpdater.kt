package com.bitchat.android.mosh

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bitchat.android.BuildConfig
import com.bitchat.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A newer MoshGrid on the website. */
data class AvailableUpdate(val versionCode: Int, val versionName: String, val url: String, val sha256: String)

/** Reads the website's version.json. Fail-closed: anything odd is "no update". */
object UpdateManifest {
    private val SHA = Regex("^[0-9a-f]{64}$")

    fun parse(json: String, currentVersionCode: Int, abis: List<String>): AvailableUpdate? = runCatching {
        val o = JSONObject(json)
        val code = o.getInt("versionCode")
        if (code <= currentVersionCode) return null
        val apks = o.getJSONObject("apk")
        val which = if (abis.firstOrNull() == "arm64-v8a" && apks.has("arm64-v8a")) "arm64-v8a" else "universal"
        val apk = apks.getJSONObject(which)
        val url = apk.getString("url"); val sha = apk.getString("sha256").lowercase()
        // Only our own site, over https, with a real checksum.
        if (!url.startsWith("https://" + InviteLink.HOST + "/downloads/") || !SHA.matches(sha)) return null
        AvailableUpdate(code, o.getString("versionName").take(32), url, sha)
    }.getOrNull()
}

/**
 * Keeps MoshGrid up to date from moshgrid.vercel.app (the same place people download it).
 * Checks at most every [CHECK_EVERY_MS] while Internet is on. On Wi-Fi it downloads by itself;
 * on mobile data it waits for a tap. An APK is installed only if its SHA-256 matches version.json
 * and it is signed with the same key as this app. Android asks the person to confirm the first
 * update (and whenever it requires); on Android 12+ later ones can install without a tap.
 */
object MoshUpdater {
    const val CHECK_EVERY_MS = 6 * 60 * 60_000L
    private const val MANIFEST_URL = "https://" + "moshgrid.vercel.app" + "/version.json"
    private const val CHANNEL_ID = "mosh_updates"
    private const val NOTIFICATION_ID = 0x49001

    sealed interface State {
        object Idle : State
        data class Available(val update: AvailableUpdate) : State
        data class Downloading(val update: AvailableUpdate, val percent: Int) : State
        data class Installing(val update: AvailableUpdate) : State
        data class Failed(val update: AvailableUpdate, val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var lastCheckMs = 0L

    /** From app start and the minute ticker. Cheap when nothing is due. */
    fun maybeCheck(context: Context) {
        val app = context.applicationContext
        if (!MoshRuntime.isInitialised || !MoshRuntime.prefs.internet) return
        val now = System.currentTimeMillis()
        if (now - lastCheckMs < CHECK_EVERY_MS) return
        lastCheckMs = now
        scope.launch { runCatching { check(app) } }
    }

    private suspend fun check(context: Context) = lock.withLock {
        if (_state.value is State.Downloading || _state.value is State.Installing) return@withLock
        val json = get(MANIFEST_URL)?.toString(Charsets.UTF_8) ?: return@withLock
        val update = UpdateManifest.parse(json, BuildConfig.VERSION_CODE, Build.SUPPORTED_ABIS.toList()) ?: run {
            _state.value = State.Idle; return@withLock
        }
        _state.value = State.Available(update)
        // Installing restarts the app: never on its own while someone needs Help.
        val helpLive = MoshRuntime.help.activeAlertId != null || MoshRuntime.store.activeHelp.value.isNotEmpty()
        if (onWifi(context) && !helpLive) install(context, update)
    }

    /** "Update" tapped. */
    fun start(context: Context) {
        val s = _state.value
        val u = when (s) { is State.Available -> s.update; is State.Failed -> s.update; else -> return }
        scope.launch { lock.withLock { install(context.applicationContext, u) } }
    }

    private fun install(context: Context, u: AvailableUpdate) {
        val file = File(context.cacheDir, "update/MoshGrid-${u.versionCode}.apk")
        try {
            if (!file.exists() || sha256(file) != u.sha256) {
                file.parentFile?.mkdirs()
                _state.value = State.Downloading(u, 0)
                download(u.url, file) { _state.value = State.Downloading(u, it) }
            }
            if (sha256(file) != u.sha256) return fail(u, file, "The download was damaged. Try again.")
            if (!sameSigner(context, file)) return fail(u, file, "That file isn't signed by MoshGrid, so it wasn't installed.")
            _state.value = State.Installing(u)
            val pi = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = pi.createSession(params)
            pi.openSession(id).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; session.fsync(out) }
                val status = PendingIntent.getBroadcast(context, id, Intent(context, UpdateStatusReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(status.intentSender)
            }
        } catch (e: Exception) {
            fail(u, file, "Couldn't update: check your connection and try again.")
        }
    }

    internal fun onStatus(context: Context, intent: Intent) {
        val u = (_state.value as? State.Installing)?.update
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // In front: show Android's confirm screen now. Otherwise a notification opens it later.
                if (androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) runCatching { context.startActivity(confirm) }
                else notify(context, u, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> _state.value = State.Idle
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                MoshMonitor.event(context, "update_failed")
                if (u != null) _state.value = State.Failed(u, if (msg?.contains("abort", true) == true) "Update cancelled." else "Android didn't install the update.")
            }
        }
    }

    private fun fail(u: AvailableUpdate, file: File, reason: String) {
        file.delete()
        _state.value = State.Failed(u, reason)
    }

    private fun notify(context: Context, u: AvailableUpdate?, confirm: Intent) {
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm?.getNotificationChannel(CHANNEL_ID) == null) {
                nm?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_LOW))
            }
            val open = PendingIntent.getActivity(context, NOTIFICATION_ID, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("MoshGrid ${u?.versionName ?: "update"} is ready")
                .setContentText("Tap to install it.")
                .setContentIntent(open).setAutoCancel(true).build())
        }
    }

    private fun onWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun get(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 15_000; c.readTimeout = 15_000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "MoshGrid")
            if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }.takeIf { it.size < 64 * 1024 }
        } finally { c.disconnect() }
    }

    private fun download(url: String, to: File, progress: (Int) -> Unit) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "MoshGrid")
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            if (total > 200L * 1024 * 1024) throw java.io.IOException("too big")
            var done = 0L; var last = -1
            c.inputStream.use { input -> to.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    if (done > 200L * 1024 * 1024) throw java.io.IOException("too big")
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    if (pct != last) { last = pct; progress(pct) }
                }
            } }
        } finally { c.disconnect() }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** The downloaded APK is for this app and signed with exactly the key this app is signed with. */
    private fun sameSigner(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val theirs = pm.getPackageArchiveInfo(apk.path, flags) ?: return false
        if (theirs.packageName != context.packageName) return false
        val mine = pm.getPackageInfo(context.packageName, flags)
        fun certs(p: android.content.pm.PackageInfo): Set<String> {
            val sigs = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else @Suppress("DEPRECATION") p.signatures
            return sigs.orEmpty().map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
        }
        val a = certs(theirs); val b = certs(mine)
        return a.isNotEmpty() && a == b
    }
}

class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = MoshUpdater.onStatus(context.applicationContext, intent)
}
