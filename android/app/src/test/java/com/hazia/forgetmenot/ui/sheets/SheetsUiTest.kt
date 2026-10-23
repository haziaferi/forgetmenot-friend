package com.hazia.forgetmenot.ui.sheets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.hazia.forgetmenot.domain.Contact
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.domain.Tag
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Each shared sheet, driven as a user would: www/js/ui/sheets.js, theme.js, conflicts.js, contacts.js. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SheetsUiTest {
    @get:Rule val compose = createComposeRule()
    private val env = TestEnv()
    private val r = env.records
    private var dismissed = 0

    private fun ann(vararg contacts: Contact) = r.saveFriend(Friend(id = "ann", name = "Ann", cadenceDays = 30, contacts = contacts.toList()))

    // ── cadence ──

    @Test fun cadencePresetPicksAndClosesWithTheHintForWhereItWasOpened() {
        var picked: Int? = null
        compose.setThemed { CadenceSheet(current = 30, savesAtOnce = true, onPick = { picked = it }, onDismiss = { dismissed++ }) }
        compose.onNodeWithText("Saved as soon as you pick.").assertExists()
        compose.onNodeWithText("1 month").assertIsSelected()
        compose.onNodeWithText("3 months").assertIsNotSelected().performClick()
        compose.settle()
        assertEquals(90, picked)
        assertEquals(1, dismissed)
    }

    @Test fun cadenceManualTotalsAndRefusesZero() {
        var picked: Int? = null
        compose.setThemed { CadenceSheet(current = 90, savesAtOnce = false, onPick = { picked = it }, onDismiss = { dismissed++ }) }
        compose.onNodeWithText("Applied when you save this friend.").assertExists()
        compose.onNodeWithText("Confirm").performScrollTo().performClick()
        assertEquals("Set at least one value", toastText())
        assertNull(picked)
        compose.onNodeWithTag("manual-days").performTextInput("3")
        compose.onNodeWithTag("manual-months").performTextInput("1")
        compose.onNodeWithText("Confirm").performClick()
        compose.settle()
        assertEquals(33, picked)
    }

    // ── note ──

    @Test fun anEmptyNoteJustClosesAndASavedOneIsUndoable() {
        ann()
        var open by mutableStateOf(true)
        compose.setThemed { if (open) NoteSheet(r, "ann", onDismiss = { open = false; dismissed++ }) }
        compose.onNodeWithText("Save note").performClick()
        compose.settle()
        assertEquals(1, dismissed)
        assertTrue(r.getFriend("ann")!!.notes.isEmpty())

        open = true
        compose.onNodeWithTag("note-text").performTextInput("  Loves  jazz ")
        compose.onNodeWithText("Save note").performClick()
        compose.settle()
        assertEquals(listOf("Loves  jazz"), r.getFriend("ann")!!.notes.map { it.text })
        assertEquals("Note saved", toastText())
        assertEquals("Note for Ann", RecentChanges.changes.value.first().label)
        Toaster.current.value!!.action!!.invoke()
        assertTrue(r.getFriend("ann")!!.notes.isEmpty())
    }

    // ── add a date ──

    @Test fun aDateWithoutALabelIsCalledADate() {
        ann()
        compose.setThemed { DateSheet(r, "ann", onDismiss = { dismissed++ }, today = { LocalDate.of(2026, 11, 12) }) }
        compose.onNodeWithText("Every year").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.settle()
        val d = r.getFriend("ann")!!.dates.single()
        assertEquals("A date", d.label)
        assertEquals("2026-11-12", d.dateISO)
        assertTrue(d.yearly)
        assertEquals("Added “A date”", toastText())
        assertEquals("Added “A date” for Ann", RecentChanges.changes.value.first().label)
    }

    // ── log date ──

    @Test fun aQuickPickLogsAtNoonWithTheChosenKind() {
        ann()
        compose.setThemed { LogDateSheet(r, "ann", onDismiss = { dismissed++ }, today = { LocalDate.of(2026, 10, 14) }) }
        compose.onNodeWithText("Met up").performClick()
        compose.onNodeWithText("Met up").assertIsSelected()
        compose.onNodeWithText("Met up").performClick() // tapping the chosen kind clears it
        compose.onNodeWithText("Met up").assertIsNotSelected()
        compose.onNodeWithText("Call").performClick()
        compose.onNodeWithText("Yesterday").performClick()
        compose.settle()
        val c = r.getFriend("ann")!!.contacts.single()
        assertEquals("call", c.kind)
        assertEquals(noonTimestamp(LocalDate.of(2026, 10, 13)), c.date)
        assertEquals("Logged for Oct 13", toastText())
        assertEquals("Logged Ann for 2026-10-13", RecentChanges.changes.value.first().label)
        assertEquals(1, dismissed)
    }

    @Test fun saveLogsThePickedDateWhichDefaultsToToday() {
        ann()
        compose.setThemed { LogDateSheet(r, "ann", onDismiss = { dismissed++ }, today = { LocalDate.of(2026, 10, 14) }) }
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.settle()
        val c = r.getFriend("ann")!!.contacts.single()
        assertEquals(noonTimestamp(LocalDate.of(2026, 10, 14)), c.date)
        assertNull(c.kind)
    }

    // ── kind ──

    @Test fun kindSetsAndClearsTheLoggedReachOutsKind() {
        ann(Contact("c1", "2026-10-01T10:00:00.000Z", "text"))
        var open by mutableStateOf(true)
        compose.setThemed { if (open) KindSheet(r, "ann", "c1", onDismiss = { open = false }) }
        compose.onNodeWithText("Message").assertIsSelected().performClick()
        compose.settle()
        assertNull(r.getFriend("ann")!!.contacts.single().kind)
        open = true
        compose.waitForIdle()
        compose.onNodeWithText("Call").performClick()
        compose.settle()
        assertEquals("call", r.getFriend("ann")!!.contacts.single().kind)
    }

    // ── tags ──

    @Test fun tagPresetsSaveAtOnceAndDeleteIsUndoable() {
        r.saveTag(Tag(id = "t1", name = "Family", cadenceDays = 30))
        compose.setThemed { TagsSheet(r, onDismiss = {}) }
        compose.onNodeWithText("1 month").assertIsSelected()
        compose.onNodeWithText("2 weeks").performClick()
        compose.waitForIdle()
        assertEquals(14, r.getTag("t1")!!.cadenceDays)
        compose.onNodeWithText("2 weeks").assertIsSelected()
        compose.onNodeWithContentDescription("Delete tag Family").performClick()
        compose.waitForIdle()
        assertNull(r.getTag("t1"))
        compose.onNodeWithText("No tags yet.").assertExists()
        assertEquals("Deleted the tag “Family”", toastText())
    }

    // ── theme ──

    @Test fun themeSavesSkinAndModeAndStaysOpen() {
        compose.setThemed { ThemeSheet(r, onDismiss = { dismissed++ }) }
        compose.onNodeWithText("Cyanotype").assertIsSelected()
        compose.onNodeWithText("Provence").assertIsNotSelected().performClick()
        compose.onNodeWithText("OLED").performClick()
        compose.waitForIdle()
        assertEquals("lavender", r.getSettings().skin)
        assertEquals("dark", r.getSettings().mode)
        compose.onNodeWithText("Provence").assertIsSelected()
        compose.onNodeWithText("OLED").assertIsSelected()
        assertEquals(0, dismissed)
    }

    // ── archived ──

    @Test fun archivedRestoresAtOnceAndDeletesOnlyAfterAsking() {
        r.saveFriend(Friend(id = "b", name = "Bo", archived = true))
        r.saveFriend(Friend(id = "a", name = "ann", archived = true))
        r.saveFriend(Friend(id = "c", name = "Cy"))
        compose.setThemed {
            val ask = rememberAskState()
            ArchivedSheet(r, ask, onDismiss = {})
            AskHost(ask)
        }
        compose.onNodeWithText("Cy").assertDoesNotExist()
        compose.onAllNodesWithText("Restore")[0].performClick() // "ann" sorts first
        compose.waitForIdle()
        assertFalse(r.getFriend("a")!!.archived)
        assertEquals("Restored ann", toastText())

        compose.onNodeWithContentDescription("Delete Bo permanently").performClick()
        compose.onNodeWithText("Delete Bo for good?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.settle()
        assertTrue(r.getFriend("b") != null)
        compose.onNodeWithContentDescription("Delete Bo permanently").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.settle()
        assertNull(r.getFriend("b"))
        assertEquals("Deleted Bo", toastText())
        compose.onNodeWithText("Nobody archived right now.").assertExists()
    }

    // ── recent changes ──

    @Test fun recentChangesListsNewestFirstAndUndoesOne() {
        val undone = mutableListOf<String>()
        RecentChanges.record("Logged Ann for 2026-10-13") { undone += "log" }
        RecentChanges.record("Note for Bo") { undone += "note" }
        compose.setThemed { RecentChangesSheet(onDismiss = {}) }
        compose.onNodeWithText("Note for Bo", substring = true).assertExists()
        compose.onAllNodesWithText("Undo")[1].performClick()
        compose.waitForIdle()
        assertEquals(listOf("log"), undone)
        assertEquals("Undone: Logged Ann for 2026-10-13", toastText())
        compose.onNodeWithText("Logged Ann for 2026-10-13  ·", substring = true).assertDoesNotExist()
        compose.onAllNodesWithText("Undo")[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Nothing to undo right now.").assertExists()
    }

    // ── contacts import ──

    @Test fun contactsImportSelectsUnderTheSearchAndImportsTheChosen() {
        r.saveFriend(Friend(id = "x", name = "Ann", phone = "1"))
        var imported = 0
        val candidates = importCandidates(listOf(ImportCandidate("Ann", phone = "1"), ImportCandidate("Bo", email = "b@x"), ImportCandidate("Bea")))
        compose.setThemed { ContactsImportSheet(r, candidates, onDismiss = {}, onImported = { imported++ }) }
        compose.onNodeWithText("Looks already added").assertExists()
        compose.onNodeWithText("Nothing selected").assertIsNotEnabled()
        compose.onNodeWithTag("contacts-search").performTextInput("b")
        compose.onNodeWithText("Ann").assertDoesNotExist()
        compose.onNodeWithText("Select all").performClick()
        compose.onNodeWithText("Import 2 selected").assertExists()
        compose.onNodeWithText("Deselect all").assertExists()
        compose.onNodeWithText("Bea").performClick() // unticks one
        compose.onNodeWithText("Import 1 selected").performClick()
        compose.settle()
        assertEquals(1, imported)
        val bo = r.listFriends().single { it.name == "Bo" }
        assertEquals("b@x", bo.email)
        assertEquals(90, bo.cadenceDays)
        assertEquals(listOf("Ann", "Bo"), r.listFriends().map { it.name })
        assertEquals("Imported 1 contact", toastText())
        assertTrue(env.reschedules > 0 && env.pushes > 0)
    }

    @Test fun contactsImportSaysWhenNobodyMatches() {
        compose.setThemed { ContactsImportSheet(r, listOf(ImportCandidate("Ann")), onDismiss = {}, onImported = {}) }
        compose.onNodeWithTag("contacts-search").performTextInput("zz")
        compose.onNodeWithText("No one matches “zz”.").assertExists()
        compose.onNodeWithText("Select all").assertDoesNotExist()
    }

    // ── sync conflicts ──

    @Test fun aConflictIsKeptOnlyAfterAskingAndTheSheetClosesWhenNoneAreLeft() {
        env.plantFriendConflict("f1", "Ann", "Annie", contacts = 2)
        runBlocking { env.session.runScan() }
        assertEquals(1, env.session.pendingConflicts.value.size)
        var open by mutableStateOf(true)
        compose.setThemed {
            val ask = rememberAskState()
            if (open) SyncConflictsSheet(env.session, ask, onDismiss = { open = false; dismissed++ })
            AskHost(ask)
        }
        compose.onNodeWithText("Ann").assertExists()
        compose.onNodeWithText("2 check-ins logged", substring = true).assertExists()
        compose.onNodeWithText("Keep the synced-in version").performClick()
        compose.onNodeWithText("The other version of Ann is discarded. This can’t be undone.").assertExists()
        compose.onNodeWithText("Keep it").performClick()
        compose.waitUntil(5000) { env.session.pendingConflicts.value.isEmpty() }
        compose.settle()
        compose.waitUntil(5000) { dismissed == 1 }
        assertEquals("Kept one version, discarded the other", toastText())
        assertEquals("Annie", r.getFriend("f1")!!.name)
    }

    // A session that turned read-only after the scan (a failed folder pick, I6) cannot save the
    // choice. It says so before asking, instead of "This entry changed since the conflict was found".
    @Test fun aReadOnlySessionSaysWhyAConflictCannotBeResolved() {
        env.plantFriendConflict("f1", "Ann", "Annie", contacts = 2)
        runBlocking { env.session.runScan() }
        env.store.blockWrites()
        compose.setThemed {
            val ask = rememberAskState()
            SyncConflictsSheet(env.session, ask, onDismiss = {})
            AskHost(ask)
        }
        compose.onNodeWithText("Keep the synced-in version").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Keep it").assertDoesNotExist()
        assertEquals("Your sync folder couldn’t be read — restart the app, then try again", toastText())
        assertEquals(1, env.session.pendingConflicts.value.size)
        // Neither side was written or deleted.
        assertTrue(env.fs.disk.getValue("friends/f1.json").contains("\"name\":\"Ann\""))
        assertTrue(env.fs.disk.containsKey("friends/f1.sync-conflict-20261012-101500-ABC.json"))
    }

    @Test fun anEmptyConflictListSaysSo() {
        compose.setThemed { SyncConflictsSheet(env.session, rememberAskState(), onDismiss = {}) }
        compose.onNodeWithText("No conflicts.").assertExists()
    }

}
