package com.hazia.forgetmenot.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

// Port of tools/dates.test.mjs: the birthday and group-event date maths, swept across 2027
// (common) and 2028 (leap) at 08:00 and 10:00, so both "today's slot is still ahead" and
// "already passed" are hit. Europe/Rome so the sweep crosses two DST changes a year.
class DatesTest {
    private val zone: ZoneId = ZoneId.of("Europe/Rome")
    private val dayMs = 86_400_000L

    private fun at(y: Int, m: Int, d: Int, h: Int = 8) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone)
    private fun lastDayOf(y: Int, m: Int) = YearMonth.of(y, m).lengthOfMonth()

    // Every day of 2027 and 2028, twice: before and after the 09:00 slot.
    private fun nows(): Sequence<ZonedDateTime> = sequence {
        var d = LocalDate.of(2027, 1, 1)
        while (d.year < 2029) {
            yield(d.atTime(8, 0).atZone(zone))
            yield(d.atTime(10, 0).atZone(zone))
            d = d.plusDays(1)
        }
    }

    private fun assertReminderShaped(next: ZonedDateTime?, now: ZonedDateTime, maxAheadDays: Int, label: String) {
        assertTrue("$label: not a date", next != null)
        val n = next!!.toInstant().toEpochMilli()
        val t = now.toInstant().toEpochMilli()
        assertTrue("$label: $next is in the past from $now", n >= t - 60_000)
        assertTrue("$label: $next is more than $maxAheadDays days from $now", n <= t + maxAheadDays * dayMs)
        assertEquals("$label: fires at ${next.hour}:00, not 09:00", 9, next.hour)
        assertEquals("$label: minutes", 0, next.minute)
    }

    @Test fun yearlyDatesNeverPastNeverMoreThanAYearOutAlwaysNineClampedToARealDay() {
        val stored = listOf("2000-02-29", "1990-01-31", "1985-03-31", "1999-04-30", "2001-12-31", "1970-06-15", "2004-01-01")
        var n = 0
        for (now in nows()) for (iso in stored) {
            val next = nextYearlyOccurrence(iso, now)
            val (_, m, d) = iso.split("-").map(String::toInt)
            assertReminderShaped(next, now, 366, "$iso from $now")
            assertEquals("$iso: month", m, next!!.monthValue)
            assertEquals("$iso: day clamp", minOf(d, lastDayOf(next.year, m)), next.dayOfMonth)
            n++
        }
        assertTrue("sweep ran", n > 10000)
    }

    @Test fun aFeb29BirthdayIsFeb29InALeapYearAndFeb28Otherwise() {
        assertEquals(LocalDate.of(2028, 2, 29), nextYearlyOccurrence("1996-02-29", at(2028, 1, 10))!!.toLocalDate())
        assertEquals(LocalDate.of(2027, 2, 28), nextYearlyOccurrence("1996-02-29", at(2027, 1, 10))!!.toLocalDate())
        // Observed Feb 28 2027 at 10:00 — already past — rolls to the real Feb 29 of 2028.
        assertEquals(LocalDate.of(2028, 2, 29), nextYearlyOccurrence("1996-02-29", at(2027, 2, 28, 10))!!.toLocalDate())
    }

    @Test fun theYearRollsOverADec31DateSeenAt10OnDec31IsNextYear() {
        val next = nextYearlyOccurrence("2001-12-31", at(2027, 12, 31, 10))!!
        assertEquals(2028, next.year)
        assertEquals(12, next.monthValue)
        assertEquals(31, next.dayOfMonth)
    }

    @Test fun monthlyEventsNeverPastAtMostAMonthOutDayClampedToTheMonth() {
        for (now in nows()) for (day in listOf(31, 30, 29, 15, 1)) {
            val ev = GroupEvent(dateISO = "2026-01-%02d".format(day), recurring = Recurring.MONTHLY)
            val next = computeNextEventDate(ev, now)
            assertReminderShaped(next, now, 31, "monthly day $day from $now")
            assertEquals("day $day: clamp", minOf(day, lastDayOf(next!!.year, next.monthValue)), next.dayOfMonth)
        }
        val day31 = GroupEvent(dateISO = "2026-01-31", recurring = Recurring.MONTHLY)
        assertEquals(LocalDate.of(2028, 4, 30), computeNextEventDate(day31, at(2028, 4, 1))!!.toLocalDate())
        assertEquals(LocalDate.of(2028, 2, 29), computeNextEventDate(day31, at(2028, 2, 1))!!.toLocalDate())
        assertEquals(LocalDate.of(2027, 2, 28), computeNextEventDate(day31, at(2027, 2, 1))!!.toLocalDate())
    }

    @Test fun yearlyGroupEventsDelegateToTheBirthdayRuleOnceEventsReturnTheirLiteralDayAtNine() {
        val now = at(2027, 6, 1)
        val yearly = computeNextEventDate(GroupEvent(dateISO = "2020-02-29", recurring = Recurring.YEARLY), now)!!
        assertEquals(nextYearlyOccurrence("2020-02-29", now)!!.toLocalDate(), yearly.toLocalDate())
        val once = computeNextEventDate(GroupEvent(dateISO = "2026-03-14", recurring = Recurring.ONCE), now)!!
        assertEquals(LocalDate.of(2026, 3, 14), once.toLocalDate())
        assertEquals(9, once.hour)
    }

    @Test fun callersThatPassNoNowGetTheRealClock() {
        val next = nextYearlyOccurrence("2000-06-15")!!.toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        assertTrue(next >= now - 60_000 && next <= now + 366 * dayMs)
    }
}
