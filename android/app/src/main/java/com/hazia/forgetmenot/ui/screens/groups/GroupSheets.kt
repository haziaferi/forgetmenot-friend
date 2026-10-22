package com.hazia.forgetmenot.ui.screens.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hazia.forgetmenot.domain.EVENT_RECURRENCE
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.domain.GroupEvent
import com.hazia.forgetmenot.domain.Recurring
import com.hazia.forgetmenot.ui.theme.AppTheme
import com.hazia.forgetmenot.ui.theme.bottomInsetPadding
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// The three group sheets (www/index.html:356-380; openGroupEditor(), openGroupMembersPicker(),
// openGroupEventEditor() in www/js/screens/groups.js). They live with the screen rather than in
// ui/sheets/ because nothing else opens them. A validation message shows under its field, inside
// the sheet: a snackbar on the screen would sit behind the sheet, where the JS toast sat on top.

/** A sheet with its live title (the level-2 heading) and a Close button; [onDismiss] runs however it closes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).bottomInsetPadding()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).semantics { heading() }.testTag(GroupsTags.SHEET_TITLE),
                )
                GroupIconButton(GroupIcons.close, "Close", close, iconSize = 16.dp)
            }
            content(close)
        }
    }
}

@Composable
private fun SheetSaveButton(text: String, onClick: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.primary)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun FieldError(text: String?) {
    if (text != null) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
    }
}

/** group-edit: a new group ([renaming] false) or a new name for one. [onSave] gets the trimmed name. */
@Composable
internal fun GroupEditSheet(renaming: Boolean, initialName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var missing by remember { mutableStateOf(false) }
    GroupSheet(if (renaming) "Rename group" else "New group", onDismiss) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; missing = false },
            label = { Text("Group name") },
            placeholder = { Text("Group name, e.g. Book club") },
            singleLine = true,
            isError = missing,
            modifier = Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.primary).testTag(GroupsTags.GROUP_NAME_FIELD),
        )
        FieldError(if (missing) "Give the group a name" else null)
        SheetSaveButton("Save") {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) missing = true else onSave(trimmed)
        }
    }
}

/**
 * group-members: every active friend, by name, with a switch. Each toggle is handed to
 * [onToggle] at once (the screen behind follows); [onDismiss] is where the caller pays the one
 * reschedule (flushGroupMemberChanges()).
 */
@Composable
internal fun GroupMembersSheet(friends: List<Friend>, memberIds: List<String>, onToggle: (String) -> Unit, onDismiss: () -> Unit) {
    GroupSheet("Members", onDismiss) {
        if (friends.isEmpty()) {
            MutedText("Add a friend first, then bring them into a group.", Modifier.padding(vertical = 8.dp, horizontal = 2.dp))
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                items(friends, key = { it.id }) { f ->
                    val inGroup = f.id in memberIds
                    // The whole row is the switch's target (SPEC §7).
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = AppTheme.sizes.primary)
                            .toggleable(value = inGroup, role = Role.Switch, onValueChange = { onToggle(f.id) })
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            f.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).testTag(GroupsTags.PICKER_NAME),
                        )
                        Switch(checked = inGroup, onCheckedChange = null)
                    }
                }
            }
        }
    }
}

/** What the event sheet hands back; the caller keeps or makes the id. */
internal data class EventForm(val title: String, val dateISO: String, val recurring: String)

/**
 * group-event: add or edit ([editing]) one event, its fields seeded once from [initial]. Recurrence
 * defaults to yearly, as in the JS. The title keeps to [editing] even if the event disappears meanwhile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupEventSheet(editing: Boolean, initial: GroupEvent?, onDismiss: () -> Unit, onSave: (EventForm) -> Unit) {
    var title by rememberSaveable { mutableStateOf(initial?.title ?: "") }
    var dateISO by rememberSaveable { mutableStateOf(initial?.dateISO ?: "") }
    var recurring by rememberSaveable { mutableStateOf(initial?.recurring ?: Recurring.YEARLY) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }

    GroupSheet(if (editing) "Edit event" else "Add event", onDismiss) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it; error = null },
            label = { Text("Event title") },
            placeholder = { Text("What's the event? e.g. Book club meetup") },
            singleLine = true,
            isError = error == TITLE_MISSING,
            modifier = Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.primary).testTag(GroupsTags.EVENT_TITLE_FIELD),
        )
        FieldError(error.takeIf { it == TITLE_MISSING })
        Spacer(Modifier.height(12.dp))
        // The inline date field is a T2 control (SPEC §7).
        OutlinedButton(
            onClick = { picking = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.choice).testTag(GroupsTags.EVENT_DATE_FIELD),
        ) {
            Text(
                parseIso(dateISO)?.let { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(it) } ?: "Event date",
                style = MaterialTheme.typography.labelMedium,
                color = if (dateISO.isEmpty()) AppTheme.colors.textMuted else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FieldError(error.takeIf { it == DATE_MISSING })
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.sizes.chipGap)) {
            for (r in EVENT_RECURRENCE) {
                FilterChip(
                    selected = r.id == recurring,
                    onClick = { recurring = r.id },
                    label = { Text(r.label, style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.height(AppTheme.sizes.choice).testTag(GroupsTags.CHIP_PREFIX + r.id),
                )
            }
        }
        SheetSaveButton("Save event") {
            val t = title.trim()
            error = when {
                t.isEmpty() -> TITLE_MISSING
                dateISO.isEmpty() -> DATE_MISSING
                else -> null
            }
            if (error == null) onSave(EventForm(t, dateISO, recurring))
        }
    }

    if (picking) {
        // The picker works in UTC midnights; the stored value is a plain YYYY-MM-DD.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = parseIso(dateISO)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        dateISO = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()
                        if (error == DATE_MISSING) error = null
                    }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

private const val TITLE_MISSING = "Give the event a title"
private const val DATE_MISSING = "Pick a date"

private fun parseIso(s: String): LocalDate? = runCatching { LocalDate.parse(s) }.getOrNull()

/**
 * The delete-group question. A minimal stand-in for the app's ask() sheet (SPEC §7 "Dialogs and
 * focus"), which P5 settings is building in ui/sheets/: switch to that once it lands.
 */
@Composable
internal fun ConfirmDeleteDialog(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, style = MaterialTheme.typography.labelLarge) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = MaterialTheme.typography.labelLarge) } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
