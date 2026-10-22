package com.hazia.forgetmenot.ui.sheets

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** www/js/ui/ask.js: true / the trimmed text on confirm, no however else it closes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AskSheetTest {
    @get:Rule val compose = createComposeRule()

    private val answers = mutableListOf<Any?>()
    private lateinit var ask: AskState
    private lateinit var launch: (suspend () -> Any?) -> Unit

    private fun host() {
        TestEnv()
        compose.setThemed {
            ask = rememberAskState()
            val scope = rememberCoroutineScope()
            launch = { block -> scope.launch { answers += block() } }
            AskHost(ask)
        }
    }

    @Test fun confirmResolvesTrueAndCancelFalse() {
        host()
        launch { ask.confirm("Delete Ann for good?", "This can’t be undone.", "Delete") }
        compose.onNodeWithText("Delete Ann for good?").assertExists()
        compose.onNodeWithText("This can’t be undone.").assertExists()
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        assertEquals(listOf<Any?>(true), answers)

        launch { ask.confirm("Again?") }
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertEquals(listOf<Any?>(true, false), answers)
    }

    @Test fun theCloseButtonAnswersNo() {
        host()
        launch { ask.confirm("Keep this version?") }
        compose.onNodeWithContentDescription("Close").performClick()
        compose.settle()
        assertEquals(listOf<Any?>(false), answers)
    }

    @Test fun promptReturnsTrimmedTextAndIgnoresAnEmptyConfirm() {
        host()
        launch { ask.prompt("Name the tag", confirmLabel = "Add") }
        compose.onNodeWithTag("ask-confirm").performClick() // empty: stays open
        compose.waitForIdle()
        assertEquals(emptyList<Any?>(), answers)
        compose.onNodeWithTag("ask-input").performTextInput("  Book club  ")
        compose.onNodeWithTag("ask-confirm").performClick()
        compose.waitForIdle()
        assertEquals(listOf<Any?>("Book club"), answers)
    }

    @Test fun aSecondQuestionReplacesTheFirstWhichAnswersNo() {
        host()
        launch { ask.prompt("First", value = "x") }
        compose.waitForIdle()
        launch { ask.confirm("Second") }
        compose.waitForIdle()
        assertEquals(listOf<Any?>(null), answers)
        compose.onNodeWithText("Second").assertExists()
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        assertEquals(listOf<Any?>(null, true), answers)
        assertNull(ask.request)
        compose.onNodeWithText("Second").assertDoesNotExist()
    }
}
