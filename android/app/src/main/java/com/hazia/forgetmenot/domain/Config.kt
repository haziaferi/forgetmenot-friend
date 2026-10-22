package com.hazia.forgetmenot.domain

// Port of www/js/config.js: the pure constants every layer shares.

/** Storage keys (SPEC §2 "Keys, files and the cache"). The `fm_` prefix is what backup import filters on. */
object Keys {
    const val FRIEND_INDEX = "fm_friend_index"
    const val TAG_INDEX = "fm_tag_index"
    const val GROUP_INDEX = "fm_group_index"
    const val SETTINGS = "fm_settings"
    const val FRIEND_PREFIX = "fm_friend:"
    const val TAG_PREFIX = "fm_tag:"
    const val GROUP_PREFIX = "fm_group:"
    fun friend(id: String) = FRIEND_PREFIX + id
    fun tag(id: String) = TAG_PREFIX + id
    fun group(id: String) = GROUP_PREFIX + id
}

/** A group event's `recurring`. Kept as strings so a value from a newer build round-trips. */
object Recurring {
    const val ONCE = "once"
    const val MONTHLY = "monthly"
    const val YEARLY = "yearly"
}

data class EventRecurrence(val id: String, val label: String)
val EVENT_RECURRENCE = listOf(
    EventRecurrence(Recurring.ONCE, "Once"),
    EventRecurrence(Recurring.MONTHLY, "Monthly"),
    EventRecurrence(Recurring.YEARLY, "Yearly"),
)

// Android silently drops pending alarms past ~500 per app. Keeping the soonest 400 lets
// us choose what waits; every resume reschedules, so later ones are deferred, not lost.
const val MAX_SCHEDULED_NOTIFICATIONS = 400

// Bump when the record shape changes in a way an older build could not read; import
// refuses a backup with a higher version. A backup without one is read as 1.
const val BACKUP_VERSION = 1

// How far "Next week" on a nudge pushes that friend's next one.
const val SNOOZE_DAYS = 7

data class ContactKind(val label: String, val phrase: String)

// How a logged reach-out happened; optional on every entry, and set or changed afterwards.
val CONTACT_KINDS: Map<String, ContactKind> = linkedMapOf(
    "call" to ContactKind("Call", "by call"),
    "meet" to ContactKind("Met up", "in person"),
    "text" to ContactKind("Message", "by message"),
)

data class CadencePreset(val label: String, val days: Int)
val CADENCE_PRESETS = listOf(
    CadencePreset("1 week", 7),
    CadencePreset("2 weeks", 14),
    CadencePreset("1 month", 30),
    CadencePreset("3 months", 90),
    CadencePreset("6 months", 182),
    CadencePreset("1 year", 365),
)

const val DEFAULT_CADENCE_DAYS = 90

/**
 * Skin key to display name, matching `ui/theme/ThemeTokens.kt`'s Skin enum (which owns the
 * colours). Backup import coerces any skin not listed here to "blue", so every skin goes here.
 */
val THEMES: Map<String, String> = linkedMapOf(
    "blue" to "Cyanotype",
    "green" to "Celadon",
    "pink" to "Dusk",
    "amber" to "Apricot",
    "lavender" to "Provence",
)

data class DefaultTag(val name: String, val cadenceDays: Int)
val DEFAULT_TAGS = listOf(
    DefaultTag("Family", 30),
    DefaultTag("Close friends", 14),
    DefaultTag("Friends", 60),
)
