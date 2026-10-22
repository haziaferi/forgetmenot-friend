package com.hazia.forgetmenot.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.hazia.forgetmenot.data.DataEffects
import com.hazia.forgetmenot.data.Records
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.ZonedDateTime
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * scheduleNativeReminders() on AlarmManager (SPEC §4): a full teardown and rebuild on every call,
 * never a diff. The plan is built from [records] on the caller's thread (the main thread, like
 * every Records read); the AlarmManager calls run in order on [executor].
 *
 * AlarmManager cannot list an app's alarms, so the last plan is kept in prefs: it names what the
 * next pass cancels and what [rearmFromSavedPlan] restores after a reboot.
 */
class ReminderScheduler(
    private val context: Context,
    private val records: () -> Records,
    private val executor: Executor = Executors.newSingleThreadExecutor(),
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
) : DataEffects {
    private val _failures = MutableSharedFlow<Throwable>(extraBufferCapacity = 1)

    /**
     * A pass that threw. The UI (P5) shows "Reminders couldn't be scheduled — check notification
     * permission in Settings"; the failure is never silent.
     */
    val failures: SharedFlow<Throwable> = _failures.asSharedFlow()

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val alarmManager get() = context.getSystemService(AlarmManager::class.java)

    override fun pushWidgets() {}

    override fun rescheduleReminders() {
        val r = records()
        // Every pass cancels everything first; from a partial cache that would drop the reminders
        // of every friend that did not load. A read-only session leaves the schedule as it was (I6).
        if (r.store.writesBlocked) return
        // As the JS: without the notification grant the pass returns before cancelling anything.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val plan = try {
            planReminders(r.getSettings(), r.listFriends(), r.listGroups(), clock())
        } catch (e: Exception) {
            return fail(e)
        }
        executor.execute { apply(plan) }
    }

    /**
     * Returns once every pass queued so far has armed its alarms and saved its plan. A receiver
     * awaits it before finish(): [rescheduleReminders] only queues the pass, and a background
     * process may be ended as soon as the receiver is done.
     */
    suspend fun awaitQueued() = suspendCancellableCoroutine { c -> executor.execute { c.resume(Unit) } }

    /** The plan the last pass armed. */
    fun currentPlan(): List<Reminder> = try {
        prefs.getString(KEY_PLAN, null)?.let { PlanJson.decodeFromString(PlanSerializer, it) } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized private fun apply(plan: List<Reminder>) {
        try {
            for (old in currentPlan()) cancel(old.id)
            // A rebuild within the 60 s of slack after 09:00 still plans today's occurrence; once
            // it has been shown, arming it again would fire it a second time at once.
            for (r in plan) if (!wasDelivered(context, r)) arm(r)
            armHeartbeat()
            prefs.edit().putString(KEY_PLAN, PlanJson.encodeToString(PlanSerializer, plan)).apply()
        } catch (e: Exception) {
            fail(e)
        }
    }

    /**
     * After a reboot, which drops every alarm: the saved plan's future reminders, as they were,
     * without reading the sync folder. One that fell due earlier today while the phone was off is
     * shown now; one from an earlier day is past caring about. The heartbeat is re-armed, and its
     * rebuild moves an ignored check-in on to its next step.
     */
    @Synchronized fun rearmFromSavedPlan() {
        val now = clock()
        val nowMs = now.toInstant().toEpochMilli()
        try {
            for (r in currentPlan()) {
                if (wasDelivered(context, r)) continue
                if (r.atMs > nowMs) arm(r)
                else if (ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(r.atMs), now.zone).toLocalDate() == now.toLocalDate()) {
                    ReminderNotifications.post(context, r)
                    markDelivered(context, r)
                }
            }
            armHeartbeat()
        } catch (e: Exception) {
            fail(e)
        }
    }

    /**
     * One inexact alarm each night that rebuilds the schedule, so it stays current without the app
     * being opened: a fired check-in has no successor until a rebuild computes its next step, and
     * revoking the exact-alarm grant cancels every exact alarm (but not this one), after which the
     * rebuild arms them inexactly. A pass that stands down (read-only, notifications off) does not
     * re-arm it; the next resume does.
     */
    private fun armHeartbeat() {
        val now = clock()
        var at = now.toLocalDate().atTime(HEARTBEAT_HOUR, HEARTBEAT_MINUTE).atZone(now.zone)
        if (!at.isAfter(now)) at = now.toLocalDate().plusDays(1).atTime(HEARTBEAT_HOUR, HEARTBEAT_MINUTE).atZone(now.zone)
        val pi = PendingIntent.getBroadcast(context, 0,
            Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_HEARTBEAT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), pi)
    }

    private fun arm(r: Reminder) {
        val pi = PendingIntent.getBroadcast(context, r.id, fireIntent(context).putExtra(EXTRA_REMINDER, PlanJson.encodeToString(Reminder.serializer(), r)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Exact when the grant allows it (ExactAlarmPlugin.kt's check); otherwise the OS may run it late,
        // which the Settings row (P5) offers to fix. Resume reschedules, so a grant upgrades these.
        if (ExactAlarms.granted(context)) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.atMs, pi)
                return
            } catch (e: SecurityException) {
                // Revoked between the check and the call: fall through to inexact.
            }
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.atMs, pi)
    }

    private fun cancel(id: Int) {
        val pi = PendingIntent.getBroadcast(context, id, fireIntent(context),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        alarmManager.cancel(pi)
        pi.cancel()
    }

    private fun fail(e: Throwable) {
        Log.e(TAG, "scheduling failed", e)
        _failures.tryEmit(e)
    }

    companion object {
        private const val TAG = "forgetmenot"
        private const val PREFS = "reminders"
        private const val KEY_PLAN = "plan"
        internal const val EXTRA_REMINDER = "reminder"

        internal val PlanJson = Json { ignoreUnknownKeys = true }
        private val PlanSerializer = ListSerializer(Reminder.serializer())

        private const val KEY_DELIVERED = "delivered"
        private const val HEARTBEAT_HOUR = 3
        private const val HEARTBEAT_MINUTE = 30
        private const val DELIVERED_KEPT_MS = 2 * 86_400_000L

        private fun deliveredKey(r: Reminder) = "${r.id}@${r.atMs}"

        /** Reminders already shown, by id and due time, so no rebuild or boot shows one twice. */
        internal fun wasDelivered(context: Context, r: Reminder): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_DELIVERED, null)
                ?.contains(deliveredKey(r)) == true

        @Synchronized internal fun markDelivered(context: Context, r: Reminder) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val kept = prefs.getStringSet(KEY_DELIVERED, emptySet()).orEmpty().filterTo(HashSet()) {
                (it.substringAfter('@').toLongOrNull() ?: 0L) > r.atMs - DELIVERED_KEPT_MS
            }
            kept += deliveredKey(r)
            prefs.edit().putStringSet(KEY_DELIVERED, kept).commit()
        }

        /** The alarm's intent; extras are not part of a PendingIntent's identity, so cancel needs none. */
        internal fun fireIntent(context: Context) =
            Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE)
    }
}
