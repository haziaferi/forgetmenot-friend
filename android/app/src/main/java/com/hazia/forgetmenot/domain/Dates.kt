package com.hazia.forgetmenot.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// Port of www/js/lib/dates.js. The JS reads the local zone implicitly through Date; here every
// function takes `now` as a ZonedDateTime and works in now.zone, so tests can pin a zone and
// production passes ZonedDateTime.now().

private val ISO_TIMESTAMP: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** The `toISOString()` form a contact's `date` is stored in: UTC, milliseconds, `Z`. */
fun isoTimestamp(instant: Instant): String = ISO_TIMESTAMP.format(instant)

/**
 * Reads a stored date the way JS `new Date(s)` does: a timestamp with an offset or `Z` is that
 * instant, one without is local time, and a bare `YYYY-MM-DD` is UTC midnight. Null when
 * unparseable (JS gives an Invalid Date).
 */
fun parseInstant(s: String?, zone: ZoneId = ZoneId.systemDefault()): Instant? {
    if (s.isNullOrEmpty()) return null
    runCatching { return OffsetDateTime.parse(s).toInstant() }
    runCatching { return Instant.parse(s) }
    runCatching { return LocalDateTime.parse(s).atZone(zone).toInstant() }
    runCatching { return LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant() }
    return null
}

/**
 * Calendar days, not elapsed 24-hour periods: both ends are taken to their local day, so a
 * DST change's 23- or 25-hour day never shifts the count. 0 for an unparseable date.
 */
fun daysSince(iso: String, now: ZonedDateTime = ZonedDateTime.now()): Int {
    val then = parseInstant(iso, now.zone) ?: return 0
    return ChronoUnit.DAYS.between(then.atZone(now.zone).toLocalDate(), now.toLocalDate()).toInt()
}

/** YYYY-MM-DD of the local calendar day (toISOString() is UTC, a different day for part of every day). */
fun localDateISO(d: ZonedDateTime): String = d.toLocalDate().toString()

fun formatCode(days: Int): String = when {
    days <= 0 -> "today"
    days < 30 -> "${days}d"
    days < 365 -> "${Math.round(days / 30.0)}m"
    else -> "${Math.round(days / 365.0)}y"
}

fun fmtRelative(days: Int): String {
    if (days <= 0) return "today"
    if (days < 30) return plural(days, " day", " days") + " ago"
    val m = Math.round(days / 30.0).toInt()
    if (m < 12) return plural(m, " month", " months") + " ago"
    return plural(Math.round(days / 365.0).toInt(), " year", " years") + " ago"
}

fun timeAgoShort(ts: Long?, nowMs: Long = System.currentTimeMillis()): String {
    if (ts == null || ts == 0L) return "unknown time"
    val mins = Math.round((nowMs - ts) / 60000.0).toInt()
    if (mins < 1) return "just now"
    if (mins < 60) return plural(mins, " minute", " minutes") + " ago"
    val hrs = Math.round(mins / 60.0).toInt()
    if (hrs < 24) return plural(hrs, " hour", " hours") + " ago"
    return plural(Math.round(hrs / 24.0).toInt(), " day", " days") + " ago"
}

/** Year, month (1-12) and day of a `YYYY-MM-DD`, read like JS `split('-').map(Number)`; null if not that shape. */
internal fun ymd(iso: String?): Triple<Int, Int, Int>? {
    val parts = (iso ?: "").split("-")
    if (parts.size < 3) return null
    val y = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    val d = parts[2].toIntOrNull() ?: return null
    if (m !in 1..12 || d < 1) return null
    return Triple(y, m, d)
}

/** JS `new Date(y, m - 1, d)`: a day or month past the end rolls into the next. */
internal fun jsLocalDate(y: Int, m: Int, d: Int): LocalDate =
    LocalDate.of(y, 1, 1).plusMonths((m - 1).toLong()).plusDays((d - 1).toLong())

internal fun LocalDate.atNine(zone: ZoneId): ZonedDateTime = atTime(9, 0).atZone(zone)

/**
 * 09:00 on `day` of month `month0` (0-based, and may run past 11 into the next year), clamped
 * to that month's last real day: a Feb 29 date observes Feb 28 in a common year rather than
 * rolling into March.
 */
private fun clampedNineAm(y: Int, month0: Int, day: Int, zone: ZoneId): ZonedDateTime {
    val ym = YearMonth.of(y, 1).plusMonths(month0.toLong())
    return ym.atDay(minOf(day, ym.lengthOfMonth())).atNine(zone)
}

private fun ZonedDateTime.isMoreThanAMinuteBefore(now: ZonedDateTime) =
    toInstant().toEpochMilli() < now.toInstant().toEpochMilli() - 60_000

/**
 * Next occurrence (today or later) of a yearly date such as a birthday, at 09:00. Only the
 * month and day are read, so an unknown birth year (1900) still gets a reminder.
 */
fun nextYearlyOccurrence(dateISO: String, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? {
    val (_, month, day) = ymd(dateISO) ?: return null
    var next = clampedNineAm(now.year, month - 1, day, now.zone)
    if (next.isMoreThanAMinuteBefore(now)) next = clampedNineAm(now.year + 1, month - 1, day, now.zone)
    return next
}

/**
 * Next occurrence of a group event, at 09:00. A `once` event returns its exact date, which may
 * be past; callers decide what to do with that. Null for a malformed dateISO.
 */
fun computeNextEventDate(ev: GroupEvent, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? {
    val (year, month, day) = ymd(ev.dateISO) ?: return null
    val start = jsLocalDate(year, month, day).atNine(now.zone)
    if (ev.recurring != Recurring.ONCE && start.isAfter(now)) return start // a series that hasn't started yet
    return when (ev.recurring) {
        Recurring.YEARLY -> nextYearlyOccurrence(ev.dateISO, now)
        Recurring.MONTHLY -> {
            var next = clampedNineAm(now.year, now.monthValue - 1, day, now.zone)
            if (next.isMoreThanAMinuteBefore(now)) next = clampedNineAm(now.year, now.monthValue, day, now.zone)
            next
        }
        else -> start
    }
}

/** True once a one-off event's calendar day has gone by, so every screen agrees on "Was". */
fun onceAlreadyPast(ev: GroupEvent, next: ZonedDateTime, now: ZonedDateTime = ZonedDateTime.now()): Boolean =
    ev.recurring == Recurring.ONCE && next.isBefore(now.toLocalDate().atStartOfDay(now.zone))

data class UpcomingEvent(val ev: GroupEvent, val next: ZonedDateTime)

/** The soonest upcoming event for a group (a past one-off doesn't count), or null. */
fun nextGroupEvent(g: Group, now: ZonedDateTime = ZonedDateTime.now()): UpcomingEvent? =
    g.events
        .mapNotNull { ev -> computeNextEventDate(ev, now)?.let { UpcomingEvent(ev, it) } }
        .filter { !onceAlreadyPast(it.ev, it.next, now) }
        .minByOrNull { it.next.toInstant() }
