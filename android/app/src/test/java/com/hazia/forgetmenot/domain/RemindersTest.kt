package com.hazia.forgetmenot.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

// Port of tools/reminders.test.mjs: when check-in nudges fire, and which ones share a
// notification. Times are local, as on the phone: every nudge lands at 9am on its day.
class RemindersTest {
    private val zone: ZoneId = ZoneId.of("Europe/Rome")
    private fun at(y: Int, m: Int, d: Int, h: Int = 9, min: Int = 0) = ZonedDateTime.of(y, m, d, h, min, 0, 0, zone)

    // Cadence under 10 days has no spread, so these expectations are exact.
    private fun weekly(paused: Boolean = false, snoozedUntil: String? = null, pausedUntil: String? = null) = Friend(
        id = "f1", name = "Ana", cadenceDays = 7, paused = paused, snoozedUntil = snoozedUntil, pausedUntil = pausedUntil,
        contacts = listOf(Contact(id = "c1", date = isoTimestamp(at(2026, 1, 1, 12).toInstant()))),
    )

    @Test fun aCheckInDueAheadFiresOnItsDayAt9am() {
        assertEquals(at(2026, 1, 8), nextCheckIn(weekly(), at(2026, 1, 3, 15)))
    }

    @Test fun anUnansweredCheckInComesBackAStepLaterNotEveryMorning() {
        // Due 8 Jan; the step for a weekly friend is 7 days.
        assertEquals(at(2026, 1, 15), nextCheckIn(weekly(), at(2026, 1, 8, 10)))
        assertEquals(at(2026, 1, 15), nextCheckIn(weekly(), at(2026, 1, 9, 8)))
        assertEquals(at(2026, 1, 15), nextCheckIn(weekly(), at(2026, 1, 14, 20)))
        assertEquals(at(2026, 1, 22), nextCheckIn(weekly(), at(2026, 1, 15, 10)))
    }

    @Test fun theStepGrowsWithTheCadence() {
        val f = Friend(id = "x", cadenceDays = 365, contacts = listOf(Contact(id = "c", date = isoTimestamp(at(2024, 1, 1, 12).toInstant()))))
        val first = nextCheckIn(f, at(2024, 1, 2))!!
        val later = nextCheckIn(f, first.plusMinutes(1))!!
        assertEquals(91, Math.round(Duration.between(first, later).toMillis() / 86_400_000.0)) // a quarter of a year
    }

    @Test fun rebuildingTheScheduleLandsOnTheSameInstant() {
        val f = Friend(id = "imported-7", cadenceDays = 90, createdAt = at(2026, 1, 1, 12).toInstant().toEpochMilli())
        assertEquals(nextCheckIn(f, at(2026, 2, 1)), nextCheckIn(f, at(2026, 2, 20, 18)))
    }

    @Test fun friendsImportedTogetherAreSpreadOverUpToAWeek() {
        val created = at(2026, 1, 1, 12).toInstant().toEpochMilli()
        val days = mutableSetOf<Long>()
        for (i in 0 until 40) {
            val next = nextCheckIn(Friend(id = "friend-$i", cadenceDays = 90, createdAt = created), at(2026, 1, 2))!!
            val offset = Math.round(Duration.between(at(2026, 4, 1), next).toMillis() / 86_400_000.0) // 1 Jan + 90 days
            assertTrue("offset $offset", offset in 0..7)
            days.add(offset)
        }
        assertTrue("used ${days.size} different days", days.size > 3)
    }

    @Test fun aSnoozeOrAPauseDateHoldsTheNudgeUntilThatMorning() {
        assertEquals(at(2026, 1, 20), nextCheckIn(weekly(snoozedUntil = "2026-01-20"), at(2026, 1, 3)))
        assertEquals(at(2026, 2, 1), nextCheckIn(weekly(pausedUntil = "2026-02-01"), at(2026, 1, 3)))
        // One already past changes nothing.
        assertEquals(at(2026, 1, 8), nextCheckIn(weekly(snoozedUntil = "2026-01-02"), at(2026, 1, 3)))
        // A malformed one is ignored, not a crash.
        assertEquals(at(2026, 1, 8), nextCheckIn(weekly(snoozedUntil = "soon"), at(2026, 1, 3)))
    }

    @Test fun pausedOutrightNoCheckInAtAll() {
        assertNull(nextCheckIn(weekly(paused = true), at(2026, 1, 3)))
    }

    @Test fun isPausedCoversBothKindsOfPauseAndEndsOnTheDate() {
        val now = at(2026, 1, 10, 15)
        assertTrue(isPaused(Friend(paused = true), now))
        assertTrue(isPaused(Friend(pausedUntil = "2026-01-11"), now))
        assertFalse(isPaused(Friend(pausedUntil = "2026-01-10"), now))
        assertFalse(isPaused(Friend(pausedUntil = "never"), now))
        assertFalse(isPaused(Friend(), now))
    }

    @Test fun checkInsWithinFiveHoursOfTheFirstShareANotification() {
        fun c(name: String, d: ZonedDateTime) = ScheduledCheckIn(Friend(name = name), d)
        val runs = groupCheckIns(listOf(
            c("Dev", at(2026, 1, 9)),
            c("Ana", at(2026, 1, 8, 9)),
            c("Bo", at(2026, 1, 8, 13)),
            c("Chiara", at(2026, 1, 8, 14, 30)), // 5h30 after Ana: a run of its own
        ))
        assertEquals(listOf(listOf("Ana", "Bo"), listOf("Chiara"), listOf("Dev")), runs.map { r -> r.map { it.friend.name } })
        assertEquals(5 * 3600 * 1000L, GROUP_WINDOW_MS)
        // Exactly five hours still counts.
        assertEquals(1, groupCheckIns(listOf(c("A", at(2026, 1, 8, 9)), c("B", at(2026, 1, 8, 14)))).size)
    }

    @Test fun namesReadNaturallyAtEveryCount() {
        assertEquals("Ana", namesLine(listOf("Ana")))
        assertEquals("Ana and Bo", namesLine(listOf("Ana", "Bo")))
        assertEquals("Ana, Bo and Chiara", namesLine(listOf("Ana", "Bo", "Chiara")))
        assertEquals("Ana, Bo and 3 more", namesLine(listOf("Ana", "Bo", "Chiara", "Dev", "Emi")))
    }

    @Test fun theBirthdayNudgeNamesTheGiftIdeasWhenThereAreAny() {
        assertEquals("Today’s the day — send a little love.", birthdayBody(Friend()))
        assertEquals(
            "Today’s the day. Gift ideas: a book and socks.",
            birthdayBody(Friend(gifts = listOf(Gift(id = "g1", text = "a book"), Gift(id = "g2", text = "socks")))),
        )
    }
}
