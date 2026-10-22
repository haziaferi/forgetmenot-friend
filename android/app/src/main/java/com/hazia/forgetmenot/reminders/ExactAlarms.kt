package com.hazia.forgetmenot.reminders

import android.app.AlarmManager
import android.content.Context
import android.os.Build

/**
 * Android 12+'s exact-alarm gate, as the scheduler reads it. On Android 14+ SCHEDULE_EXACT_ALARM
 * is denied by default and has no runtime dialog; without it reminders are armed inexactly and
 * may fire late. Below API 31 the permission does not exist, so it counts as granted. This reads
 * the appop, which `dumpsys package` can contradict (SPEC §4).
 *
 * The Settings rows that show it and send the user to the toggle live in
 * ui/screens/settings/SettingsPlatform.kt (P5), with the battery-optimisation row.
 */
object ExactAlarms {
    fun granted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
}
