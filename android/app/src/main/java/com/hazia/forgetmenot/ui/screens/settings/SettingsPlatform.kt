package com.hazia.forgetmenot.ui.screens.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hazia.forgetmenot.reminders.ExactAlarms

/** The Notifications row: "On", "Blocked", or "Not enabled" (never asked, on Android 13+). */
enum class NotifStatus(val label: String) { ON("On"), BLOCKED("Blocked"), NOT_ENABLED("Not enabled") }

/**
 * What the REMINDERS rows read from the system and the screens their taps open. An interface so
 * the UI tests can stand in for Android; [AndroidSettingsPlatform] is the real one. Ports the
 * status/request halves of www/js/features/notifications.js and ExactAlarmPlugin.kt.
 */
interface SettingsPlatform {
    fun notifications(): NotifStatus
    /** True when the POST_NOTIFICATIONS runtime dialog can still be asked for. */
    fun canAskNotifications(): Boolean
    /** `SDK_INT < S || canScheduleExactAlarms()`: the appop, not the permission list (SPEC §4). */
    fun exactAlarmsGranted(): Boolean
    fun batteryUnrestricted(): Boolean
    fun openNotificationSettings()
    fun openExactAlarmSettings()
    fun openBatterySettings()
    val appVersion: String
}

class AndroidSettingsPlatform(private val context: Context) : SettingsPlatform {

    override fun notifications(): NotifStatus = when {
        NotificationManagerCompat.from(context).areNotificationsEnabled() -> NotifStatus.ON
        canAskNotifications() -> NotifStatus.NOT_ENABLED
        else -> NotifStatus.BLOCKED
    }

    override fun canAskNotifications(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    // The scheduler's own check, so the row and the alarms never disagree.
    override fun exactAlarmsGranted(): Boolean = ExactAlarms.granted(context)

    override fun batteryUnrestricted(): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    override fun openNotificationSettings() = start(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )

    // Deep-links to "Alarms & reminders"; some OEM builds omit that screen or hide it after a
    // first denial, so a failure to start it is swallowed.
    override fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            start(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName)))
        }
    }

    // The system list where the app can be exempted. The direct per-app request needs
    // REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which Play restricts, so it is not used.
    override fun openBatterySettings() = start(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

    override val appVersion: String
        get() = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: PackageManager.NameNotFoundException) { "?" }

    private fun start(intent: Intent) {
        try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { /* no such screen on this build */ }
    }
}
