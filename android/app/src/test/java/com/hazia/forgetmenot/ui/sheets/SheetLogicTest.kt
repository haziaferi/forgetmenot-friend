package com.hazia.forgetmenot.ui.sheets

import com.hazia.forgetmenot.data.RecordKind
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.domain.Tag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** The sheets' pure rules, without a UI: the parts of sheets.js, toast.js, conflicts.js and contacts.js that compute. */
class SheetLogicTest {

    @Test fun manualCadenceIsDaysPlusThirtyPerMonthPlus365PerYear() {
        assertEquals(10, manualCadenceDays("10", "", ""))
        assertEquals(10 + 60 + 365, manualCadenceDays("10", "2", "1"))
        assertEquals(0, manualCadenceDays("", " ", "x"))
    }

    @Test fun logDatePicksAreYesterdayTwoDaysAgoAndTheLastSaturdayThatIsOver() {
        val wed = LocalDate.of(2026, 10, 14)
        assertEquals(
            listOf(LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 10)),
            logDatePicks(wed, Locale.UK).map { it.date },
        )
        assertEquals(listOf("Yesterday", "2 days ago", "Last weekend"), logDatePicks(wed).map { it.label })
        assertEquals("Sat 10 Oct", logDatePicks(wed, Locale.UK)[2].sub)
        // On a weekend, "last weekend" is the one a week back.
        assertEquals(LocalDate.of(2026, 10, 10), logDatePicks(LocalDate.of(2026, 10, 17), Locale.UK)[2].date) // Saturday
        assertEquals(LocalDate.of(2026, 10, 10), logDatePicks(LocalDate.of(2026, 10, 18), Locale.UK)[2].date) // Sunday
        assertEquals(LocalDate.of(2026, 10, 10), logDatePicks(LocalDate.of(2026, 10, 12), Locale.UK)[2].date) // Monday
    }

    @Test fun aBackDatedLogIsNoonLocalTime() {
        assertEquals("2026-10-12T10:00:00.000Z", noonTimestamp(LocalDate.of(2026, 10, 12), ZoneId.of("Europe/Rome")))
    }

    @Test fun recentChangesKeepTheLastFiveAndUndoByIdInAnyOrder() {
        RecentChanges.reset(); Toaster.reset()
        val undone = mutableListOf<Int>()
        for (i in 1..7) RecentChanges.record("change $i") { undone += i }
        val list = RecentChanges.changes.value
        assertEquals(listOf("change 7", "change 6", "change 5", "change 4", "change 3"), list.map { it.label })
        RecentChanges.undo(list[2].id)
        RecentChanges.undo(list[2].id) // already gone: nothing
        assertEquals(listOf(5), undone)
        assertEquals("Undone: change 5", toastText())
        assertEquals(4, RecentChanges.changes.value.size)
    }

    @Test fun theUndoToastUndoesTheChangeItWasRaisedFor() {
        val env = TestEnv()
        val undone = mutableListOf<String>()
        RecentChanges.record("first") { undone += "first" }
        Toaster.showWithUndo("First done")
        val toast = Toaster.current.value!!
        RecentChanges.record("second") { undone += "second" }
        toast.action!!.invoke()
        assertEquals(listOf("first"), undone)
        assertTrue(env.records.listFriends().isEmpty())
    }

    @Test fun deletingATagIsUndoneTagAndFriendsTogether() {
        val env = TestEnv()
        val r = env.records
        r.saveTag(Tag(id = "t1", name = "Family", cadenceDays = 30))
        r.saveFriend(Friend(id = "a", name = "Ann", tags = listOf("t1")))
        r.saveFriend(Friend(id = "b", name = "Bo"))
        deleteTagUndoably(r, "t1")
        assertNull(r.getTag("t1"))
        assertEquals(emptyList<String>(), r.getFriend("a")!!.tags)
        assertEquals("Deleted the tag “Family” · 1 friend untagged", toastText())
        Toaster.current.value!!.action!!.invoke()
        assertEquals("Family", r.getTag("t1")!!.name)
        assertEquals(listOf("t1"), r.getFriend("a")!!.tags)
        assertEquals(emptyList<String>(), r.getFriend("b")!!.tags)
    }

    @Test fun undoingAnAddedEntryDoesNothingOnceItIsGone() {
        val env = TestEnv()
        val r = env.records
        r.saveFriend(Friend(id = "a", name = "Ann", notes = listOf(com.hazia.forgetmenot.domain.Note("n1", "2026-10-01T00:00:00.000Z", "hi"))))
        val undo = undoAddedEntry(r, "a", EntryList.NOTES, "n1")
        undo()
        assertTrue(r.getFriend("a")!!.notes.isEmpty())
        undo()
        assertTrue(r.getFriend("a")!!.notes.isEmpty())
        undoAddedEntry(r, "gone", EntryList.NOTES, "n1")() // no friend: nothing
    }

    @Test fun contactsImportSortsNamedContactsAndFlagsLikelyDuplicates() {
        val raw = listOf(ImportCandidate("zoe"), ImportCandidate("  "), ImportCandidate("Ann", phone = "1"), ImportCandidate("bo"))
        assertEquals(listOf("Ann", "bo", "zoe"), importCandidates(raw).map { it.name })
        val existing = listOf(Friend(id = "f", name = "ann", phone = "1"), Friend(id = "g", name = "Bo", phone = "9"))
        assertTrue(looksAlreadyAdded(ImportCandidate("ANN", phone = "1"), existing))
        assertFalse(looksAlreadyAdded(ImportCandidate("Ann", phone = "2"), existing))
        assertTrue("no phone: the name is enough", looksAlreadyAdded(ImportCandidate("bo"), existing))
    }

    @Test fun conflictChoicesSummariseWhatEachVersionHolds() {
        val rec = Json.parseToJsonElement("""{"name":"A","updatedAt":0,"contacts":[{},{}],"memberIds":["x"]}""").jsonObject
        val now = 3 * 3_600_000L
        assertEquals("Edited unknown time · 2 check-ins logged", conflictSummary(RecordKind.FRIEND, rec, now))
        val stamped = Json.parseToJsonElement("""{"updatedAt":0,"memberIds":["x"]}""").jsonObject.let {
            kotlinx.serialization.json.JsonObject(it + ("updatedAt" to kotlinx.serialization.json.JsonPrimitive(1L)))
        }
        assertEquals("Edited 3 hours ago · 1 member", conflictSummary(RecordKind.GROUP, stamped, now))
        assertEquals("Edited 3 hours ago", conflictSummary(RecordKind.TAG, stamped, now))
    }

    @Test fun theThemeRowNamesTheSkinAndModeAndFallsBack() {
        assertEquals("Cyanotype, Light", themeLabel("blue", "light"))
        assertEquals("Provence, OLED", themeLabel("lavender", "dark"))
        assertEquals("Cyanotype, Light", themeLabel("mauve", "dim"))
    }
}
