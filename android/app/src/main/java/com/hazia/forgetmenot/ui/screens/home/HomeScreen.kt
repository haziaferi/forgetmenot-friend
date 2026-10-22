package com.hazia.forgetmenot.ui.screens.home

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavHostController
import com.hazia.forgetmenot.AppContainer
import com.hazia.forgetmenot.data.Records
import com.hazia.forgetmenot.data.RefreshResult
import com.hazia.forgetmenot.data.SyncSession
import com.hazia.forgetmenot.domain.plural
import com.hazia.forgetmenot.ui.AddRoute
import com.hazia.forgetmenot.ui.FriendRoute
import com.hazia.forgetmenot.ui.GroupsRoute
import com.hazia.forgetmenot.ui.SettingsRoute
import com.hazia.forgetmenot.ui.screenHeading
import com.hazia.forgetmenot.ui.screens.settings.AndroidSettingsPlatform
import com.hazia.forgetmenot.ui.screens.settings.NotifStatus
import com.hazia.forgetmenot.ui.screens.settings.SettingsActions
import com.hazia.forgetmenot.ui.screens.settings.SettingsPlatform
import com.hazia.forgetmenot.ui.sheets.AppIcon
import com.hazia.forgetmenot.ui.sheets.AskHost
import com.hazia.forgetmenot.ui.sheets.Glyph
import com.hazia.forgetmenot.ui.sheets.LogDateSheet
import com.hazia.forgetmenot.ui.sheets.SupportIconButton
import com.hazia.forgetmenot.ui.sheets.SyncConflictsSheet
import com.hazia.forgetmenot.ui.sheets.Toaster
import com.hazia.forgetmenot.ui.sheets.logToday
import com.hazia.forgetmenot.ui.sheets.observeRecords
import com.hazia.forgetmenot.ui.sheets.rememberAskState
import com.hazia.forgetmenot.ui.theme.AppTheme
import com.hazia.forgetmenot.ui.theme.bottomInsets
import java.time.ZonedDateTime
import kotlinx.coroutines.launch

// Port of www/js/screens/home.js, the home markup (www/index.html:20-47), and the home halves of
// www/js/main.js: search, row taps, the long press, pull-to-refresh and resume.

/**
 * What home works on. [pickFolder] is SafFolder's picker contract; [adoptPick] takes the picked
 * tree and toasts the outcome (SettingsActions.adoptPick). [runOnboarding] is false in tests that
 * are not about it.
 */
class HomeDeps(
    val records: Records,
    val session: SyncSession,
    val platform: SettingsPlatform,
    val pickFolder: ActivityResultContract<Uri?, Uri?>,
    val adoptPick: suspend (Uri?) -> Unit,
    val folderGranted: suspend () -> Boolean = { records.store.fsStatus().granted },
    val now: () -> ZonedDateTime = ZonedDateTime::now,
    val runOnboarding: Boolean = true,
)

/** Where home's buttons go. */
class HomeNav(
    val openFriend: (String) -> Unit,
    val add: () -> Unit,
    val settings: () -> Unit,
    val groups: () -> Unit,
)

/** The home destination: wires [HomeScreen] to the app's container and the nav graph. */
@Composable
fun HomeDestination(navController: NavHostController) {
    val context = LocalContext.current
    val deps = remember {
        val c = AppContainer.from(context)
        val actions = SettingsActions(c.records, c.session, c.backup)
        HomeDeps(
            records = c.records, session = c.session,
            platform = AndroidSettingsPlatform(c.appContext),
            pickFolder = c.syncFolder.pickContract(),
            adoptPick = { uri -> actions.adoptPick { c.syncFolder.takeGrant(uri) } },
        )
    }
    val nav = remember(navController) {
        HomeNav(
            openFriend = { navController.navigate(FriendRoute(it)) },
            add = { navController.navigate(AddRoute()) },
            settings = { navController.navigate(SettingsRoute) },
            groups = { navController.navigate(GroupsRoute) },
        )
    }
    HomeScreen(deps, nav)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(deps: HomeDeps, nav: HomeNav) {
    val records = deps.records
    val scope = rememberCoroutineScope()
    val ask = rememberAskState()
    val rev = observeRecords(records)
    val pending by deps.session.pendingConflicts.collectAsState()
    val lastScan by deps.session.lastScan.collectAsState()
    // I6: a folder that could not be read is not "no folder"; picking one with data lifts it.
    val readOnly by records.store.readOnly.collectAsState()

    // Day counts go stale in a backgrounded app, so every resume re-reads the clock.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        resumes++
        AfterMessage.offer(records)
    }

    var search by rememberSaveable { mutableStateOf("") }
    var logFor by rememberSaveable { mutableStateOf<String?>(null) }
    var conflictsOpen by rememberSaveable { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var focusFirst by remember { mutableStateOf(false) }
    val firstRow = remember { FocusRequester() }

    val settings = remember(rev) { records.getSettings() }
    // Toggling Smaller home in Settings starts it afresh (state.homeShowAll = false).
    LaunchedEffect(settings.smallHome) {
        val last = HomeSession.lastSmallHome
        if (last != null && last != settings.smallHome) HomeSession.showAll = false
        HomeSession.lastSmallHome = settings.smallHome
    }
    val now = remember(rev, resumes) { deps.now() }
    val content = remember(rev, now, search, settings.smallHome, HomeSession.showAll) {
        val tags = records.listTags().associate { it.id to it.name }
        buildHome(records.activeFriends(), { tags[it] }, search, settings.smallHome, HomeSession.showAll, now)
    }

    // The no-folder banner: checked on each render until a folder has been seen.
    var noFolder by remember { mutableStateOf(false) }
    var folderChecks by remember { mutableIntStateOf(0) }
    LaunchedEffect(rev, resumes, folderChecks) {
        if (HomeSession.folderSeen) { noFolder = false; return@LaunchedEffect }
        // A cancelled check (home left meanwhile) must not count as "folder seen".
        val granted = try { deps.folderGranted() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { true }
        HomeSession.folderSeen = granted
        noFolder = !granted
    }

    // "N sync conflicts resolved automatically", once per scan, from whichever scan ran.
    LaunchedEffect(lastScan) {
        val s = lastScan ?: return@LaunchedEffect
        if (s === HomeSession.toastedScan) return@LaunchedEffect
        HomeSession.toastedScan = s
        autoResolvedText(s)?.let(Toaster::show)
    }

    // ── onboarding, queued behind home ───────────────────────────────────────
    val onboarding = remember(records) { HomeSession.onboarding(records) }
    val folderLauncher = rememberLauncherForActivityResult(deps.pickFolder) { uri ->
        scope.launch {
            deps.adoptPick(uri)
            folderChecks++
            onboarding.primaryDone(OnboardingStep.SYNC_FOLDER)
        }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { Toaster.show("Notifications on"); records.rescheduleReminders() }
        else Toaster.show("Notifications blocked in system settings")
        onboarding.primaryDone(OnboardingStep.NOTIFICATIONS)
    }
    LaunchedEffect(Unit) {
        // Read-only, the settings are unread, so every step would look unseen and none could be saved.
        if (!deps.runOnboarding || HomeSession.onboardingRan || records.store.writesBlocked) return@LaunchedEffect
        val steps = onboardingQueue(records.getSettings().onboarded, deps.folderGranted) { deps.platform.notifications() == NotifStatus.ON }
        // No suspension between the check above and here once the queue is known: marked and started together.
        if (HomeSession.onboardingRan) return@LaunchedEffect
        HomeSession.onboardingRan = true
        onboarding.start(steps)
    }
    val runStep: () -> Unit = {
        when (onboarding.beginPrimary()) {
            OnboardingStep.SYNC_FOLDER -> folderLauncher.launch(null)
            OnboardingStep.NOTIFICATIONS -> when {
                deps.platform.canAskNotifications() -> notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                deps.platform.notifications() == NotifStatus.ON -> {
                    Toaster.show("Notifications on"); records.rescheduleReminders()
                    onboarding.primaryDone(OnboardingStep.NOTIFICATIONS)
                }
                else -> {
                    Toaster.show("Notifications blocked in system settings")
                    deps.platform.openNotificationSettings()
                    onboarding.primaryDone(OnboardingStep.NOTIFICATIONS)
                }
            }
            null -> {}
        }
    }

    // "Show everyone" moves focus to the first row once it is drawn.
    LaunchedEffect(focusFirst, content) {
        if (!focusFirst || content !is HomeContent.Full) return@LaunchedEffect
        withFrameNanos { }
        runCatching { firstRow.requestFocus() }
        focusFirst = false
    }

    val pullState = rememberPullToRefreshState()
    val refresh: () -> Unit = {
        if (!refreshing) scope.launch {
            refreshing = true
            try {
                // Skipped in a read-only session (I6): nothing is read, written or pushed.
                if (deps.session.refreshFromDisk() == RefreshResult.Failed) {
                    Toaster.show("Couldn’t read the sync folder — try again in a moment")
                }
            } finally {
                refreshing = false
                // A refresh that returns within one frame (Skipped when read-only) never flips the box's
                // isRefreshing, which is what would otherwise send the indicator back up.
                pullState.animateToHidden()
            }
        }
    }

    val insetBottom = bottomInsets.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(nav)
            SearchBar(search, { search = it })
            if (readOnly) {
                Banner({ Glyph(AppIcon.FOLDER, 16.dp, it) }, "Couldn’t read your sync folder — nothing is saved until the app restarts. Tap to choose it again.", "readonly-banner") { folderLauncher.launch(null) }
            } else if (noFolder) {
                Banner({ Glyph(AppIcon.FOLDER, 16.dp, it) }, "Nothing is saved until you choose a folder. Tap to choose one.", "folder-banner") { folderLauncher.launch(null) }
            }
            if (pending.isNotEmpty()) {
                Banner({ Glyph(AppIcon.WARN, 16.dp, it) }, plural(pending.size, " sync conflict", " sync conflicts") + " found — tap to resolve", "conflict-banner") { conflictsOpen = true }
            }
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = refresh,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = pullState,
                indicator = { RefreshIndicator(pullState, refreshing) },
            ) {
                LazyColumn(
                    Modifier.fillMaxSize().testTag("home-list"),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = insetBottom + 100.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    homeItems(
                        content, firstRow,
                        open = nav.openFriend,
                        log = { logToday(records, it, now = deps.now().toInstant()) },
                        pickDate = { logFor = it },
                        showEveryone = { HomeSession.showAll = true; focusFirst = true },
                    )
                }
            }
        }
        FloatingActionButton(
            onClick = nav.add,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(bottomInsets)
                .padding(end = 18.dp, bottom = 18.dp)
                .size(AppTheme.sizes.primary)
                .semantics { contentDescription = "Add friend" },
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
        ) { Glyph(AppIcon.PLUS, 24.dp) }
    }

    logFor?.let { id -> LogDateSheet(records, id, onDismiss = { logFor = null }, today = { deps.now().toLocalDate() }) }
    if (conflictsOpen) SyncConflictsSheet(deps.session, ask, onDismiss = { conflictsOpen = false })
    // Keyed: each step is a sheet of its own, not the last one's composition (and its closing state) reused.
    onboarding.current?.let { step -> key(step) { OnboardingSheet(step, onPrimary = runStep, onDismiss = onboarding::dismiss) } }
    AskHost(ask)
}

private fun androidx.compose.foundation.lazy.LazyListScope.homeItems(
    content: HomeContent,
    firstRow: FocusRequester,
    open: (String) -> Unit,
    log: (String) -> Unit,
    pickDate: (String) -> Unit,
    showEveryone: () -> Unit,
) {
    fun rows(list: List<HomeRow>, focusFirst: Boolean) {
        items(list, key = { it.friend.id }) { r ->
            FriendRow(
                r,
                onOpen = { open(r.friend.id) },
                onLog = { log(r.friend.id) },
                onPickDate = { pickDate(r.friend.id) },
                openFocus = if (focusFirst && r === list.first()) firstRow else null,
            )
        }
    }
    when (content) {
        HomeContent.Empty -> item {
            EmptyState(title = "Nobody here yet", sub = "Add someone you’d like to stay close to.")
        }
        is HomeContent.NoMatch -> item { EmptyState(title = null, sub = "No one matches “${content.query}”.") }
        is HomeContent.Small -> {
            if (content.few.isNotEmpty()) {
                item { SectionLabel("Maybe today", first = true) }
                rows(content.few, focusFirst = false)
            } else {
                item { EmptyState(title = null, sub = "Everyone’s been in touch lately. Nothing to do today.") }
            }
            item {
                TextButton(
                    onClick = showEveryone,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Show everyone (${content.total})", style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.textMuted) }
            }
        }
        is HomeContent.Full -> content.sections.forEachIndexed { i, s ->
            item(key = "label-${s.section.key}") { SectionLabel(s.title, first = i == 0) }
            rows(s.rows, focusFirst = i == 0)
        }
    }
}

@Composable
private fun TopBar(nav: HomeNav) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("forgetmenot", Modifier.weight(1f).screenHeading(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        HeaderButton(HomeIcon.USERS, "Groups", nav.groups)
        Spacer(Modifier.width(12.dp))
        HeaderButton(HomeIcon.GEAR, "Settings", nav.settings)
    }
}

/** T3: a header icon button, 32 dp drawn; outside any card row, so its hit area reaches 48 dp. */
@Composable
private fun HeaderButton(icon: HomeIcon, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(AppTheme.sizes.support).semantics { contentDescription = label }) {
        HomeGlyph(icon, 19.dp, MaterialTheme.colorScheme.onBackground)
    }
}

/**
 * The search bar: drawn at T1 like the other text fields, and a tap anywhere on it focuses the
 * field. The clear button stays out of the focus order: it duplicates deleting the text.
 */
@Composable
private fun SearchBar(value: String, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = MaterialTheme.shapes.large
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.primary)
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(1.dp, AppTheme.colors.borderStrong, shape)
            .pointerInput(Unit) { detectTapGestures { runCatching { focus.requestFocus() } } }
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(AppIcon.SEARCH, 15.dp, AppTheme.colors.textMuted)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text("Find someone", style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic), color = AppTheme.colors.textMuted)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Search friends" }.testTag("search"),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            )
        }
        if (value.isNotEmpty()) {
            SupportIconButton(
                AppIcon.X, "Clear search",
                onClick = { onChange(""); runCatching { focus.requestFocus() } },
                modifier = Modifier.focusProperties { canFocus = false },
                iconSize = 13.dp,
            )
        }
    }
}

/** The sync banners (`.sync-banner`): warn colours, the whole strip is the button. */
@Composable
private fun Banner(icon: @Composable (androidx.compose.ui.graphics.Color) -> Unit, text: String, tag: String, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.small
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.primary)
            .background(AppTheme.colors.warnBg, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        icon(AppTheme.colors.warnText)
        Text(text, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.warnText)
    }
}

@Composable
private fun SectionLabel(text: String, first: Boolean) {
    Text(
        text,
        Modifier.padding(top = if (first) 4.dp else 8.dp, start = 2.dp).semantics { heading() },
        style = MaterialTheme.typography.labelMedium,
        color = AppTheme.colors.textMuted,
    )
}

@Composable
private fun EmptyState(title: String?, sub: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (title != null) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
            Spacer(Modifier.size(6.dp))
        }
        Text(sub, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.textMuted, textAlign = TextAlign.Center)
    }
}

/**
 * The pull indicator: a spinner and "Pull to refresh" / "Release to refresh" / "Refreshing…".
 * With reduced motion the spinner still shows, unrotated (SPEC §7).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoxScope.RefreshIndicator(state: PullToRefreshState, refreshing: Boolean) {
    val fraction = if (refreshing) 1f else state.distanceFraction
    if (fraction <= 0f) return
    val reduced = AppTheme.reducedMotion
    Row(
        Modifier
            .align(Alignment.TopCenter)
            .graphicsLayer {
                translationY = fraction.coerceAtMost(1.5f) * 56.dp.toPx() - 40.dp.toPx()
                alpha = fraction.coerceIn(0f, 1f)
            }
            .background(MaterialTheme.colorScheme.surface, CircleShape)
            .border(1.dp, AppTheme.colors.border, CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("refresh-indicator"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (refreshing && !reduced) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, trackColor = AppTheme.colors.border)
        } else {
            CircularProgressIndicator(
                progress = { if (refreshing) 0.75f else fraction.coerceAtMost(1f) * 0.75f },
                modifier = Modifier.size(20.dp), strokeWidth = 2.dp, trackColor = AppTheme.colors.border,
            )
        }
        Text(
            when { refreshing -> "Refreshing…"; fraction >= 1f -> "Release to refresh"; else -> "Pull to refresh" },
            style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.textMuted,
        )
    }
}
