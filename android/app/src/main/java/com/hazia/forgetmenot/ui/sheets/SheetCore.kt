package com.hazia.forgetmenot.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hazia.forgetmenot.data.Records
import com.hazia.forgetmenot.ui.theme.AppTheme
import com.hazia.forgetmenot.ui.theme.RowHitArea
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// The parts every sheet shares: the sheet itself (www/js/ui/overlays.js and the .sheet markup),
// its title row, and the controls at their SPEC §7 size tiers. Public, so the screens that
// build their own sheets (groups) look the same.

/**
 * The open sheets, bottom to top, by token. Only the topmost draws the toast; 0 is the screen.
 * Sheets normally close top first, but a list keeps the answer right if one under closes first.
 */
object SheetStack {
    private val open = MutableStateFlow<List<Long>>(emptyList())
    private var seq = 0L
    val top: StateFlow<Long> get() = _top
    private val _top = MutableStateFlow(0L)

    internal fun push(): Long = (++seq).also { t -> open.update { it + t }; _top.value = open.value.lastOrNull() ?: 0L }
    internal fun pop(token: Long) { open.update { it - token }; _top.value = open.value.lastOrNull() ?: 0L }
}

/** The sheet (token) a composable is drawn in; 0 on a screen. */
val LocalSheetLevel = compositionLocalOf { 0L }

/**
 * A modal bottom sheet with a heading and a Close button. Every way it closes (Close, back,
 * scrim tap, swipe, or [content]'s own `close()`) ends in [onDismiss], once, which is where the
 * per-sheet hooks of closeOverlay() go. The sheet's pane is named from [title] at open time, as
 * openOverlay() copied the live heading into aria-label.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val reduced = AppTheme.reducedMotion
    var closing by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (!closing) {
            closing = true
            if (reduced) onDismiss()
            else scope.launch { state.hide() }.invokeOnCompletion { onDismiss() }
        }
    }
    val token = remember { SheetStack.push() }
    DisposableEffect(token) { onDispose { SheetStack.pop(token) } }

    ModalBottomSheet(
        onDismissRequest = { if (!closing) { closing = true; onDismiss() } },
        sheetState = state,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalSheetLevel provides token) {
            Box {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 16.dp)
                        .navigationBarsPadding()
                        .semantics { paneTitle = title },
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SheetTitle(title, close)
                    content(close)
                }
                ToastHost(Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
fun SheetTitle(title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        SupportIconButton(AppIcon.X, "Close", onClose)
    }
}

/** The muted line under a sheet's title. */
@Composable
fun SheetIntro(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.textMuted)
}

/** An upper-case section label (`.field-label`). */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier.semantics { heading() },
        style = MaterialTheme.typography.labelSmall,
        color = AppTheme.colors.textMuted,
    )
}

/** T1: a sheet's Save, full width. */
@Composable
fun SheetSaveButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(AppTheme.sizes.primary),
        shape = MaterialTheme.shapes.medium,
    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
}

/**
 * T3: an inline text button (Restore, Undo, Cancel, Select all). labelSmall and 8/4 padding so it
 * fits its 32 dp; its hit area is capped at a row's 40 dp so it never reaches the next row.
 */
@Composable
fun InlineTextButton(
    label: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    RowHitArea {
        TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = AppTheme.sizes.support),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = color),
        ) { Text(label, style = MaterialTheme.typography.labelSmall) }
    }
}

/** T3: an icon button, drawn at 32 dp; [label] is its accessible name. */
@Composable
fun SupportIconButton(icon: AppIcon, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, iconSize: androidx.compose.ui.unit.Dp = 16.dp) {
    RowHitArea {
        IconButton(onClick = onClick, modifier = modifier.size(AppTheme.sizes.support).semantics { contentDescription = label }) {
            Glyph(icon, iconSize)
        }
    }
}

/** T2: a toggle chip (cadence presets, kinds). Selected state is announced as on/off. */
@Composable
fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        modifier = modifier.height(AppTheme.sizes.choice),
        shape = MaterialTheme.shapes.small,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

/** Re-reads whenever the cache changes: `val rev = observeRecords(records)`, then `remember(rev) { … }`. */
@Composable
fun observeRecords(records: Records): Long {
    val rev by records.store.revision.collectAsState()
    return rev
}

/**
 * T2: a date shown in the device's format; tapping opens the system-styled date picker. [value]
 * and [onChange] are ISO `yyyy-MM-dd` (what `<input type="date">` held). [max] caps the pick
 * (the log-date sheet's "no later than today").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    max: LocalDate? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val date = runCatching { LocalDate.parse(value) }.getOrNull()
    val shown = date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) ?: "Pick a date"
    OutlinedButton(
        onClick = { open = true },
        modifier = modifier.fillMaxWidth().height(AppTheme.sizes.choice).testTag("date-field")
            .semantics { contentDescription = "$label, $shown" },
        shape = MaterialTheme.shapes.small,
    ) { Text(shown, Modifier.fillMaxWidth(), textAlign = TextAlign.Start, color = MaterialTheme.colorScheme.onSurface) }

    if (open) {
        val maxMs = max?.toEpochDay()?.times(DAY_MS)
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = date?.toEpochDay()?.times(DAY_MS),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = maxMs == null || utcTimeMillis <= maxMs
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { ms ->
                        onChange(Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC).toLocalDate().toString())
                    }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(picker) }
    }
}

private const val DAY_MS = 86_400_000L
