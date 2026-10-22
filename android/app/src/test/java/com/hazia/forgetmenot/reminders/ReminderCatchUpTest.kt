package com.hazia.forgetmenot.reminders

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import com.hazia.forgetmenot.data.CachedStore
import com.hazia.forgetmenot.data.FakeFolder
import com.hazia.forgetmenot.data.Records
import com.hazia.forgetmenot.domain.Friend
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * What keeps the schedule alive without the app being opened: the nightly rebuild (an ignored
 * check-in comes back a step later; a revoked exact grant falls back to inexact), never firing a
 * delivered reminder twice, and the boot catch-up for a reminder due while the phone was off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderCatchUpTest {
    private val zone = ZoneId.systemDefault()
    private val birthdayMorning = ZonedDateTime.of(2026, 10, 18, 9, 0, 0, 0, zone)
    private var now = birthdayMorning.minusHours(1)

    private lateinit var context: Context
    private lateinit var records: Records
    private lateinit var scheduler: ReminderScheduler
    private val am get() = context.getSystemService(AlarmManager::class.java)
    private val all get() = shadowOf(am).scheduledAlarms
    private val fires get() = all.filter { shadowOf(it.operation).savedIntent.action == ReminderReceiver.ACTION_FIRE }
    private val heartbeats get() = all.filter { shadowOf(it.operation).savedIntent.action == ReminderReceiver.ACTION_HEARTBEAT }
    private val nm get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        records = Records(CachedStore(FakeFolder()))
        scheduler = newScheduler()
        records.effects = CombinedEffects(scheduler)
    }

    @After fun tearDown() = ShadowAlarmManager.reset()

    private fun newScheduler() = ReminderScheduler(context, { records }, executor = { it.run() }, clock = { now })
    private fun saveDee() = records.saveFriend(Friend(id = "dee", name = "Dee", paused = true, birthday = "1990-10-18",
        createdAt = birthdayMorning.minusDays(200).toInstant().toEpochMilli()))
    private fun reboot() { all.toList().forEach { am.cancel(it.operation!!) }; assertEquals(0, all.size) }
    private val deeToday = birthdayMorning.toInstant().toEpochMilli()

    @Test fun everyPassArmsOneInexactHeartbeatForTheComingNight() {
        saveDee()
        val hb = heartbeats.single()
        assertEquals(ZonedDateTime.of(2026, 10, 19, 3, 30, 0, 0, zone).toInstant().toEpochMilli(), hb.triggerAtMs)
        assertTrue("inexact: a revoked exact grant cancels only exact alarms", hb.windowLengthMs != ShadowAlarmManager.WINDOW_EXACT)
        records.rescheduleReminders()
        assertEquals("replaced, not stacked", 1, heartbeats.size)
    }

    @Test fun aDeliveredReminderIsNotArmedAgainByARebuildInsideTheMinuteOfSlack() {
        saveDee()
        val alarm = fires.single { it.triggerAtMs == deeToday }
        now = birthdayMorning.plusSeconds(30) // the alarm fires, then the app is opened half a minute later
        ReminderReceiver().onReceive(context, shadowOf(alarm.operation).savedIntent)
        assertEquals(1, nm.allNotifications.size)
        records.rescheduleReminders()
        // Today's birthday is still "current" for 60 s of slack, but it has been shown: no past alarm.
        assertTrue(fires.none { it.triggerAtMs <= now.toInstant().toEpochMilli() })
        assertEquals(1, nm.allNotifications.size)
        // A minute later the domain moves on to next year's, which is armed as usual.
        now = birthdayMorning.plusMinutes(2)
        records.rescheduleReminders()
        assertEquals(listOf(birthdayMorning.plusYears(1).toInstant().toEpochMilli()), fires.map { it.triggerAtMs })
    }

    @Test fun aReminderDueWhileThePhoneWasOffIsPostedAtBootOnce() {
        saveDee()
        reboot()
        now = birthdayMorning.plusHours(5) // switched back on at 14:00 the same day
        newScheduler().rearmFromSavedPlan()
        assertEquals(1, nm.allNotifications.size)
        assertEquals(1, heartbeats.size)
        assertTrue(fires.none { it.triggerAtMs == deeToday })
        context.getSystemService(NotificationManager::class.java).cancelAll()
        reboot()
        newScheduler().rearmFromSavedPlan()
        assertEquals("already shown: not again", 0, nm.activeNotifications.size)
    }

    @Test fun aReminderMissedOnAnEarlierDayIsDropped() {
        saveDee()
        reboot()
        now = birthdayMorning.plusDays(1).plusHours(2)
        newScheduler().rearmFromSavedPlan()
        assertEquals(0, nm.allNotifications.size)
    }
}
