package com.hazia.forgetmenot.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hazia.forgetmenot.data.Records
import com.hazia.forgetmenot.domain.CADENCE_PRESETS
import com.hazia.forgetmenot.domain.plural
import com.hazia.forgetmenot.domain.timeAgoShort
import com.hazia.forgetmenot.ui.theme.AppTheme
import com.hazia.forgetmenot.ui.theme.Mode
import com.hazia.forgetmenot.ui.theme.Skin
import com.hazia.forgetmenot.ui.theme.ThemeTokens
import kotlinx.coroutines.launch
import java.text.Collator

// The Settings sheets that list things: tag defaults, theme, archived friends, recent changes.
// Ports of openTagsManager()/deleteTagUndoably() and openRecentChanges() (www/js/ui/sheets.js),
// openThemePicker() (www/js/ui/theme.js) and openArchivedManager() (www/js/screens/settings.js).

// ── tag defaults ────────────────────────────────────────────────────────────

/**
 * Deletes a tag, which strips it from every friend at once; undo puts the tag and each of those
 * back. Toasts "Deleted the tag “Family” · 3 friends untagged" with Undo.
 */
fun deleteTagUndoably(records: Records, id: String) {
    val tag = records.getTag(id) ?: return
    val had = records.listFriends().filter { id in it.tags }.map { it.id }
    records.deleteTag(id)
    RecentChanges.record("Deleted the tag “${tag.name}”") {
        if (records.getTag(id) != null) return@record
        records.saveTag(tag)
        for (fid in had) {
            val fr = records.getFriend(fid) ?: continue
            if (id !in fr.tags) records.saveFriend(fr.copy(tags = fr.tags + id), skipSync = true)
        }
    }
    Toaster.showWithUndo("Deleted the tag “${tag.name}”" + if (had.isNotEmpty()) " · " + plural(had.size, " friend", " friends") + " untagged" else "")
}

/** "Tag defaults": each tag's check-in preset, saved on tap, and its delete (undoable). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagsSheet(records: Records, onDismiss: () -> Unit) {
    val rev = observeRecords(records)
    val tags = remember(rev) { records.listTags() }
    AppSheet("Tag defaults", onDismiss) {
        if (tags.isEmpty()) SheetIntro("No tags yet.")
        tags.forEachIndexed { i, t ->
            if (i > 0) HorizontalDivider(color = AppTheme.colors.border)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    SupportIconButton(AppIcon.X, "Delete tag ${t.name}", { deleteTagUndoably(records, t.id) }, iconSize = 12.dp)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.sizes.chipGap), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in CADENCE_PRESETS) ChoiceChip(p.label, t.cadenceDays == p.days, onClick = {
                        records.getTag(t.id)?.let { records.saveTag(it.copy(cadenceDays = p.days)) }
                    })
                }
            }
        }
    }
}

// ── theme ───────────────────────────────────────────────────────────────────

/** "Cyanotype, Light": the Settings row's value for the stored skin and mode. */
fun themeLabel(skin: String?, mode: String?): String {
    val (s, m) = ThemeTokens.resolveKeys(skin, mode)
    return "${s.displayName}, ${m.displayName}"
}

/**
 * "Theme": five skins and Light / OLED. A tap saves at once; MainActivity re-themes from the
 * settings, so the sheet and the screen behind it change together and the sheet stays open.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeSheet(records: Records, onDismiss: () -> Unit) {
    val rev = observeRecords(records)
    val settings = remember(rev) { records.getSettings() }
    val (skin, mode) = ThemeTokens.resolveKeys(settings.skin, settings.mode)
    AppSheet("Theme", onDismiss) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
            for (s in Skin.entries) {
                PickerButton(selected = s == skin, onClick = { records.saveSettings(records.getSettings().copy(skin = s.key)) }, modifier = Modifier.weight(1f)) {
                    Box(Modifier.size(16.dp).clip(CircleShape).background(Color(ThemeTokens.swatch(s, mode))))
                    Spacer(Modifier.width(8.dp))
                    Text(s.displayName, style = MaterialTheme.typography.labelLarge)
                }
            }
            if (Skin.entries.size % 2 == 1) Spacer(Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in Mode.entries) {
                PickerButton(selected = m == mode, onClick = { records.saveSettings(records.getSettings().copy(mode = m.key)) }, modifier = Modifier.weight(1f)) {
                    Text(m.displayName, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** T2: a picker button whose state is announced as selected (aria-pressed). */
@Composable
private fun PickerButton(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = MaterialTheme.shapes.small
    Row(
        modifier
            .height(AppTheme.sizes.choice)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else AppTheme.colors.borderStrong, shape)
            .selectable(selected = selected, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) { content() }
}

// ── archived friends ────────────────────────────────────────────────────────

/** "Archived friends", by name: Restore at once, or delete for good behind a question. */
@Composable
fun ArchivedSheet(records: Records, ask: AskState, onDismiss: () -> Unit) {
    val rev = observeRecords(records)
    val collator = remember { Collator.getInstance() }
    val archived = remember(rev) { records.archivedFriends().sortedWith(compareBy(collator) { it.name }) }
    val scope = rememberCoroutineScope()
    AppSheet("Archived friends", onDismiss) {
        SheetIntro("Hidden from your list and reminders, but nothing's deleted.")
        if (archived.isEmpty()) SheetIntro("Nobody archived right now.")
        archived.forEachIndexed { i, f ->
            if (i > 0) HorizontalDivider(color = AppTheme.colors.border)
            Row(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.rowHit), verticalAlignment = Alignment.CenterVertically) {
                Text(f.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                InlineTextButton("Restore") {
                    val x = records.getFriend(f.id) ?: return@InlineTextButton
                    records.saveFriend(x.copy(archived = false))
                    Toaster.show("Restored ${x.name}")
                }
                SupportIconButton(AppIcon.TRASH, "Delete ${f.name} permanently", {
                    scope.launch {
                        val x = records.getFriend(f.id) ?: return@launch
                        if (ask.confirm("Delete ${x.name} for good?", "Their notes and reach-outs go with them. This can’t be undone.", "Delete")) {
                            records.deleteFriend(x.id)
                            Toaster.show("Deleted ${x.name}")
                        }
                    }
                }, iconSize = 15.dp)
            }
        }
    }
}

// ── recent changes ──────────────────────────────────────────────────────────

/** "Recent changes": the last five, newest first, each undoable on its own. */
@Composable
fun RecentChangesSheet(onDismiss: () -> Unit, nowMs: () -> Long = System::currentTimeMillis) {
    val changes by RecentChanges.changes.collectAsState()
    AppSheet("Recent changes", onDismiss) {
        SheetIntro("The last five, newest first. Undo any of them, in any order. Closing the app clears this list.")
        if (changes.isEmpty()) SheetIntro("Nothing to undo right now.")
        changes.forEachIndexed { i, c ->
            if (i > 0) HorizontalDivider(color = AppTheme.colors.border)
            Row(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.rowHit), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.label + "  · " + timeAgoShort(c.at, nowMs()),
                    Modifier.weight(1f).padding(vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                InlineTextButton("Undo") { RecentChanges.undo(c.id) }
            }
        }
    }
}
