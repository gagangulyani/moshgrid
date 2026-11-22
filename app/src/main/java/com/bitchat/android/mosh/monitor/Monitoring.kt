package com.bitchat.android.mosh.monitor

import android.app.Application
import com.bitchat.android.BuildConfig
import com.bitchat.android.mosh.MoshPrefs
import com.bitchat.android.mosh.sentryAllowed
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.android.core.SentryAndroid

/** Strips everything that could identify a person or place before a report leaves the phone. */
object Scrubber {
    private val EVENT_TEXT = Regex("^[a-z_]+( \\d+)?$")

    fun scrub(event: SentryEvent): SentryEvent {
        event.user = null
        event.serverName = null
        event.request = null
        event.extras?.keys?.toList()?.forEach { event.removeExtra(it) }
        event.tags?.keys?.toList()?.forEach { event.removeTag(it) }
        event.breadcrumbs = event.breadcrumbs?.mapNotNull { breadcrumb(it) }
        return event
    }

    fun breadcrumb(b: Breadcrumb): Breadcrumb? {
        val msg = b.message ?: return null
        if (!EVENT_TEXT.matches(msg)) return null
        b.data.keys.toList().forEach { b.removeData(it) }
        return b
    }
}

/** Opt-in crash and silent-failure reporting. Off when the DSN is empty, opt-in is off, or offline-only. */
object Monitoring {
    @Volatile private var enabled = false

    fun init(app: Application, prefs: MoshPrefs) {
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank() || !sentryAllowed(prefs)) return
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
            o.setBeforeSend { event, _ -> if (sentryAllowed(prefs)) Scrubber.scrub(event) else null }
            o.setBeforeBreadcrumb { b, _ -> Scrubber.breadcrumb(b) }
        }
        enabled = true
    }

    fun breadcrumb(name: String, count: Int) {
        if (!enabled) return
        Sentry.addBreadcrumb(Breadcrumb().apply { category = "mosh"; message = if (count == 1) name else "$name $count" })
    }

    fun report(name: String, count: Int) {
        if (!enabled) return
        Sentry.captureMessage(if (count == 1) name else "$name $count")
    }
}
