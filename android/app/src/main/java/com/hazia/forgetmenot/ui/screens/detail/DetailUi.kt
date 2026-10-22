package com.hazia.forgetmenot.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.ui.sheets.AvatarRing
import com.hazia.forgetmenot.ui.theme.AppTheme
import com.hazia.forgetmenot.ui.theme.RowHitArea
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// The detail screen's pieces: its icons, the avatar ring, cards and rows (the .card / .card-row
// markup in renderDetail() and app.css). Private to this package; the shared controls (T3 text
// and icon buttons, chips) come from ui/sheets/.

/**
 * www/js/ui/icons.js and the header's inline SVGs, as stroked vectors. Arc flags are spelled out
 * ("a2 2 0 0 1 2 2", not "a2 2 0 012 2"): Compose's path parser reads SVG's packed flags as one
 * number and draws the arc garbled (the same rule as ui/sheets/Icons.kt; DetailIconsTest guards it).
 */
internal object DetailIcons {
    /** Every path string above, for DetailIconsTest. */
    internal val paths = mutableListOf<String>()

    val back = icon("M19 12H5 M11 5l-7 7 7 7")
    val edit = icon("M12 20h9 M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z")
    val check = icon("M5 12l5 5L20 7")
    val mail = icon("M4 6h16v12H4z M4 7l8 6 8-6")
    val calendar = icon("M4 5h16v16H4z M4 9h16 M8 3v4 M16 3v4")
    val share = icon("M18 8a3 3 0 1 0 0 -6 3 3 0 0 0 0 6z M6 15a3 3 0 1 0 0 -6 3 3 0 0 0 0 6z M18 22a3 3 0 1 0 0 -6 3 3 0 0 0 0 6z M8.6 13.5l6.8 3.9 M15.4 6.6L8.6 10.5")
    val cake = icon("M20 21v-8a2 2 0 0 0 -2 -2H6a2 2 0 0 0 -2 2v8 M4 16s.5-1 2-1 2 1 3.5 1 2-1 3.5-1 2 1 3.5 1 2-1 2-1 M12 11V7 M9 7a1.5 1.5 0 1 1 1.5 -1.5A1.5 1.5 0 0 1 9 7z M12 7a1.5 1.5 0 1 1 1.5 -1.5A1.5 1.5 0 0 1 12 7z M15 7a1.5 1.5 0 1 1 1.5 -1.5A1.5 1.5 0 0 1 15 7z M2 21h20")
    val gift = icon("M20 12v9H4v-9 M2 7h20v5H2z M12 22V7 M12 7H7.5a2.5 2.5 0 0 1 0 -5C11 2 12 7 12 7z M12 7h4.5a2.5 2.5 0 0 0 0 -5C13 2 12 7 12 7z")
    val heart = icon("M20.8 4.6a5.5 5.5 0 0 0 -7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0 -7.8 7.8l1 1L12 21l7.8-7.8 1-1a5.5 5.5 0 0 0 0 -7.8z")
    val plus = icon("M12 5v14 M5 12h14")
    val chevronDown = icon("M6 9l6 6 6-6")

    private fun icon(d: String): ImageVector = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(
        pathData = addPathNodes(d),
        fill = null,
        stroke = SolidColor(Color.Black), // Icon tints it
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ).build().also { paths += d }
}

@Composable
internal fun RowIcon(icon: ImageVector, size: Dp = 15.dp, tint: Color = AppTheme.colors.textMuted) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(size), tint = tint)
}

/** The ring's progress (classify().pct), exposed for tests; the ring itself is decorative. */
val AvatarProgress = SemanticsPropertyKey<Float>("AvatarProgress")
private var SemanticsPropertyReceiver.avatarProgress by AvatarProgress

/** The shared ring (ui/sheets/AvatarRing.kt) at detail's 96 dp, its progress exposed for tests. */
@Composable
internal fun DetailAvatar(f: Friend, pct: Float) = AvatarRing(
    f.name, f.avatar, pct, 96.dp,
    Modifier.testTag(DetailTags.AVATAR).semantics { avatarProgress = pct },
)

/** The header-nav row: back and edit, no title (the name below is the heading). */
@Composable
internal fun DetailHeader(onBack: () -> Unit, onEdit: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        HeaderIconButton(DetailIcons.back, "Back", onBack, 19.dp)
        Spacer(Modifier.weight(1f))
        HeaderIconButton(DetailIcons.edit, "Edit friend", onEdit, 17.dp)
    }
}

/** T3 icon button: 32 dp drawn, the 48 dp hit area added by the theme. */
@Composable
private fun HeaderIconButton(icon: ImageVector, label: String, onClick: () -> Unit, iconSize: Dp) {
    IconButton(onClick = onClick, modifier = Modifier.size(AppTheme.sizes.support)) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(iconSize))
    }
}

/** `.card`: rows on the surface colour, divided by a hairline. */
@Composable
internal fun DetailCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier.fillMaxWidth().padding(bottom = 10.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
    ) { Column(content = content) }
}

@Composable
internal fun RowDivider() = HorizontalDivider(color = AppTheme.colors.border)

/**
 * `.card-row`: one line of a card. With [onClick] the whole row is the button (role=button
 * rows: ask-about, Add a date, cadence). Inline controls sit in [RowHitArea] so their hit areas
 * stop at the row's edge.
 */
@Composable
internal fun CardRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = AppTheme.sizes.hit)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { RowHitArea { content() } }
}

/** `.row-label`: an icon and a label, taking the row's free width. */
@Composable
internal fun RowScope.RowLabel(icon: ImageVector, text: String, textModifier: Modifier = Modifier) {
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        RowIcon(icon)
        Spacer(Modifier.width(8.dp))
        Text(text, textModifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** `.empty-sub`: muted small text. */
@Composable
internal fun Muted(text: String, modifier: Modifier = Modifier, align: TextAlign? = null) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.textMuted, textAlign = align)
}

/** `.section-label`: a level-2 heading over a card. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier.padding(top = 8.dp, bottom = 6.dp, start = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.labelSmall,
        color = AppTheme.colors.textMuted,
    )
}

/** `.chip.chip-decorative`: a tag, not interactive. */
@Composable
internal fun DecorativeChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier
            .background(AppTheme.colors.decorative, CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        color = AppTheme.colors.onDecorative,
        style = MaterialTheme.typography.labelLarge,
    )
}

/**
 * The pause-until date (`<input type="date" min=tomorrow>`): T2, the device's date format,
 * tapping opens the date picker limited to days after [today]. [onPick] gets YYYY-MM-DD.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PauseUntilField(value: String?, today: LocalDate, label: String, onPick: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val date = value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val shown = date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) ?: "Pick a date"
    OutlinedButton(
        onClick = { open = true },
        modifier = Modifier.height(AppTheme.sizes.choice).semantics { contentDescription = "$label, $shown" },
        shape = MaterialTheme.shapes.small,
    ) { Text(shown, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium) }

    if (open) {
        val minMs = today.plusDays(1).toEpochDay() * DAY_MS
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = date?.toEpochDay()?.times(DAY_MS),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= minMs
                override fun isSelectableYear(year: Int) = year >= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { ms -> onPick(Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC).toLocalDate().toString()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(picker) }
    }
}

private const val DAY_MS = 86_400_000L
