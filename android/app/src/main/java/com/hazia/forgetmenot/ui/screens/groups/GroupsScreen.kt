package com.hazia.forgetmenot.ui.screens.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hazia.forgetmenot.data.Records
import com.hazia.forgetmenot.domain.Group
import com.hazia.forgetmenot.domain.uid
import com.hazia.forgetmenot.ui.theme.AppTheme
import com.hazia.forgetmenot.ui.theme.bottomInsetPadding
import com.hazia.forgetmenot.ui.theme.bottomInsets
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/**
 * The groups list (renderGroups(), www/js/screens/groups.js:15): every group by name with how
 * many people are in it and its next event, and a New group action. [message] is a one-off
 * notice handed over by the screen that sent us here ("Deleted Book club"); [onMessageShown]
 * lets the caller forget it so it is not shown twice.
 */
@Composable
fun GroupsScreen(
    records: Records,
    onBack: () -> Unit,
    onOpenGroup: (String) -> Unit,
    message: String? = null,
    onMessageShown: () -> Unit = {},
    now: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    val revision by records.store.revision.collectAsState()
    val rows = remember(revision) {
        val at = now()
        sortedGroups(records.listGroups()).map { it to groupTeaser(it, at) }
    }
    var creating by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(message) {
        if (message != null) {
            onMessageShown()
            scope.launch { snackbar.showSnackbar(message) }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar, Modifier.bottomInsetPadding()) },
        topBar = { GroupsHeader("Groups", onBack, GroupIcons.plus, "New group") { creating = true } },
    ) { padding ->
        if (rows.isEmpty()) {
            Column(Modifier.padding(padding)) {
                EmptyState(
                    "No groups yet",
                    "Bundle friends together for shared events and reminders — like a book club or a family thread.",
                )
            }
        } else {
            val bottom = bottomInsets.asPaddingValues().calculateBottomPadding()
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp + bottom),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(rows, key = { it.first.id }) { (g, teaser) -> GroupRow(g, teaser) { onOpenGroup(g.id) } }
            }
        }
    }

    if (creating) {
        GroupEditSheet(renaming = false, initialName = "", onDismiss = { creating = false }) { name ->
            val g = Group(id = uid(), name = name, createdAt = System.currentTimeMillis())
            records.saveGroup(g)
            creating = false
            onOpenGroup(g.id)
        }
    }
}

@Composable
private fun GroupRow(g: Group, teaser: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
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
                g.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).testTag(GroupsTags.GROUP_NAME),
            )
            Spacer(Modifier.width(12.dp))
            MutedText(teaser, Modifier.weight(1f, fill = false), align = TextAlign.End)
            Spacer(Modifier.width(8.dp))
            Icon(GroupIcons.arrowRight, contentDescription = null, tint = AppTheme.colors.textMuted, modifier = Modifier.size(14.dp))
        }
    }
}
