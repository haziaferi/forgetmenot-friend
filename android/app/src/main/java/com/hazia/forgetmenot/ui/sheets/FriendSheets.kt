package com.hazia.forgetmenot.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.toggleable
import com.hazia.forgetmenot.data.Records
import com.hazia.forgetmenot.domain.CADENCE_PRESETS
import com.hazia.forgetmenot.domain.CONTACT_KINDS
import com.hazia.forgetmenot.domain.FriendDate
import com.hazia.forgetmenot.domain.Note
import com.hazia.forgetmenot.domain.addContact
import com.hazia.forgetmenot.domain.isoTimestamp
import com.hazia.forgetmenot.domain.uid
import com.hazia.forgetmenot.ui.theme.AppTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Port of the friend sheets in www/js/ui/sheets.js: cadence, add-a-date, log-date, kind and
// note. Each saves on its own, records its change for undo and toasts, as the JS did, so the
// screens that open them (home, detail, add) only decide when.

// ── cadence ─────────────────────────────────────────────────────────────────

/** Days for a manual cadence: d + 30·m + 365·y, blanks as 0 (www/js/ui/sheets.js confirmManualCadence). */
fun manualCadenceDays(days: String, months: String, years: String): Int {
    fun n(s: String) = s.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
    return n(days) + n(months) * 30 + n(years) * 365
}

/**
 * "How often": the six presets, or days/months/years typed by hand. [current] is the friend's
 * cadence (its preset chip shows pressed). [savesAtOnce] picks the hint: true from detail
 * ("Saved as soon as you pick."), false from the add form ("Applied when you save this
 * friend."). [onPick] gets the days; the caller saves or stores them. Closes after a pick.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CadenceSheet(current: Int, savesAtOnce: Boolean, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    var d by rememberSaveable { mutableStateOf("") }
    var m by rememberSaveable { mutableStateOf("") }
    var y by rememberSaveable { mutableStateOf("") }
    AppSheet("How often", onDismiss) { close ->
        SheetIntro(if (savesAtOnce) "Saved as soon as you pick." else "Applied when you save this friend.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.sizes.chipGap), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in CADENCE_PRESETS) ChoiceChip(p.label, p.days == current, onClick = { onPick(p.days); close() })
        }
        Surface(color = MaterialTheme.colorScheme.background, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Or set it manually", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((label, value, set) in listOf(
                        Triple("days", d) { s: String -> d = s },
                        Triple("months", m) { s: String -> m = s },
                        Triple("years", y) { s: String -> y = s },
                    )) {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { s -> set(s.filter(Char::isDigit).take(4)) },
                            modifier = Modifier.weight(1f).testTag("manual-$label"),
                            label = { Text(label) },
                            placeholder = { Text("0") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    InlineTextButton("Cancel") { d = ""; m = ""; y = "" }
                    OutlinedButton(onClick = {
                        val total = manualCadenceDays(d, m, y)
                        if (total <= 0) Toaster.show("Set at least one value")
                        else { onPick(total); close() }
                    }, modifier = Modifier.heightIn(min = AppTheme.sizes.choice)) { Text("Confirm") }
                }
            }
        }
    }
}

// ── add a date ──────────────────────────────────────────────────────────────

/**
 * "Add a date": a dated entry on a friend ("Interview", 12 Nov), once or every year; it gets its
 * own reminder. A blank label saves as "A date". Undoable.
 */
@Composable
fun DateSheet(records: Records, friendId: String, onDismiss: () -> Unit, today: () -> LocalDate = LocalDate::now) {
    var label by rememberSaveable { mutableStateOf("") }
    var value by rememberSaveable { mutableStateOf(today().toString()) }
    var yearly by rememberSaveable { mutableStateOf(false) }
    AppSheet("Add a date", onDismiss) { close ->
        FieldLabel("What's happening")
        OutlinedTextField(
            value = label, onValueChange = { label = it },
            modifier = Modifier.fillMaxWidth().testTag("date-label"),
            placeholder = { Text("An interview, a move, a first day") }, singleLine = true,
        )
        FieldLabel("When")
        DateField(value, { value = it }, "When")
        Row(
            Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.rowHit)
                .toggleable(yearly, role = Role.Checkbox, onValueChange = { yearly = it }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = yearly, onCheckedChange = null)
            Text("Every year", Modifier.padding(start = 8.dp))
        }
        SheetSaveButton("Save") {
            val f = records.getFriend(friendId)
            if (f == null || runCatching { LocalDate.parse(value) }.isFailure) { close(); return@SheetSaveButton }
            val entry = FriendDate(id = uid(), label = label.trim().ifEmpty { "A date" }, dateISO = value, yearly = yearly)
            records.saveFriend(f.copy(dates = f.dates + entry))
            RecentChanges.record("Added “${entry.label}” for ${f.name}", undoAddedEntry(records, friendId, EntryList.DATES, entry.id))
            close()
            Toaster.showWithUndo("Added “${entry.label}”")
        }
    }
}

// ── log a past reach-out ────────────────────────────────────────────────────

/** One quick pick on the log-date sheet: [label] "Yesterday", [sub] its date, [date] the day. */
data class LogDatePick(val label: String, val sub: String, val date: LocalDate)

/**
 * The days a back-dated log usually means, one tap each: yesterday, the day before, and the
 * Saturday of the last weekend that is over (a week back if today is the weekend).
 */
fun logDatePicks(today: LocalDate, locale: Locale = Locale.getDefault()): List<LogDatePick> {
    val jsDow = today.dayOfWeek.value % 7 // JS getDay(): 0 Sunday … 6 Saturday
    val fmt = DateTimeFormatter.ofPattern("EEE d MMM", locale)
    return listOf(
        "Yesterday" to 1L,
        "2 days ago" to 2L,
        "Last weekend" to when (jsDow) { 6 -> 7L; 0 -> 8L; else -> jsDow + 1L },
    ).map { (label, back) -> today.minusDays(back).let { LogDatePick(label, it.format(fmt), it) } }
}

/** The ISO timestamp of noon local time on [date], as `new Date(dateStr + 'T12:00:00').toISOString()`. */
fun noonTimestamp(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String =
    isoTimestamp(date.atTime(12, 0).atZone(zone).toInstant())

/**
 * "Log when you reached out": an optional kind, three quick picks, or any date up to today.
 * Logs at local noon, records the change for undo, toasts "Logged for Oct 12" with Undo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogDateSheet(records: Records, friendId: String, onDismiss: () -> Unit, today: () -> LocalDate = LocalDate::now) {
    val day = remember { today() }
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    var picked by rememberSaveable { mutableStateOf(day.toString()) }
    AppSheet("Log when you reached out", onDismiss) { close ->
        val commit = { date: LocalDate ->
            val f = records.getFriend(friendId)
            if (f != null) {
                val (updated, entry) = addContact(f, noonTimestamp(date), kind)
                records.saveFriend(updated)
                RecentChanges.record("Logged ${f.name} for $date", undoAddedEntry(records, friendId, EntryList.CONTACTS, entry.id))
                close()
                Toaster.showWithUndo("Logged for " + date.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())))
            } else close()
        }
        FieldLabel("How (optional)")
        KindChips(kind) { kind = it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in logDatePicks(day)) {
                OutlinedButton(
                    onClick = { commit(p.date) },
                    modifier = Modifier.weight(1f).heightIn(min = AppTheme.sizes.primary),
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(p.label, style = MaterialTheme.typography.labelLarge)
                        Text(p.sub, style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.textMuted)
                    }
                }
            }
        }
        FieldLabel("Or pick a date")
        DateField(picked, { picked = it }, "Or pick a date", max = day)
        SheetSaveButton("Save") {
            val date = runCatching { LocalDate.parse(picked) }.getOrNull()
            if (date == null) close() else commit(date)
        }
    }
}

/** One chip per kind (Call, Met up, Message), none chosen by default; tapping the chosen one clears it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KindChips(chosen: String?, onPick: (String?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.sizes.chipGap), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((id, k) in CONTACT_KINDS) ChoiceChip(k.label, chosen == id, onClick = { onPick(if (chosen == id) null else id) })
    }
}

/**
 * "How did you reach out?" for an already logged reach-out (Log today, a nudge and a widget log
 * with no kind). Saves and closes on a pick; not on the undo list, since picking again puts any
 * choice back.
 */
@Composable
fun KindSheet(records: Records, friendId: String, contactId: String, onDismiss: () -> Unit) {
    val entry = remember { records.getFriend(friendId)?.contacts?.firstOrNull { it.id == contactId } }
    if (entry == null) { androidx.compose.runtime.LaunchedEffect(Unit) { onDismiss() }; return }
    AppSheet("How did you reach out?", onDismiss) { close ->
        KindChips(entry.kind) { k ->
            val f = records.getFriend(friendId)
            if (f != null && f.contacts.any { it.id == contactId }) {
                records.saveFriend(f.copy(contacts = f.contacts.map { if (it.id == contactId) it.copy(kind = k) else it }))
            }
            close()
        }
    }
}

// ── note ────────────────────────────────────────────────────────────────────

/** "Add a note": newest first, timestamped now. An empty note just closes. Undoable. */
@Composable
fun NoteSheet(records: Records, friendId: String, onDismiss: () -> Unit, now: () -> Instant = Instant::now) {
    var text by rememberSaveable { mutableStateOf("") }
    AppSheet("Add a note", onDismiss) { close ->
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).testTag("note-text"),
            placeholder = { Text("What do you want to remember?") },
            minLines = 4,
        )
        SheetSaveButton("Save note") {
            val t = text.trim()
            val f = records.getFriend(friendId)
            if (t.isEmpty() || f == null) { close(); return@SheetSaveButton }
            val entry = Note(id = uid(), date = isoTimestamp(now()), text = t)
            records.saveFriend(f.copy(notes = listOf(entry) + f.notes))
            RecentChanges.record("Note for ${f.name}", undoAddedEntry(records, friendId, EntryList.NOTES, entry.id))
            close()
            Toaster.showWithUndo("Note saved")
        }
    }
}

