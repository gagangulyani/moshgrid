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
    const val EXTRA_OPEN_FIND = "moshgrid_open_find"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "Help from friends", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A friend asked for help"
            setBypassDnd(true)
            enableVibration(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
        }
        nm.createNotificationChannel(ch)
    }

    fun onReceived(context: Context, fromPeerID: String, p: MoshPayload) {
        when (p) {
            is HelpAlert -> show(context, fromPeerID, p)
            is HelpCancel -> NotificationManagerCompat.from(context).cancel(notificationId(fromPeerID))
            else -> Unit
        }
    }

    private fun show(context: Context, fromPeerID: String, alert: HelpAlert) {
        ensureChannel(context)
        val name = MoshRuntime.friends.displayName(fromPeerID) ?: "A friend"
        val where = if (HelpRepeater.isLocationUnknown(alert)) "Location unknown." else "Tap to see where."
        val body = listOf(alert.note.takeIf { it.isNotBlank() }, where).filterNotNull().joinToString(" ")
        val open = PendingIntent.getActivity(
            context, notificationId(fromPeerID),
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_FIND, fromPeerID).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$name needs help")
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(open)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
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

    private fun notificationId(peerID: String) = 0x4E1F0000 or (peerID.hashCode() and 0xFFFF)
}
