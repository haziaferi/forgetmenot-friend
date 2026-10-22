package com.hazia.forgetmenot.reminders

import com.hazia.forgetmenot.data.Records
import com.hazia.forgetmenot.data.StartResult
import com.hazia.forgetmenot.domain.SNOOZE_DAYS
import com.hazia.forgetmenot.domain.addContact
import com.hazia.forgetmenot.domain.isoTimestamp
import com.hazia.forgetmenot.domain.localDateISO
import com.hazia.forgetmenot.ui.sheets.EntryList
import com.hazia.forgetmenot.ui.sheets.RecentChanges
import com.hazia.forgetmenot.ui.sheets.undoAddedEntry
import kotlinx.coroutines.Deferred
import java.time.ZonedDateTime

sealed interface ActionOutcome {
    /** "Logged — nice." */
    data class Logged(val name: String) : ActionOutcome
    /** "Next nudge for Ana in a week" / "Next nudges in a week". */
    data class Snoozed(val names: List<String>) : ActionOutcome
    /** The sync folder did not load: nothing may be written (I6). */
    data object ReadOnly : ActionOutcome
    /** The ids are no longer friends. */
    data object NothingToDo : ActionOutcome
}

/**
 * What the nudge's buttons do (SPEC §4 "The nudge's buttons"). The JS app was brought forward and
 * ran logContactQuick() / snoozeFriends(); here the receiver does it in the background, so it
 * first waits for the cold-start load ([started]) and must run on the main thread, as every
 * Records call does. Writes are flushed to the folder before returning, since the process may
 * end once the receiver finishes.
 */
object ReminderActions {
    suspend fun perform(
        started: Deferred<StartResult>,
        records: Records,
        action: ReminderAction,
        ids: List<String>,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): ActionOutcome {
        if (started.await().readOnly || records.store.writesBlocked) return ActionOutcome.ReadOnly
        val out = when (action) {
            ReminderAction.LOGGED, ReminderAction.WISHED -> {
                // A grouped nudge has no one friend to log; it never carries this button.
                val f = ids.singleOrNull()?.let(records::getFriend) ?: return ActionOutcome.NothingToDo
                val (updated, entry) = addContact(f, isoTimestamp(now.toInstant()))
                records.saveFriend(updated) // saves, reschedules, pushes widgets
                // On Settings → Recent changes, as logContactQuick() put it; the system toast has
                // no button, so that list is where a mistaken press is undone.
                RecentChanges.record("Logged ${f.name}", undoAddedEntry(records, f.id, EntryList.CONTACTS, entry.id))
                ActionOutcome.Logged(f.name)
            }
            ReminderAction.LATER -> {
                // That friend's next nudge waits SNOOZE_DAYS, without touching the cadence.
                val until = localDateISO(now.plusDays(SNOOZE_DAYS.toLong()))
                val friends = ids.mapNotNull(records::getFriend)
                if (friends.isEmpty()) return ActionOutcome.NothingToDo
                for (f in friends) records.saveFriend(f.copy(snoozedUntil = until), skipSync = true)
                records.rescheduleReminders()
                ActionOutcome.Snoozed(friends.map { it.name })
            }
        }
        records.store.flushWrites()
        return out
    }
}
