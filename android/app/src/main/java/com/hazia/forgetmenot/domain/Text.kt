package com.hazia.forgetmenot.domain

import java.text.Normalizer
import java.util.Locale
import kotlin.math.pow
import kotlin.random.Random

// Port of the pure helpers in www/js/lib/text.js. vCard/iCalendar building (buildVCard,
// buildICS) is left to P6, which owns sharing.
//
// Every pattern here is checked with Regex.matches(), which must consume the whole input.
// Java's `$` also matches before a trailing newline, so a find() with ^…$ would let "abc\n"
// through as an id, where the JS (no m flag) rejects it.

private const val BASE36 = "0123456789abcdefghijklmnopqrstuvwxyz"

/** `Date.now().toString(36)` plus six random base-36 characters: lowercase alphanumeric, time-ordered by prefix. */
fun uid(nowMs: Long = System.currentTimeMillis(), random: Random = Random.Default): String =
    java.lang.Long.toString(nowMs, 36) + String(CharArray(6) { BASE36[random.nextInt(36)] })

/** Encodes quotes as well as & < >, for text and quoted-attribute contexts alike. */
fun esc(s: Any?): String = (s?.toString() ?: "")
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

private val ID = Regex("[A-Za-z0-9]{1,40}")

/**
 * Ids double as storage keys and path segments, so any id from outside the app (backup import,
 * a conflict filename, a deep link) must pass this first.
 */
fun isValidId(id: Any?): Boolean = id is String && ID.matches(id)

private val AVATAR = Regex("data:image/(png|jpe?g);base64,[A-Za-z0-9+/=]+")

/** Avatars are only ever local JPEG/PNG data URIs; an imported backup may not carry anything else. */
fun isValidAvatar(s: Any?): Boolean = s is String && AVATAR.matches(s)

fun initials(name: String?): String {
    val parts = (name ?: "").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (parts.isEmpty()) return "?"
    return (parts[0].take(1) + (parts.getOrNull(1)?.take(1) ?: "")).uppercase()
}

fun plural(n: Int, one: String, many: String): String = n.toString() + if (n == 1) one else many

/**
 * cadenceLabel() in www/js/screens/add.js: the preset's name, else days under a month, months
 * under a year, then years, rounded as Math.round does. Uses [plural], so 1 reads "1 year"
 * where the JS said "1 years". Shared by add and detail.
 */
fun cadenceLabel(days: Int): String {
    CADENCE_PRESETS.firstOrNull { it.days == days }?.let { return it.label }
    // Math.round: halves go up, as in the JS.
    fun round(x: Double) = kotlin.math.floor(x + 0.5).toInt()
    return when {
        days < 30 -> plural(days, " day", " days")
        days < 365 -> plural(round(days / 30.0), " month", " months")
        else -> plural(round(days / 365.0), " year", " years")
    }
}

fun onlyDigits(s: String?): String = (s ?: "").filter { it in '0'..'9' }

/**
 * The contacts provider often omits the birth year; 1900 stands in for it, and every reader
 * uses only the month and day, or treats 1900 as unknown. Null without a month and a day.
 */
fun birthdayToISO(month: Int?, day: Int?, year: Int? = null): String? {
    if (month == null || month == 0 || day == null || day == 0) return null
    val y = if (year == null || year == 0) 1900 else year
    // Locale.ROOT: the default locale may format digits in another script.
    return String.format(Locale.ROOT, "%d-%02d-%02d", y, month, day)
}

private val HEX_COLOR = Regex("#?([0-9a-fA-F]{3}|[0-9a-fA-F]{6})")

/** True when a #rgb/#rrggbb is light enough to read as "white-ish", by the WCAG relative-luminance curve. */
fun isLightColor(hex: String?): Boolean {
    val m = HEX_COLOR.matchEntire(hex ?: "") ?: return true
    var h = m.groupValues[1]
    if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
    fun chan(i: Int): Double {
        val v = h.substring(i * 2, i * 2 + 2).toInt(16) / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * chan(0) + 0.7152 * chan(1) + 0.0722 * chan(2) > 0.4
}

private val COMBINING_MARKS = Regex("[̀-ͯ]")

/** For matching typed text: lower case, accents dropped ("Zoë" and "zoe" fold alike). */
fun foldText(s: String?): String =
    Normalizer.normalize(s ?: "", Normalizer.Form.NFD).replace(COMBINING_MARKS, "").lowercase()
