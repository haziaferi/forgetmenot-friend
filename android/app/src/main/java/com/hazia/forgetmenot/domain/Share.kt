package com.hazia.forgetmenot.domain

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// vCard / iCalendar text for the detail screen's Share and Plan something (www/js/lib/text.js).

private val ICS_SPECIAL = Regex("([,;\\\\])")
private val LINE_BREAK = Regex("\r\n|\r|\n")

/** RFC 6350 (vCard) and RFC 5545 (iCalendar) share these escaping rules. */
internal fun escapeText(s: String): String =
    s.replace(ICS_SPECIAL, "\\\\$1").replace(LINE_BREAK, "\\\\n")

/**
 * VERSION:4.0 because an unknown birth year (1900) is written as RFC 6350's partial date
 * "--MMDD", which 3.0 importers reject. 4.0 also takes only the basic date format.
 */
fun buildVCard(f: Friend): String {
    val lines = mutableListOf("BEGIN:VCARD", "VERSION:4.0", "FN:" + escapeText(f.name))
    if (!f.phone.isNullOrEmpty()) lines += "TEL;TYPE=CELL:" + escapeText(f.phone)
    if (!f.email.isNullOrEmpty()) lines += "EMAIL:" + escapeText(f.email)
    val b = f.birthday
    if (!b.isNullOrEmpty()) {
        lines += "BDAY:" + if (b.take(4) == "1900") "--" + b.drop(5).replaceFirst("-", "") else b.replace("-", "")
    }
    lines += "END:VCARD"
    return lines.joinToString("\r\n")
}

private val ICS_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

/** toISOString() without separators and milliseconds, as fmtICSDate() wrote it. */
internal fun fmtICSDate(t: Instant): String = ICS_DATE.format(t.truncatedTo(ChronoUnit.SECONDS))

/** One "Time with <name>" event; [uid] and [now] are parameters so tests can pin them. */
fun buildICS(f: Friend, start: Instant, end: Instant, uid: String = uid(), now: Instant = Instant.now()): String =
    listOf(
        "BEGIN:VCALENDAR",
        "VERSION:2.0",
        "PRODID:-//forgetmenot//EN",
        "BEGIN:VEVENT",
        "UID:$uid@forgetmenot",
        "DTSTAMP:" + fmtICSDate(now),
        "DTSTART:" + fmtICSDate(start),
        "DTEND:" + fmtICSDate(end),
        "SUMMARY:" + escapeText("Time with " + f.name),
        "END:VEVENT",
        "END:VCALENDAR",
    ).joinToString("\r\n")
