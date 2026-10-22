package com.hazia.forgetmenot.reminders

import com.hazia.forgetmenot.data.CachedStore
import com.hazia.forgetmenot.data.DataEffects
import com.hazia.forgetmenot.data.FakeFolder
import com.hazia.forgetmenot.data.Records
import com.hazia.forgetmenot.data.StartResult
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.domain.isoTimestamp
import com.hazia.forgetmenot.ui.sheets.RecentChanges
import com.hazia.forgetmenot.ui.sheets.Toaster
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The nudge's buttons (SPEC §4): Logged it / Wished them log a reach-out, Next week snoozes 7 days. */
class ReminderActionsTest {
    private val now = ZonedDateTime.of(2026, 10, 18, 21, 30, 0, 0, ZoneId.of("Europe/Rome"))
    private val ready = CompletableDeferred(StartResult(readOnly = false))

    private lateinit var fs: FakeFolder
    private lateinit var store: CachedStore
    private lateinit var records: Records
    private var reschedules = 0

    @Before fun setUp() {
        fs = FakeFolder()
        store = CachedStore(fs)
        records = Records(store)
        records.saveFriend(Friend(id = "f1", name = "Ana"))
        records.saveFriend(Friend(id = "f2", name = "Bo"))
        records.effects = object : DataEffects {
            override fun pushWidgets() {}
            override fun rescheduleReminders() { reschedules++ }
        }
    }

    private suspend fun run(action: ReminderAction, vararg ids: String) =
        ReminderActions.perform(ready, records, action, ids.toList(), now)

    @Test fun loggedItAddsAReachOutNowAndWritesItToTheFolder() = runBlocking {
        val out = run(ReminderAction.LOGGED, "f1")
        assertEquals(ActionOutcome.Logged("Ana"), out)
        val f = records.getFriend("f1")!!
        assertEquals(isoTimestamp(now.toInstant()), f.contacts.single().date)
        assertEquals(null, f.contacts.single().kind)
        assertEquals(1, reschedules)
        assertTrue("flushed before the receiver finishes", fs.disk.values.any { it.contains(f.contacts.single().id) })
    }

    // SPEC §4: the same undo the on-screen Log gets, from Settings → Recent changes.
    @Test fun loggedItCanBeUndoneFromRecentChanges() = runBlocking {
        RecentChanges.reset()
        run(ReminderAction.LOGGED, "f1")
        val change = RecentChanges.changes.value.single()
        assertEquals("Logged Ana", change.label)
        RecentChanges.undo(change.id)
        assertTrue(records.getFriend("f1")!!.contacts.isEmpty())
        RecentChanges.reset(); Toaster.reset()
    }

    @Test fun wishedThemLogsExactlyAsLoggedItDoes() = runBlocking {
        assertEquals(ActionOutcome.Logged("Bo"), run(ReminderAction.WISHED, "f2"))
        assertEquals(1, records.getFriend("f2")!!.contacts.size)
    }

    @Test fun nextWeekSnoozesEveryNamedFriendAndReschedulesOnce() = runBlocking {
        assertEquals(ActionOutcome.Snoozed(listOf("Ana", "Bo")), run(ReminderAction.LATER, "f1", "gone", "f2"))
        assertEquals("2026-10-25", records.getFriend("f1")!!.snoozedUntil)
        assertEquals("2026-10-25", records.getFriend("f2")!!.snoozedUntil)
        assertEquals(1, reschedules)
    }

    @Test fun idsThatAreNoLongerAFriendAreSkipped() = runBlocking {
        assertEquals(ActionOutcome.NothingToDo, run(ReminderAction.LOGGED, "gone"))
        assertEquals(ActionOutcome.NothingToDo, run(ReminderAction.LATER, "gone"))
        assertEquals(0, reschedules)
    }

    @Test fun nothingIsEditedBeforeTheColdStartLoadCompletes() = runBlocking {
        val pending = CompletableDeferred<StartResult>()
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            ReminderActions.perform(pending, records, ReminderAction.LOGGED, listOf("f1"), now)
        }
        yield()
        assertFalse(job.isCompleted)
        assertTrue(records.getFriend("f1")!!.contacts.isEmpty())
        pending.complete(StartResult(readOnly = false))
        assertEquals(ActionOutcome.Logged("Ana"), job.await())
    }

    @Test fun aReadOnlySessionRefusesTheEdit() = runBlocking {
        store.blockWrites()
        val out = ReminderActions.perform(CompletableDeferred(StartResult(readOnly = true)),
            records, ReminderAction.LOGGED, listOf("f1"), now)
        assertEquals(ActionOutcome.ReadOnly, out)
        assertTrue(records.getFriend("f1")!!.contacts.isEmpty())
    }
}
