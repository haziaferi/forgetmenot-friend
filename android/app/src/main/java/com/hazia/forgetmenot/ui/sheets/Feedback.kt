package com.hazia.forgetmenot.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hazia.forgetmenot.data.Records
import com.hazia.forgetmenot.domain.Contact
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.domain.addContact
import com.hazia.forgetmenot.domain.isoTimestamp
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// Port of www/js/ui/toast.js: the one toast and the recent-changes list. Both are app-wide and
// memory only, as in the JS, so they are process singletons rather than members of one screen:
// a toast raised just before navigating (Backup imported, then home) outlives the screen.

/** One toast; [action] adds a button (Undo). [seq] tells two identical messages apart. */
data class ToastMessage(
    val text: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
    val seq: Long = 0,
)

object Toaster {
    /** Plain toasts stay 2.6 s; with a button, 10 s: long enough to notice and reach it, screen reader included. */
    const val PLAIN_MS = 2600L
    const val ACTION_MS = 10_000L

    private val _current = MutableStateFlow<ToastMessage?>(null)
    val current: StateFlow<ToastMessage?> = _current.asStateFlow()
    private var seq = 0L

    fun show(text: String) { _current.value = ToastMessage(text, seq = ++seq) }

    /** A message with one button; ignored, it simply goes away with the toast. */
    fun showWithAction(text: String, label: String, action: () -> Unit) {
        _current.value = ToastMessage(text, label, action, ++seq)
    }

    /** Offers the change just recorded, so a tap undoes that one even if another lands meanwhile. */
    fun showWithUndo(text: String) {
        val id = RecentChanges.changes.value.firstOrNull()?.id
        showWithAction(text, "Undo") { if (id != null) RecentChanges.undo(id) }
    }

    fun dismiss(msg: ToastMessage) { _current.update { if (it == msg) null else it } }

    /** Tests only. */
    fun reset() { _current.value = null }
}

/** A change on the recent-changes list, carrying what reverses it. */
data class RecentChange(val id: Long, val label: String, val at: Long, val undo: () -> Unit)

/**
 * The last five changes, newest first. Any of them can be undone, in any order, from the toast or
 * from Settings → Recent changes, so every undo works by id and does nothing if a later change
 * already took its record away. Memory only: closing the app clears the list.
 */
object RecentChanges {
    const val MAX = 5

    private val _changes = MutableStateFlow<List<RecentChange>>(emptyList())
    val changes: StateFlow<List<RecentChange>> = _changes.asStateFlow()
    private var seq = 0L
    var clock: () -> Long = System::currentTimeMillis

    fun record(label: String, undo: () -> Unit) {
        _changes.update { (listOf(RecentChange(++seq, label, clock(), undo)) + it).take(MAX) }
    }

    fun undo(id: Long) {
        val c = _changes.value.firstOrNull { it.id == id } ?: return
        _changes.update { list -> list.filter { it.id != id } }
        c.undo()
        Toaster.show("Undone: ${c.label}")
    }

    /** Tests only. */
    fun reset() { _changes.value = emptyList(); clock = System::currentTimeMillis }
}

/** Reverses adding an entry to a friend's contacts, notes or dates. */
fun undoAddedEntry(records: Records, friendId: String, list: EntryList, entryId: String): () -> Unit = {
    val f = records.getFriend(friendId)
    if (f != null && list.ids(f).contains(entryId)) records.saveFriend(list.without(f, entryId))
}

/** Reverses removing a logged reach-out: puts [entry] back first, unless it is already there. */
fun undoRemovedContact(records: Records, friendId: String, entry: Contact): () -> Unit = {
    val f = records.getFriend(friendId)
    if (f != null && f.contacts.none { it.id == entry.id }) records.saveFriend(f.copy(contacts = listOf(entry) + f.contacts))
}

/** The friend lists an undoable entry can be added to. */
enum class EntryList {
    CONTACTS, NOTES, DATES;

    internal fun ids(f: Friend): List<String> = when (this) {
        CONTACTS -> f.contacts.map { it.id }
        NOTES -> f.notes.map { it.id }
        DATES -> f.dates.map { it.id }
    }

    internal fun without(f: Friend, id: String): Friend = when (this) {
        CONTACTS -> f.copy(contacts = f.contacts.filter { it.id != id })
        NOTES -> f.copy(notes = f.notes.filter { it.id != id })
        DATES -> f.copy(dates = f.dates.filter { it.id != id })
    }
}

/**
 * Draws [Toaster]'s toast at the bottom of whatever it is placed over. MainActivity places the
 * one under every screen, so screens place none; every [AppSheet] has its own, and only the
 * topmost of them draws (a sheet's window would otherwise cover the screen's toast).
 * `role="status"`: announced politely.
 */
@Composable
fun ToastHost(modifier: Modifier = Modifier) {
    val msg by Toaster.current.collectAsState()
    val top by SheetStack.top.collectAsState()
    val level = LocalSheetLevel.current
    // Only the drawing host times the toast out, so two hosts never race to dismiss it.
    val m = msg
    if (m == null || level != top) return
    LaunchedEffect(m) {
        delay(if (m.action != null) Toaster.ACTION_MS else Toaster.PLAIN_MS)
        Toaster.dismiss(m)
    }
    Box(modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier
                .widthIn(max = 480.dp)
                .heightIn(min = 48.dp)
                .background(MaterialTheme.colorScheme.onBackground, MaterialTheme.shapes.medium)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(m.text, Modifier.weight(1f, fill = false), color = MaterialTheme.colorScheme.background, style = MaterialTheme.typography.bodyMedium)
            if (m.action != null && m.actionLabel != null) {
                InlineTextButton(m.actionLabel, color = MaterialTheme.colorScheme.background) {
                    Toaster.dismiss(m)
                    m.action.invoke()
                }
            }
        }
    }
}

/**
 * logContactQuick() in www/js/screens/home.js: logs a reach-out now, puts it on the recent-changes
 * list and offers Undo. Home's Log button, detail's Log today and Mark as wished, and the
 * After-Message offer (which passes [kind] = "text") all use it.
 */
fun logToday(records: Records, id: String, kind: String? = null, now: Instant = Instant.now()) {
    val f = records.getFriend(id) ?: return
    val (updated, entry) = addContact(f, isoTimestamp(now), kind)
    records.saveFriend(updated)
    RecentChanges.record("Logged ${f.name}", undoAddedEntry(records, id, EntryList.CONTACTS, entry.id))
    Toaster.showWithUndo("Logged — nice.")
}
