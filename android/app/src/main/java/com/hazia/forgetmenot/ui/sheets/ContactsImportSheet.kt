package com.hazia.forgetmenot.ui.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hazia.forgetmenot.data.Records
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.domain.plural
import com.hazia.forgetmenot.domain.uid
import com.hazia.forgetmenot.ui.theme.AppTheme
import java.text.Collator

// Port of the bulk half of www/js/features/contacts.js: the device's contacts as a checklist,
// everyone unchecked. Reading the contacts (READ_CONTACTS, ContactsContract) is P6's; it hands
// this sheet the list, already through [importCandidates].

/** One device contact, its first phone and email, and birthday as ISO (birthdayToISO). */
data class ImportCandidate(val name: String, val phone: String? = null, val email: String? = null, val birthday: String? = null)

/**
 * The checklist's rows: named contacts only, by name. Empty means "No contacts with a name were
 * found" (the caller toasts that and does not open the sheet).
 */
fun importCandidates(raw: List<ImportCandidate>): List<ImportCandidate> {
    val c = Collator.getInstance()
    return raw.filter { it.name.isNotBlank() }.sortedWith(compareBy(c) { it.name })
}

/** "Looks already added": a friend with the same name (any case) and, when the contact has a phone, the same phone. */
fun looksAlreadyAdded(c: ImportCandidate, existing: List<Friend>): Boolean =
    existing.any { it.name.equals(c.name, ignoreCase = true) && (c.phone == null || it.phone == c.phone) }

/** Saves each chosen contact as a new friend at the default 90 days; one widget push and reschedule. */
fun importContacts(records: Records, chosen: List<ImportCandidate>) {
    for (c in chosen) {
        records.saveFriend(
            Friend(id = uid(), name = c.name, cadenceDays = 90, phone = c.phone, email = c.email, birthday = c.birthday),
            skipSync = true,
        )
    }
    records.pushWidgets()
    records.rescheduleReminders()
}

/**
 * "Import contacts": search, Select all / Deselect all over what the search shows, and
 * "Import N selected". On import it saves, toasts "Imported N contacts" and calls [onImported]
 * (the caller goes home).
 */
@Composable
fun ContactsImportSheet(records: Records, candidates: List<ImportCandidate>, onDismiss: () -> Unit, onImported: () -> Unit) {
    val existing = remember { records.listFriends() }
    val dup = remember(candidates) { candidates.map { looksAlreadyAdded(it, existing) } }
    val checked = remember(candidates) { mutableStateListOf<Boolean>().apply { repeat(candidates.size) { add(false) } } }
    var query by remember { mutableStateOf("") }
    val visible = candidates.indices.filter { q -> query.isBlank() || candidates[q].name.lowercase().contains(query.trim().lowercase()) }
    val count = checked.count { it }

    AppSheet("Import contacts", onDismiss, scrollable = false) { close ->
        SheetIntro("Pick who to add. Everyone starts unchecked — already-added-looking names are flagged below.")
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().testTag("contacts-search"),
            placeholder = { Text("Search contacts") },
            leadingIcon = { Glyph(AppIcon.SEARCH, 15.dp) },
            trailingIcon = if (query.isNotEmpty()) ({ SupportIconButton(AppIcon.X, "Clear search", { query = "" }, iconSize = 13.dp) }) else null,
            singleLine = true,
        )
        if (visible.isNotEmpty()) {
            val all = visible.all { checked[it] }
            InlineTextButton(if (all) "Deselect all" else "Select all") { visible.forEach { checked[it] = !all } }
        }
        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 480.dp)) {
            if (visible.isEmpty()) item { SheetIntro("No one matches “$query”.", Modifier.padding(vertical = 12.dp)) }
            items(visible, key = { it }) { i ->
                val c = candidates[i]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = AppTheme.sizes.primary)
                        .toggleable(checked[i], role = Role.Checkbox) { checked[i] = it }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.bodyLarge)
                        if (dup[i]) Text("Looks already added", style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.textMuted)
                    }
                    Switch(checked = checked[i], onCheckedChange = null)
                }
            }
        }
        SheetSaveButton(if (count > 0) "Import $count selected" else "Nothing selected", enabled = count > 0) {
            val chosen = candidates.filterIndexed { i, _ -> checked[i] }
            if (chosen.isEmpty()) return@SheetSaveButton
            importContacts(records, chosen)
            close()
            onImported()
            Toaster.show("Imported " + plural(chosen.size, " contact", " contacts"))
        }
    }
}

