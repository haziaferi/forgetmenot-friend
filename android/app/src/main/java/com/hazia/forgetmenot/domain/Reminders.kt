package com.hazia.forgetmenot.domain

import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime

// Port of www/js/lib/reminders.js: check-in timing (SPEC §4). Every nudge lands at 09:00
// local; P3's scheduler turns these times into alarms.

/** Check-ins due within this long of the first in a run share one notification. */
const val GROUP_WINDOW_MS = 5 * 3600 * 1000L

private val ISO_DAY = Regex("(\\d{4})-(\\d{2})-(\\d{2})")

private fun isoAtNine(iso: String?, now: ZonedDateTime): ZonedDateTime? {
    val m = ISO_DAY.matchEntire(iso ?: "") ?: return null
    val (y, mo, d) = m.destructured
    return jsLocalDate(y.toInt(), mo.toInt(), d.toInt()).atNine(now.zone)
}

/** The next check-in for [f], or null while it is paused outright. */
fun nextCheckIn(f: Friend, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? {
    if (f.paused) return null
    val cadence = cadenceOf(f)
    // Never logged: count from when the friend was added, not from now, or a schedule
    // rebuilt on every resume would push the first nudge out forever. An unparseable last
    // date also falls through here (the JS would return an Invalid Date).
    val base: Instant = latestContact(f)?.let { parseInstant(it.date, now.zone) }
        ?: f.createdAt?.takeIf { it != 0L }?.let(Instant::ofEpochMilli)
        ?: now.toInstant()
    // Up to a week later, fixed per friend: friends imported together share a createdAt,
    // and would otherwise all come due on the same morning.
    val spread = minOf(7, cadence / 10)
    val offset = if (spread > 0) hashToId("spread:" + f.id) % (spread + 1) else 0
    var at = base.atZone(now.zone).toLocalDate().plusDays((cadence + offset).toLong()).atNine(now.zone)
    // Already past: that nudge has had its turn. The next is one step on, so an unanswered
    // check-in comes back every step, not every morning. Anchors count from the due date,
    // so every rebuild lands on the same instant.
    val step = maxOf(7, Math.round(cadence / 4.0).toInt()).toLong()
    while (at.isBefore(now)) at = at.toLocalDate().plusDays(step).atNine(now.zone)
    // Snoozed, or paused until a date: nothing before that morning.
    for (hold in listOf(isoAtNine(f.snoozedUntil, now), isoAtNine(f.pausedUntil, now))) {
        if (hold != null && hold.isAfter(at)) at = hold
    }
    return at
}

data class ScheduledCheckIn(val friend: Friend, val at: ZonedDateTime)

/**
 * Check-ins into runs, soonest first: each run starts a new notification and takes in every
 * check-in due within [GROUP_WINDOW_MS] of its first.
 */
fun groupCheckIns(items: List<ScheduledCheckIn>): List<List<ScheduledCheckIn>> {
    val runs = mutableListOf<MutableList<ScheduledCheckIn>>()
    for (it in items.sortedBy { it.at.toInstant() }) {
        val run = runs.lastOrNull()
        if (run != null && Duration.between(run[0].at, it.at).toMillis() <= GROUP_WINDOW_MS) run.add(it)
        else runs.add(mutableListOf(it))
    }
    return runs
}

/** The birthday nudge names the gift ideas noted for them, oldest first, when there are any. */
fun birthdayBody(f: Friend): String {
    val ideas = f.gifts.map { it.text }.filter { it.isNotEmpty() }
    return if (ideas.isNotEmpty()) "Today’s the day. Gift ideas: " + namesLine(ideas) + "."
    else "Today’s the day — send a little love."
}

/** "Ana and Bo", "Ana, Bo and Chiara", "Ana, Bo and 3 more". */
fun namesLine(names: List<String>): String = when {
    names.size <= 1 -> names.joinToString("")
    names.size <= 3 -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    else -> names.take(2).joinToString(", ") + " and " + (names.size - 2) + " more"
}
