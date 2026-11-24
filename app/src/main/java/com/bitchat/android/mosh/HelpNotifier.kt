package com.bitchat.android.mosh

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bitchat.android.MainActivity
import com.bitchat.android.R

/** Loud notification when a friend raises Help. Breaks through Do Not Disturb only if the user allowed it. */
object HelpNotifier {
    const val CHANNEL_ID = "mosh_help"
    /** Same alarm channel, created after the user granted Do Not Disturb access, so it may bypass DND. */
    const val CHANNEL_ID_DND = "mosh_help_dnd"
    const val EXTRA_OPEN_FIND = "moshgrid_open_find"

    /**
     * Android fixes a channel's DND bypass when the channel is created, and honours it only if the app
     * has Do Not Disturb access at that moment. So once access is granted, Help moves to a second
     * channel created with access; the first channel is kept for users who never grant it.
     */
    fun ensureChannel(context: Context): String {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return CHANNEL_ID
        val id = if (nm.isNotificationPolicyAccessGranted) CHANNEL_ID_DND else CHANNEL_ID
        if (nm.getNotificationChannel(id) != null) return id
        val ch = NotificationChannel(id, "Help from friends", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A friend asked for help"
            setBypassDnd(true)
            enableVibration(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
        }
        nm.createNotificationChannel(ch)
        if (id == CHANNEL_ID_DND) runCatching { nm.deleteNotificationChannel(CHANNEL_ID) }
        return id
    }

    /** When each friend's current alert first rang and last rang, to re-ring a few times then go quiet. */
    private val rings = HashMap<String, Pair<String, LongArray>>()

    /**
     * Acts on what [FriendStateStore.apply] decided, never on the raw payload: a cancelled, late or
     * replayed alert does not ring, and a cancel for an older alert does not hide a newer one.
     */
    @Synchronized
    fun onChange(context: Context, fromPeerID: String, change: HelpChange, active: HelpAlert?) {
        val now = System.currentTimeMillis()
        when (change) {
            HelpChange.NONE -> Unit
            HelpChange.CANCELLED -> { rings.remove(fromPeerID); NotificationManagerCompat.from(context).cancel(notificationId(fromPeerID)) }
            HelpChange.NEW -> {
                val alert = active ?: return
                rings[fromPeerID] = alert.alertId to longArrayOf(now, now)
                show(context, fromPeerID, alert, ring = true)
            }
            HelpChange.UPDATED -> {
                val alert = active ?: return
                val r = rings[fromPeerID]?.takeIf { it.first == alert.alertId }
                    ?: (alert.alertId to longArrayOf(now, 0L)).also { rings[fromPeerID] = it }
                // Ring again with each repeat for the first minutes: one ring in a loud crowd is easy to miss.
                val ring = now - r.second[0] <= RE_RING_FOR_MS && now - r.second[1] >= RE_RING_EVERY_MS
                if (ring) r.second[1] = now
                show(context, fromPeerID, alert, ring)
            }
        }
    }

    private fun show(context: Context, fromPeerID: String, alert: HelpAlert, ring: Boolean) {
        val channel = ensureChannel(context)
        val name = MoshRuntime.friends.displayName(fromPeerID) ?: "A friend"
        val where = if (HelpRepeater.isLocationUnknown(alert)) "Location unknown." else "Tap to see where."
        val body = listOf(alert.note.takeIf { it.isNotBlank() }, where).filterNotNull().joinToString(" ")
        val open = PendingIntent.getActivity(
            context, notificationId(fromPeerID),
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_FIND, fromPeerID).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$name needs help")
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(open)
            .setAutoCancel(false)
            .setOnlyAlertOnce(!ring)
            // Lock screen: who, not the note (a stranger glancing at the phone sees no details).
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("$name needs help")
                    .setContentText("Open MoshGrid to see where.")
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .build()
            )
            .build()
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) {
            // Notifications denied: the in-app Help banner still shows, but record the miss.
            MoshMonitor.event(context, "help_notify_blocked")
            return
        }
        try {
            nm.notify(notificationId(fromPeerID), n)
        } catch (_: SecurityException) {
            MoshMonitor.event(context, "help_notify_blocked")
        }
    }

    /** Remove Help notifications for these friends (panic wipe, removed friend). */
    @Synchronized
    fun cancel(context: Context, peerIDs: Collection<String>) {
        peerIDs.forEach { rings.remove(it) }
        val nm = NotificationManagerCompat.from(context)
        peerIDs.forEach { nm.cancel(notificationId(it)) }
    }

    private fun notificationId(peerID: String) = ("help:" + peerID).hashCode()

    const val RE_RING_EVERY_MS = 110_000L
    const val RE_RING_FOR_MS = 10 * 60_000L
}
