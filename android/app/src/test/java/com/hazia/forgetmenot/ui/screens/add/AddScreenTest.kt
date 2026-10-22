package com.hazia.forgetmenot.ui.screens.add

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.domain.Tag
import com.hazia.forgetmenot.ui.sheets.ImportCandidate
import com.hazia.forgetmenot.ui.sheets.TestEnv
import com.hazia.forgetmenot.ui.sheets.settle
import com.hazia.forgetmenot.ui.sheets.toastText
import com.hazia.forgetmenot.ui.theme.ForgetmenotTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// SPEC §1 Screens, `add`: adding and editing (www/js/screens/add.js, #screen-add in www/index.html).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h900dp-xhdpi")
class AddScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val env = TestEnv()
    private var left = 0
    private var imported = 0
    private val saved = mutableListOf<SaveResult>()

    @After fun clearDraft() = AddDrafts.reset()

    private fun show(editId: String? = null, integrations: AddIntegrations = NoAddIntegrations) = compose.setContent {
        ForgetmenotTheme {
            val model = remember { AddFormModel(env.records, editId) }
            AddScreen(
                model = model,
                records = env.records,
                integrations = integrations,
                onLeave = { left++ },
                onSaved = { saved += it },
                onImported = { imported++ },
            )
        }
    }

    private fun field(tag: String) = compose.onNodeWithTag(tag)

    @Test fun theHeadingIsALevelOneHeadingAndTakesFocus() {
        show()
        compose.onNodeWithText("Add friend").assertIsFocused()
    }

    @Test fun addingWithANameSavesAndReportsIt() {
        show()
        field(AddTags.NAME).performTextInput("Ana")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertEquals(listOf<SaveResult>(SaveResult.Added("Ana")), saved)
        assertEquals("Ana", env.records.listFriends().single().name)
        assertEquals("Added Ana", toastText())
    }

    @Test fun aBlankNameFocusesTheNameField() {
        show()
        field(AddTags.PHONE).performTextInput("123")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertTrue(saved.isEmpty())
        assertEquals("Give them a name first", toastText())
        field(AddTags.NAME).assertIsFocused()
    }

    @Test fun everyTextFieldReachesTheRecord() {
        show()
        field(AddTags.NAME).performTextInput("Bo")
        field(AddTags.PHONE).performTextInput("555")
        field(AddTags.EMAIL).performTextInput("bo@example.org")
        field(AddTags.HOW_MET).performTextInput("school")
        field(AddTags.NOTE).performScrollTo().performTextInput("likes tea")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        val f = env.records.listFriends().single()
        assertEquals("555", f.phone)
        assertEquals("bo@example.org", f.email)
        assertEquals("school", f.howWeMet)
        assertEquals("likes tea", f.notes.single().text)
        assertTrue(f.contacts.isEmpty())
    }

    @Test fun messageChannelChipsToggle() {
        show()
        val chip = compose.onNode(hasText("WhatsApp"))
        chip.performScrollTo().performClick()
        chip.assertIsSelected()
        chip.performClick()
        chip.assertIsNotSelected()
        compose.onNode(hasText("Telegram")).performClick()
        field(AddTags.NAME).performTextInput("Cy")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertEquals("telegram", env.records.listFriends().single().messageChannel)
    }

    @Test fun aTagChipSetsTheCadenceShownOnTheRow() {
        env.records.saveTag(Tag("t1", "Close friends", 14))
        show()
        compose.onNodeWithTag(AddTags.CADENCE_VALUE, useUnmergedTree = true).assertTextContains("3 months", substring = true)
        compose.onNode(hasText("Close friends")).performScrollTo().performClick()
        compose.onNode(hasText("Close friends")).assertIsSelected()
        compose.onNodeWithTag(AddTags.CADENCE_VALUE, useUnmergedTree = true).assertTextContains("2 weeks", substring = true)
    }

    @Test fun newTagAsksForANameAndSelectsTheTag() {
        show()
        field(AddTags.NEW_TAG).performScrollTo().performClick()
        compose.onNodeWithTag("ask-input").assertExists()
        compose.onNodeWithTag("ask-input").performTextInput("Climbing")
        compose.onNodeWithTag("ask-confirm").performClick()
        compose.settle()
        val t = env.records.listTags().single()
        assertEquals("Climbing", t.name)
        assertEquals(90, t.cadenceDays)
        compose.onNode(hasText("Climbing")).assertIsSelected()
    }

    @Test fun aCancelledNewTagSavesNothing() {
        show()
        field(AddTags.NEW_TAG).performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.settle()
        assertTrue(env.records.listTags().isEmpty())
    }

    @Test fun theCadenceSheetSaysItAppliesOnSave() {
        show()
        field(AddTags.CADENCE_ROW).performScrollTo().performClick()
        compose.onNodeWithText("Applied when you save this friend.").assertIsDisplayed()
        compose.onNodeWithText("6 months").performClick()
        compose.settle()
        compose.onNodeWithTag(AddTags.CADENCE_VALUE, useUnmergedTree = true).assertTextContains("6 months", substring = true)
        assertTrue(env.records.listFriends().isEmpty())
    }

    @Test fun editModeShowsTheFriendAndHidesAddOnlyParts() {
        env.records.saveFriend(Friend(id = "x1", name = "Flo", birthday = "1990-05-06", askNext = "the move"))
        show("x1", FakeIntegrations())
        compose.onNodeWithText("Edit friend").assertIsDisplayed()
        field(AddTags.NAME).assertTextContains("Flo")
        compose.onNodeWithText("Import from contacts").assertDoesNotExist()
        compose.onNodeWithText("Import several at once").assertDoesNotExist()
        compose.onNodeWithText("FIRST NOTE (OPTIONAL)").assertDoesNotExist()
        field(AddTags.BIRTHDAY_CLEAR).performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertEquals(listOf<SaveResult>(SaveResult.Saved("x1")), saved)
        val f = env.records.getFriend("x1")!!
        assertNull(f.birthday)
        assertEquals("the move", f.askNext)
        assertEquals("Saved", toastText())
    }

    @Test fun anUnknownEditIdLeavesAtOnce() {
        show("nobody")
        compose.waitForIdle()
        assertEquals(1, left)
    }

    @Test fun cancelKeepsAChangedFormForNextTime() {
        show()
        field(AddTags.NAME).performTextInput("Gu")
        compose.onNodeWithContentDescription("Cancel").performClick()
        assertEquals(1, left)
        // Opening the form again, as goTo('add') would.
        val again = AddFormModel(env.records, null)
        assertTrue(again.restored)
        assertEquals("Gu", again.form.name)
    }

    @Test fun theBackButtonLeavesTheSameWay() {
        show()
        field(AddTags.NAME).performTextInput("Ha")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, left)
        assertTrue(AddFormModel(env.records, null).restored)
    }

    @Test fun aRestoredFormOffersStartOver() {
        AddFormModel(env.records, null).apply { update { it.copy(name = "Io") }; leave() }
        show()
        field(AddTags.NAME).assertTextContains("Io")
        assertEquals("Picked up where you left off", toastText())
        compose.onNodeWithText("Start over with an empty form").performClick()
        compose.onNodeWithText("Start over with an empty form").assertDoesNotExist()
        assertEquals("", nameText())
    }

    private fun nameText(): String =
        compose.onNode(hasTestTag(AddTags.NAME)).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text

    @Test fun withoutIntegrationsThereIsNoPhotoOrImport() {
        show()
        compose.onNodeWithText("Import from contacts").assertDoesNotExist()
        compose.onNodeWithText("Import several at once").assertDoesNotExist()
        compose.onNodeWithContentDescription("Add a photo").assertDoesNotExist()
    }

    @Test fun withoutAPickerAnExistingPhotoIsShownNotOffered() {
        env.records.saveFriend(Friend(id = "x1", name = "Flo", avatar = "data:image/jpeg;base64,AA"))
        show("x1")
        compose.onNodeWithContentDescription("Photo").assertExists().assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithContentDescription("Change photo").assertDoesNotExist()
        compose.onNodeWithContentDescription("Remove photo").assertExists()
    }

    @Test fun aDoubleTappedSaveAddsTheFriendOnce() {
        show()
        field(AddTags.NAME).performTextInput("Ana")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Save").performClick() // the screen is still there while it animates away
        compose.waitForIdle()
        assertEquals(1, env.records.listFriends().size)
    }

    @Test fun integrationsFillTheFormAndThePhotoCanBeRemoved() {
        val fake = FakeIntegrations(contact = PickedContact("Lu", "777", null, null, null), photo = "data:image/jpeg;base64,AA")
        show(integrations = fake)
        compose.onNodeWithText("Import from contacts").performClick()
        compose.waitForIdle()
        field(AddTags.NAME).assertTextContains("Lu")
        field(AddTags.PHONE).assertTextContains("777")
        assertEquals("Imported Lu", toastText())
        compose.onNodeWithContentDescription("Add a photo").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Remove photo").performClick()
        compose.onNodeWithContentDescription("Remove photo").assertDoesNotExist()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertNull(env.records.listFriends().single().avatar)
    }

    @Test fun bulkImportOpensTheChecklistOrSaysThereIsNobody() {
        show(integrations = FakeIntegrations(all = emptyList()))
        compose.onNodeWithText("Import several at once").performClick()
        compose.waitForIdle()
        assertEquals("No contacts with a name were found", toastText())
    }

    @Test fun bulkImportShowsTheChecklist() {
        show(integrations = FakeIntegrations(all = listOf(ImportCandidate("Zed"), ImportCandidate(" "))))
        compose.onNodeWithText("Import several at once").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Zed")).assertExists()
    }

    private class FakeIntegrations(
        val contact: PickedContact? = null,
        val photo: String? = null,
        val all: List<ImportCandidate>? = null,
    ) : AddIntegrations {
        override val canPickPhoto = true
        override val canImportContacts = true
        override suspend fun pickPhoto() = photo
        override suspend fun pickContact() = contact
        override suspend fun readContacts() = all
    }
}
