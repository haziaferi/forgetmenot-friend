package com.hazia.forgetmenot.ui.screens.add

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.ui.AddRoute
import com.hazia.forgetmenot.ui.FriendRoute
import com.hazia.forgetmenot.ui.HomeRoute
import com.hazia.forgetmenot.ui.sheets.TestEnv
import com.hazia.forgetmenot.ui.theme.ForgetmenotTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Where add leaves to (BACK_TO in www/js/main.js:318, goTo() calls in add.js): a new friend
// goes home, an edit back to its friend, Cancel and back to wherever add was opened from.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h900dp-xhdpi")
class AddDestinationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val env = TestEnv()
    private lateinit var nav: NavHostController

    @After fun clearDraft() = AddDrafts.reset()

    /** A graph like AppNavHost's, with stand-ins for home and detail. */
    private fun host() = compose.setContent {
        ForgetmenotTheme {
            nav = rememberNavController()
            NavHost(nav, startDestination = HomeRoute) {
                composable<HomeRoute> { Text("home screen") }
                composable<FriendRoute> { e -> Text("friend " + e.toRoute<FriendRoute>().id) }
                composable<AddRoute> { e -> AddDestination(nav, e, env.records) }
            }
        }
    }

    private fun go(vararg routes: Any) {
        compose.runOnUiThread { routes.forEach { nav.navigate(it) } }
        compose.waitForIdle()
    }

    private fun stack(): List<String> = nav.currentBackStack.value.mapNotNull { e ->
        val d = e.destination
        when {
            d.hasRoute<HomeRoute>() -> "home"
            d.hasRoute<FriendRoute>() -> "friend"
            d.hasRoute<AddRoute>() -> "add"
            else -> null
        }
    }

    private fun seed() = env.records.saveFriend(Friend(id = "x1", name = "Flo"))

    @Test fun aNewFriendIsSavedToHome() {
        host()
        go(AddRoute())
        compose.onNodeWithTag(AddTags.NAME).performTextInput("Ana")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertEquals(listOf("home"), stack())
        compose.onNodeWithText("home screen").assertExists()
    }

    @Test fun anEditReturnsToItsFriend() {
        seed()
        host()
        go(FriendRoute("x1"), AddRoute("x1"))
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertEquals(listOf("home", "friend"), stack())
        compose.onNodeWithText("friend x1").assertExists()
    }

    @Test fun cancelGoesBackToWhereAddWasOpened() {
        seed()
        host()
        go(FriendRoute("x1"), AddRoute("x1"))
        compose.onNodeWithContentDescription("Cancel").performClick()
        compose.waitForIdle()
        assertEquals(listOf("home", "friend"), stack())

        go(AddRoute())
        compose.onNodeWithContentDescription("Cancel").performClick()
        compose.waitForIdle()
        assertEquals(listOf("home", "friend"), stack())
    }

    @Test fun anUnknownFriendLeavesAtOnce() {
        host()
        go(AddRoute("nobody"))
        assertEquals(listOf("home"), stack())
    }

    @Test fun aDoubleTapDuringTheExitSavesOnceAndPopsOnce() {
        seed()
        host()
        go(FriendRoute("x1"), AddRoute())
        compose.onNodeWithTag(AddTags.NAME).performTextInput("Ana")
        // Hold the clock so the add screen is still on screen, animating out, for the second tap.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Save").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Save").performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(2, env.records.listFriends().size) // Flo and one Ana
        assertTrue(env.records.listFriends().count { it.name == "Ana" } == 1)
        assertEquals(listOf("home"), stack())
    }
}
