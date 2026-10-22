package com.hazia.forgetmenot.ui.screens.groups

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.domain.Group
import com.hazia.forgetmenot.domain.GroupEvent
import com.hazia.forgetmenot.domain.Recurring
import com.hazia.forgetmenot.ui.sheets.RecentChanges
import com.hazia.forgetmenot.ui.sheets.Toaster
import com.hazia.forgetmenot.ui.sheets.toastText
import com.hazia.forgetmenot.ui.theme.ForgetmenotTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// SPEC §1 Screens, `group-detail`: members, events, rename, delete (renderGroupDetail() and the
// three group sheets, www/js/screens/groups.js:38-214).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xhdpi")
class GroupDetailScreenTest {
    @get:Rule val compose = createComposeRule()
    private val fx = GroupsFixture()
    private var backs = 0
    private var openedFriend: String? = null
    private var deletedName: String? = null

    private fun show(groupId: String = "g1") = compose.setContent {
        ForgetmenotTheme {
            GroupDetailScreen(
                records = fx.records,
                groupId = groupId,
                onBack = { backs++ },
                onOpenFriend = { openedFriend = it },
                onDeleted = { deletedName = it },
                now = { TEST_NOW },
            )
        }
    }

    private fun group() = fx.records.getGroup("g1")!!

    private fun seed(memberIds: List<String> = emptyList(), events: List<GroupEvent> = emptyList()) {
        fx.records.saveGroup(Group(id = "g1", name = "Book club", memberIds = memberIds, events = events))
        fx.effects.reschedules = 0
    }

    private fun texts(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        .map { it.config[SemanticsProperties.Text].joinToString() }

    // ── members ──────────────────────────────────────────────────────────────

    @Test fun membersShowInGroupOrderWithArchivedMarkedAndGapsDropped() {
        fx.records.saveFriend(Friend(id = "f1", name = "Zoe"))
        fx.records.saveFriend(Friend(id = "f2", name = "Ann", archived = true))
        seed(memberIds = listOf("f1", "gone", "f2"))
        show()
        compose.tagged(GroupsTags.TITLE).assertTextEquals("Book club")
        assertEquals(listOf("Zoe", "Ann"), texts(GroupsTags.MEMBER_NAME))
        compose.onNodeWithText("(archived)").assertIsDisplayed()
        compose.onNodeWithText("Ann").performClick()
        assertEquals("f2", openedFriend)
    }

    @Test fun emptyGroupSaysSo() {
        seed()
        show()
        compose.onNodeWithText("No members yet.").assertIsDisplayed()
        compose.onNodeWithText("No events yet — add one to get a shared reminder.").assertIsDisplayed()
    }

    @Test fun membersPickerTogglesActiveFriendsAndReschedulesOnceOnClose() {
        fx.records.saveFriend(Friend(id = "f1", name = "Zoe"))
        fx.records.saveFriend(Friend(id = "f2", name = "Ann"))
        fx.records.saveFriend(Friend(id = "f3", name = "Old", archived = true))
        seed(memberIds = listOf("f1"))
        show()
        compose.onNodeWithText("+ Manage members").performClick()
        assertEquals("sorted by name, archived left out", listOf("Ann", "Zoe"), texts(GroupsTags.PICKER_NAME))
        compose.onNode(hasText("Zoe") and androidx.compose.ui.test.isToggleable()).assertIsOn()
        compose.onNode(hasText("Ann") and androidx.compose.ui.test.isToggleable()).assertIsOff().performClick()
        compose.onNode(hasText("Zoe") and androidx.compose.ui.test.isToggleable()).performClick()
        compose.waitForIdle()
        assertEquals(listOf("f2"), group().memberIds)
        assertEquals("toggles save with skipSync", 0, fx.effects.reschedules)

        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()
        assertEquals("closing the picker pays the reschedule once", 1, fx.effects.reschedules)
        assertEquals("the screen behind follows", listOf("Ann"), texts(GroupsTags.MEMBER_NAME))
    }

    @Test fun membersPickerWithNoFriendsPointsToAddingOne() {
        seed()
        show()
        compose.onNodeWithText("+ Manage members").performClick()
        compose.onNodeWithText("Add a friend first, then bring them into a group.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()
        assertEquals("nothing changed, nothing to reschedule", 0, fx.effects.reschedules)
    }

    // ── events ───────────────────────────────────────────────────────────────

    @Test fun eventsSortByNextDateWithWasAndRepeatLabels() {
        seed(events = listOf(
            GroupEvent("e1", "Picnic", "2026-12-01", Recurring.ONCE),
            GroupEvent("e2", "Launch", "2026-10-01", Recurring.ONCE),
            GroupEvent("e3", "Dinner", "2026-01-20", Recurring.MONTHLY),
        ))
        show()
        assertEquals(listOf("Launch", "Dinner", "Picnic"), texts(GroupsTags.EVENT_TITLE))
        assertEquals(listOf("Was Oct 1", "Oct 20 · monthly", "Dec 1"), texts(GroupsTags.EVENT_WHEN))
    }

    // The app-wide toast and the recent-changes list, as groups.js:79 used: the Undo outlives this
    // screen, and the deletion shows in Settings → Recent changes (SPEC §9).
    @Test fun deletingAnEventActsAtOnceAndUndoPutsItBack() {
        RecentChanges.reset(); Toaster.reset()
        seed(events = listOf(GroupEvent("e1", "Picnic", "2026-12-01", Recurring.ONCE)))
        show()
        compose.onNodeWithContentDescription("Delete event").performClick()
        compose.waitForIdle()
        assertTrue(group().events.isEmpty())
        assertEquals("a saved group reschedules", 1, fx.effects.reschedules)
        assertEquals("Deleted “Picnic”", toastText())
        assertEquals(listOf("Deleted “Picnic”"), RecentChanges.changes.value.map { it.label })
        Toaster.current.value!!.action!!.invoke()
        compose.waitForIdle()
        assertEquals(listOf("e1"), group().events.map { it.id })
        assertEquals("Undone: Deleted “Picnic”", toastText())
        compose.onNodeWithText("Picnic").assertIsDisplayed()
    }

    @Test fun editingAnEventKeepsItsIdAndRepeat() {
        seed(events = listOf(GroupEvent("e1", "Picnic", "2026-12-01", Recurring.MONTHLY)))
        show()
        compose.onNodeWithContentDescription("Edit event").performClick()
        compose.tagged(GroupsTags.SHEET_TITLE).assertTextEquals("Edit event")
        compose.tagged(GroupsTags.CHIP_PREFIX + Recurring.MONTHLY).assertIsSelected()
        compose.tagged(GroupsTags.EVENT_TITLE_FIELD).performTextClearance()
        compose.tagged(GroupsTags.EVENT_TITLE_FIELD).performTextInput("Potluck")
        compose.onNodeWithText("Save event").performClick()
        compose.waitForIdle()
        assertEquals(listOf(GroupEvent("e1", "Potluck", "2026-12-01", Recurring.MONTHLY)), group().events)
        compose.onNodeWithText("Potluck").assertIsDisplayed()
    }

    @Test fun addingAnEventNeedsATitleAndADateAndDefaultsToYearly() {
        seed()
        show()
        compose.onNodeWithText("+ Add event").performClick()
        compose.tagged(GroupsTags.SHEET_TITLE).assertTextEquals("Add event")
        compose.tagged(GroupsTags.CHIP_PREFIX + Recurring.YEARLY).assertIsSelected()
        compose.tagged(GroupsTags.CHIP_PREFIX + Recurring.ONCE).assertIsNotSelected()
        compose.onNodeWithText("Save event").performClick()
        compose.onNodeWithText("Give the event a title").assertIsDisplayed()
        compose.tagged(GroupsTags.EVENT_TITLE_FIELD).performTextInput("Meetup")
        compose.onNodeWithText("Save event").performClick()
        compose.onNodeWithText("Pick a date").assertIsDisplayed()
        assertTrue(group().events.isEmpty())
    }

    @Test fun addingAnEventSavesTitleDateAndRepeat() {
        seed()
        show()
        compose.onNodeWithText("+ Add event").performClick()
        compose.tagged(GroupsTags.EVENT_TITLE_FIELD).performTextInput("Meetup")
        compose.tagged(GroupsTags.CHIP_PREFIX + Recurring.ONCE).performClick()
        compose.tagged(GroupsTags.CHIP_PREFIX + Recurring.ONCE).assertIsSelected()
        compose.tagged(GroupsTags.CHIP_PREFIX + Recurring.YEARLY).assertIsNotSelected()
        // The date picker's text-input mode, in the test locale's (en-US) MM/dd/yyyy.
        compose.tagged(GroupsTags.EVENT_DATE_FIELD).performClick()
        compose.onNodeWithContentDescription("Switch to text input mode", substring = true).performClick()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction() and hasText("Date", substring = true))
            .performTextInput("10252026")
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("Save event").performClick()
        compose.waitForIdle()
        val ev = group().events.single()
        assertEquals("Meetup", ev.title)
        assertEquals("2026-10-25", ev.dateISO)
        assertEquals(Recurring.ONCE, ev.recurring)
        assertTrue(ev.id.isNotEmpty())
        compose.onNodeWithText("Oct 25").assertIsDisplayed()
    }

    @Test fun membersPickerStillReschedulesAfterARecreation() {
        fx.records.saveFriend(Friend(id = "f1", name = "Zoe"))
        seed()
        val restorer = StateRestorationTester(compose)
        restorer.setContent {
            ForgetmenotTheme {
                GroupDetailScreen(fx.records, "g1", onBack = {}, onOpenFriend = {}, onDeleted = {}, now = { TEST_NOW })
            }
        }
        compose.onNodeWithText("+ Manage members").performClick()
        compose.onNode(hasText("Zoe") and androidx.compose.ui.test.isToggleable()).performClick()
        compose.waitForIdle()
        assertEquals(0, fx.effects.reschedules)

        restorer.emulateSavedInstanceStateRestore() // rotation with the sheet up
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()
        assertEquals("the toggle made before the rotation is still paid for", 1, fx.effects.reschedules)
    }

    @Test fun anEventDeletedWhileBeingEditedIsNotSavedBackAsANewOne() {
        seed(events = listOf(
            GroupEvent("e1", "Picnic", "2026-12-01", Recurring.ONCE),
            GroupEvent("e2", "Dinner", "2026-11-01", Recurring.ONCE),
        ))
        show()
        compose.onAllNodesWithContentDescription("Edit event")[1].performClick() // Picnic, the later one
        compose.tagged(GroupsTags.SHEET_TITLE).assertTextEquals("Edit event")
        // A sync refresh takes the event away while the sheet is up.
        compose.runOnIdle { fx.records.saveGroup(group().copy(events = group().events.filter { it.id != "e1" })) }
        compose.tagged(GroupsTags.SHEET_TITLE).assertTextEquals("Edit event")
        compose.tagged(GroupsTags.EVENT_TITLE_FIELD).performTextInput("!")
        compose.onNodeWithText("Save event").performClick()
        compose.waitForIdle()
        assertEquals("as in saveGroupEventFromForm(), a vanished event is not re-added", listOf("e2"), group().events.map { it.id })
    }

    // ── rename, delete ───────────────────────────────────────────────────────

    @Test fun renameUpdatesTheGroupAndTheTitle() {
        seed()
        show()
        compose.onNodeWithContentDescription("Rename group").performClick()
        compose.tagged(GroupsTags.SHEET_TITLE).assertTextEquals("Rename group")
        compose.tagged(GroupsTags.GROUP_NAME_FIELD).assert(hasText("Book club"))
        compose.tagged(GroupsTags.GROUP_NAME_FIELD).performTextClearance()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Give the group a name").assertIsDisplayed()
        compose.tagged(GroupsTags.GROUP_NAME_FIELD).performTextInput("Readers")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertEquals("Readers", group().name)
        compose.tagged(GroupsTags.TITLE).assertTextEquals("Readers")
    }

    @Test fun deleteAsksFirstThenDeletesAndLeavesMembersAlone() {
        fx.records.saveFriend(Friend(id = "f1", name = "Zoe"))
        seed(memberIds = listOf("f1"), events = listOf(GroupEvent("e1", "Picnic", "2026-12-01", Recurring.ONCE)))
        show()
        compose.onNodeWithText("Delete group").performClick()
        compose.onNodeWithText("Delete “Book club”?").assertIsDisplayed()
        compose.onNodeWithText("The group and its events go; its members stay. This can’t be undone.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertNotNull(fx.records.getGroup("g1"))
        assertNull(deletedName)

        compose.onNodeWithText("Delete group").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        assertNull(fx.records.getGroup("g1"))
        assertTrue(fx.records.listGroups().isEmpty())
        assertEquals("Zoe", fx.records.getFriend("f1")?.name)
        assertEquals("Book club", deletedName)
        assertEquals("deleting clears the group's reminders", 1, fx.effects.reschedules)
    }

    @Test fun anUnknownGroupGoesBackToTheList() {
        show(groupId = "nope")
        compose.waitForIdle()
        assertEquals(1, backs)
    }

    @Test fun aGroupDeletedElsewhereGoesBackToTheList() {
        seed()
        show()
        compose.runOnIdle { fx.records.deleteGroup("g1") }
        compose.waitForIdle()
        assertEquals(1, backs)
    }

    @Test fun backReturnsToTheList() {
        seed()
        show()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    // ── I6 ───────────────────────────────────────────────────────────────────

    @Test fun aReadOnlySessionWritesNothingToTheFolder() = runBlocking {
        fx.records.saveFriend(Friend(id = "f1", name = "Zoe"))
        seed(events = listOf(GroupEvent("e1", "Picnic", "2026-12-01", Recurring.ONCE)))
        fx.store.flushWrites()
        fx.fs.calls.clear()
        fx.store.blockWrites()
        show()
        compose.onNodeWithContentDescription("Rename group").performClick()
        compose.tagged(GroupsTags.GROUP_NAME_FIELD).performTextInput("!")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithContentDescription("Delete event").performClick()
        compose.onNodeWithText("+ Manage members").performClick()
        compose.onNode(hasText("Zoe") and androidx.compose.ui.test.isToggleable()).performClick()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()
        fx.store.flushWrites()
        assertEquals(emptyList<Pair<String, String>>(), fx.fs.calls.toList())
        assertEquals("no reschedule in a read-only session", 0, fx.effects.reschedules)
        Unit
    }
}
