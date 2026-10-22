package com.hazia.forgetmenot.ui.screens.settings

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.hazia.forgetmenot.AppContainer
import com.hazia.forgetmenot.data.Backup
import com.hazia.forgetmenot.data.FolderStatus
import com.hazia.forgetmenot.data.PendingGrant
import com.hazia.forgetmenot.data.Records
import com.hazia.forgetmenot.data.SyncSession
import com.hazia.forgetmenot.domain.plural
import com.hazia.forgetmenot.ui.sheets.AppIcon
import com.hazia.forgetmenot.ui.sheets.ArchivedSheet
import com.hazia.forgetmenot.ui.sheets.AskHost
import com.hazia.forgetmenot.ui.sheets.FieldLabel
import com.hazia.forgetmenot.ui.sheets.Glyph
import com.hazia.forgetmenot.ui.sheets.RecentChanges
import com.hazia.forgetmenot.ui.sheets.RecentChangesSheet
import com.hazia.forgetmenot.ui.sheets.SupportIconButton
import com.hazia.forgetmenot.ui.sheets.SyncConflictsSheet
import com.hazia.forgetmenot.ui.sheets.TagsSheet
import com.hazia.forgetmenot.ui.sheets.ThemeSheet
import com.hazia.forgetmenot.ui.sheets.Toaster
import com.hazia.forgetmenot.ui.sheets.observeRecords
import com.hazia.forgetmenot.ui.sheets.rememberAskState
import com.hazia.forgetmenot.ui.sheets.themeLabel
import com.hazia.forgetmenot.ui.theme.AppTheme
import com.hazia.forgetmenot.ui.theme.bottomInsetPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.hazia.forgetmenot.ui.screenHeading

// Port of www/js/screens/settings.js and the settings markup (www/index.html:128-219).

/** What the screen works on. [pickFolder]/[takeGrant] are SafFolder's picker contract and grant. */
class SettingsDeps(
    val records: Records,
    val session: SyncSession,
    val backup: Backup,
    val platform: SettingsPlatform,
    val pickFolder: ActivityResultContract<Uri?, Uri?>,
    val takeGrant: (Uri?) -> PendingGrant,
    /** The picked backup file's text, or null when it cannot be read. */
    val readText: suspend (Uri) -> String?,
)

/** The Settings destination: wires [SettingsScreen] to the app's container. */
@Composable
fun SettingsDestination(onBack: () -> Unit, onGoHome: () -> Unit) {
    val context = LocalContext.current
    val deps = remember {
        val c = AppContainer.from(context)
        SettingsDeps(
            records = c.records, session = c.session, backup = c.backup,
            platform = AndroidSettingsPlatform(c.appContext),
            pickFolder = c.syncFolder.pickContract(),
            takeGrant = c.syncFolder::takeGrant,
            readText = { uri ->
                withContext(Dispatchers.IO) {
                    runCatching { c.appContext.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
                }
            },
        )
    }
    SettingsScreen(deps, onBack, onGoHome)
}

private enum class SettingsSheet { THEME, RECENT, ARCHIVED, TAGS, CONFLICTS }

@Composable
fun SettingsScreen(deps: SettingsDeps, onBack: () -> Unit, onGoHome: () -> Unit) {
    val records = deps.records
    val actions = remember(deps) { SettingsActions(records, deps.session, deps.backup) }
    val ask = rememberAskState()
    val scope = rememberCoroutineScope()
    val rev = observeRecords(records)
    val settings = remember(rev) { records.getSettings() }
    val tagCount = remember(rev) { records.listTags().size }
    val archivedCount = remember(rev) { records.archivedFriends().size }
    val recent by RecentChanges.changes.collectAsState()
    val conflicts by deps.session.pendingConflicts.collectAsState()
    var sheet by rememberSaveable { mutableStateOf<SettingsSheet?>(null) }

    // System state changes behind the app's back (a grant made in system Settings lands when the
    // user returns), so the status rows re-read on every resume.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    val notif = remember(resumes) { deps.platform.notifications() }
    val exact = remember(resumes) { deps.platform.exactAlarmsGranted() }
    val battery = remember(resumes) { deps.platform.batteryUnrestricted() }
    var folder by remember { mutableStateOf<FolderStatus?>(null) }
    LaunchedEffect(rev, resumes) { folder = runCatching { records.store.fsStatus() }.getOrElse { FolderStatus(false, null) } }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        resumes++
        if (granted) { Toaster.show("Notifications on"); records.rescheduleReminders() }
        else Toaster.show("Notifications blocked in system settings")
    }
    val folderLauncher = rememberLauncherForActivityResult(deps.pickFolder) { uri ->
        scope.launch { actions.adoptPick { deps.takeGrant(uri) }; folder = records.store.fsStatus() }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { if (actions.importText(deps.readText(uri), ask)) onGoHome() }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SupportIconButton(AppIcon.ARROW_LEFT, "Back", onBack, iconSize = 19.dp)
                Spacer(Modifier.width(8.dp))
                Text("Settings", Modifier.screenHeading(), style = MaterialTheme.typography.headlineSmall)
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).bottomInsetPadding().testTag("settings-list"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Section("Appearance") {
                    NavRow("Theme", themeLabel(settings.skin, settings.mode), dot = true) { sheet = SettingsSheet.THEME }
                }
                Section("Home") {
                    SwitchRow("Smaller home", "Just the three people most worth a hello", settings.smallHome) { actions.toggleSmallHome() }
                }
                Section("Friends") {
                    NavRow("Recent changes", recent.size.toString(), dot = true) { sheet = SettingsSheet.RECENT }
                    Divider()
                    NavRow("Archived friends", archivedCount.toString(), dot = true) { sheet = SettingsSheet.ARCHIVED }
                }
                Section("Reminders") {
                    SwitchRow("Quiet mode", null, settings.quietMode) { actions.toggleQuiet() }
                    Divider()
                    ValueRow("Notifications", notif.label) {
                        when {
                            deps.platform.canAskNotifications() -> notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            notif == com.hazia.forgetmenot.ui.screens.settings.NotifStatus.ON -> { Toaster.show("Notifications on"); records.rescheduleReminders() }
                            else -> { Toaster.show("Notifications blocked in system settings"); deps.platform.openNotificationSettings() }
                        }
                    }
                    Divider()
                    ValueRow("Exact reminders", if (exact) "On" else "Not enabled") { deps.platform.openExactAlarmSettings() }
                    Divider()
                    // Optimised means the system may hold reminders back; the list it opens is where to exempt the app.
                    ValueRow("Battery limits", if (battery) "None" else "May delay — tap to fix") { deps.platform.openBatterySettings() }
                    Divider()
                    NavRow("Tag check-in defaults", plural(tagCount, " tag", " tags")) { sheet = SettingsSheet.TAGS }
                }
                Section("Sync") {
                    val f = folder
                    NavRow(
                        "Sync folder",
                        when { f == null -> "Checking…"; f.granted -> f.name ?: "Chosen"; else -> "Not set — tap to choose" },
                        icon = AppIcon.FOLDER, chevron = false,
                    ) { folderLauncher.launch(null) }
                    if (conflicts.isNotEmpty()) {
                        Divider()
                        CompositionLocalProvider(LocalContentColor provides AppTheme.colors.warnText) {
                            NavRow("Sync conflicts", conflicts.size.toString(), icon = AppIcon.WARN, background = AppTheme.colors.warnBg) { sheet = SettingsSheet.CONFLICTS }
                        }
                    }
                }
                folder?.let { f ->
                    Text(
                        if (f.granted) "Point Syncthing-fork at this same folder to sync across devices. Tap to change."
                        else "Your data isn’t saved until you pick a folder — ideally one Syncthing-fork already watches.",
                        Modifier.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.textMuted,
                    )
                }
                Section("About") {
                    NavRow("Export backup", null, icon = AppIcon.DOWNLOAD) { scope.launch { actions.export() } }
                    Divider()
                    NavRow("Import backup", null, icon = AppIcon.UPLOAD) {
                        // application/json, plus what some providers call a .json file.
                        importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    }
                    Divider()
                    InfoRow("Version", deps.platform.appVersion)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    val close = { sheet = null }
    when (sheet) {
        SettingsSheet.THEME -> ThemeSheet(records, close)
        SettingsSheet.RECENT -> RecentChangesSheet(close)
        SettingsSheet.ARCHIVED -> ArchivedSheet(records, ask, close)
        SettingsSheet.TAGS -> TagsSheet(records, close)
        SettingsSheet.CONFLICTS -> SyncConflictsSheet(deps.session, ask, close)
        null -> {}
    }
    AskHost(ask)
}

// ── rows ────────────────────────────────────────────────────────────────────

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    FieldLabel(title, Modifier.padding(start = 4.dp, top = 12.dp))
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
        Column(content = content)
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = AppTheme.colors.border)

@Composable
private fun RowLabel(label: String, dot: Boolean, icon: AppIcon?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (dot) { Box(Modifier.size(8.dp).clip(CircleShape).background(AppTheme.colors.decorative)); Spacer(Modifier.width(10.dp)) }
        if (icon != null) { Glyph(icon, 15.dp); Spacer(Modifier.width(10.dp)) }
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** T1: a row that opens something; its value and a chevron on the right. */
@Composable
private fun NavRow(
    label: String,
    value: String?,
    dot: Boolean = false,
    icon: AppIcon? = null,
    chevron: Boolean = true,
    background: Color = Color.Transparent,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .heightIn(min = AppTheme.sizes.primary)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowLabel(label, dot, icon, Modifier.weight(1f))
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = if (background == Color.Transparent) AppTheme.colors.textMuted else LocalContentColor.current)
        if (chevron) { Spacer(Modifier.width(6.dp)); Glyph(AppIcon.ARROW_RIGHT, 14.dp) }
    }
}

/** T1: a status row whose tap fixes it. */
@Composable
private fun ValueRow(label: String, value: String, onClick: () -> Unit) = NavRow(label, value, chevron = false, onClick = onClick)

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.primary).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.textMuted)
    }
}

/** A switch row: the whole row toggles, announced as a switch with its state. */
@Composable
private fun SwitchRow(label: String, sub: String?, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.primary)
            .toggleable(on, role = Role.Switch) { onToggle() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("switch-$label"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.textMuted)
        }
        Switch(checked = on, onCheckedChange = null)
    }
}
