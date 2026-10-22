package com.hazia.forgetmenot.ui.screens.detail

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.hazia.forgetmenot.domain.Contact
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.domain.FriendDate
import com.hazia.forgetmenot.domain.Gift
import com.hazia.forgetmenot.domain.Note
import com.hazia.forgetmenot.domain.Tag
import com.hazia.forgetmenot.domain.nextCheckIn
import com.hazia.forgetmenot.ui.screens.home.AfterMessage
import com.hazia.forgetmenot.ui.sheets.RecentChanges
import com.hazia.forgetmenot.ui.sheets.TestEnv
import com.hazia.forgetmenot.ui.sheets.Toaster
import com.hazia.forgetmenot.ui.sheets.setThemed
import com.hazia.forgetmenot.ui.sheets.settle
import com.hazia.forgetmenot.ui.sheets.toastText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

// SPEC §1 Screens, `detail` (renderDetail(), www/js/screens/detail.js): one friend, every row,
// and what each tap writes. Saves go through Records, so TestEnv sees the reschedule (P3) and
// the folder sees the file (P2); in a read-only session it sees neither (I6).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h4000dp-xhdpi")
class DetailScreenTest {
    @get:Rule val compose = createComposeRule()
    private val env = TestEnv()
    private val records = env.records
    private val zone: ZoneId = ZoneId.systemDefault()
    private val now = ZonedDateTime.of(2026, 10, 19, 10, 0, 0, 0, zone)

    private var backs = 0
    private var edits: String? = null
    private var left = 0
    private var archivedLeft = 0

    // The Message button hands off (AfterMessage.handedOff); nothing pending carries across tests.
    @Before fun resetAfterMessage() { AfterMessage.reset() }

    private fun seed(f: Friend) {
        records.saveFriend(f)
        // saveFriend only queues the file write; wait for it so a seeded friend is on disk
        // and can't land later, mid-test (e.g. between a test's disk snapshot and blockWrites()).
        kotlinx.coroutines.runBlocking { env.store.flushWrites() }
        env.reschedules = 0
        RecentChanges.reset()
        Toaster.reset()
    }

    private fun show(
        id: String = "ana",
        allowArchived: Boolean = false,
        integrations: DetailIntegrations = NoDetailIntegrations,
    ) = compose.setThemed {
        CompositionLocalProvider(LocalDetailIntegrations provides integrations) {
            DetailScreen(
                records = records,
                friendId = id,
                allowArchived = allowArchived,
                onBack = { backs++ },
                onEdit = { edits = it },
                onLeave = { left++ },
                onArchived = { archivedLeft++ },
                now = { now },
            )
        }
    }

    private fun friend() = records.getFriend("ana")!!
    private fun onDisk(id: String = "ana") = env.fs.disk["friends/$id.json"]
    private fun tapUndo() = Toaster.current.value!!.action!!.invoke()
    private fun texts(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        .map { it.config[SemanticsProperties.Text].joinToString() }

    private val ana = Friend(id = "ana", name = "Ana María", createdAt = 1_760_000_000_000)

    // ── header ───────────────────────────────────────────────────────────────

    @Test fun headerShowsNameAsFocusedHeadingLastInTouchAndTags() {
        records.saveTag(Tag(id = "t1", name = "Climbing"))
        records.saveTag(Tag(id = "t2", name = "Work"))
        seed(ana.copy(tags = listOf("t2", "gone", "t1"), contacts = listOf(Contact("c1", "2026-10-17T10:00:00.000Z", "text"))))
        show()
        compose.onNodeWithTag(DetailTags.NAME).assertTextEquals("Ana María")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertIsFocused()
        compose.onNodeWithTag(DetailTags.LAST, useUnmergedTree = true).assertTextEquals("Last in touch 2 days ago, by message")
        assertEquals(listOf("Work", "Climbing"), texts(DetailTags.TAG))
    }

    @Test fun noTagsAndNoReachOutsSayHowItIs() {
        seed(ana)
        show()
        compose.onNodeWithText("No tags yet").assertIsDisplayed()
        compose.onNodeWithText("No reach-outs logged yet").assertIsDisplayed()
        compose.onNodeWithText("Nothing logged yet.").assertIsDisplayed()
    }

    @Test fun avatarRingFollowsClassifyProgress() {
        seed(ana.copy(cadenceDays = 10, contacts = listOf(Contact("c1", "2026-10-14T10:00:00.000Z"))))
        show()
        compose.onNodeWithTag(DetailTags.AVATAR, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(AvatarProgress, 0.5f))
    }

    @Test fun backAndEditCallTheirRoutes() {
        seed(ana)
        show()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Edit friend").performClick()
        assertEquals(1, backs)
        assertEquals("ana", edits)
    }

    // ── which friends it shows ───────────────────────────────────────────────

    @Test fun unknownIdLeaves() {
        show(id = "nobody")
        compose.waitForIdle()
        assertEquals(1, left)
    }

    @Test fun archivedIdLeavesUnlessReachedFromAGroup() {
        seed(ana.copy(archived = true))
        show(allowArchived = false)
        compose.waitForIdle()
        assertEquals(1, left)
    }

    @Test fun archivedFriendOpensFromAGroupMember() {
        seed(ana.copy(archived = true))
        show(allowArchived = true)
        compose.waitForIdle()
        assertEquals(0, left)
        compose.onNodeWithTag(DetailTags.NAME).assertTextEquals("Ana María")
    }

    @Test fun friendDeletedWhileOpenLeaves() {
        seed(ana)
        show()
        compose.runOnIdle { records.deleteFriend("ana") }
        compose.waitForIdle()
        assertEquals(1, left)
    }

    // ── logging ──────────────────────────────────────────────────────────────

    @Test fun logTodayAddsAReachOutSavesAndUndoes() {
        seed(ana)
        show()
        compose.onNodeWithText("Log today").performClick()
        assertEquals(1, friend().contacts.size)
        assertEquals("Logged — nice.", toastText())
        assertEquals("Logged Ana María", RecentChanges.changes.value.first().label)
        kotlinx.coroutines.runBlocking { env.store.flushWrites() }
        assertTrue(onDisk()!!.contains(friend().contacts.first().id))
        assertEquals(1, env.reschedules)
        compose.onNodeWithText("Last in touch today").assertIsDisplayed()
        compose.runOnIdle { tapUndo() }
        assertTrue(friend().contacts.isEmpty())
    }

    @Test fun earlierOpensTheLogDateSheet() {
        seed(ana)
        show()
        compose.onNodeWithText("Earlier…").performClick()
        compose.onNodeWithText("Log when you reached out").assertIsDisplayed()
        compose.onNodeWithText("Yesterday").performClick()
        compose.settle()
        val logged = com.hazia.forgetmenot.domain.parseInstant(friend().contacts.single().date)!!
        assertEquals("2026-10-18", logged.atZone(zone).toLocalDate().toString())
    }

    // ── ask about / how we met ───────────────────────────────────────────────

    @Test fun askAboutSavesThroughThePrompt() {
        seed(ana)
        show()
        compose.onNodeWithText("Nothing yet. Tap to add.").assertIsDisplayed()
        compose.onNodeWithTag(DetailTags.ASK_ROW).performClick()
        compose.onNode(hasText("How the move went, the new job"), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("ask-input").performTextInput("the new flat")
        compose.onNodeWithTag("ask-confirm").performClick()
        compose.settle()
        assertEquals("the new flat", friend().askNext)
        compose.onNodeWithText("the new flat").assertIsDisplayed()
    }

    @Test fun clearItRemovesWhatToAskAndUndoes() {
        seed(ana.copy(askNext = "the new job", howWeMet = "Climbing gym"))
        show()
        compose.onNodeWithText("How you met").assertIsDisplayed()
        compose.onNodeWithText("Climbing gym").assertIsDisplayed()
        compose.onNodeWithText("Clear it").performClick()
        assertNull(friend().askNext)
        assertEquals("Cleared", toastText())
        assertEquals("Cleared what to ask Ana María", RecentChanges.changes.value.first().label)
        compose.runOnIdle { tapUndo() }
        assertEquals("the new job", friend().askNext)
    }

    // ── dates ────────────────────────────────────────────────────────────────

    @Test fun birthdayAnniversaryAndOwnDatesShowTheirNextDay() {
        seed(ana.copy(
            birthday = "1990-11-02", anniversary = "2015-06-01",
            dates = listOf(FriendDate("d1", "Interview", "2026-12-01"), FriendDate("d2", "Name day", "2000-11-20", yearly = true)),
        ))
        show()
        compose.onNodeWithText("Birthday").assertIsDisplayed()
        compose.onNodeWithText("Anniversary").assertIsDisplayed()
        assertEquals(listOf("Name day", "Interview"), texts(DetailTags.DATE_LABEL))
        compose.onNodeWithText(com.hazia.forgetmenot.ui.screens.groups.fmtEventDate(now.withMonth(11).withDayOfMonth(20)) + " · yearly").assertIsDisplayed()
        compose.onAllNodesWithText("Mark as wished").assertCountEquals(0)
    }

    @Test fun markAsWishedShowsOnTheDayAndLogs() {
        seed(ana.copy(birthday = "1990-10-19"))
        show()
        compose.onNodeWithText("It’s today").assertIsDisplayed()
        compose.onNodeWithText("Mark as wished").performClick()
        assertEquals(1, friend().contacts.size)
        assertEquals("Logged — nice.", toastText())
    }

    @Test fun removingADateUndoes() {
        seed(ana.copy(dates = listOf(FriendDate("d1", "Interview", "2026-12-01"))))
        show()
        compose.onNodeWithContentDescription("Remove Interview").performClick()
        assertTrue(friend().dates.isEmpty())
        assertEquals("Removed “Interview”", toastText())
        assertEquals("Removed “Interview” for Ana María", RecentChanges.changes.value.first().label)
        compose.runOnIdle { tapUndo() }
        assertEquals("d1", friend().dates.single().id)
    }

    @Test fun addADateOpensTheDateSheet() {
        seed(ana)
        show()
        compose.onNodeWithText("Add a date").performClick()
        compose.onNodeWithTag("date-label").performTextInput("Move")
        compose.onNodeWithText("Save").performClick()
        compose.settle()
        assertEquals("Move", friend().dates.single().label)
    }

    // ── gifts ────────────────────────────────────────────────────────────────

    @Test fun giftIdeasAddAndRemoveWithUndo() {
        seed(ana.copy(gifts = listOf(Gift("g1", "Socks", "2026-10-01T10:00:00.000Z"))))
        show()
        compose.onNodeWithText("Socks").assertIsDisplayed()
        compose.onNodeWithText("Add a gift idea").performClick()
        compose.onNodeWithText("Gift idea for Ana").assertExists()
        compose.onNodeWithTag("ask-input").performTextInput("The book")
        compose.onNodeWithTag("ask-confirm").performClick()
        compose.settle()
        assertEquals(listOf("Socks", "The book"), friend().gifts.map { it.text })
        assertEquals("Gift idea saved", toastText())
        assertEquals("Gift idea for Ana María", RecentChanges.changes.value.first().label)
        compose.runOnIdle { tapUndo() }
        assertEquals(listOf("Socks"), friend().gifts.map { it.text })

        compose.onNodeWithContentDescription("Remove the gift idea Socks").performClick()
        assertTrue(friend().gifts.isEmpty())
        assertEquals("Removed “Socks”", toastText())
        compose.runOnIdle { tapUndo() }
        assertEquals("g1", friend().gifts.single().id)
    }

    // ── cadence, pause, snooze ───────────────────────────────────────────────

    @Test fun cadenceRowShowsLabelAndNextNudgeAndSavesAtOnce() {
        val f = ana.copy(cadenceDays = 30, contacts = listOf(Contact("c1", "2026-10-10T10:00:00.000Z")))
        seed(f)
        show()
        compose.onNodeWithTag(DetailTags.CADENCE_ROW).assertTextEqualsContains("1 month")
        compose.onNodeWithText("Next nudge around " + shortDate(nextCheckIn(f, now)!!.toInstant().toString(), now)).assertIsDisplayed()
        compose.onNodeWithTag(DetailTags.CADENCE_ROW).performClick()
        compose.onNodeWithText("Saved as soon as you pick.").assertIsDisplayed()
        compose.onNodeWithText("2 weeks").performClick()
        compose.settle()
        assertEquals(14, friend().cadenceDays)
        assertEquals(1, env.reschedules)
    }

    @Test fun pauseSwitchTogglesFromTheWholeRowAndHidesPauseUntilAndNextNudge() {
        seed(ana.copy(contacts = listOf(Contact("c1", "2026-10-10T10:00:00.000Z"))))
        show()
        compose.onNodeWithText("Pause reminders for Ana").assertIsDisplayed()
        compose.onNodeWithTag(DetailTags.PAUSE_ROW).assertIsOff()
        compose.onNodeWithText("Or pause until").assertIsDisplayed()
        compose.onNodeWithTag(DetailTags.PAUSE_ROW).performClick()
        assertTrue(friend().paused)
        compose.onNodeWithTag(DetailTags.PAUSE_ROW).assertIsOn()
        compose.onAllNodesWithText("Or pause until").assertCountEquals(0)
        compose.onAllNodesWithText("Next nudge around", substring = true).assertCountEquals(0)
        assertEquals(1, env.reschedules)
    }

    @Test fun pausedUntilShowsWithClear() {
        seed(ana.copy(pausedUntil = "2026-11-01"))
        show()
        compose.onNodeWithText("Paused until").assertIsDisplayed()
        compose.onNodeWithText("Clear").performClick()
        assertNull(friend().pausedUntil)
        compose.onNodeWithText("Or pause until").assertIsDisplayed()
    }

    @Test fun pauseUntilAcceptsOnlyADayAfterToday() {
        seed(ana)
        show()
        compose.runOnIdle {
            setPausedUntil(records, "ana", "2026-10-19", now)
        }
        assertNull(friend().pausedUntil)
        compose.runOnIdle { setPausedUntil(records, "ana", "2026-10-25", now) }
        assertEquals("2026-10-25", friend().pausedUntil)
        compose.onNodeWithText("Paused until").assertIsDisplayed()
    }

    @Test fun pastPausedUntilReadsAsNotPaused() {
        seed(ana.copy(pausedUntil = "2026-10-01"))
        show()
        compose.onNodeWithText("Or pause until").assertIsDisplayed()
    }

    @Test fun snoozeRowShowsUntilClearedAndHidesWhilePaused() {
        seed(ana.copy(snoozedUntil = "2026-10-26"))
        show()
        val line = "Next nudge after " + shortDate("2026-10-26T12:00:00", now)
        compose.onNodeWithText(line).assertIsDisplayed()
        compose.onNodeWithTag(DetailTags.SNOOZE_CLEAR).performClick()
        assertNull(friend().snoozedUntil)
        compose.onAllNodesWithText(line).assertCountEquals(0)
    }

    @Test fun snoozeRowHiddenWhilePausedOrPast() {
        seed(ana.copy(snoozedUntil = "2026-10-26", paused = true))
        show()
        compose.onAllNodesWithText("Next nudge after", substring = true).assertCountEquals(0)
    }

    // ── reach-out history ────────────────────────────────────────────────────

    @Test fun historyNewestFirstCappedAtEightWithKindsAndHow() {
        val contacts = (1..10).map { Contact("c$it", "2026-09-%02dT10:00:00.000Z".format(it), if (it == 10) "call" else null) }
        seed(ana.copy(contacts = contacts))
        show()
        assertEquals(8, texts(DetailTags.HISTORY_DATE).size)
        assertTrue(texts(DetailTags.HISTORY_DATE).first().startsWith(shortDate("2026-09-10T10:00:00.000Z", now)))
        compose.onNodeWithText("and 2 earlier").assertIsDisplayed()
        compose.onNodeWithContentDescription("Call on ${shortDate("2026-09-10T10:00:00.000Z", now)}. Change").assertIsDisplayed()
        compose.onNodeWithContentDescription("Say how you reached out on ${shortDate("2026-09-09T10:00:00.000Z", now)}").performClick()
        compose.onNodeWithText("How did you reach out?").assertIsDisplayed()
        compose.onNodeWithText("Met up").performClick()
        compose.settle()
        assertEquals("meet", friend().contacts.first { it.id == "c9" }.kind)
    }

    @Test fun removingAReachOutUndoes() {
        seed(ana.copy(contacts = listOf(Contact("c1", "2026-10-10T10:00:00.000Z"))))
        show()
        val day = shortDate("2026-10-10T10:00:00.000Z", now)
        compose.onNodeWithContentDescription("Remove the reach-out on $day").performClick()
        assertTrue(friend().contacts.isEmpty())
        assertEquals("Removed the reach-out on $day", toastText())
        assertEquals("Removed a reach-out with Ana María", RecentChanges.changes.value.first().label)
        compose.runOnIdle { tapUndo() }
        assertEquals("c1", friend().contacts.single().id)
    }

    // ── notes ────────────────────────────────────────────────────────────────

    @Test fun noteFiltersDefaultToAllTimeAndNarrow() {
        seed(ana.copy(notes = listOf(
            Note("n1", "2026-10-02T10:00:00.000Z", "October"),
            Note("n2", "2026-03-02T10:00:00.000Z", "March"),
            Note("n3", "2025-03-02T10:00:00.000Z", "Last year"),
        )))
        show()
        assertEquals(listOf("October", "March", "Last year"), texts(DetailTags.NOTE_TEXT))
        compose.onNodeWithText("All time").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.onNodeWithText("This year").performClick()
        assertEquals(listOf("October", "March"), texts(DetailTags.NOTE_TEXT))
        compose.onNodeWithText("This month").performClick()
        assertEquals(listOf("October"), texts(DetailTags.NOTE_TEXT))
        compose.onNodeWithText("This month").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
    }

    @Test fun emptyNoteRangeSaysSo() {
        seed(ana.copy(notes = listOf(Note("n3", "2025-03-02T10:00:00.000Z", "Last year"))))
        show()
        compose.onNodeWithText("This month").performClick()
        compose.onNodeWithText("No notes in this range yet.").assertIsDisplayed()
    }

    @Test fun addNoteOpensTheNoteSheet() {
        seed(ana)
        show()
        compose.onNodeWithContentDescription("Add note").performClick()
        compose.onNodeWithTag("note-text").performTextInput("Loves figs")
        compose.onNodeWithText("Save note").performClick()
        compose.settle()
        assertEquals("Loves figs", friend().notes.single().text)
    }

    // ── archive ──────────────────────────────────────────────────────────────

    @Test fun archiveActsAtOnceGoesHomeAndUndoes() {
        seed(ana)
        show()
        compose.onNodeWithText("Archive Ana María").performScrollTo().performClick()
        assertTrue(friend().archived)
        assertEquals(1, archivedLeft)
        assertEquals(0, left)
        assertEquals("Archived Ana María", toastText())
        compose.runOnIdle { tapUndo() }
        assertFalse(friend().archived)
    }

    // ── Message / Plan something / Share (P6) ────────────────────────────────

    @Test fun integrationsRowIsHiddenByDefault() {
        seed(ana)
        show()
        compose.onAllNodesWithTag(DetailTags.INTEGRATIONS).assertCountEquals(0)
        compose.onAllNodesWithText("Plan something").assertCountEquals(0)
    }

    @Test fun integrationsRowCallsTheProvidedActionsWithTheChannelLabel() {
        val calls = mutableListOf<String>()
        val fake = object : DetailIntegrations {
            override val available = true
            override fun message(context: Context, friend: Friend): Boolean { calls += "message:" + friend.id; return true }
            override fun plan(context: Context, friend: Friend) { calls += "plan:" + friend.id }
            override fun share(context: Context, friend: Friend) { calls += "share:" + friend.id }
        }
        seed(ana.copy(messageChannel = "signal"))
        show(integrations = fake)
        compose.onNodeWithText("Signal").performClick()
        compose.onNodeWithText("Plan something").performClick()
        compose.onNodeWithText("Share").performClick()
        assertEquals(listOf("message:ana", "plan:ana", "share:ana"), calls)
    }

    // ── After Message (SPEC §4): back from the messaging app, "Talked to Ana?" ──

    /** The screen's lifecycle, standing in for the back stack entry's, so a test can leave and come back. */
    private class Entry : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        fun leaveAndReturn() {
            registry.currentState = Lifecycle.State.STARTED // the messaging app on top
            registry.currentState = Lifecycle.State.RESUMED
        }
    }

    /** False: the integration toasted (no phone or email, no app) instead of opening anything. */
    private var opens = true

    private val messaging = object : DetailIntegrations {
        override val available = true
        override fun message(context: Context, friend: Friend) = opens
        override fun plan(context: Context, friend: Friend) {}
        override fun share(context: Context, friend: Friend) {}
    }

    private fun showWith(entry: Entry) = compose.setThemed {
        CompositionLocalProvider(LocalDetailIntegrations provides messaging, LocalLifecycleOwner provides entry) {
            DetailScreen(records, "ana", false, { backs++ }, { edits = it }, { left++ }, { archivedLeft++ }, now = { now })
        }
    }

    private fun returnTo(entry: Entry) {
        compose.runOnIdle { entry.leaveAndReturn() }
        compose.waitForIdle()
    }

    @Test fun messageThenReturnOffersToLogItOnceAndLogItLogsAText() {
        seed(ana)
        val entry = Entry()
        showWith(entry)
        assertNull("nothing handed off yet", toastText())
        compose.onNodeWithText("Message").performClick()
        returnTo(entry)
        assertEquals("Talked to Ana?", toastText())

        compose.runOnIdle { Toaster.current.value!!.action!!.invoke() } // Log it
        assertEquals(listOf("text"), friend().contacts.map { it.kind })
        Toaster.reset()
        returnTo(entry)
        assertNull("asked once", toastText())
    }

    @Test fun loggedBeforeReturningIsNotAsked() {
        seed(ana)
        val entry = Entry()
        showWith(entry)
        compose.onNodeWithText("Message").performClick()
        assertEquals("handed off", "ana", AfterMessage.pending?.friendId) // else "no toast" below proves nothing
        compose.runOnIdle { com.hazia.forgetmenot.ui.sheets.logToday(records, "ana") } // logged on a widget, say
        Toaster.reset()
        returnTo(entry)
        assertNull(toastText())
    }

    @Test fun messageThatOpensNothingIsNotAsked() {
        seed(ana.copy(phone = null, email = ""))
        opens = false
        val entry = Entry()
        showWith(entry)
        compose.onNodeWithText("Message").performClick()
        assertNull("not handed off", AfterMessage.pending)
        returnTo(entry)
        assertNull(toastText())
    }

    @Test fun returningRereadsTheClockSoDayCountsAreNotStale() {
        seed(ana.copy(contacts = listOf(Contact("c1", "2026-10-17T10:00:00.000Z", "text"))))
        val entry = Entry()
        var clock = now
        compose.setThemed {
            CompositionLocalProvider(LocalLifecycleOwner provides entry) {
                DetailScreen(records, "ana", false, { backs++ }, { edits = it }, { left++ }, { archivedLeft++ }, now = { clock })
            }
        }
        val last = compose.onNodeWithTag(DetailTags.LAST, useUnmergedTree = true)
        last.assertTextEquals("Last in touch 2 days ago, by message")
        clock = now.plusDays(3) // left in the background over a weekend
        returnTo(entry)
        last.assertTextEquals("Last in touch 5 days ago, by message")
    }

    // ── the app-wide toast over the screen (MainActivity stacks them this way) ──

    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    @Test fun undoOnTheAppWideToastTakesTheTapNotTheRowBeneath() {
        seed(ana.copy(gifts = listOf(Gift("g1", "Socks", "2026-10-01T10:00:00.000Z"))))
        compose.setThemed {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                DetailScreen(records, "ana", false, { backs++ }, { edits = it }, { left++ }, { archivedLeft++ }, now = { now })
                com.hazia.forgetmenot.ui.sheets.ToastHost(androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
            }
        }
        compose.onNodeWithContentDescription("Remove the gift idea Socks").performScrollTo().performClick()
        compose.onNodeWithText("Undo").performClick()
        assertEquals("g1", friend().gifts.single().id)
        assertEquals("Undone: Removed a gift idea for Ana María", toastText())
    }

    // ── read-only session (I6) ───────────────────────────────────────────────

    // Guards seed(): with a slow folder (as on CI), a seed write left queued would land after a
    // test's disk snapshot and fail the read-only test below at random.
    @Test fun seedLeavesTheFriendOnDiskEvenWhenTheFolderIsSlow() {
        env.fs.beforeWrite = { Thread.sleep(200) }
        seed(ana)
        assertTrue(onDisk()!!.contains(ana.name))
    }

    @Test fun readOnlySessionWritesNothingAndReschedulesNothing() {
        seed(ana.copy(contacts = listOf(Contact("c1", "2026-10-10T10:00:00.000Z"))))
        val before = onDisk()
        env.store.blockWrites()
        show()
        compose.onNodeWithText("Log today").performClick()
        compose.onNodeWithTag(DetailTags.PAUSE_ROW).performClick()
        compose.onNodeWithText("Archive Ana María").performScrollTo().performClick()
        kotlinx.coroutines.runBlocking { env.store.flushWrites() }
        assertEquals(before, onDisk())
        assertEquals(0, env.reschedules)
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertTextEqualsContains(text: String) =
    assert(SemanticsMatcher("contains text $text") { node ->
        val c = node.config
        (SemanticsProperties.Text in c && c[SemanticsProperties.Text].any { it.text.contains(text) }) ||
            (SemanticsProperties.ContentDescription in c && c[SemanticsProperties.ContentDescription].any { it.contains(text) })
    })
