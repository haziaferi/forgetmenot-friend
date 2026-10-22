package com.hazia.forgetmenot.widget

import android.content.Context
import com.hazia.forgetmenot.WIDGET_PREFS_KEY
import com.hazia.forgetmenot.WIDGET_PUSHED_DAY_KEY
import com.hazia.forgetmenot.WIDGET_THEME_KEY
import com.hazia.forgetmenot.data.CachedStore
import com.hazia.forgetmenot.data.DataEffects
import com.hazia.forgetmenot.data.FakeFolder
import com.hazia.forgetmenot.data.Records
import com.hazia.forgetmenot.data.StartResult
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.reminders.CombinedEffects
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** SPEC §5 "The data path" and "The reverse path" over a fake folder, as AppContainer wires them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetDataPathTest {
    private val now = ZonedDateTime.of(2026, 10, 20, 12, 0, 0, 0, ZoneId.of("Europe/Rome"))
    private val ready = CompletableDeferred(StartResult(readOnly = false))

    private lateinit var context: Context
    private lateinit var fs: FakeFolder
    private lateinit var store: CachedStore
    private lateinit var records: Records
    private lateinit var scope: CoroutineScope
    private var updates = 0
    private var reschedules = 0

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        widgetPrefs(context).edit().clear().commit()
        fs = FakeFolder()
        store = CachedStore(fs)
        records = Records(store)
        scope = CoroutineScope(Dispatchers.Unconfined)
        val pusher = WidgetPusher(context, { records }, scope, clock = { now }, update = { updates++ })
        val reminders = object : DataEffects {
            override fun pushWidgets() {}
            override fun rescheduleReminders() { reschedules++ }
        }
        records.effects = CombinedEffects(reminders, pusher)
    }

    @After fun tearDown() { scope.cancel() }

    private fun prefs(key: String) = widgetPrefs(context).getString(key, null)
    private fun summaries() = readFriendSummaries(context)

    // ── the data path ────────────────────────────────────────────────────────

    @Test fun aFriendSavePushesSummaryThemeAndDay() {
        records.saveFriend(Friend(id = "ana", name = "Ana"))
        assertEquals(listOf(FriendSummary("ana", "Ana", "new", 0.0, false)), summaries())
        assertEquals(DEFAULT_WIDGET_THEME, readWidgetTheme(context))
        assertEquals("2026-10-20", prefs(WIDGET_PUSHED_DAY_KEY))
        assertEquals(1, updates)
        assertEquals(1, reschedules) // the composite drives both effects
    }

    @Test fun archivedFriendsLeaveTheSummary() {
        records.saveFriend(Friend(id = "ana", name = "Ana"))
        records.saveFriend(Friend(id = "bo", name = "Bo"))
        records.saveFriend(records.getFriend("ana")!!.copy(archived = true))
        assertEquals(listOf("bo"), summaries().map { it.id })
    }

    @Test fun theSettingsSkinIsWhatTheWidgetsWear() {
        records.saveSettings(records.getSettings().copy(skin = "lavender", mode = "dark"))
        records.pushWidgets()
        assertEquals(widgetTheme(records.getSettings()), readWidgetTheme(context))
        assertFalse(readWidgetTheme(context) == DEFAULT_WIDGET_THEME)
    }

    @Test fun readOnlyKeepsTheLastGoodSummary() {
        records.saveFriend(Friend(id = "ana", name = "Ana"))
        val before = prefs(WIDGET_PREFS_KEY)
        store.blockWrites()
        records.saveFriend(Friend(id = "bo", name = "Bo"))
        WidgetPusher(context, { records }, scope, clock = { now }, update = { updates++ }).pushWidgets() // gated itself too
        assertEquals(before, prefs(WIDGET_PREFS_KEY))
        assertEquals(1, updates)
    }

    @Test fun aSkinOrModeChangePushesOtherSettingsDoNot() = runBlocking {
        val job = pushOnThemeChange(scope, store, records)
        val pushedBefore = updates
        records.saveSettings(records.getSettings().copy(quietMode = true))
        assertEquals(pushedBefore, updates)
        records.saveSettings(records.getSettings().copy(skin = "green"))
        assertEquals(pushedBefore + 1, updates)
        records.saveSettings(records.getSettings().copy(mode = "dark"))
        assertEquals(pushedBefore + 2, updates)
        assertEquals(widgetTheme(records.getSettings()), readWidgetTheme(context))
        job.cancel()
    }

    // ── the reverse path ─────────────────────────────────────────────────────

    @Test fun theTickWritesTheReachOutToTheFolder() = runBlocking {
        records.saveFriend(Friend(id = "ana", name = "Ana"))
        val tap = Instant.parse("2026-10-20T10:15:30.123Z")
        val out = WidgetTick.log(ready, records, "ana", tap)
        assertEquals(TickOutcome.Logged("Ana"), out)
        assertEquals(listOf("2026-10-20T10:15:30.123Z"), records.getFriend("ana")!!.contacts.map { it.date })
        assertTrue("flushed to the folder", fs.disk["friends/ana.json"]!!.contains("2026-10-20T10:15:30.123Z"))
        // The save pushed the widgets: the friend now reads "today", weight 0.
        assertEquals("today", summaries().single().code)
    }

    @Test fun theTickWaitsForTheColdStartLoad() = runBlocking {
        records.saveFriend(Friend(id = "ana", name = "Ana"))
        val pending = CompletableDeferred<StartResult>()
        val tick = async(start = CoroutineStart.UNDISPATCHED) { WidgetTick.log(pending, records, "ana") }
        yield()
        assertTrue(records.getFriend("ana")!!.contacts.isEmpty())
        pending.complete(StartResult(readOnly = false))
        assertEquals(TickOutcome.Logged("Ana"), tick.await())
        assertEquals(1, records.getFriend("ana")!!.contacts.size)
    }

    @Test fun theTickWritesNothingInAReadOnlySession() = runBlocking {
        records.saveFriend(Friend(id = "ana", name = "Ana"))
        val disk = fs.disk.toMap()
        val widgetData = prefs(WIDGET_PREFS_KEY)
        assertEquals(TickOutcome.ReadOnly, WidgetTick.log(CompletableDeferred(StartResult(readOnly = true)), records, "ana"))
        store.blockWrites()
        assertEquals(TickOutcome.ReadOnly, WidgetTick.log(ready, records, "ana"))
        assertTrue(records.getFriend("ana")!!.contacts.isEmpty())
        assertEquals(disk, fs.disk.toMap())
        assertEquals(widgetData, prefs(WIDGET_PREFS_KEY))
    }

    @Test fun theTickIgnoresUnknownAndArchivedIds() = runBlocking {
        records.saveFriend(Friend(id = "ana", name = "Ana", archived = true))
        assertEquals(TickOutcome.Gone, WidgetTick.log(ready, records, "ana"))
        assertEquals(TickOutcome.Gone, WidgetTick.log(ready, records, "nobody"))
        assertTrue(records.getFriend("ana")!!.contacts.isEmpty())
        assertNull(records.getFriend("nobody"))
    }

    // A second tap while the first waited for the load: one reach-out, not two.
    @Test fun aSecondTapWithinAMinuteIsNotLoggedAgain() = runBlocking {
        records.saveFriend(Friend(id = "ana", name = "Ana"))
        val tap = Instant.parse("2026-10-20T10:15:30.000Z")
        val pending = CompletableDeferred<StartResult>()
        val first = async(start = CoroutineStart.UNDISPATCHED) { WidgetTick.log(pending, records, "ana", tap) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { WidgetTick.log(pending, records, "ana", tap.plusSeconds(2)) }
        pending.complete(StartResult(readOnly = false))
        assertEquals(TickOutcome.Logged("Ana"), first.await())
        assertEquals(TickOutcome.AlreadyLogged("Ana"), second.await())
        assertEquals(1, records.getFriend("ana")!!.contacts.size)
        // A tap a minute later is a new reach-out.
        assertEquals(TickOutcome.Logged("Ana"), WidgetTick.log(ready, records, "ana", tap.plusSeconds(61)))
        assertEquals(2, records.getFriend("ana")!!.contacts.size)
    }

    // Only the wait for the load is capped: a timeout means nothing was written.
    @Test fun aLoadThatNeverEndsTimesOutWithoutWriting() = runBlocking {
        records.saveFriend(Friend(id = "ana", name = "Ana"))
        val disk = fs.disk.toMap()
        assertEquals(TickOutcome.TimedOut, WidgetTick.log(CompletableDeferred(), records, "ana", waitMs = 50))
        assertTrue(records.getFriend("ana")!!.contacts.isEmpty())
        assertEquals(disk, fs.disk.toMap())
    }

    // ── the instant repaint ──────────────────────────────────────────────────

    @Test fun markTodayRepaintsOneFriend() {
        val list = listOf(FriendSummary("ana", "Ana", "3m", 3.0, false), FriendSummary("bo", "Bo", "9d", 0.6, false))
        assertEquals(listOf(FriendSummary("ana", "Ana", "today", 0.0, false), list[1]), markToday(list, "ana"))
        assertEquals(list, markToday(list, "nobody"))
    }

    @Test fun theTapRepaintsAtOnceAndAFailureRestores() {
        records.saveFriend(Friend(id = "ana", name = "Ana", contacts = listOf(
            com.hazia.forgetmenot.domain.Contact(id = "c1", date = "2026-07-20T10:00:00.000Z"))))
        val before = prefs(WIDGET_PREFS_KEY)
        val mark = markTappedToday(context, "ana")
        assertEquals("today", summaries().single().code)
        mark.restore(context)
        assertEquals(before, prefs(WIDGET_PREFS_KEY))
    }

    @Test fun aRestoreNeverClobbersANewerPush() {
        records.saveFriend(Friend(id = "ana", name = "Ana"))
        val mark = markTappedToday(context, "ana")
        records.saveFriend(Friend(id = "bo", name = "Bo")) // a real push lands in between
        val pushed = prefs(WIDGET_PREFS_KEY)
        mark.restore(context)
        assertEquals(pushed, prefs(WIDGET_PREFS_KEY))
    }
}
