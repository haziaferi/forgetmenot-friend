package com.hazia.forgetmenot.ui.screens.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hazia.forgetmenot.ui.sheets.AppIcon
import com.hazia.forgetmenot.ui.sheets.AvatarRing
import com.hazia.forgetmenot.ui.sheets.Glyph
import com.hazia.forgetmenot.ui.theme.AppTheme

// friendRowHTML() (www/js/screens/home.js; the ring is ui/sheets/AvatarRing.kt), and the
// header icons home needs that ui/sheets/Icons.kt does not carry (www/index.html:25-26,
// www/js/ui/icons.js).

/**
 * Stroked 24-unit icons from the web build's SVG paths, as AppIcon builds its own. The arc flags
 * are written out ("0 0 0 -4 4", not "000-4 4"): Compose's path parser reads the compressed SVG
 * form as one number, and the arcs draw garbled. Search and warn come from AppIcon.
 */
enum class HomeIcon(val path: String) {
    USERS("M17 21v-2a4 4 0 0 0 -4 -4H5a4 4 0 0 0 -4 4v2 M9 11a4 4 0 1 0 0 -8 4 4 0 0 0 0 8z M23 21v-2a4 4 0 0 0 -3 -3.87 M16 3.13a4 4 0 0 1 0 7.75"),
    GEAR("M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0 -6z M19 12a7 7 0 0 0 -.1 -1.2l2 -1.5 -2 -3.5 -2.3 1a7 7 0 0 0 -2.1 -1.2L14 3h-4l-.5 2.6a7 7 0 0 0 -2.1 1.2l-2.3 -1 -2 3.5 2 1.5A7 7 0 0 0 5 12a7 7 0 0 0 .1 1.2l-2 1.5 2 3.5 2.3 -1a7 7 0 0 0 2.1 1.2L10 21h4l.5 -2.6a7 7 0 0 0 2.1 -1.2l2.3 1 2 -3.5 -2 -1.5c.06 -.4 .1 -.8 .1 -1.2z"),
    MOON("M20 14.5A8.5 8.5 0 1 1 10 3.5a7 7 0 0 0 10 11z");

    val vector: ImageVector by lazy {
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = addPathNodes(path),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()
    }
}

@Composable
fun HomeGlyph(icon: HomeIcon, size: Dp, tint: Color = androidx.compose.material3.LocalContentColor.current) {
    Icon(icon.vector, null, Modifier.size(size), tint = tint)
}

/**
 * One home row: two sibling targets, open and Log, never one nested in the other. A tap on Log
 * logs today; holding it opens the date picker. Compose's long press already gives up once the
 * finger moves past touch slop, so a hold that turns into a pull is not a long press (SPEC §7
 * "Gestures").
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FriendRow(
    row: HomeRow,
    onOpen: () -> Unit,
    onLog: () -> Unit,
    onPickDate: () -> Unit,
    modifier: Modifier = Modifier,
    openFocus: FocusRequester? = null,
) {
    val f = row.friend
    val shape = MaterialTheme.shapes.large
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(1.dp, AppTheme.colors.border, shape)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clip(MaterialTheme.shapes.medium)
                .then(if (openFocus != null) Modifier.focusRequester(openFocus) else Modifier)
                .clickable(role = Role.Button, onClickLabel = "Open ${f.name}", onClick = onOpen)
                .semantics { if (row.paused) stateDescription = "Paused" }
                .testTag("open-${f.id}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AvatarRing(f.name, f.avatar, row.cl.pct.toFloat(), 50.dp)
            Text(
                f.name,
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.paused) HomeGlyph(HomeIcon.MOON, 15.dp, AppTheme.colors.textMuted)
            else Text(row.cl.code, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.textMuted, maxLines = 1)
        }
        Row(
            Modifier
                .heightIn(min = AppTheme.sizes.primary)
                .clip(CircleShape)
                .border(1.dp, AppTheme.colors.borderStrong, CircleShape)
                .combinedClickable(
                    role = Role.Button,
                    onLongClickLabel = "Pick a date",
                    onLongClick = onPickDate,
                    onClick = onLog,
                )
                .semantics { contentDescription = "Log that you reached out to ${f.name} (hold to pick a date)" }
                .padding(horizontal = 14.dp)
                .testTag("log-${f.id}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Glyph(AppIcon.CHECK, 16.dp, MaterialTheme.colorScheme.onSurface)
            Text("Log", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
