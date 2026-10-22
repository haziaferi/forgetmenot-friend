package com.hazia.forgetmenot.ui.screens.detail

import com.hazia.forgetmenot.data.Records
import com.hazia.forgetmenot.domain.Gift
import com.hazia.forgetmenot.domain.addContact
import com.hazia.forgetmenot.domain.isoTimestamp
import com.hazia.forgetmenot.domain.localDateISO
import com.hazia.forgetmenot.domain.uid
import com.hazia.forgetmenot.ui.sheets.EntryList
import com.hazia.forgetmenot.ui.sheets.RecentChanges
import com.hazia.forgetmenot.ui.sheets.Toaster
import com.hazia.forgetmenot.ui.sheets.undoAddedEntry
import com.hazia.forgetmenot.ui.sheets.undoRemovedContact
import java.time.ZonedDateTime

// What the detail screen's taps write (the handlers in renderDetail(), www/js/screens/detail.js).
// Each re-reads the friend first, so a tap never saves over a change made since the screen drew,
// and each undo does nothing once a later change has already put things back. Saves go through
// Records, which reschedules reminders (P3) and does nothing on disk in a read-only session (I6).

fun setAskNext(records: Records, id: String, text: String) {
    val f = records.getFriend(id) ?: return
    records.saveFriend(f.copy(askNext = text))
}

fun clearAskNext(records: Records, id: String) {
    val f = records.getFriend(id) ?: return
    val before = f.askNext
    records.saveFriend(f.copy(askNext = null))
    RecentChanges.record("Cleared what to ask ${f.name}") {
        val x = records.getFriend(id)
        if (x != null && x.askNext.isNullOrEmpty()) records.saveFriend(x.copy(askNext = before))
    }
    Toaster.showWithUndo("Cleared")
}

fun addGift(records: Records, id: String, text: String, now: ZonedDateTime) {
    val f = records.getFriend(id) ?: return
    val entry = Gift(id = uid(), text = text, addedAt = isoTimestamp(now.toInstant()))
    records.saveFriend(f.copy(gifts = f.gifts + entry))
    RecentChanges.record("Gift idea for ${f.name}") {
        val x = records.getFriend(id)
        if (x != null && x.gifts.any { it.id == entry.id }) records.saveFriend(x.copy(gifts = x.gifts.filter { it.id != entry.id }))
    }
    Toaster.showWithUndo("Gift idea saved")
}

fun removeGift(records: Records, id: String, giftId: String) {
    val f = records.getFriend(id) ?: return
    val entry = f.gifts.firstOrNull { it.id == giftId } ?: return
    records.saveFriend(f.copy(gifts = f.gifts.filter { it.id != giftId }))
    RecentChanges.record("Removed a gift idea for ${f.name}") {
        val x = records.getFriend(id)
        if (x != null && x.gifts.none { it.id == entry.id }) records.saveFriend(x.copy(gifts = x.gifts + entry))
    }
    Toaster.showWithUndo("Removed “${entry.text}”")
}

fun removeDate(records: Records, id: String, dateId: String) {
    val f = records.getFriend(id) ?: return
    val entry = f.dates.firstOrNull { it.id == dateId } ?: return
    val label = entry.label.ifEmpty { "a date" }
    records.saveFriend(f.copy(dates = f.dates.filter { it.id != dateId }))
    RecentChanges.record("Removed “$label” for ${f.name}") {
        val x = records.getFriend(id)
        if (x != null && x.dates.none { it.id == entry.id }) records.saveFriend(x.copy(dates = x.dates + entry))
    }
    Toaster.showWithUndo("Removed “$label”")
}

fun removeContact(records: Records, id: String, contactId: String, now: ZonedDateTime) {
    val f = records.getFriend(id) ?: return
    val entry = f.contacts.firstOrNull { it.id == contactId } ?: return
    records.saveFriend(f.copy(contacts = f.contacts.filter { it.id != contactId }))
    RecentChanges.record("Removed a reach-out with ${f.name}", undoRemovedContact(records, id, entry))
    Toaster.showWithUndo("Removed the reach-out on " + shortDate(entry.date, now))
}

/** The pause switch; the whole row toggles. */
fun togglePause(records: Records, id: String) {
    val f = records.getFriend(id) ?: return
    records.saveFriend(f.copy(paused = !f.paused))
}

/** Pause until [day] (YYYY-MM-DD); a day that is not after today clears it, as the JS did. */
fun setPausedUntil(records: Records, id: String, day: String?, now: ZonedDateTime) {
    val f = records.getFriend(id) ?: return
    val value = day?.takeIf { it.isNotEmpty() && it > localDateISO(now) }
    records.saveFriend(f.copy(pausedUntil = value))
}

fun clearSnooze(records: Records, id: String) {
    val f = records.getFriend(id) ?: return
    records.saveFriend(f.copy(snoozedUntil = null))
}

/**
 * Archive: deletes nothing and can be undone, so it acts at once instead of asking first. The
 * caller goes home; the toast is app-wide, so it outlives this screen.
 */
fun archiveFriend(records: Records, id: String) {
    val f = records.getFriend(id) ?: return
    records.saveFriend(f.copy(archived = true))
    RecentChanges.record("Archived ${f.name}") {
        val x = records.getFriend(id)
        if (x != null && x.archived) records.saveFriend(x.copy(archived = false))
    }
    Toaster.showWithUndo("Archived ${f.name}")
}
