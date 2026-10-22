package com.hazia.forgetmenot.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hazia.forgetmenot.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A reboot drops every alarm (RECEIVE_BOOT_COMPLETED, SPEC §6): the saved plan is re-armed as it
 * was, without reading the sync folder, and what fell due earlier today is shown now
 * ([ReminderScheduler.rearmFromSavedPlan]); the nightly heartbeat or the next resume rebuilds it.
 *
 * Two more broadcasts rebuild from the folder (skipped in a read-only session, I6):
 * MY_PACKAGE_REPLACED, right after an update is installed (alarms survive an update, so the new
 * build's rules apply before anyone opens it), and the exact-alarm grant, which Android announces
 * so the inexact alarms can be upgraded at once instead of on the next resume.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != EXACT_ALARM_GRANTED) return
        val container = AppContainer.from(context.applicationContext)
        val pending = goAsync()
        container.appScope.launch {
            try {
                if (action == Intent.ACTION_BOOT_COMPLETED) {
                    withContext(Dispatchers.IO) { container.reminders.rearmFromSavedPlan() }
                } else {
                    withTimeoutOrNull(45_000L) {
                        container.started.await()
                        container.records.rescheduleReminders()
                        container.reminders.awaitQueued()
                    }
                }
            } catch (e: Exception) {
                Log.e("forgetmenot", "restoring reminders failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED (API 31; minSdk is 30). Sent on grant only. */
        const val EXACT_ALARM_GRANTED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}
