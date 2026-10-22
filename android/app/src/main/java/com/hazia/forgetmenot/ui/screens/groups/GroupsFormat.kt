package com.hazia.forgetmenot.ui.screens.groups

import android.text.format.DateFormat
import com.hazia.forgetmenot.domain.Group
import com.hazia.forgetmenot.domain.GroupEvent
import com.hazia.forgetmenot.domain.Recurring
import com.hazia.forgetmenot.domain.computeNextEventDate
import com.hazia.forgetmenot.domain.nextGroupEvent
import com.hazia.forgetmenot.domain.onceAlreadyPast
import com.hazia.forgetmenot.domain.plural
import java.text.Collator
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// The text rules of renderGroups() and renderGroupDetail() (www/js/screens/groups.js:15-90),
// kept apart from the composables so they can be tested without a layout.

/**
 * fmtEventDate() (www/js/lib/dates.js:90): `toLocaleDateString(undefined, { month: 'short',
 * day: 'numeric' })`. The best "MMMd" pattern for the locale puts day and month in the order
 * that locale writes them, as Intl does ("Oct 25", "25 Oct").
 */
fun fmtEventDate(d: ZonedDateTime, locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale).format(d)

/** The groups-list teaser: "2 people · Meetup Oct 25", or just the count with nothing coming up. */
fun groupTeaser(g: Group, now: ZonedDateTime, locale: Locale = Locale.getDefault()): String {
    val next = nextGroupEvent(g, now)
    return plural(g.memberIds.size, " person", " people") +
        (next?.let { " · " + it.ev.title + " " + fmtEventDate(it.next, locale) } ?: "")
}

/**
 * An event row's second line: its next date, "Was …" once a one-off's day has gone by (the same
 * test the teaser and the reminders use), and the repeat for a monthly or yearly one.
 */
fun eventLine(ev: GroupEvent, now: ZonedDateTime, locale: Locale = Locale.getDefault()): String {
    val next = computeNextEventDate(ev, now) ?: return ev.dateISO // malformed: show what is stored
    val past = onceAlreadyPast(ev, next, now)
    val repeat = if (ev.recurring == Recurring.MONTHLY || ev.recurring == Recurring.YEARLY) " · " + ev.recurring else ""
    return (if (past) "Was " else "") + fmtEventDate(next, locale) + repeat
}

/** By next occurrence; one with a malformed date (JS compared NaN, unordered) goes last. */
fun sortedEvents(events: List<GroupEvent>, now: ZonedDateTime): List<GroupEvent> =
    events.sortedWith(compareBy(nullsLast()) { computeNextEventDate(it, now)?.toInstant() })

/** By name, as localeCompare() orders them. */
fun sortedGroups(groups: List<Group>): List<Group> {
    val collator = Collator.getInstance()
    return groups.sortedWith { a, b -> collator.compare(a.name, b.name) }
}

/** Test tags, for the Compose UI tests. */
object GroupsTags {
    const val TITLE = "groups:title"
    const val GROUP_NAME = "groups:group-name"
    const val MEMBER_NAME = "groups:member-name"
    const val EVENT_TITLE = "groups:event-title"
    const val EVENT_WHEN = "groups:event-when"
    const val PICKER_NAME = "groups:picker-name"
    const val SHEET_TITLE = "groups:sheet-title"
    const val GROUP_NAME_FIELD = "groups:group-name-field"
    const val EVENT_TITLE_FIELD = "groups:event-title-field"
    const val EVENT_DATE_FIELD = "groups:event-date-field"
    const val CHIP_PREFIX = "groups:recurring-"
}
