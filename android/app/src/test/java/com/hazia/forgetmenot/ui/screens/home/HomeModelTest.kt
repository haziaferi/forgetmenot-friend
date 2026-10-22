package com.hazia.forgetmenot.ui.screens.home

import com.hazia.forgetmenot.data.ScanResult
import com.hazia.forgetmenot.domain.Contact
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.domain.Note
import com.hazia.forgetmenot.domain.Section
import com.hazia.forgetmenot.domain.Settings
import com.hazia.forgetmenot.domain.isoTimestamp
import com.hazia.forgetmenot.ui.sheets.RecentChanges
import com.hazia.forgetmenot.ui.sheets.TestEnv
import com.hazia.forgetmenot.ui.sheets.Toaster
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import com.hazia.forgetmenot.ui.sheets.logToday

/** A fixed noon, so day counts never straddle midnight. */
val NOW: ZonedDateTime = ZonedDateTime.of(2026, 10, 19, 12, 0, 0, 0, ZoneId.systemDefault())

/** A friend last logged [daysAgo] days before [NOW] (null: never logged). */
fun friend(id: String, name: String, daysAgo: Int?, cadence: Int = 30, tags: List<String> = emptyList(), notes: List<String> = emptyList(), paused: Boolean = false) =
    Friend(
        id = id, name = name, cadenceDays = cadence, tags = tags, paused = paused,
        notes = notes.mapIndexed { i, t -> Note(id = "n$i", date = "2026-01-01T10:00:00.000Z", text = t) },
        contacts = if (daysAgo == null) emptyList() else listOf(Contact(id = "c-$id", date = isoTimestamp(NOW.minusDays(daysAgo.toLong()).toInstant()))),
    )

/** SPEC §1 "Screens" (home) and §4 "Classifying a friend": what renderHome() decides. */
class HomeModelTest {
    private val noTags: (String) -> String? = { null }

    @Before fun reset() { Toaster.reset(); RecentChanges.reset(); AfterMessage.reset(); HomeSession.reset() }

    private fun full(friends: List<Friend>, search: String = "", tags: (String) -> String? = noTags) =
        buildHome(friends, tags, search, smallHome = false, showAll = false, now = NOW) as HomeContent.Full

    @Test fun threeSectionsInFixedOrderAndEmptyOnesLeftOut() {
        val c = full(listOf(friend("a", "Recent", 1), friend("b", "Due", 40)))
        assertEquals(listOf("Been a while", "Recently in touch"), c.sections.map { it.title })
        assertEquals(listOf(Section.THINKING, Section.RECENT), c.sections.map { it.section })
    }

    @Test fun sectionsUseTheWidgetsOrder() {
        val c = full(
            listOf(
                friend("y", "Yearly400", 400, cadence = 365), // ratio 1.10
                friend("w", "WeeklyMonth", 30, cadence = 7), // ratio 4.3: first
                friend("c1", "Coming20of30", 20, cadence = 30), // 10 left
                friend("c2", "Coming50of60", 50, cadence = 60), // 10 left, stable after c1
                friend("c3", "Coming25of30", 25, cadence = 30), // 5 left: first
                friend("r1", "Recent1", 1, cadence = 30),
                friend("r2", "Recent5", 5, cadence = 30), // higher ratio first
                friend("n", "New", null),
            ),
        )
        val names = c.sections.associate { it.section to it.rows.map { r -> r.friend.name } }
        assertEquals(listOf("WeeklyMonth", "Yearly400"), names[Section.THINKING])
        assertEquals(listOf("Coming25of30", "Coming20of30", "Coming50of60"), names[Section.COMING])
        assertEquals(listOf("Recent5", "Recent1", "New"), names[Section.RECENT])
    }

    @Test fun pausedFriendIsRecentAndMarked() {
        val c = full(listOf(friend("p", "Paula", 400, paused = true)))
        val row = c.sections.single().rows.single()
        assertEquals(Section.RECENT, row.cl.section)
        assertTrue(row.paused)
    }

    @Test fun searchFoldsCaseAndAccentsOverNamesNotesAndTags() {
        val people = listOf(
            friend("z", "Zoë Smith", 3),
            friend("b", "Bo", 3, notes = listOf("Loves CAFÉ au lait")),
            friend("t", "Tim", 3, tags = listOf("fam")),
            friend("x", "Xavier", 3),
        )
        val tags: (String) -> String? = { if (it == "fam") "Fâmily" else null }
        assertEquals(listOf("Zoë Smith"), full(people, "  ZOE ", tags).sections.flatMap { it.rows }.map { it.friend.name })
        assertEquals(listOf("Bo"), full(people, "cafe", tags).sections.flatMap { it.rows }.map { it.friend.name })
        assertEquals(listOf("Tim"), full(people, "family", tags).sections.flatMap { it.rows }.map { it.friend.name })
        assertEquals(HomeContent.NoMatch("Quentin "), buildHome(people, tags, "Quentin ", false, false, NOW))
    }

    @Test fun nobodyAtAllIsTheEmptyState() {
        assertEquals(HomeContent.Empty, buildHome(emptyList(), noTags, "", false, false, NOW))
        assertEquals(HomeContent.Empty, buildHome(emptyList(), noTags, "x", true, false, NOW))
    }

    @Test fun smallerHomeShowsTheFirstThreeOfBeenAWhileThenInBetween() {
        val people = listOf(
            friend("a", "A", 100), friend("b", "B", 60), // thinking: A (3.3), B (2.0)
            friend("c", "C", 25), friend("d", "D", 20), // coming: C (5 left), D (10 left)
            friend("e", "E", 1),
        )
        val small = buildHome(people, noTags, "", smallHome = true, showAll = false, now = NOW) as HomeContent.Small
        assertEquals(listOf("A", "B", "C"), small.few.map { it.friend.name })
        assertEquals(5, small.total)
        // A search always looks through everyone; so does "Show everyone".
        assertTrue(buildHome(people, noTags, "e", smallHome = true, showAll = false, now = NOW) is HomeContent.Full)
        assertTrue(buildHome(people, noTags, "", smallHome = true, showAll = true, now = NOW) is HomeContent.Full)
    }

    @Test fun smallerHomeWithNobodyDueIsEmptyButCountsEveryone() {
        val small = buildHome(listOf(friend("e", "E", 1), friend("n", "N", null)), noTags, "", true, false, NOW) as HomeContent.Small
        assertTrue(small.few.isEmpty())
        assertEquals(2, small.total)
    }

    @Test fun logTodayLogsWithUndo() {
        val env = TestEnv()
        env.records.saveFriend(friend("a", "Ana", 40))
        logToday(env.records, "a", now = NOW.toInstant())
        val f = env.records.getFriend("a")!!
        assertEquals(2, f.contacts.size)
        assertEquals(isoTimestamp(NOW.toInstant()), f.contacts.first().date)
        assertEquals("Logged — nice.", Toaster.current.value?.text)
        assertEquals("Undo", Toaster.current.value?.actionLabel)
        assertEquals("Logged Ana", RecentChanges.changes.value.first().label)
        Toaster.current.value!!.action!!.invoke()
        assertEquals(1, env.records.getFriend("a")!!.contacts.size)
    }

    @Test fun logTodayOnAnUnknownIdDoesNothing() {
        val env = TestEnv()
        logToday(env.records, "nope")
        assertNull(Toaster.current.value)
    }

    @Test fun autoResolvedToastText() {
        assertNull(autoResolvedText(ScanResult(emptyList(), 0, 0, emptySet())))
        assertEquals("1 sync conflict resolved automatically", autoResolvedText(ScanResult(emptyList(), 1, 0, emptySet())))
        assertEquals(
            "2 sync conflicts resolved automatically; the older edits are kept beside their records",
            autoResolvedText(ScanResult(emptyList(), 2, 1, emptySet())),
        )
    }

    @Test fun afterMessageOffersOnceWithinTwelveHours() {
        val env = TestEnv()
        env.records.saveFriend(friend("a", "Ana Lima", 40))
        val at = NOW.toInstant().toEpochMilli()
        AfterMessage.handedOff("a", at)
        assertTrue(AfterMessage.offer(env.records, at + 3600_000))
        assertEquals("Talked to Ana?", Toaster.current.value?.text)
        assertEquals("Log it", Toaster.current.value?.actionLabel)
        Toaster.current.value!!.action!!.invoke()
        assertEquals("text", env.records.getFriend("a")!!.contacts.first().kind)
        assertFalse("asked once", AfterMessage.offer(env.records, at + 3600_000))
    }

    @Test fun afterMessageLapsesOrSkipsWhenAlreadyLogged() {
        val env = TestEnv()
        env.records.saveFriend(friend("a", "Ana", 40))
        val at = NOW.toInstant().toEpochMilli()
        AfterMessage.handedOff("a", at)
        assertFalse(AfterMessage.offer(env.records, at + AfterMessage.WINDOW_MS + 1))
        AfterMessage.handedOff("a", at - 1000)
        logToday(env.records, "a", now = NOW.toInstant())
        Toaster.reset()
        assertFalse(AfterMessage.offer(env.records, at + 1000))
        assertNull(Toaster.current.value)
    }

    @Test fun onboardingQueueSkipsSeenAndSatisfiedSteps() = runBlocking {
        assertEquals(OnboardingStep.entries.toList(), onboardingQueue(emptyMap(), { false }) { false })
        assertEquals(listOf(OnboardingStep.NOTIFICATIONS), onboardingQueue(emptyMap(), { true }) { false })
        assertEquals(listOf(OnboardingStep.NOTIFICATIONS), onboardingQueue(mapOf("syncFolder" to true), { false }) { false })
        assertEquals(emptyList<OnboardingStep>(), onboardingQueue(emptyMap(), { true }) { true })
        // A status check that fails does not nag.
        assertEquals(emptyList<OnboardingStep>(), onboardingQueue(emptyMap(), { error("boom") }) { true })
    }

    @Test fun onboardingMarksEachStepSeenHoweverItEnds() {
        val env = TestEnv()
        val c = OnboardingController(env.records)
        c.start(OnboardingStep.entries.toList())
        assertEquals(OnboardingStep.SYNC_FOLDER, c.current)
        val running = c.beginPrimary()
        assertEquals(OnboardingStep.SYNC_FOLDER, running)
        assertNull("hidden while the picker is up", c.current)
        assertEquals(emptyMap<String, Boolean>(), env.records.getSettings().onboarded)
        c.primaryDone(OnboardingStep.SYNC_FOLDER)
        assertEquals(mapOf("syncFolder" to true), env.records.getSettings().onboarded)
        assertEquals(OnboardingStep.NOTIFICATIONS, c.current)
        c.dismiss()
        assertNull(c.current)
        assertEquals(mapOf("syncFolder" to true, "notifications" to true), env.records.getSettings().onboarded)
        // Other settings survive.
        env.records.saveSettings(env.records.getSettings().copy(smallHome = true))
        assertEquals(Settings(smallHome = true, onboarded = mapOf("syncFolder" to true, "notifications" to true)), env.records.getSettings())
    }
}
