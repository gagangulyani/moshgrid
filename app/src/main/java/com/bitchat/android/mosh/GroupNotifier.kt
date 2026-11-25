package com.bitchat.android.mosh

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bitchat.android.MainActivity
import com.bitchat.android.R

/** One notification per group for new messages, replaced as more arrive. Quieter than Help. */
object GroupNotifier {
    private const val CHANNEL_ID = "mosh_groups"
    const val EXTRA_OPEN_GROUP = "mosh_open_group"

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Group messages", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun id(groupId: String) = 0x47000 + (groupId.hashCode() and 0xFFF)

    fun show(context: Context, g: Group, from: String, text: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        runCatching {
            ensureChannel(context)
            val open = PendingIntent.getActivity(
                context, id(g.id),
                Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_GROUP, g.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val unread = g.unread
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(g.roster.name)
                .setContentText(if (unread > 1) "$from: $text ($unread new)" else "$from: $text")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("New group message").build())
                .build()
            NotificationManagerCompat.from(context).notify(id(g.id), n)
        }
    }

    fun cancel(context: Context, groupId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(id(groupId)) }
    }
}
