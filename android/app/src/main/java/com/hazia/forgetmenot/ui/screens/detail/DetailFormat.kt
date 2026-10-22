package com.hazia.forgetmenot.ui.screens.detail

import android.text.format.DateFormat
import com.hazia.forgetmenot.domain.CADENCE_PRESETS
import com.hazia.forgetmenot.domain.CONTACT_KINDS
import com.hazia.forgetmenot.domain.Contact
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.domain.FriendDate
import com.hazia.forgetmenot.domain.Note
import com.hazia.forgetmenot.domain.daysSince
import com.hazia.forgetmenot.domain.fmtRelative
import com.hazia.forgetmenot.domain.latestContact
import com.hazia.forgetmenot.domain.nextYearlyOccurrence
import com.hazia.forgetmenot.domain.parseInstant
import java.time.LocalDate
import java.time.Year
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// The text and selection rules of renderDetail() (www/js/screens/detail.js), kept apart from the
// composables so they can be tested without a layout.

/** Reach-outs listed under "Reach-outs"; the ring and the sort already use them all. */
const val HISTORY_SHOWN = 8

/** The notes filter chips (NOTE_FILTERS); the JS default (state.notesFilter) is all time. */
enum class NoteFilter(val label: String) { MONTH("This month"), YEAR("This year"), ALL("All time") }

/**
 * shortDate(): "12 Oct", or "12 Oct 2024" outside the current year, in the locale's order (the
 * best "dMMM" / "dMMMy" pattern, as toLocaleDateString picks one). "" when unparseable.
 */
fun shortDate(iso: String, now: ZonedDateTime, locale: Locale = Locale.getDefault()): String {
    val d = parseInstant(iso, now.zone)?.atZone(now.zone) ?: return ""
    val skeleton = if (d.year == now.year) "dMMM" else "dMMMy"
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(d)
}

/** "Ana" from "Ana María López": the JS's `f.name.split(' ')[0]`. */
fun firstName(name: String): String = name.split(' ')[0]

/** The line under the name: "Last in touch 3 days ago, in person", or that nothing is logged. */
fun lastInTouchLine(f: Friend, now: ZonedDateTime): String {
    val last = latestContact(f) ?: return "No reach-outs logged yet"
    val kind = last.kind?.let { CONTACT_KINDS[it] }
    return "Last in touch " + fmtRelative(daysSince(last.date, now)) + (kind?.let { ", " + it.phrase } ?: "")
}

/**
 * Whether today is the birthday, by month and day (nextYearlyOccurrence() has moved on to next
 * year once today's 9am has passed). A 29 February birthday is kept on the 28th in other years,
 * as its reminder is.
 */
fun birthdayIsToday(birthday: String?, today: LocalDate): Boolean {
    if (birthday.isNullOrEmpty() || birthday.length < 10) return false
    val md = birthday.substring(5)
    val todayMd = today.toString().substring(5)
    return md == todayMd || (!Year.isLeap(today.year.toLong()) && md == "02-29" && todayMd == "02-28")
}

/**
 * The friend's own dates, soonest first, each with the day it shows: a yearly one its next
 * occurrence, a one-off its own day even once passed. A malformed date sorts last with null.
 */
fun sortedDates(f: Friend, now: ZonedDateTime): List<Pair<FriendDate, ZonedDateTime?>> =
    f.dates.map { d ->
        d to if (d.yearly) nextYearlyOccurrence(d.dateISO, now)
        else runCatching { LocalDate.parse(d.dateISO).atTime(9, 0).atZone(now.zone) }.getOrNull()
    }.sortedWith(compareBy(nullsLast()) { it.second })

/** The notes in [filter]'s range, newest first. */
fun filterNotes(notes: List<Note>, filter: NoteFilter, now: ZonedDateTime): List<Note> {
    fun local(n: Note) = parseInstant(n.date, now.zone)?.atZone(now.zone)
    return notes
        .sortedByDescending { local(it)?.toInstant() }
        .filter { n ->
            val d = local(n)
            when (filter) {
                NoteFilter.ALL -> true
                NoteFilter.YEAR -> d != null && d.year == now.year
                NoteFilter.MONTH -> d != null && d.year == now.year && d.month == now.month
            }
        }
}

/** The reach-outs shown (newest first, at most [HISTORY_SHOWN]) and how many are left out. */
data class History(val shown: List<Contact>, val earlier: Int)

fun historyOf(f: Friend): History {
    val all = f.contacts.sortedByDescending { parseInstant(it.date) }
    return History(all.take(HISTORY_SHOWN), (all.size - HISTORY_SHOWN).coerceAtLeast(0))
}

/** The label on the Message button: the friend's preferred channel (MESSAGE_CHANNEL_LABEL). */
fun messageLabel(channel: String?): String = when (channel) {
    "whatsapp" -> "WhatsApp"
    "telegram" -> "Telegram"
    "signal" -> "Signal"
    "email" -> "Email"
    else -> "Message"
}

/** Test tags of the parts the tests read. */
object DetailTags {
    const val NAME = "detail-name"
    const val LAST = "detail-last"
    const val AVATAR = "detail-avatar"
    const val TAG = "detail-tag"
    const val ASK_ROW = "detail-ask-row"
    const val DATE_LABEL = "detail-date-label"
    const val CADENCE_ROW = "detail-cadence-row"
    const val PAUSE_ROW = "detail-pause-row"
    const val SNOOZE_CLEAR = "detail-snooze-clear"
    const val HISTORY_DATE = "detail-history-date"
    const val NOTE_TEXT = "detail-note-text"
    const val INTEGRATIONS = "detail-integrations"
}
