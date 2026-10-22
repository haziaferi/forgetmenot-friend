package com.hazia.forgetmenot.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hazia.forgetmenot.MainActivity
import com.hazia.forgetmenot.R
import com.hazia.forgetmenot.ui.theme.ThemeTokens

/** Posting a fired reminder: the channel, the small icon, the body tap and the buttons. */
object ReminderNotifications {
    /** The one channel every nudge posts on; recreating it on each post is a no-op. */
    const val CHANNEL_ID = "reminders"

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Check-ins, birthdays and dates" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun post(context: Context, r: Reminder) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        ensureChannel(context)
        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_forgetmenot)
            .setColor(ThemeTokens.DEFAULT.primary)
            .setContentTitle(r.title)
            .setContentText(r.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setWhen(r.atMs)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, r))
        for (a in r.actions) b.addAction(0, a.label, actionIntent(context, r, a))
        try {
            nm.notify(r.id, b.build())
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked since the check.
        }
    }

    fun dismiss(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    /** One friend's nudge opens their detail screen through the I3 deep link; the rest open home. */
    private fun openIntent(context: Context, r: Reminder): PendingIntent {
        val intent = r.openFriendId?.let { Intent(Intent.ACTION_VIEW, Uri.parse("forgetmenot://friend/$it")) }
            ?: Intent(Intent.ACTION_MAIN)
        intent.setClass(context, MainActivity::class.java)
        return PendingIntent.getActivity(context, r.id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** A broadcast to [ReminderReceiver]; the action string keeps it apart from the alarm's own intent. */
    private fun actionIntent(context: Context, r: Reminder, a: ReminderAction): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_PREFIX + a.name)
            .putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, r.id)
            .putExtra(ReminderReceiver.EXTRA_FRIEND_IDS, r.friendIds.toTypedArray())
        return PendingIntent.getBroadcast(context, r.id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
