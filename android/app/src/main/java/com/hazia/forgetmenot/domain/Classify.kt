package com.hazia.forgetmenot.domain

import java.time.ZonedDateTime
import kotlin.math.abs

// Port of www/js/lib/classify.js (SPEC §4 "Classifying a friend").

/** The home screen's three sections; [key] is the JS string. */
enum class Section(val key: String) { RECENT("recent"), COMING("coming"), THINKING("thinking") }

/** Everything the home screen shows about a person, derived and never stored. */
data class Classification(val days: Int, val pct: Double, val section: Section, val code: String)

/**
 * The entry with the greatest date, wherever it sits in the list (a back-dated log lands
 * anywhere). Ties and unparseable dates keep the earlier entry, as the JS reduce does.
 */
fun latestContact(f: Friend): Contact? {
    if (f.contacts.isEmpty()) return null
    var best = f.contacts[0]
    var bestAt = parseInstant(best.date)
    for (c in f.contacts.drop(1)) {
        val at = parseInstant(c.date)
        if (at != null && bestAt != null && at.isAfter(bestAt)) { best = c; bestAt = at }
    }
    return best
}

/**
 * Logs a reach-out: returns the friend with the entry first in its list (JS `unshift`) and
 * the entry, for undo. A kind other than one of [CONTACT_KINDS]' own keys is not kept.
 */
fun addContact(f: Friend, date: String, kind: String? = null, id: String = uid()): Pair<Friend, Contact> {
    val entry = Contact(id = id, date = date, kind = kind?.takeIf { it in CONTACT_KINDS })
    return f.copy(contacts = listOf(entry) + f.contacts) to entry
}

/** The friend's cadence when positive, else the 90-day default every reader agrees on. */
fun cadenceOf(f: Friend?): Int = f?.cadenceDays?.takeIf { it > 0 } ?: DEFAULT_CADENCE_DAYS

private val ISO_DAY = Regex("(\\d{4})-(\\d{2})-(\\d{2})")

/** Paused outright, or until a YYYY-MM-DD date still to come; reminders resume that morning. */
fun isPaused(f: Friend, now: ZonedDateTime = ZonedDateTime.now()): Boolean {
    if (f.paused) return true
    val m = ISO_DAY.matchEntire(f.pausedUntil ?: "") ?: return false
    val (y, mo, d) = m.destructured
    return jsLocalDate(y.toInt(), mo.toInt(), d.toInt()).atStartOfDay(now.zone).isAfter(now)
}

fun classify(f: Friend, now: ZonedDateTime = ZonedDateTime.now()): Classification {
    // Never logged: a fresh start, not 9999 days overdue. Counting begins at the first log.
    val last = latestContact(f) ?: return Classification(0, 0.0, Section.RECENT, "new")
    val days = daysSince(last.date, now)
    val pct = minOf(1.0, days.toDouble() / maxOf(1, cadenceOf(f)))
    val section = when {
        isPaused(f, now) -> Section.RECENT
        pct < 0.33 -> Section.RECENT
        pct < 1 -> Section.COMING
        else -> Section.THINKING
    }
    return Classification(days, pct, section, formatCode(days))
}

/**
 * Stable small-int notification id from a string, bit for bit the JS one: a 32-bit
 * `h * 31 + charCode` over UTF-16 code units. The absolute value is taken in Long, since
 * JS `Math.abs(-2^31)` is 2^31 while Kotlin's `abs(Int.MIN_VALUE)` stays negative.
 */
fun hashToId(str: String): Int {
    var h = 0
    for (ch in str) h = (h shl 5) - h + ch.code
    return (abs(h.toLong()) % 2_000_000_000L).toInt()
}
