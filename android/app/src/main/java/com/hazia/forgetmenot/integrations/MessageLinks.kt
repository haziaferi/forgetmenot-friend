package com.hazia.forgetmenot.integrations

import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.domain.onlyDigits
import java.time.Instant
import java.time.ZonedDateTime

// The pure half of the detail screen's Message / Plan something row (www/js/features/share.js).

/** channelUrl(): the link one channel opens for [f], or null when the friend lacks the field it needs. */
fun channelUrl(channel: String, f: Friend): String? {
    val phone = f.phone?.takeIf { it.isNotEmpty() }
    val email = f.email?.takeIf { it.isNotEmpty() }
    return when {
        channel == "sms" && phone != null -> "sms:$phone"
        channel == "whatsapp" && phone != null -> "https://wa.me/" + onlyDigits(phone)
        channel == "telegram" && phone != null -> "https://t.me/+" + onlyDigits(phone)
        channel == "signal" && phone != null -> "https://signal.me/#p/+" + onlyDigits(phone)
        channel == "email" && email != null -> "mailto:$email"
        else -> null
    }
}

/** messageFriend()'s pick: the preferred channel, else SMS, else email; null = nothing to open. */
fun messageUrl(f: Friend): String? =
    f.messageChannel?.let { channelUrl(it, f) } ?: channelUrl("sms", f) ?: channelUrl("email", f)

fun noAddressToast(f: Friend): String = "Add a phone or email for " + f.name.split(' ')[0] + " to enable this"

/** planWithFriend()'s slot: a week from today at 18:00 local, for one hour. */
fun planWindow(now: ZonedDateTime): Pair<Instant, Instant> {
    val start = now.toLocalDate().plusDays(7).atTime(18, 0).atZone(now.zone).toInstant()
    return start to start.plusSeconds(3600)
}

fun planTitle(f: Friend): String = "Time with " + f.name

// JS's \w without the u flag: ASCII letters, digits and underscore only.
private val NOT_FILENAME_SAFE = Regex("[^A-Za-z0-9_\\- ]")

/** time-with-<name>.ics, sanitised first, then "friend" when nothing is left. */
fun icsFileName(name: String?): String =
    "time-with-" + (name.orEmpty().replace(NOT_FILENAME_SAFE, "").trim().ifEmpty { "friend" }) + ".ics"
