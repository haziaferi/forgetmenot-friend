package com.hazia.forgetmenot.ui.screens.groups

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextEquals
import com.hazia.forgetmenot.domain.Group
import com.hazia.forgetmenot.domain.GroupEvent
import com.hazia.forgetmenot.domain.Recurring
import com.hazia.forgetmenot.ui.theme.ForgetmenotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag

// SPEC §1 Screens, `groups`: the list of groups with a one-line next-event teaser
// (renderGroups(), www/js/screens/groups.js:15), and the New group sheet (openGroupEditor(null)).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xhdpi")
class GroupsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val fx = GroupsFixture()
    private var opened: String? = null
    private var backs = 0

    private fun show(message: String? = null) = compose.setContent {
        ForgetmenotTheme {
            GroupsScreen(
                records = fx.records,
                onBack = { backs++ },
                onOpenGroup = { opened = it },
                message = message,
                now = { TEST_NOW },
            )
        }
    }

    @Test fun emptyStateExplainsGroups() {
        show()
        compose.onNodeWithText("No groups yet").assertIsDisplayed()
        compose.onNodeWithText("Bundle friends together", substring = true).assertIsDisplayed()
    }

    @Test fun rowsAreSortedByNameAndCarryTheTeaser() {
        fx.records.saveGroup(Group(id = "g1", name = "book club", memberIds = listOf("a", "b"),
            events = listOf(GroupEvent("e1", "Meetup", "2026-10-25", Recurring.ONCE))))
        fx.records.saveGroup(Group(id = "g2", name = "Alpha", memberIds = listOf("a")))
        show()
        val names = compose.onAllNodesWithTag(GroupsTags.GROUP_NAME, useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString() }
        assertEquals(listOf("Alpha", "book club"), names)
        compose.onNodeWithText("2 people · Meetup Oct 25").assertIsDisplayed()
        compose.onNodeWithText("1 person").assertIsDisplayed()
    }

    @Test fun tappingARowOpensThatGroup() {
        fx.records.saveGroup(Group(id = "g1", name = "Book club"))
        show()
        compose.onNodeWithText("Book club").performClick()
        assertEquals("g1", opened)
    }

    @Test fun theListFollowsChangesMadeElsewhere() {
        show()
        compose.onNodeWithText("No groups yet").assertIsDisplayed()
        compose.runOnIdle { fx.records.saveGroup(Group(id = "g1", name = "Cousins")) }
        compose.onNodeWithText("Cousins").assertIsDisplayed()
    }

    @Test fun newGroupNeedsANameThenOpensTheNewGroup() {
        show()
        compose.onNodeWithContentDescription("New group").performClick()
        compose.tagged(GroupsTags.SHEET_TITLE).assertTextEquals("New group")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Give the group a name").assertIsDisplayed()
        assertTrue(fx.records.listGroups().isEmpty())

        compose.tagged(GroupsTags.GROUP_NAME_FIELD).performTextInput("  Book club ")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        val g = fx.records.listGroups().single()
        assertEquals("Book club", g.name)
        assertEquals(emptyList<String>(), g.memberIds)
        assertEquals(emptyList<GroupEvent>(), g.events)
        assertNotNull("createdAt is stamped as in saveGroupEdit()", g.createdAt)
        assertEquals(g.id, opened)
    }

    @Test fun backLeavesForHome() {
        show()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    @Test fun aMessageHandedOverIsShown() {
        show(message = "Deleted Book club")
        compose.onNodeWithText("Deleted Book club").assertIsDisplayed()
    }

    // SPEC §7: the screen's title takes focus when the screen comes up.
    @Test fun theTitleTakesFocus() {
        show()
        compose.onNodeWithTag(GroupsTags.TITLE).assertIsFocused()
    }
}
