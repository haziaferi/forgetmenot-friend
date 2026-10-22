package com.hazia.forgetmenot.reminders

import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.domain.Group
import com.hazia.forgetmenot.domain.MAX_SCHEDULED_NOTIFICATIONS
import com.hazia.forgetmenot.domain.Recurring
import com.hazia.forgetmenot.domain.ScheduledCheckIn
import com.hazia.forgetmenot.domain.Settings
import com.hazia.forgetmenot.domain.atNine
import com.hazia.forgetmenot.domain.birthdayBody
import com.hazia.forgetmenot.domain.computeNextEventDate
import com.hazia.forgetmenot.domain.groupCheckIns
import com.hazia.forgetmenot.domain.hashToId
import com.hazia.forgetmenot.domain.jsLocalDate
import com.hazia.forgetmenot.domain.namesLine
import com.hazia.forgetmenot.domain.nextCheckIn
import com.hazia.forgetmenot.domain.nextYearlyOccurrence
import com.hazia.forgetmenot.domain.ymd
import kotlinx.serialization.Serializable
import java.time.ZonedDateTime

// Port of buildReminders() in www/js/features/notifications.js (SPEC §4 "The five kinds"):
// the whole pending set, built before anything is cancelled, as plain data.

enum class ReminderKind { CHECKIN, CHECKINS, BIRTHDAY, ANNIVERSARY, DATE, GROUP_EVENT }

/** A nudge's buttons. Wished them logs exactly as Logged it does; only the label differs. */
enum class ReminderAction(val label: String) {
    LOGGED("Logged it"),
    LATER("Next week"),
    WISHED("Wished them"),
}

/**
 * One scheduled notification. [id] is the hashed, namespaced id (SPEC §4 "Notification ids"), used
 * as the alarm's request code and the posted notification's id, so a rebuild replaces it in place.
 */
@Serializable
data class Reminder(
    val id: Int,
    val kind: ReminderKind,
    val title: String,
    val body: String,
    val atMs: Long,
    /** The friends it is about; the buttons act on these. */
    val friendIds: List<String> = emptyList(),
    /** A tap on the body opens this friend's detail screen; null opens home. */
    val openFriendId: String? = null,
) {
    /** CHECKIN_ACTION_TYPE, CHECKIN_GROUP_ACTION_TYPE and BIRTHDAY_ACTION_TYPE; the rest have none. */
    val actions: List<ReminderAction>
        get() = when (kind) {
            ReminderKind.CHECKIN -> listOf(ReminderAction.LOGGED, ReminderAction.LATER)
            ReminderKind.CHECKINS -> listOf(ReminderAction.LATER)
            ReminderKind.BIRTHDAY -> listOf(ReminderAction.WISHED)
            else -> emptyList()
        }
}

/** scheduleNativeReminders()'s list: empty in quiet mode, so the cancel pass still tears everything down. */
fun planReminders(settings: Settings, friends: List<Friend>, groups: List<Group>, now: ZonedDateTime): List<Reminder> =
    if (settings.quietMode) emptyList() else buildReminders(friends, groups, now)

/**
 * Every reminder for the non-archived [friends] and the [groups], sorted and truncated to the
 * soonest [cap] when over it. Each date is computed on its own: one that throws or is malformed
 * skips only its own reminder, so one bad synced record cannot cost everyone theirs.
 */
fun buildReminders(
    friends: List<Friend>,
    groups: List<Group>,
    now: ZonedDateTime,
    cap: Int = MAX_SCHEDULED_NOTIFICATIONS,
): List<Reminder> {
    val active = friends.filter { !it.archived }
    val byId = friends.associateBy { it.id }
    val out = ArrayList<Reminder>()
    fun add(r: () -> Reminder?) { safe(r)?.let(out::add) }

    val checkIns = ArrayList<ScheduledCheckIn>()
    for (f in active) {
        safe { nextCheckIn(f, now) }?.let { checkIns += ScheduledCheckIn(f, it) }
        // Pausing stops the check-in nudges, not the birthday or the friend's own dates.
        f.birthday?.takeIf { it.isNotEmpty() }?.let { iso ->
            add {
                nextYearlyOccurrence(iso, now)?.let {
                    Reminder(hashToId("birthday:" + f.id), ReminderKind.BIRTHDAY, "🎂 " + f.name + "’s birthday",
                        birthdayBody(f), it.ms(), listOf(f.id), f.id)
                }
            }
        }
        // A dated entry (an interview, a move): yearly ones recur, a one-off fires once on its day.
        for (d in f.dates) add {
            val at = if (d.yearly) nextYearlyOccurrence(d.dateISO, now)
            else ymd(d.dateISO)?.let { (y, m, day) -> jsLocalDate(y, m, day).atNine(now.zone) }
            if (at == null || (!d.yearly && at.ms() < now.ms() - 60_000)) null
            else Reminder(hashToId("date:" + f.id + ":" + d.id), ReminderKind.DATE,
                "📅 " + d.label.ifEmpty { "A date" } + " · " + f.name, "Today’s the day — maybe check in.",
                at.ms(), listOf(f.id), f.id)
        }
        f.anniversary?.takeIf { it.isNotEmpty() }?.let { iso ->
            add {
                nextYearlyOccurrence(iso, now)?.let {
                    // As in the JS, the anniversary carries no friend: no button, and a tap opens home.
                    Reminder(hashToId("anniversary:" + f.id), ReminderKind.ANNIVERSARY, "💞 Anniversary with " + f.name,
                        "Today marks it — worth a message.", it.ms())
                }
            }
        }
    }

    // One nudge per friend, unless several fall within a few hours of each other: then one
    // notification names them all, at the time of the first.
    for (run in groupCheckIns(checkIns)) add {
        val first = run[0]
        if (run.size == 1) {
            val f = first.friend
            val body = f.askNext?.takeIf { it.isNotEmpty() }?.let { "Maybe ask about $it." }
                ?: "It’s been a little while — maybe say hi."
            Reminder(hashToId("checkin:" + f.id), ReminderKind.CHECKIN, "Thinking of " + f.name + "?", body,
                first.at.ms(), listOf(f.id), f.id)
        } else {
            Reminder(hashToId("checkins:" + first.at.ms()), ReminderKind.CHECKINS, "A few people you might think of",
                namesLine(run.map { it.friend.name }) + ". Maybe say hi.", first.at.ms(), run.map { it.friend.id })
        }
    }

    for (g in groups) {
        val names = g.memberIds.mapNotNull { byId[it] }.filter { !it.archived }.map { it.name }
        val who = if (names.size <= 2) names.joinToString(" & ")
        else names.take(2).joinToString(", ") + " + " + (names.size - 2) + " more"
        for (ev in g.events) add {
            val next = computeNextEventDate(ev, now)
            if (next == null || (ev.recurring == Recurring.ONCE && next.ms() < now.ms() - 60_000)) null
            else Reminder(hashToId("gevent:" + g.id + ":" + ev.id), ReminderKind.GROUP_EVENT, "🎉 " + ev.title,
                if (who.isNotEmpty()) "With " + who + (if (g.name.isNotEmpty()) " (" + g.name + ")" else "") else "Today’s the day.",
                next.ms())
        }
    }

    // Android drops alarms past ~500 without saying so; choose which ones rather than let it.
    return if (out.size > cap) out.sortedBy { it.atMs }.take(cap) else out
}

private fun ZonedDateTime.ms() = toInstant().toEpochMilli()

private inline fun <T> safe(fn: () -> T?): T? = try { fn() } catch (e: Exception) { null }
