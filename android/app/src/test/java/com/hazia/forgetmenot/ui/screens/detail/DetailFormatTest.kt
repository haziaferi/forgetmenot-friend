package com.hazia.forgetmenot.ui.screens.detail

import com.hazia.forgetmenot.domain.Contact
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.domain.FriendDate
import com.hazia.forgetmenot.domain.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

// The formatting and selection rules behind renderDetail() (www/js/screens/detail.js): the
// pure parts, so the screen test can stay about what is drawn and what a tap does.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DetailFormatTest {
    private val zone: ZoneId = ZoneId.systemDefault()
    private val now = ZonedDateTime.of(2026, 10, 19, 10, 0, 0, 0, zone)

    @Test fun shortDateDropsTheYearOnlyInsideTheCurrentYear() {
        assertEquals("Oct 12", shortDate("2026-10-12T12:00:00.000Z", now, Locale.US))
        assertEquals("Oct 12, 2024", shortDate("2024-10-12T12:00:00.000Z", now, Locale.US))
        assertEquals("12 Oct", shortDate("2026-10-12T12:00:00.000Z", now, Locale.UK))
        // A plain local timestamp (the snooze row's "<day>T12:00:00") reads as local time.
        assertEquals("Oct 26", shortDate("2026-10-26T12:00:00", now, Locale.US))
        assertEquals("", shortDate("not a date", now, Locale.US))
    }

    @Test fun lastInTouchNamesTheKindOfTheNewestReachOut() {
        assertEquals("No reach-outs logged yet", lastInTouchLine(Friend(id = "a"), now))
        val f = Friend(id = "a", contacts = listOf(
            Contact("c1", "2026-10-01T10:00:00.000Z", "call"),
            Contact("c2", "2026-10-17T10:00:00.000Z", "meet"),
        ))
        assertEquals("Last in touch 2 days ago, in person", lastInTouchLine(f, now))
        val plain = Friend(id = "a", contacts = listOf(Contact("c1", "2026-10-17T10:00:00.000Z")))
        assertEquals("Last in touch 2 days ago", lastInTouchLine(plain, now))
    }

    @Test fun birthdayTodayMatchesMonthAndDayAndKeepsLeapDayOnThe28th() {
        assertTrue(birthdayIsToday("1990-10-19", LocalDate.of(2026, 10, 19)))
        assertFalse(birthdayIsToday("1990-10-20", LocalDate.of(2026, 10, 19)))
        assertFalse(birthdayIsToday(null, LocalDate.of(2026, 10, 19)))
        assertTrue(birthdayIsToday("2000-02-29", LocalDate.of(2027, 2, 28)))
        assertFalse(birthdayIsToday("2000-02-29", LocalDate.of(2028, 2, 28)))
        assertTrue(birthdayIsToday("2000-02-29", LocalDate.of(2028, 2, 29)))
    }

    @Test fun datesSortSoonestFirstAndAOneOffKeepsItsPastDay() {
        val f = Friend(id = "a", dates = listOf(
            FriendDate("d1", "Move", "2026-12-01", yearly = false),
            FriendDate("d2", "Interview", "2026-10-01", yearly = false),
            FriendDate("d3", "Name day", "2000-11-02", yearly = true),
            FriendDate("d4", "Broken", "nope", yearly = false),
        ))
        val sorted = sortedDates(f, now)
        assertEquals(listOf("d2", "d3", "d1", "d4"), sorted.map { it.first.id })
        assertEquals(LocalDate.of(2026, 10, 1), sorted[0].second!!.toLocalDate())
        assertEquals(LocalDate.of(2026, 11, 2), sorted[1].second!!.toLocalDate())
        assertNull(sorted[3].second)
    }

    @Test fun notesFilterByMonthYearOrAllNewestFirst() {
        val notes = listOf(
            Note("n1", "2026-10-02T10:00:00.000Z", "this month"),
            Note("n2", "2026-03-02T10:00:00.000Z", "this year"),
            Note("n3", "2025-12-30T10:00:00.000Z", "last year"),
            Note("n4", "2026-10-18T10:00:00.000Z", "newest"),
        )
        assertEquals(listOf("n4", "n1"), filterNotes(notes, NoteFilter.MONTH, now).map { it.id })
        assertEquals(listOf("n4", "n1", "n2"), filterNotes(notes, NoteFilter.YEAR, now).map { it.id })
        assertEquals(listOf("n4", "n1", "n2", "n3"), filterNotes(notes, NoteFilter.ALL, now).map { it.id })
    }

    @Test fun historyShowsTheNewestEightAndCountsTheRest() {
        val contacts = (1..11).map { Contact("c$it", "2026-09-%02dT10:00:00.000Z".format(it)) }
        val h = historyOf(Friend(id = "a", contacts = contacts.shuffled()))
        assertEquals((11 downTo 4).map { "c$it" }, h.shown.map { it.id })
        assertEquals(3, h.earlier)
        assertEquals(0, historyOf(Friend(id = "a", contacts = contacts.take(3))).earlier)
    }

    @Test fun firstNameIsTheNameUpToTheFirstSpace() {
        assertEquals("Ana", firstName("Ana María López"))
        assertEquals("Bo", firstName("Bo"))
    }
}
