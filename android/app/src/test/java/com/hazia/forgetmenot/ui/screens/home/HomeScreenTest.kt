package com.hazia.forgetmenot.ui.screens.home

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.hazia.forgetmenot.data.FolderError
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.domain.Tag
import com.hazia.forgetmenot.ui.screens.settings.FakePlatform
import com.hazia.forgetmenot.ui.screens.settings.NotifStatus
import com.hazia.forgetmenot.ui.sheets.TestEnv
import com.hazia.forgetmenot.ui.sheets.setThemed
import com.hazia.forgetmenot.ui.sheets.settle
import com.hazia.forgetmenot.ui.sheets.toastText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** SPEC §1 "Screens", home: every home behaviour, driven through the screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val env = TestEnv()
    private val r = env.records
    private val platform = FakePlatform()
    private val went = mutableListOf<String>()
    private var adopted = 0

    @Before fun reset() { HomeSession.reset(); AfterMessage.reset() }

    private fun deps(onboarding: Boolean = false, folderGranted: (suspend () -> Boolean)? = null) = HomeDeps(
        env.records, env.session, platform,
        pickFolder = ActivityResultContracts.OpenDocumentTree(),
        adoptPick = { adopted++ },
        folderGranted = folderGranted ?: { env.store.fsStatus().granted },
        now = { NOW },
        runOnboarding = onboarding,
    )

    private val nav = HomeNav(
        openFriend = { went += "friend:$it" },
        add = { went += "add" },
        settings = { went += "settings" },
        groups = { went += "groups" },
    )

    private fun show(onboarding: Boolean = false) {
        val d = deps(onboarding)
        compose.setThemed { HomeScreen(d, nav) }
        compose.waitForIdle()
    }

    /** Home that a test can take away and bring back, as a rotation or a trip to another screen does. */
    private var homeVisible by mutableStateOf(true)

    private fun showRebuildable(d: HomeDeps) {
        compose.setThemed { if (homeVisible) HomeScreen(d, nav) }
        compose.waitForIdle()
    }

    private fun rebuildHome() {
        homeVisible = false
        compose.waitForIdle()
        homeVisible = true
        compose.waitForIdle()
    }

    private fun seedThree() {
        r.saveFriend(friend("a", "Ana", 40)) // Been a while
        r.saveFriend(friend("b", "Bo", 20)) // In between
        r.saveFriend(friend("c", "Cy", 1)) // Recently in touch
    }

    private fun waitForText(text: String) =
        compose.waitUntil(5000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun startedIntent(): Intent? = shadowOf(compose.activity).nextStartedActivity

    @Test fun headingSectionsAndRows() {
        seedThree()
        show()
        compose.onNodeWithText("forgetmenot").assertIsFocused()
        compose.onNodeWithText("Been a while").assertExists()
        compose.onNodeWithText("In between").assertExists()
        compose.onNodeWithText("Recently in touch").assertExists()
        compose.onNodeWithText("Ana").assertExists()
        compose.onNodeWithText("1m").assertExists() // Ana, 40 days: formatCode
        compose.onNodeWithText("20d").assertExists()
        compose.onNodeWithContentDescription("Log that you reached out to Bo (hold to pick a date)").assertExists()
    }

    @Test fun emptyHomeSaysSo() {
        show()
        compose.onNodeWithText("Nobody here yet").assertExists()
        compose.onNodeWithText("Add someone you’d like to stay close to.").assertExists()
    }

    @Test fun searchFiltersByFoldedNameNoteAndTag() {
        r.saveTag(Tag(id = "fam", name = "Family", cadenceDays = 30))
        r.saveFriend(friend("z", "Zoë", 3))
        r.saveFriend(friend("t", "Tim", 3, tags = listOf("fam")))
        r.saveFriend(friend("b", "Bo", 3, notes = listOf("met at the café")))
        show()
        compose.onNodeWithTag("search").performTextInput("zoe")
        compose.onNodeWithText("Zoë").assertExists()
        compose.onNodeWithText("Tim").assertDoesNotExist()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithTag("search").performTextInput("FAMILY")
        compose.onNodeWithText("Tim").assertExists()
        compose.onNodeWithText("Zoë").assertDoesNotExist()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithTag("search").performTextInput("cafe")
        compose.onNodeWithText("Bo").assertExists()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithTag("search").performTextInput("nobody")
        compose.onNodeWithText("No one matches “nobody”.").assertExists()
    }

    @Test fun rowOpensTheFriendAndHeaderButtonsNavigate() {
        seedThree()
        show()
        compose.onNodeWithText("Bo").performClick()
        compose.onNodeWithContentDescription("Add friend").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithContentDescription("Groups").performClick()
        assertEquals(listOf("friend:b", "add", "settings", "groups"), went)
    }

    @Test fun logTapLogsTodayWithUndo() {
        seedThree()
        show()
        compose.onNodeWithTag("log-a").performClick()
        compose.waitForIdle()
        assertEquals(2, r.getFriend("a")!!.contacts.size)
        assertEquals("Logged — nice.", toastText())
        // Logged today: Ana moves to "Recently in touch" with code "today".
        compose.onNodeWithText("today").assertExists()
        assertTrue(went.isEmpty())
    }

    @Test fun logLongPressOpensTheDateSheetAndDoesNotLog() {
        seedThree()
        show()
        compose.onNodeWithTag("log-b").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Log when you reached out").assertExists()
        assertEquals(1, r.getFriend("b")!!.contacts.size)
        assertNull(toastText())
    }

    @Test fun smallerHomeShowsMaybeTodayThenEveryone() {
        seedThree()
        r.saveFriend(friend("d", "Di", 25)) // In between, sooner than Bo
        r.saveSettings(r.getSettings().copy(smallHome = true))
        show()
        compose.onNodeWithText("Maybe today").assertExists()
        compose.onNodeWithText("Ana").assertExists()
        compose.onNodeWithText("Di").assertExists()
        compose.onNodeWithText("Bo").assertExists()
        compose.onNodeWithText("Cy").assertDoesNotExist()
        compose.onNodeWithText("Show everyone (4)").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Been a while").assertExists()
        compose.onNodeWithTag("open-a").assertIsFocused()
        compose.onNodeWithTag("home-list").performScrollToNode(hasText("Cy"))
        compose.onNodeWithText("Cy").assertExists()
        // Toggling the setting (in Settings) starts the smaller home afresh.
        r.saveSettings(r.getSettings().copy(smallHome = false))
        compose.waitForIdle()
        r.saveSettings(r.getSettings().copy(smallHome = true))
        compose.waitForIdle()
        assertFalse(HomeSession.showAll)
        compose.onNodeWithTag("home-list").performScrollToNode(hasText("Maybe today"))
    }

    @Test fun smallerHomeWithNobodyDue() {
        r.saveFriend(friend("c", "Cy", 1))
        r.saveSettings(r.getSettings().copy(smallHome = true))
        show()
        compose.onNodeWithText("Everyone’s been in touch lately. Nothing to do today.").assertExists()
        compose.onNodeWithText("Show everyone (1)").assertExists()
    }

    @Test fun conflictBannerOpensTheConflictsSheet() {
        env.plantFriendConflict("a", "Ana", "Ana B")
        runBlocking { env.store.load(); env.session.runScan() }
        show()
        compose.onNodeWithText("1 sync conflict found — tap to resolve").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Sync conflicts").assertExists()
    }

    @Test fun noFolderBannerOpensThePicker() {
        env.fs.picked = false
        show()
        waitForText("Nothing is saved until you choose a folder")
        compose.onNodeWithTag("folder-banner").performClick()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, startedIntent()?.action)
    }

    // Found on a device: a vanished folder read as "no folder", with the choose-a-folder banner and onboarding.
    @Test fun readOnlySessionSaysTheFolderIsUnreadableAndSkipsOnboarding() {
        env.fs.rootGone = true
        env.store.blockWrites()
        platform.notif = NotifStatus.NOT_ENABLED
        show(onboarding = true)
        waitForText("Couldn’t read your sync folder")
        compose.onNodeWithTag("folder-banner").assertDoesNotExist()
        compose.onNodeWithText("Choose where to save your data").assertDoesNotExist()
        compose.onNodeWithText("Get a nudge when it’s time to reach out").assertDoesNotExist()
        assertFalse("left to run once the folder is back", HomeSession.onboardingRan)
        compose.onNodeWithTag("readonly-banner").performClick()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, startedIntent()?.action)
    }

    @Test fun noFolderBannerStaysAwayOnceAFolderIsSeen() {
        show()
        compose.waitForIdle()
        compose.onNodeWithTag("folder-banner").assertDoesNotExist()
        assertTrue(HomeSession.folderSeen)
    }

    @Test fun pullToRefreshReadsTheFolderAgain() {
        seedThree()
        runBlocking { env.store.flushWrites() }
        show()
        env.fs.disk["friends/zed.json"] = """{"id":"zed","name":"Zed","contacts":[]}"""
        env.fs.disk["friend_index.json"] = """["a","b","c","zed"]"""
        val pushes = env.pushes
        compose.onNodeWithTag("home-list").performTouchInput { swipeDown(startY = top, endY = bottom, durationMillis = 600) }
        compose.waitUntil(5000) { env.pushes > pushes }
        compose.onNodeWithTag("home-list").performScrollToNode(hasText("Zed"))
        compose.onNodeWithText("Zed").assertExists()
    }

    @Test fun pullToRefreshFailureToasts() {
        seedThree()
        runBlocking { env.store.flushWrites() }
        show()
        env.fs.readFileFaults["friend_index.json"] = FolderError.READ_FAILED
        env.fs.readdirFaults["friends"] = FolderError.READ_FAILED
        compose.onNodeWithTag("home-list").performTouchInput { swipeDown(startY = top, endY = bottom) }
        compose.waitUntil(5000) { toastText() == "Couldn’t read the sync folder — try again in a moment" }
    }

    @Test fun readOnlySessionNeitherRefreshesNorWrites() {
        seedThree()
        runBlocking { env.store.flushWrites() }
        env.store.blockWrites()
        env.reschedules = 0
        env.pushes = 0
        env.fs.calls.clear()
        show()
        env.fs.disk["friends/zed.json"] = """{"id":"zed","name":"Zed","contacts":[]}"""
        env.fs.disk["friend_index.json"] = """["a","b","c","zed"]"""
        compose.onNodeWithTag("home-list").performTouchInput { swipeDown(startY = top, endY = bottom) }
        compose.waitForIdle()
        compose.onNodeWithText("Zed").assertDoesNotExist()
        // The indicator goes back up even though the refresh returned at once.
        compose.mainClock.advanceTimeBy(1000)
        compose.waitUntil(5000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("refresh-indicator")).fetchSemanticsNodes().isEmpty() }
        // Log still answers (the cache changes, as in the JS), but nothing reaches the folder.
        compose.onNodeWithTag("log-a").performClick()
        runBlocking { env.store.flushWrites() }
        assertTrue(env.fs.calls.isEmpty())
        assertEquals(0, env.reschedules)
        assertEquals(0, env.pushes)
    }

    @Test fun onboardingAsksForTheFolderThenNotificationsOnce() {
        env.fs.picked = false
        platform.notif = NotifStatus.NOT_ENABLED
        show(onboarding = true)
        waitForText("Choose where to save your data")
        compose.onNodeWithText("Not now").performClick()
        compose.settle()
        compose.waitUntil(5000) { r.getSettings().onboarded["syncFolder"] == true }
        waitForText("Get a nudge when it’s time to reach out")
        compose.onNodeWithText("Not now").performClick()
        compose.settle()
        compose.waitUntil(5000) { r.getSettings().onboarded["notifications"] == true }
        compose.onNodeWithText("Get a nudge when it’s time to reach out").assertDoesNotExist()
        assertTrue(HomeSession.onboardingRan)
    }

    @Test fun onboardingFolderStepLaunchesThePicker() {
        env.fs.picked = false
        show(onboarding = true)
        waitForText("Choose where to save your data")
        compose.onNodeWithText("Choose folder").performClick()
        compose.waitForIdle()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, startedIntent()?.action)
        compose.onNodeWithText("Choose where to save your data").assertDoesNotExist()
        assertNull("marked seen when the picker returns", r.getSettings().onboarded["syncFolder"])
    }

    @Test fun leavingHomeBeforeTheQueueIsKnownStillAsksOnReturn() {
        val folderCheck = CompletableDeferred<Boolean>()
        showRebuildable(deps(onboarding = true, folderGranted = { folderCheck.await() }))
        homeVisible = false
        compose.waitForIdle()
        assertFalse("not marked as run while its status checks were cut short", HomeSession.onboardingRan)
        assertFalse("nor the folder as seen", HomeSession.folderSeen)
        folderCheck.complete(false)
        homeVisible = true
        waitForText("Choose where to save your data")
        assertTrue(HomeSession.onboardingRan)
        compose.onNodeWithTag("folder-banner").assertExists()
    }

    @Test fun aShownStepComesBackWhenHomeIsRebuilt() {
        env.fs.picked = false
        showRebuildable(deps(onboarding = true))
        waitForText("Choose where to save your data")
        rebuildHome()
        waitForText("Choose where to save your data")
        assertNull(r.getSettings().onboarded["syncFolder"])
    }

    @Test fun aStepInFlightIsMarkedSeenAfterHomeIsRebuilt() {
        env.fs.picked = false
        platform.notif = NotifStatus.NOT_ENABLED
        showRebuildable(deps(onboarding = true))
        waitForText("Choose where to save your data")
        compose.onNodeWithText("Choose folder").performClick()
        compose.waitForIdle()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, startedIntent()?.action)
        rebuildHome()
        compose.onNodeWithText("Choose where to save your data").assertDoesNotExist()
        // The picker's result reaches the rebuilt screen's launcher, which reports to the same queue.
        compose.runOnIdle { HomeSession.onboarding(r).primaryDone(OnboardingStep.SYNC_FOLDER) }
        compose.waitUntil(5000) { r.getSettings().onboarded["syncFolder"] == true }
        waitForText("Get a nudge when it’s time to reach out")
        compose.onNodeWithText("Choose where to save your data").assertDoesNotExist()
    }

    @Test fun onboardingSkipsWhatIsAlreadySatisfied() {
        platform.notif = NotifStatus.ON
        show(onboarding = true)
        compose.waitForIdle()
        compose.onNodeWithText("Choose where to save your data").assertDoesNotExist()
        compose.onNodeWithText("Get a nudge when it’s time to reach out").assertDoesNotExist()
        assertFalse(r.getSettings().onboarded.containsKey("syncFolder"))
    }

    @Test fun autoResolvedScanToastsOnce() {
        // Two copies with identical content settle without the user.
        env.fs.disk["friend_index.json"] = """["a"]"""
        env.fs.disk["friends/a.json"] = """{"id":"a","name":"Ana","contacts":[],"updatedAt":100}"""
        env.fs.disk["friends/a.sync-conflict-20261012-101500-ABC.json"] = """{"id":"a","name":"Ana","contacts":[],"updatedAt":100}"""
        runBlocking { env.store.load(); env.session.runScan() }
        val scan = env.session.lastScan.value!!
        show()
        if (scan.autoResolved > 0) {
            compose.waitUntil(5000) { toastText()?.contains("resolved automatically") == true }
        }
        assertTrue(HomeSession.toastedScan === scan)
    }

    @Test fun afterMessageOfferShowsOnResume() {
        r.saveFriend(friend("a", "Ana", 40))
        AfterMessage.handedOff("a")
        show()
        compose.waitForIdle()
        assertEquals("Talked to Ana?", toastText())
    }
}
