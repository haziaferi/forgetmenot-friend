package com.hazia.forgetmenot.reminders

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.hazia.forgetmenot.data.CachedStore
import com.hazia.forgetmenot.data.FakeFolder
import com.hazia.forgetmenot.data.Records
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.domain.Settings
import com.hazia.forgetmenot.domain.hashToId
import org.junit.After
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.ZoneId
import java.time.ZonedDateTime

/** scheduleNativeReminders() on AlarmManager: teardown and rebuild, exactness, I6, boot re-arm, delivery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderSchedulerTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val now = ZonedDateTime.of(2026, 10, 18, 12, 0, 0, 0, zone)
    private val created = now.minusDays(200).toInstant().toEpochMilli()

    private lateinit var context: Context
    private lateinit var store: CachedStore
    private lateinit var records: Records
    private lateinit var scheduler: ReminderScheduler
    /** The reminders' own alarms, not the nightly heartbeat (ReminderCatchUpTest covers that). */
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
        .filter { shadowOf(it.operation).savedIntent.action == ReminderReceiver.ACTION_FIRE }
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        store = CachedStore(FakeFolder())
        records = Records(store)
        scheduler = ReminderScheduler(context, { records }, executor = { it.run() }, clock = { now })
        records.effects = CombinedEffects(scheduler)
    }

    @After fun tearDown() = ShadowAlarmManager.reset()

    private fun ana() = Friend(id = "f1", name = "Ana", paused = true, birthday = "1990-03-14", createdAt = created)
    private fun bo() = Friend(id = "f2", name = "Bo", cadenceDays = 30, createdAt = created)

    @Test fun aRebuildArmsOneExactAllowWhileIdleWakeupPerReminder() {
        records.saveFriend(ana()); records.saveFriend(bo())
        val plan = scheduler.currentPlan()
        assertEquals(2, plan.size)
        assertEquals(plan.map { it.atMs }.sorted(), alarms.map { it.triggerAtMs }.sorted())
        assertTrue(alarms.all { it.type == AlarmManager.RTC_WAKEUP && it.allowWhileIdle })
        assertTrue("exact when allowed", alarms.all { it.windowLengthMs == ShadowAlarmManager.WINDOW_EXACT })
    }

    // A receiver awaits this before finish(): rescheduleReminders() only queues the pass, and a
    // background process may be ended as soon as the receiver is done.
    @Test fun awaitQueuedReturnsOnlyOnceTheQueuedPassHasArmed() = runBlocking {
        val queued = ArrayList<Runnable>()
        val held = ReminderScheduler(context, { records }, executor = { queued += it }, clock = { now })
        records.effects = CombinedEffects(held)
        records.saveFriend(bo())
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) { held.awaitQueued() }
        assertFalse(waiter.isCompleted)
        assertEquals(0, alarms.size)
        while (queued.isNotEmpty()) queued.removeAt(0).run()
        waiter.join()
        assertEquals(1, alarms.size)
        assertEquals(1, held.currentPlan().size)
    }

    @Test fun withoutTheExactGrantRemindersStillScheduleInexactly() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        records.saveFriend(ana())
        assertEquals(1, alarms.size)
        assertTrue(alarms.single().windowLengthMs != ShadowAlarmManager.WINDOW_EXACT)
        assertTrue(alarms.single().allowWhileIdle)
    }

    @Test fun everyPassCancelsWhatThePreviousOneArmed() {
        records.saveFriend(ana()); records.saveFriend(bo())
        assertEquals(2, alarms.size)
        records.deleteFriend("f2"); records.rescheduleReminders()
        assertEquals(listOf(hashToId("birthday:f1")), scheduler.currentPlan().map { it.id })
        assertEquals(1, alarms.size)
    }

    @Test fun quietModeTearsDownEveryPendingAlarm() {
        records.saveFriend(ana()); records.saveFriend(bo())
        records.saveSettings(Settings(quietMode = true)); records.rescheduleReminders()
        assertEquals(0, alarms.size)
        records.saveSettings(Settings(quietMode = false)); records.rescheduleReminders()
        assertEquals(2, alarms.size)
    }

    @Test fun aReadOnlySessionLeavesTheScheduleAsItWas() {
        records.saveFriend(ana()); records.saveFriend(bo())
        val before = alarms.map { it.triggerAtMs }
        store.blockWrites()
        records.saveSettings(Settings(quietMode = true))
        records.rescheduleReminders()
        scheduler.rescheduleReminders() // a direct call is gated too
        assertEquals(before, alarms.map { it.triggerAtMs })
    }

    @Test fun withNotificationsOffNothingIsCancelledOrArmed() {
        records.saveFriend(ana())
        notifications.setNotificationsEnabled(false)
        records.saveFriend(bo())
        assertEquals(1, alarms.size)
    }

    @Test fun afterABootTheSavedPlanIsReArmedFutureOnly() {
        records.saveFriend(ana()); records.saveFriend(bo())
        val plan = scheduler.currentPlan()
        val am = context.getSystemService(AlarmManager::class.java)
        alarms.toList().forEach { am.cancel(it.operation!!) } // a reboot drops every alarm
        assertEquals(0, alarms.size)
        val later = ReminderScheduler(context, { records }, executor = { it.run() },
            clock = { ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(plan.minOf { it.atMs }), zone) })
        later.rearmFromSavedPlan()
        assertEquals(plan.size - 1, alarms.size)
    }

    @Test fun aFiredAlarmPostsTheNudgeWithItsButtonsOnTheRemindersChannel() {
        records.saveFriend(bo())
        val alarm = alarms.single()
        ReminderReceiver().onReceive(context, shadowOf(alarm.operation).savedIntent)
        val n = notifications.allNotifications.single()
        assertEquals("reminders", n.channelId)
        assertEquals(listOf("Logged it", "Next week"), n.actions.map { it.title.toString() })
        assertEquals("Thinking of Bo?", n.extras.getString("android.title"))
        assertEquals(hashToId("checkin:f2"), notifications.activeNotifications.single().id)
    }

    @Test fun aBirthdayNudgeHasOnlyWishedThem() {
        records.saveFriend(ana())
        ReminderReceiver().onReceive(context, shadowOf(alarms.single().operation).savedIntent)
        assertEquals(listOf("Wished them"), notifications.allNotifications.single().actions.map { it.title.toString() })
    }
}
