package com.hazia.forgetmenot.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.hazia.forgetmenot.data.Records
import com.hazia.forgetmenot.domain.CONTACT_KINDS
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.domain.cadenceLabel
import com.hazia.forgetmenot.domain.cadenceOf
import com.hazia.forgetmenot.domain.classify
import com.hazia.forgetmenot.domain.daysSince
import com.hazia.forgetmenot.domain.fmtRelative
import com.hazia.forgetmenot.domain.localDateISO
import com.hazia.forgetmenot.domain.nextCheckIn
import com.hazia.forgetmenot.domain.nextYearlyOccurrence
import com.hazia.forgetmenot.ui.screenHeading
import com.hazia.forgetmenot.ui.screens.groups.fmtEventDate
import com.hazia.forgetmenot.ui.screens.home.AfterMessage
import com.hazia.forgetmenot.ui.sheets.AppIcon
import com.hazia.forgetmenot.ui.sheets.AskHost
import com.hazia.forgetmenot.ui.sheets.CadenceSheet
import com.hazia.forgetmenot.ui.sheets.ChoiceChip
import com.hazia.forgetmenot.ui.sheets.DateSheet
import com.hazia.forgetmenot.ui.sheets.InlineTextButton
import com.hazia.forgetmenot.ui.sheets.KindSheet
import com.hazia.forgetmenot.ui.sheets.LogDateSheet
import com.hazia.forgetmenot.ui.sheets.NoteSheet
import com.hazia.forgetmenot.ui.sheets.SupportIconButton
import com.hazia.forgetmenot.ui.sheets.logToday
import com.hazia.forgetmenot.ui.sheets.observeRecords
import com.hazia.forgetmenot.ui.sheets.rememberAskState
import com.hazia.forgetmenot.ui.theme.AppTheme
import com.hazia.forgetmenot.ui.theme.bottomInsetPadding
import java.time.ZonedDateTime
import kotlinx.coroutines.launch

/** Which sheet is up. A plain string, so it survives recreation. */
private object Sheet {
    const val CADENCE = "cadence"
    const val LOG_DATE = "log-date"
    const val ADD_DATE = "add-date"
    const val NOTE = "note"
    const val KIND = "kind:" // + contact id
}

/**
 * One friend (renderDetail(), www/js/screens/detail.js). Every row re-reads the friend whenever
 * the cache changes, as render() did. A friend that is not there (unknown id, deleted, or
 * archived when [allowArchived] is false) sends the user away through [onLeave]; archiving here
 * reports through [onArchived] instead, which goes home.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    records: Records,
    friendId: String,
    allowArchived: Boolean,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onLeave: () -> Unit,
    onArchived: () -> Unit,
    now: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    val rev = observeRecords(records)
    val f = remember(rev, friendId) { records.getFriend(friendId) }
    var archivedHere by remember { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var noteFilter by rememberSaveable { mutableStateOf(NoteFilter.ALL) }
    val ask = rememberAskState()
    val scope = rememberCoroutineScope()

    // Day counts go stale in a backgrounded app, so every resume re-reads the clock, as home does.
    // Back from the messaging app the Message button opened: "Talked to Ana?" (SPEC §4). The
    // lifecycle is the back stack entry's, so only the screen on top asks, home or here.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        resumes++
        AfterMessage.offer(records)
    }

    if (f == null || (f.archived && !allowArchived && !archivedHere)) {
        if (!archivedHere) LaunchedEffect(Unit) { onLeave() }
        return
    }
    val at = remember(rev, resumes) { now() }
    val tagNames = remember(rev, friendId) { f.tags.mapNotNull { records.getTag(it)?.name } }

    Column(Modifier.fillMaxSize()) {
        DetailHeader(onBack = onBack, onEdit = { onEdit(f.id) })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .bottomInsetPadding(),
        ) {
            HeaderBlock(f, tagNames, at)
            LogActions(onLogToday = { logToday(records, f.id, now = now().toInstant()) }, onEarlier = { sheet = Sheet.LOG_DATE })
            QuickActions(f)

            // What to ask next time, and how you met.
            DetailCard {
                CardRow(Modifier.testTag(DetailTags.ASK_ROW), onClick = {
                    scope.launch {
                        val text = ask.prompt(
                            "Next time, ask about", confirmLabel = "Save",
                            value = f.askNext ?: "", placeholder = "How the move went, the new job",
                        ) ?: return@launch
                        setAskNext(records, f.id, text)
                    }
                }) {
                    Column(Modifier.weight(1f)) {
                        Muted("Next time, ask about")
                        if (!f.askNext.isNullOrEmpty()) Text(f.askNext, style = MaterialTheme.typography.bodyMedium)
                        else Muted("Nothing yet. Tap to add.")
                    }
                    RowIcon(DetailIcons.edit)
                }
                if (!f.askNext.isNullOrEmpty()) {
                    RowDivider()
                    CardRow {
                        Muted("Asked?", Modifier.weight(1f))
                        InlineTextButton("Clear it") { clearAskNext(records, f.id) }
                    }
                }
                if (!f.howWeMet.isNullOrEmpty()) {
                    RowDivider()
                    CardRow {
                        Column(Modifier.weight(1f)) {
                            Muted("How you met")
                            Text(f.howWeMet, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            DatesCard(f, at, onWished = { logToday(records, f.id, now = now().toInstant()) }, onRemove = { removeDate(records, f.id, it) }, onAdd = { sheet = Sheet.ADD_DATE })

            // Gift ideas.
            DetailCard {
                f.gifts.forEachIndexed { i, g ->
                    if (i > 0) RowDivider()
                    CardRow {
                        RowLabel(DetailIcons.gift, g.text)
                        SupportIconButton(AppIcon.X, "Remove the gift idea ${g.text}", { removeGift(records, f.id, g.id) }, iconSize = 14.dp)
                    }
                }
                if (f.gifts.isNotEmpty()) RowDivider()
                CardRow(onClick = {
                    scope.launch {
                        val text = ask.prompt("Gift idea for " + firstName(f.name), confirmLabel = "Add", placeholder = "The book they mentioned")
                            ?: return@launch
                        addGift(records, f.id, text, now())
                    }
                }) {
                    Text("Add a gift idea", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    RowIcon(DetailIcons.plus)
                }
            }

            RemindersCard(
                f, at,
                onCadence = { sheet = Sheet.CADENCE },
                onTogglePause = { togglePause(records, f.id) },
                onPauseUntil = { setPausedUntil(records, f.id, it, now()) },
                onClearSnooze = { clearSnooze(records, f.id) },
            )

            SectionLabel("Reach-outs")
            HistoryCard(f, at, onKind = { sheet = Sheet.KIND + it }, onRemove = { removeContact(records, f.id, it, now()) })

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Notes", Modifier.weight(1f))
                SupportIconButton(AppIcon.PLUS, "Add note", { sheet = Sheet.NOTE }, iconSize = 18.dp)
            }
            FlowRow(
                Modifier.padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(AppTheme.sizes.chipGap),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (filter in NoteFilter.entries) ChoiceChip(filter.label, noteFilter == filter, onClick = { noteFilter = filter })
            }
            val notes = filterNotes(f.notes, noteFilter, at)
            if (notes.isEmpty()) Muted("No notes in this range yet.", Modifier.padding(vertical = 8.dp, horizontal = 4.dp))
            for (n in notes) {
                DetailCard {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            fmtRelative(daysSince(n.date, at)),
                            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = AppTheme.colors.textMuted,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(n.text, Modifier.testTag(DetailTags.NOTE_TEXT), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            TextButton(
                onClick = {
                    archivedHere = true
                    archiveFriend(records, f.id)
                    onArchived()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.hit).padding(vertical = 8.dp),
            ) {
                Text(
                    "Archive ${f.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.textMuted,
                    textDecoration = TextDecoration.Underline,
                )
            }
        }
    }

    when (val s = sheet) {
        null -> {}
        Sheet.CADENCE -> CadenceSheet(
            current = cadenceOf(f),
            savesAtOnce = true,
            onPick = { days -> records.getFriend(friendId)?.let { records.saveFriend(it.copy(cadenceDays = days)) } },
            onDismiss = { sheet = null },
        )
        Sheet.LOG_DATE -> LogDateSheet(records, friendId, onDismiss = { sheet = null }, today = { now().toLocalDate() })
        Sheet.ADD_DATE -> DateSheet(records, friendId, onDismiss = { sheet = null }, today = { now().toLocalDate() })
        Sheet.NOTE -> NoteSheet(records, friendId, onDismiss = { sheet = null }, now = { now().toInstant() })
        else -> if (s.startsWith(Sheet.KIND)) {
            KindSheet(records, friendId, s.removePrefix(Sheet.KIND), onDismiss = { sheet = null })
        }
    }
    AskHost(ask)
}

/** Avatar ring, name (the screen's heading), last in touch, tags. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeaderBlock(f: Friend, tagNames: List<String>, at: ZonedDateTime) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        DetailAvatar(f, classify(f, at).pct.toFloat())
        Text(
            f.name,
            Modifier.padding(top = 10.dp).screenHeading().testTag(DetailTags.NAME),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Muted(lastInTouchLine(f, at), Modifier.padding(top = 4.dp).testTag(DetailTags.LAST), align = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        // Tags that no longer exist are skipped, as getTag().filter(Boolean) did.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tagNames.isEmpty()) DecorativeChip("No tags yet")
            else for (n in tagNames) DecorativeChip(n, Modifier.testTag(DetailTags.TAG))
        }
    }
}

/** Log today (T1, the screen's main job, so first and largest) and Earlier…. */
@Composable
private fun LogActions(onLogToday: () -> Unit, onEarlier: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onLogToday, modifier = Modifier.weight(2f).height(AppTheme.sizes.primary), shape = CircleShape) {
            Icon(DetailIcons.check, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Log today", style = MaterialTheme.typography.labelLarge)
        }
        OutlinedButton(onClick = onEarlier, modifier = Modifier.weight(1f).height(AppTheme.sizes.primary), shape = CircleShape) {
            Text("Earlier…", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground)
        }
    }
}

/** Message / Plan something / Share: drawn only when P6 provides [LocalDetailIntegrations]. */
@Composable
private fun QuickActions(f: Friend) {
    val integrations = LocalDetailIntegrations.current
    if (!integrations.available) return
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).testTag(DetailTags.INTEGRATIONS), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickAction(DetailIcons.mail, messageLabel(f.messageChannel)) { if (integrations.message(context, f)) AfterMessage.handedOff(f.id) }
        QuickAction(DetailIcons.calendar, "Plan something") { integrations.plan(context, f) }
        QuickAction(DetailIcons.share, "Share") { integrations.share(context, f) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.QuickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.weight(1f).heightIn(min = AppTheme.sizes.primary),
        shape = MaterialTheme.shapes.medium,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        }
    }
}

/** Birthday (with Mark as wished on the day), anniversary, the friend's own dates, Add a date. */
@Composable
private fun DatesCard(f: Friend, at: ZonedDateTime, onWished: () -> Unit, onRemove: (String) -> Unit, onAdd: () -> Unit) {
    val dates = sortedDates(f, at)
    DetailCard {
        var first = true
        fun divider(): Boolean = (!first).also { first = false }
        if (!f.birthday.isNullOrEmpty()) {
            divider()
            CardRow {
                RowLabel(DetailIcons.cake, "Birthday")
                nextYearlyOccurrence(f.birthday, at)?.let { Text(fmtEventDate(it), style = MaterialTheme.typography.bodyMedium) }
            }
            if (birthdayIsToday(f.birthday, at.toLocalDate())) {
                RowDivider()
                CardRow {
                    Text("It’s today", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    InlineTextButton("Mark as wished", onClick = onWished)
                }
            }
        }
        if (!f.anniversary.isNullOrEmpty()) {
            if (divider()) RowDivider()
            CardRow {
                RowLabel(DetailIcons.heart, "Anniversary")
                nextYearlyOccurrence(f.anniversary, at)?.let { Text(fmtEventDate(it), style = MaterialTheme.typography.bodyMedium) }
            }
        }
        for ((d, next) in dates) {
            if (divider()) RowDivider()
            val label = d.label.ifEmpty { "A date" }
            CardRow {
                RowLabel(DetailIcons.calendar, label, Modifier.testTag(DetailTags.DATE_LABEL))
                Text(
                    (next?.let { fmtEventDate(it) } ?: d.dateISO) + if (d.yearly) " · yearly" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                SupportIconButton(AppIcon.X, "Remove " + d.label.ifEmpty { "this date" }, { onRemove(d.id) }, iconSize = 14.dp)
            }
        }
        if (divider()) RowDivider()
        CardRow(onClick = onAdd) {
            Text("Add a date", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            RowIcon(DetailIcons.plus)
        }
    }
}

/** Cadence and the next nudge, the pause switch, pause-until, and any snooze. */
@Composable
private fun RemindersCard(
    f: Friend,
    at: ZonedDateTime,
    onCadence: () -> Unit,
    onTogglePause: () -> Unit,
    onPauseUntil: (String?) -> Unit,
    onClearSnooze: () -> Unit,
) {
    val today = localDateISO(at)
    val snoozed = !f.snoozedUntil.isNullOrEmpty() && f.snoozedUntil > today
    val pausedTo = !f.paused && !f.pausedUntil.isNullOrEmpty() && f.pausedUntil > today
    // The same computation the scheduler uses, so this is the day the nudge will come.
    val next = nextCheckIn(f, at)
    DetailCard {
        CardRow(Modifier.testTag(DetailTags.CADENCE_ROW), onClick = onCadence) {
            Column(Modifier.weight(1f)) {
                Text(
                    buildAnnotatedString {
                        append("Check in roughly every ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(cadenceLabel(cadenceOf(f))) }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (next != null) Muted("Next nudge around " + shortDate(next.toInstant().toString(), at))
            }
            RowIcon(DetailIcons.chevronDown, 18.dp)
        }
        RowDivider()
        // The whole row toggles, so the target is the row, not the switch painted in it.
        Box(
            Modifier
                .fillMaxWidth()
                .toggleable(value = f.paused, role = Role.Switch, onValueChange = { onTogglePause() })
                .testTag(DetailTags.PAUSE_ROW),
        ) {
            CardRow {
                Text("Pause reminders for " + firstName(f.name), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = f.paused, onCheckedChange = null, modifier = Modifier.semantics { contentDescription = "Pause reminders" })
            }
        }
        if (!f.paused) {
            RowDivider()
            CardRow {
                val label = if (pausedTo) "Paused until" else "Or pause until"
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                PauseUntilField(if (pausedTo) f.pausedUntil else null, at.toLocalDate(), label, onPick = { onPauseUntil(it) })
                if (pausedTo) InlineTextButton("Clear") { onPauseUntil(null) }
            }
        }
        if (snoozed && !f.paused) {
            RowDivider()
            CardRow {
                Text("Next nudge after " + shortDate(f.snoozedUntil + "T12:00:00", at), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                InlineTextButton("Clear", Modifier.testTag(DetailTags.SNOOZE_CLEAR), onClick = onClearSnooze)
            }
        }
    }
}

/** The newest reach-outs with their kind (or How?) and remove; "and N earlier" below. */
@Composable
private fun HistoryCard(f: Friend, at: ZonedDateTime, onKind: (String) -> Unit, onRemove: (String) -> Unit) {
    val h = historyOf(f)
    DetailCard {
        if (h.shown.isEmpty()) Muted("Nothing logged yet.", Modifier.padding(horizontal = 14.dp, vertical = 14.dp))
        h.shown.forEachIndexed { i, c ->
            if (i > 0) RowDivider()
            val day = shortDate(c.date, at)
            val kind = c.kind?.let { CONTACT_KINDS[it] }
            CardRow {
                Text(
                    buildAnnotatedString {
                        append("$day ")
                        withStyle(SpanStyle(color = AppTheme.colors.textMuted)) { append("· " + fmtRelative(daysSince(c.date, at))) }
                    },
                    Modifier.weight(1f).testTag(DetailTags.HISTORY_DATE),
                    style = MaterialTheme.typography.bodySmall,
                )
                InlineTextButton(
                    kind?.label ?: "How?",
                    Modifier.semantics {
                        contentDescription = if (kind != null) "${kind.label} on $day. Change" else "Say how you reached out on $day"
                    },
                    color = if (kind != null) MaterialTheme.colorScheme.primary else AppTheme.colors.textMuted,
                ) { onKind(c.id) }
                SupportIconButton(AppIcon.X, "Remove the reach-out on $day", { onRemove(c.id) }, iconSize = 14.dp)
            }
        }
        if (h.earlier > 0) Muted("and ${h.earlier} earlier", Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
    }
}
