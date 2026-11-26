package com.bitchat.android.mosh.monitor

import android.app.Application
import com.bitchat.android.BuildConfig
import com.bitchat.android.mosh.MoshPrefs
import com.bitchat.android.mosh.sentryAllowed
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.Message

/** Strips everything that could identify a person or place before a report leaves the phone. */
object Scrubber {
    private val EVENT_TEXT = Regex("^[a-z_]+( \\d+)?$")
    const val USER_REPORT = "user_report"

    fun scrub(event: SentryEvent): SentryEvent {
        event.user = null
        event.serverName = null
        event.request = null
        event.extras?.keys?.toList()?.forEach { event.removeExtra(it) }
        event.tags?.keys?.toList()?.forEach { event.removeTag(it) }
        event.breadcrumbs = event.breadcrumbs?.mapNotNull { breadcrumb(it) }
        // Exception messages can quote whatever the code was handling (a name, a message, an ID).
        // Keep the exception type and stack, which say where it broke, and drop the text.
        event.exceptions?.forEach { it.value = null }
        // Device and OS: only what helps fix a bug. No install ID, timezone, locale, boot time,
        // battery or storage figures, which together could single out a phone or hint where it is.
        event.contexts.device?.let { d ->
            event.contexts.setDevice(io.sentry.protocol.Device().apply {
                manufacturer = d.manufacturer; brand = d.brand; model = d.model; family = d.family
                archs = d.archs; isSimulator = d.isSimulator; memorySize = d.memorySize; isLowMemory = d.isLowMemory
            })
        }
        event.contexts.operatingSystem?.let { o ->
            event.contexts.setOperatingSystem(io.sentry.protocol.OperatingSystem().apply { name = o.name; version = o.version; isRooted = o.isRooted })
        }
        // Only our own fixed texts: an event name, or a problem report the person wrote and chose to send.
        val msg = event.message?.formatted
        if (msg != null && !EVENT_TEXT.matches(msg) && event.fingerprints?.firstOrNull() != USER_REPORT) event.message = null
        return event
    }

    fun breadcrumb(b: Breadcrumb): Breadcrumb? {
        val msg = b.message ?: return null
        if (!EVENT_TEXT.matches(msg)) return null
        b.data.keys.toList().forEach { b.removeData(it) }
        return b
    }
}

/**
 * Small cap on how many silent-failure reports one phone sends: a broken feature on a phone that's
 * offline all day must not turn into hundreds of reports (or eat data at a gig).
 */
class FailureLimiter(private val perNameMs: Long = 60 * 60_000L, private val maxPerHour: Int = 10) {
    private val lastByName = HashMap<String, Long>()
    private val sent = ArrayDeque<Long>()

    @Synchronized
    fun allow(name: String, nowMs: Long): Boolean {
        while (sent.isNotEmpty() && nowMs - sent.first() >= 60 * 60_000L) sent.removeFirst()
        val last = lastByName[name]
        if (last != null && nowMs - last < perNameMs) return false
        if (sent.size >= maxPerHour) return false
        lastByName[name] = nowMs; sent.addLast(nowMs)
        return true
    }
}

/**
 * Crash, silent-failure and problem reports to MoshGrid's own GlitchTip. Crash and failure reports
 * only while crash reports are on (the default, with a switch in setup and in You) and Internet is
 * on. A problem report the person writes and sends is the one exception: it goes once, on their tap.
 */
object Monitoring {
    @Volatile private var started = false
    @Volatile private var prefs: MoshPrefs? = null
    private val limiter = FailureLimiter()

    private fun allowed() = prefs?.let { sentryAllowed(it) } == true

    /**
     * Safe to call again. Starts the reporting library once when a DSN is built in; nothing is sent
     * unless [allowed] (or for a problem report, Internet is on). Sessions are off, so starting it
     * sends nothing by itself.
     */
    @Synchronized
    fun init(app: Application, prefs: MoshPrefs) {
        this.prefs = prefs
        if (started) return
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank()) return
        SentryAndroid.init(app) { o ->
            o.dsn = dsn
            o.isSendDefaultPii = false
            o.isAttachScreenshot = false
            o.isAttachViewHierarchy = false
            o.isEnableUserInteractionBreadcrumbs = false
            o.isEnableActivityLifecycleBreadcrumbs = false
            o.isEnableSystemEventBreadcrumbs = false
            o.isEnableNetworkEventBreadcrumbs = false
            o.isEnableAppComponentBreadcrumbs = false
            o.isAnrEnabled = true
            o.isEnableAutoSessionTracking = false
            o.isSendClientReports = false
            o.setBeforeSend { event, _ ->
                val report = event.fingerprints?.firstOrNull() == Scrubber.USER_REPORT
                if (allowed() || (report && prefs.internet && prefs.onboardingDone)) Scrubber.scrub(event) else null
            }
            o.setBeforeBreadcrumb { b, _ -> Scrubber.breadcrumb(b) }
        }
        started = true
    }

    fun breadcrumb(name: String, count: Int) {
        if (!started || !allowed()) return
        Sentry.addBreadcrumb(Breadcrumb().apply { category = "mosh"; message = if (count == 1) name else "$name $count" })
    }

    fun report(name: String, count: Int) {
        if (!started || !allowed()) return
        Sentry.captureMessage(if (count == 1) name else "$name $count")
    }

    /**
     * Something that should have worked didn't, and nothing crashed. One report per [name] per hour
     * at most. [e] adds the exception type and where it happened (its text is dropped by [Scrubber]).
     */
    fun failure(name: String, e: Throwable? = null) {
        if (!started || !allowed() || !limiter.allow(name, System.currentTimeMillis())) return
        val event = SentryEvent(e).apply {
            message = Message().apply { formatted = name }
            level = SentryLevel.WARNING
            fingerprints = listOf("failure", name)
        }
        Sentry.captureEvent(event)
    }

    /** "Report a problem": the person's own note plus recent event counts. False if it can't be sent. */
    fun userReport(note: String, counts: String): Boolean {
        val p = prefs ?: return false
        if (!started || !p.internet) return false
        val event = SentryEvent().apply {
            message = Message().apply { formatted = (note.take(1000) + "\n\n--- recent events ---\n" + counts).take(4000) }
            level = SentryLevel.INFO
            fingerprints = listOf(Scrubber.USER_REPORT)
        }
        Sentry.captureEvent(event)
        return true
    }
}
