package com.hazia.forgetmenot.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.hazia.forgetmenot.AppContainer
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app's own replacement for the plugin's TimedNotificationPublisher and action handling:
 * [ACTION_FIRE] posts a reminder when its alarm goes off; [ACTION_HEARTBEAT] is the nightly
 * rebuild; [ACTION_PREFIX] + a [ReminderAction] name is a button press on a posted nudge. Not
 * exported: only our own PendingIntents reach it.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == ACTION_FIRE) {
            val json = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER) ?: return
            val r = try {
                ReminderScheduler.PlanJson.decodeFromString(Reminder.serializer(), json)
            } catch (e: Exception) {
                Log.w(TAG, "unreadable reminder", e)
                return
            }
            ReminderNotifications.post(context, r)
            ReminderScheduler.markDelivered(context, r)
            return
        }
        if (action == ACTION_HEARTBEAT) {
            // The nightly rebuild, from the folder, gated on I6 like any other.
            val container = AppContainer.from(context.applicationContext)
            val pending = goAsync()
            container.appScope.launch {
                try {
                    withTimeoutOrNull(TIMEOUT_MS) {
                        container.started.await()
                        container.records.rescheduleReminders()
                        container.reminders.awaitQueued()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "nightly reschedule failed", e)
                } finally {
                    pending.finish()
                }
            }
            return
        }
        val button = action.removePrefix(ACTION_PREFIX).takeIf { action.startsWith(ACTION_PREFIX) }
            ?.let { name -> ReminderAction.entries.firstOrNull { it.name == name } } ?: return
        val ids = intent.getStringArrayExtra(EXTRA_FRIEND_IDS)?.toList().orEmpty()
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val app = context.applicationContext
        val container = AppContainer.from(app)
        val pending = goAsync()
        // appScope runs on the main thread, where Records is used; the edit waits for the
        // cold-start load when the app was not running.
        container.appScope.launch {
            try {
                // The edit reschedules; that pass is awaited too, before the receiver finishes.
                val out = withTimeoutOrNull(TIMEOUT_MS) {
                    ReminderActions.perform(container.started, container.records, button, ids)
                        .also { container.reminders.awaitQueued() }
                }
                when (out) {
                    is ActionOutcome.Logged -> done(app, notificationId, "Logged — nice.")
                    is ActionOutcome.Snoozed -> done(app, notificationId,
                        if (out.names.size == 1) "Next nudge for ${out.names[0]} in a week" else "Next nudges in a week")
                    ActionOutcome.NothingToDo -> ReminderNotifications.dismiss(app, notificationId)
                    // The nudge stays, so the press can be retried once the folder is back.
                    ActionOutcome.ReadOnly -> toast(app, "Couldn’t save — open forgetmenot to check the sync folder")
                    null -> toast(app, "Couldn’t save — try again from the app")
                }
            } catch (e: Exception) {
                Log.e(TAG, "notification action failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun done(context: Context, notificationId: Int, message: String) {
        ReminderNotifications.dismiss(context, notificationId)
        toast(context, message)
    }

    private fun toast(context: Context, message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "forgetmenot"
        /** A broadcast receiver has about a minute in the background; leave room to finish. */
        private const val TIMEOUT_MS = 45_000L
        const val ACTION_FIRE = "com.hazia.forgetmenot.reminders.FIRE"
        const val ACTION_HEARTBEAT = "com.hazia.forgetmenot.reminders.HEARTBEAT"
        const val ACTION_PREFIX = "com.hazia.forgetmenot.reminders.ACTION_"
        const val EXTRA_NOTIFICATION_ID = "notificationId"
        const val EXTRA_FRIEND_IDS = "friendIds"
    }
}
