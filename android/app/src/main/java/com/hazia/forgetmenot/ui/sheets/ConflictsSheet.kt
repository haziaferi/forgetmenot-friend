package com.hazia.forgetmenot.ui.sheets

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hazia.forgetmenot.data.PendingConflict
import com.hazia.forgetmenot.data.RecordKind
import com.hazia.forgetmenot.data.SyncSession
import com.hazia.forgetmenot.domain.plural
import com.hazia.forgetmenot.domain.timeAgoShort
import com.hazia.forgetmenot.ui.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

// Port of www/js/ui/conflicts.js: the sync-conflicts sheet. The banner on home and the row in
// Settings read the same list, SyncSession.pendingConflicts.

/** A resolve tapped in a read-only session (I6): the choice could not be saved. */
private const val READ_ONLY_RESOLVE = "Your sync folder couldn’t be read — restart the app, then try again"

private fun JsonObject.text(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.count(k: String) = (this[k] as? JsonArray)?.size ?: 0

/** The record's name for the card: the kept side's, else the other's, else "Untitled". */
fun conflictName(item: PendingConflict): String = item.canonical.text("name") ?: item.conflicting.text("name") ?: "Untitled"

/**
 * The second line of a choice: "Edited 2 hours ago · 4 check-ins logged", so the two choices
 * differ by content, not only by time.
 */
fun conflictSummary(kind: RecordKind, rec: JsonObject, nowMs: Long = System.currentTimeMillis()): String {
    val edited = "Edited " + timeAgoShort((rec["updatedAt"] as? JsonPrimitive)?.longOrNull, nowMs)
    return edited + when (kind) {
        RecordKind.FRIEND -> " · " + plural(rec.count("contacts"), " check-in logged", " check-ins logged")
        RecordKind.GROUP -> " · " + plural(rec.count("memberIds"), " member", " members")
        RecordKind.TAG -> ""
    }
}

/**
 * "Sync conflicts": per record, keep this version or the synced-in one. The version not kept is
 * gone for good, so the choice is asked twice. Closes itself when the last one is settled.
 */
@Composable
fun SyncConflictsSheet(session: SyncSession, ask: AskState, onDismiss: () -> Unit, nowMs: () -> Long = System::currentTimeMillis) {
    val pending by session.pendingConflicts.collectAsState()
    val scope = rememberCoroutineScope()
    var resolving by remember { mutableStateOf(false) }
    val closeNow = remember { arrayOfNulls<() -> Unit>(1) }
    AppSheet("Sync conflicts", onDismiss) { close ->
        closeNow[0] = close
        SheetIntro("Edited on two devices while offline. Pick which version to keep for each — the other is discarded.")
        if (pending.isEmpty()) SheetIntro("No conflicts.")
        for (item in pending) {
            Column(
                Modifier.fillMaxWidth().border(1.dp, AppTheme.colors.border, MaterialTheme.shapes.medium).padding(vertical = 4.dp),
            ) {
                Text(conflictName(item), Modifier.padding(horizontal = 12.dp, vertical = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                for ((keepConflicting, label, rec) in listOf(
                    Triple(false, "Keep this version", item.canonical),
                    Triple(true, "Keep the synced-in version", item.conflicting),
                )) {
                    HorizontalDivider(color = AppTheme.colors.border)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = AppTheme.sizes.primary)
                            .clickable(role = Role.Button, enabled = !resolving) {
                                scope.launch {
                                    if (resolving) return@launch
                                    // Read-only (I6): resolve() refuses, and its false reads as
                                    // "changed since the scan", which would send the user round again.
                                    if (session.store.writesBlocked) { Toaster.show(READ_ONLY_RESOLVE); return@launch }
                                    val name = item.canonical.text("name") ?: item.conflicting.text("name") ?: "this record"
                                    if (!ask.confirm("Keep this version?", "The other version of $name is discarded. This can’t be undone.", "Keep it")) return@launch
                                    resolving = true
                                    try {
                                        if (!session.resolve(item, keepConflicting)) {
                                            Toaster.show("This entry changed since the conflict was found — resolve it again to be safe")
                                            return@launch
                                        }
                                        Toaster.show("Kept one version, discarded the other")
                                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                                        Toaster.show("Couldn’t save that choice — try again")
                                    } finally { resolving = false }
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            Text(conflictSummary(item.kind, rec, nowMs()), style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.textMuted)
                        }
                        Glyph(AppIcon.ARROW_RIGHT, 14.dp)
                    }
                }
            }
        }
    }
    // The last conflict settled (here or by a rescan): nothing left to choose.
    var hadAny by remember { mutableStateOf(pending.isNotEmpty()) }
    LaunchedEffect(pending.isEmpty()) {
        if (pending.isNotEmpty()) hadAny = true else if (hadAny) closeNow[0]?.invoke()
    }
}
