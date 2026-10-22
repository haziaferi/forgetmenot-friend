package com.hazia.forgetmenot.ui.screens.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hazia.forgetmenot.data.Records
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.domain.GroupEvent
import com.hazia.forgetmenot.domain.uid
import com.hazia.forgetmenot.ui.sheets.RecentChanges
import com.hazia.forgetmenot.ui.sheets.Toaster
import com.hazia.forgetmenot.ui.theme.AppTheme
import com.hazia.forgetmenot.ui.theme.RowHitArea
import com.hazia.forgetmenot.ui.theme.bottomInsetPadding
import java.time.ZonedDateTime

/** Which sheet is up. Saved as a plain string so it survives recreation. */
private object Sheet {
    const val RENAME = "rename"
    const val MEMBERS = "members"
    const val ADD_EVENT = "add-event"
    const val EDIT_EVENT = "edit-event:" // + event id
}

/**
 * One group (renderGroupDetail(), www/js/screens/groups.js:38): its members, which open the
 * friend; its events, soonest first, each editable and deletable; Manage members, Add event,
 * Rename and Delete. A group that is not there (deleted elsewhere, or never was) sends the user
 * back to the list through [onBack]; deleting it here reports through [onDeleted] instead.
 */
@Composable
fun GroupDetailScreen(
    records: Records,
    groupId: String,
    onBack: () -> Unit,
    onOpenFriend: (String) -> Unit,
    onDeleted: (name: String) -> Unit,
    now: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    val revision by records.store.revision.collectAsState()
    val group = remember(revision, groupId) { records.getGroup(groupId) }
    var deletedHere by remember { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    // Saveable like [sheet]: the members sheet survives a rotation, so the debt it owes must too.
    var membersDirty by rememberSaveable { mutableStateOf(false) }

    if (group == null) {
        if (!deletedHere) LaunchedEffect(Unit) { onBack() }
        return
    }

    val members: List<Friend> = remember(revision, groupId) { group.memberIds.mapNotNull(records::getFriend) }
    val events = remember(revision, groupId) {
        val at = now()
        sortedEvents(group.events, at).map { it to eventLine(it, at) }
    }

    fun deleteEvent(ev: GroupEvent) {
        val g = records.getGroup(groupId) ?: return
        records.saveGroup(g.copy(events = g.events.filter { it.id != ev.id }))
        val label = "Deleted “" + ev.title + "”"
        // SPEC §9: on the recent-changes list, with the app-wide toast's Undo, so both outlive
        // this screen. Undo puts the event back unless a later change already took the group
        // away or restored it.
        RecentChanges.record(label) {
            val g2 = records.getGroup(groupId)
            if (g2 != null && g2.events.none { it.id == ev.id }) records.saveGroup(g2.copy(events = g2.events + ev))
        }
        Toaster.showWithUndo(label)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = { GroupsHeader(group.name, onBack, GroupIcons.edit, "Rename group") { sheet = Sheet.RENAME } },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .bottomInsetPadding(),
        ) {
            SectionLabel("Members")
            GroupCard {
                if (members.isEmpty()) {
                    MutedText("No members yet.", Modifier.padding(horizontal = 12.dp, vertical = 14.dp))
                } else {
                    members.forEachIndexed { i, f ->
                        if (i > 0) HorizontalDivider(color = AppTheme.colors.border)
                        MemberRow(f) { onOpenFriend(f.id) }
                    }
                }
            }
            SectionTextButton("+ Manage members") { sheet = Sheet.MEMBERS }

            SectionLabel("Events")
            if (events.isEmpty()) {
                MutedText("No events yet — add one to get a shared reminder.", Modifier.padding(vertical = 8.dp, horizontal = 2.dp))
            } else {
                GroupCard {
                    events.forEachIndexed { i, (ev, line) ->
                        if (i > 0) HorizontalDivider(color = AppTheme.colors.border)
                        EventRow(ev, line, onEdit = { sheet = Sheet.EDIT_EVENT + ev.id }, onDelete = { deleteEvent(ev) })
                    }
                }
            }
            SectionTextButton("+ Add event") { sheet = Sheet.ADD_EVENT }

            InlineTextButton("Delete group") { confirmingDelete = true }
            Spacer(Modifier.padding(bottom = 8.dp))
        }
    }

    when (val s = sheet) {
        null -> {}
        Sheet.RENAME -> GroupEditSheet(renaming = true, initialName = group.name, onDismiss = { sheet = null }) { name ->
            records.getGroup(groupId)?.let { records.saveGroup(it.copy(name = name)) }
            sheet = null
        }
        Sheet.MEMBERS -> {
            val friends = remember(revision) { records.activeFriends().sortedWith(compareBy(java.text.Collator.getInstance()) { it.name }) }
            GroupMembersSheet(
                friends = friends,
                memberIds = group.memberIds,
                onToggle = { id ->
                    val g = records.getGroup(groupId) ?: return@GroupMembersSheet
                    val ids = if (id in g.memberIds) g.memberIds - id else g.memberIds + id
                    // skipSync: a ten-member edit is one reschedule, paid when the sheet closes.
                    records.saveGroup(g.copy(memberIds = ids), skipSync = true)
                    membersDirty = true
                },
                onDismiss = {
                    if (membersDirty) { membersDirty = false; records.rescheduleReminders() }
                    sheet = null
                },
            )
        }
        else -> {
            // Keyed on the id, not the event: a refresh can take the event away while the sheet is up.
            val editId = if (s.startsWith(Sheet.EDIT_EVENT)) s.removePrefix(Sheet.EDIT_EVENT) else null
            GroupEventSheet(
                editing = editId != null,
                initial = editId?.let { id -> group.events.find { it.id == id } },
                onDismiss = { sheet = null },
            ) { form ->
                val g = records.getGroup(groupId)
                when {
                    g == null -> {}
                    editId == null -> records.saveGroup(g.copy(events = g.events + GroupEvent(uid(), form.title, form.dateISO, form.recurring)))
                    // As saveGroupEventFromForm(): an event that is gone is not brought back as a new one.
                    g.events.any { it.id == editId } -> records.saveGroup(g.copy(events = g.events.map {
                        if (it.id == editId) it.copy(title = form.title, dateISO = form.dateISO, recurring = form.recurring) else it
                    }))
                }
                sheet = null
            }
        }
    }

    if (confirmingDelete) {
        ConfirmDeleteDialog(
            title = "Delete “" + group.name + "”?",
            body = "The group and its events go; its members stay. This can’t be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmingDelete = false
                deletedHere = true
                val name = group.name
                records.deleteGroup(groupId)
                onDeleted(name)
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

@Composable
private fun GroupCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) { content() }
}

@Composable
private fun MemberRow(f: Friend, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowDot()
        Text(
            f.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag(GroupsTags.MEMBER_NAME),
        )
        if (f.archived) {
            Spacer(Modifier.width(6.dp))
            MutedText("(archived)")
        }
    }
}

@Composable
private fun EventRow(ev: GroupEvent, line: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.primary).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowDot()
        Column(Modifier.weight(1f)) {
            Text(
                ev.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag(GroupsTags.EVENT_TITLE),
            )
            MutedText(line, Modifier.testTag(GroupsTags.EVENT_WHEN))
        }
        RowHitArea {
            Row {
                GroupIconButton(GroupIcons.edit, "Edit event", onEdit, iconSize = 15.dp)
                Spacer(Modifier.width(8.dp))
                GroupIconButton(GroupIcons.trash, "Delete event", onDelete, iconSize = 15.dp)
            }
        }
    }
}
