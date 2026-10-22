package com.hazia.forgetmenot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import com.hazia.forgetmenot.ui.theme.ForgetmenotTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SPEC §7: a screen change moves focus to the new screen's level-1 heading. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenHeadingTest {
    @get:Rule val compose = createComposeRule()

    private var screen by mutableStateOf("first")

    private fun show() = compose.setContent {
        ForgetmenotTheme {
            when (screen) {
                "first" -> Button(onClick = { screen = "second" }) { Text("Go") }
                else -> Column {
                    Text("Second screen", Modifier.screenHeading())
                    Button(onClick = { screen = "first" }) { Text("Back") }
                }
            }
        }
    }

    @Test fun theHeadingIsAHeading() {
        screen = "second"
        show()
        compose.onNodeWithText("Second screen")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test fun aScreenChangeMovesFocusToTheNewHeading() {
        show()
        compose.onNodeWithText("Go").requestFocus().assertIsFocused()
        compose.onNodeWithText("Go").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Second screen").assertIsFocused()
    }

    @Test fun comingBackToAScreenFocusesItsHeadingAgain() {
        screen = "second"
        show()
        compose.onNodeWithText("Second screen").assertIsFocused()
        compose.runOnIdle { screen = "first" }
        compose.onNodeWithText("Second screen").assertDoesNotExist()
        compose.runOnIdle { screen = "second" }
        compose.waitForIdle()
        compose.onNodeWithText("Second screen").assertIsFocused()
    }
}
