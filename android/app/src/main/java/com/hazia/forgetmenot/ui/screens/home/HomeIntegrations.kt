package com.hazia.forgetmenot.ui.screens.home

import com.hazia.forgetmenot.data.Records
import com.hazia.forgetmenot.domain.latestContact
import com.hazia.forgetmenot.domain.parseInstant
import com.hazia.forgetmenot.ui.sheets.logToday

// Hooks home exposes for P6 (messaging intents) and the detail screen. Home itself sends no
// intent and reads no contacts; these are the seams those phases plug into.

/**
 * "After Message" (SPEC §4, offerLogAfterMessage() in www/js/screens/home.js). The app cannot
 * see whether a message was sent, so it asks once: whoever hands off to a messaging app calls
 * [handedOff]; on the next return, [offer] shows "Talked to Ana?" with a Log it button, if that
 * friend has not been logged since and it is within 12 hours. An ignored offer lapses with the
 * toast. Detail's Message button calls [handedOff]; home and detail each call [offer] on their
 * ON_RESUME. Those are back stack entry lifecycles, so only the screen on top asks: one caller
 * per return, and coming back to a screen in-app asks too (pending is usually empty by then).
 */
object AfterMessage {
    const val WINDOW_MS = 12 * 3600 * 1000L

    data class Pending(val friendId: String, val atMs: Long)

    @Volatile var pending: Pending? = null
        private set

    fun handedOff(friendId: String, atMs: Long = System.currentTimeMillis()) { pending = Pending(friendId, atMs) }

    /**
     * Shows the offer if one is due; true when it did. A widget-log toast on the same return
     * takes precedence. Not wired yet: the widget drain (P6) runs only at start, and it has no
     * toast; when P6 adds one on resume, it should skip [offer] (or clear [pending]) there.
     */
    fun offer(records: Records, nowMs: Long = System.currentTimeMillis()): Boolean {
        val p = pending ?: return false
        pending = null
        if (nowMs - p.atMs > WINDOW_MS) return false
        val f = records.getFriend(p.friendId) ?: return false
        val last = latestContact(f)?.let { parseInstant(it.date) }
        if (last != null && last.toEpochMilli() >= p.atMs) return false // logged since
        com.hazia.forgetmenot.ui.sheets.Toaster.showWithAction("Talked to ${f.name.split(' ')[0]}?", "Log it") {
            logToday(records, f.id, kind = "text")
        }
        return true
    }

    /** Tests only. */
    fun reset() { pending = null }
}
