package com.hazia.forgetmenot.ui.screens.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hazia.forgetmenot.ui.sheets.AppIcon
import com.hazia.forgetmenot.ui.theme.AppTheme
import com.hazia.forgetmenot.ui.theme.RowHitArea
import com.hazia.forgetmenot.ui.screenHeading

// Pieces the groups screens and sheets share. Private to this package on purpose: the shared
// sheets and any common chrome belong to ui/sheets/ (P5 settings); fold these into those once
// they exist.

/** The web build's inline-SVG icons, from the shared set (ui/sheets/Icons.kt). */
internal object GroupIcons {
    val back = AppIcon.ARROW_LEFT.vector
    val plus = AppIcon.PLUS.vector
    val edit = AppIcon.EDIT.vector
    val trash = AppIcon.TRASH.vector
    val close = AppIcon.X.vector
    val arrowRight = AppIcon.ARROW_RIGHT.vector
}

/** A T3 icon button: 32dp drawn, the 48dp hit area added by the theme's ViewConfiguration. */
@Composable
internal fun GroupIconButton(icon: ImageVector, label: String, onClick: () -> Unit, iconSize: Dp = 19.dp) {
    IconButton(onClick = onClick, modifier = Modifier.size(AppTheme.sizes.support)) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(iconSize))
    }
}

/** The header-nav row: back, the level-1 title, one action. */
@Composable
internal fun GroupsHeader(
    title: String,
    onBack: () -> Unit,
    actionIcon: ImageVector,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupIconButton(GroupIcons.back, "Back", onBack)
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp).screenHeading().testTag(GroupsTags.TITLE),
            maxLines = 2,
        )
        GroupIconButton(actionIcon, actionLabel, onAction, iconSize = 20.dp)
    }
}

/** `.card-row .row-label .dot`: the 10dp primary dot that starts a row. */
@Composable
internal fun RowDot() {
    Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
    Spacer(Modifier.width(10.dp))
}

/** `.field-label`: a small level-2 section heading. */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = AppTheme.colors.textMuted,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp, start = 4.dp).semantics { heading() },
    )
}

/** `.empty-sub`: muted small text. */
@Composable
internal fun MutedText(text: String, modifier: Modifier = Modifier, align: TextAlign? = null) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.textMuted, modifier = modifier, textAlign = align)
}

/** `.text-btn`: a full-width muted text button; these are the section's own actions, so T1. */
@Composable
internal fun SectionTextButton(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.primary),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.textMuted)
    }
}

/** `.text-btn.danger-lite`: an inline, underlined T3 text button (labelSmall, 8/4dp padding). */
@Composable
internal fun InlineTextButton(text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
        RowHitArea {
            TextButton(
                onClick = onClick,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = AppTheme.sizes.support),
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.colors.textMuted,
                    textDecoration = TextDecoration.Underline,
                )
            }
        }
    }
}

/** `.empty-state`: a centred title and a line under it. */
@Composable
internal fun EmptyState(title: String, sub: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 6.dp).semantics { heading() },
        )
        MutedText(sub, align = TextAlign.Center)
    }
}
