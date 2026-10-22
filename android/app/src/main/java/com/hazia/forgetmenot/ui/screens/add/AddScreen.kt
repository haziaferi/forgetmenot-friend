package com.hazia.forgetmenot.ui.screens.add

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hazia.forgetmenot.data.Records
import com.hazia.forgetmenot.domain.cadenceLabel
import com.hazia.forgetmenot.ui.screenHeading
import com.hazia.forgetmenot.ui.sheets.AppIcon
import com.hazia.forgetmenot.ui.sheets.AskHost
import com.hazia.forgetmenot.ui.sheets.CadenceSheet
import com.hazia.forgetmenot.ui.sheets.ChoiceChip
import com.hazia.forgetmenot.ui.sheets.ContactsImportSheet
import com.hazia.forgetmenot.ui.sheets.DateField
import com.hazia.forgetmenot.ui.sheets.FieldLabel
import com.hazia.forgetmenot.ui.sheets.Glyph
import com.hazia.forgetmenot.ui.sheets.ImportCandidate
import com.hazia.forgetmenot.ui.sheets.InlineTextButton
import com.hazia.forgetmenot.ui.sheets.SupportIconButton
import com.hazia.forgetmenot.ui.sheets.Toaster
import com.hazia.forgetmenot.ui.sheets.importCandidates
import com.hazia.forgetmenot.ui.sheets.observeRecords
import com.hazia.forgetmenot.ui.sheets.rememberAskState
import com.hazia.forgetmenot.ui.theme.AppTheme
import com.hazia.forgetmenot.ui.theme.bottomInsetPadding
import kotlinx.coroutines.launch

/** Test tags for the form's fields and rows. */
object AddTags {
    const val NAME = "add-name"
    const val PHONE = "add-phone"
    const val EMAIL = "add-email"
    const val HOW_MET = "add-how-met"
    const val NOTE = "add-note"
    const val CADENCE_ROW = "add-cadence-row"
    const val CADENCE_VALUE = "add-cadence-value"
    const val NEW_TAG = "add-new-tag"
    const val BIRTHDAY_CLEAR = "add-birthday-clear"
    const val ANNIVERSARY_CLEAR = "add-anniversary-clear"
}

/**
 * The add/edit friend form (#screen-add, www/js/screens/add.js). [model] holds the form for this
 * visit. Cancel and back go through [AddFormModel.leave] and then [onLeave]; a successful save
 * reports [SaveResult.Added] or [SaveResult.Saved] through [onSaved]. [onImported] follows a bulk
 * contacts import (the JS went home).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddScreen(
    model: AddFormModel,
    records: Records,
    onLeave: () -> Unit,
    onSaved: (SaveResult) -> Unit,
    integrations: AddIntegrations = NoAddIntegrations,
    onImported: () -> Unit = {},
) {
    if (model.friendMissing) {
        LaunchedEffect(Unit) { onLeave() }
        return
    }
    val ask = rememberAskState()
    val scope = rememberCoroutineScope()
    val rev = observeRecords(records)
    val tags = remember(rev) { records.listTags() }
    val nameFocus = remember { FocusRequester() }
    var cadenceOpen by rememberSaveable { mutableStateOf(false) }
    var importList by remember { mutableStateOf<List<ImportCandidate>?>(null) }
    val form = model.form

    val leave = { model.leave(); onLeave() }
    BackHandler(onBack = leave)
    val save = {
        when (val r = model.save()) {
            SaveResult.NeedsName -> { runCatching { nameFocus.requestFocus() }; Unit }
            SaveResult.Gone -> onLeave()
            else -> onSaved(r)
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SupportIconButton(AppIcon.X, "Cancel", leave, iconSize = 19.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (model.editing) "Edit friend" else "Add friend",
                    Modifier.weight(1f).screenHeading(),
                    style = MaterialTheme.typography.headlineSmall,
                )
                // T1: the action the screen exists for.
                Button(onClick = save, modifier = Modifier.height(AppTheme.sizes.primary), shape = MaterialTheme.shapes.medium) {
                    Text("Save", style = MaterialTheme.typography.labelLarge)
                }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).bottomInsetPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (model.restored) InlineTextButton("Start over with an empty form") { model.startOver() }

                if (!model.editing && integrations.canImportContacts) {
                    ImportRow(PERSON, "Import from contacts") {
                        scope.launch { integrations.pickContact()?.let(model::applyContact) }
                    }
                    ImportRow(PEOPLE, "Import several at once") {
                        scope.launch {
                            val raw = integrations.readContacts() ?: return@launch
                            val list = importCandidates(raw)
                            if (list.isEmpty()) Toaster.show("No contacts with a name were found") else importList = list
                        }
                    }
                    Text(
                        "or add manually",
                        Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.colors.textMuted,
                        textAlign = TextAlign.Center,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (form.avatar != null || integrations.canPickPhoto) {
                        AvatarButton(
                            avatar = form.avatar,
                            canPick = integrations.canPickPhoto,
                            onPick = { scope.launch { integrations.pickPhoto()?.let { a -> model.update { it.copy(avatar = a) } } } },
                            onRemove = { model.update { it.copy(avatar = null) } },
                        )
                    }
                    FormField(form.name, { v -> model.update { it.copy(name = v) } }, "Name", "Friend's name", AddTags.NAME,
                        Modifier.weight(1f).focusRequester(nameFocus), capitalization = KeyboardCapitalization.Words)
                }
                FormField(form.phone, { v -> model.update { it.copy(phone = v) } }, "Phone (optional)", "Phone number", AddTags.PHONE,
                    keyboardType = KeyboardType.Phone)
                FormField(form.email, { v -> model.update { it.copy(email = v) } }, "Email (optional)", "Email address", AddTags.EMAIL,
                    keyboardType = KeyboardType.Email)
                FormField(form.howWeMet, { v -> model.update { it.copy(howWeMet = v) } }, "How you met (optional)", "How you met", AddTags.HOW_MET,
                    capitalization = KeyboardCapitalization.Sentences)

                FieldLabel("Special dates (optional)")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DateColumn("Birthday", form.birthday, { v -> model.update { it.copy(birthday = v) } }, AddTags.BIRTHDAY_CLEAR, Modifier.weight(1f))
                    DateColumn("Anniversary", form.anniversary, { v -> model.update { it.copy(anniversary = v) } }, AddTags.ANNIVERSARY_CLEAR, Modifier.weight(1f))
                }

                FieldLabel("Message via (optional)")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.sizes.chipGap), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((id, label) in MESSAGE_CHANNELS) ChoiceChip(label, form.channel == id, onClick = { model.toggleChannel(id) })
                }

                FieldLabel("Tags")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.sizes.chipGap), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in tags) ChoiceChip(t.name, t.id in form.tags, onClick = { model.toggleTag(t.id) })
                    // .chip-dashed: the one chip that is an action, not a choice.
                    OutlinedButton(
                        onClick = { scope.launch { ask.prompt("New tag", confirmLabel = "Add", placeholder = "Tag name")?.let(model::addTag) } },
                        modifier = Modifier.height(AppTheme.sizes.choice).testTag(AddTags.NEW_TAG),
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, AppTheme.colors.borderStrong),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                    ) {
                        Glyph(AppIcon.PLUS, 12.dp)
                        Spacer(Modifier.width(4.dp))
                        Text("New tag", style = MaterialTheme.typography.labelLarge)
                    }
                }

                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.primary)
                            .clickable(role = Role.Button) { cadenceOpen = true }
                            .padding(horizontal = 16.dp).testTag(AddTags.CADENCE_ROW),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            buildAnnotatedString {
                                append("Check in roughly every ")
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(cadenceLabel(form.cadence)) }
                            },
                            Modifier.weight(1f).testTag(AddTags.CADENCE_VALUE),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Glyph(AppIcon.CHEVRON_DOWN, 15.dp)
                    }
                }

                if (!model.editing) {
                    FieldLabel("First note (optional)")
                    OutlinedTextField(
                        value = form.note,
                        onValueChange = { v -> model.update { it.copy(note = v) } },
                        modifier = Modifier.fillMaxWidth().testTag(AddTags.NOTE).semantics { contentDescription = "First note" },
                        placeholder = { Text("How do you know them? Anything to remember…") },
                        minLines = 3,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (cadenceOpen) CadenceSheet(form.cadence, savesAtOnce = false, onPick = model::setCadence, onDismiss = { cadenceOpen = false })
    importList?.let { ContactsImportSheet(records, it, onDismiss = { importList = null }, onImported = onImported) }
    AskHost(ask)
}

@Composable
private fun FormField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    label: String,
    tag: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.testTag(tag).semantics { contentDescription = label },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = capitalization, keyboardType = keyboardType),
    )
}

/** A date with its label above and, once set, a T3 Clear (an `<input type="date">` could be emptied). */
@Composable
private fun DateColumn(label: String, value: String, onChange: (String) -> Unit, clearTag: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.textMuted)
            if (value.isNotEmpty()) {
                InlineTextButton("Clear", Modifier.testTag(clearTag).semantics { contentDescription = "Clear $label" }) { onChange("") }
            }
        }
        DateField(value, onChange, label)
    }
}

/** `.form-row-import`: a T1 row with a leading icon and an arrow. */
@Composable
private fun ImportRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.primary).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.material3.Icon(icon, null, Modifier.size(17.dp))
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Glyph(AppIcon.ARROW_RIGHT, 15.dp)
        }
    }
}

/** The photo: a T1 circle that picks one (when P6 can), with a T3 remove on its corner once set. */
@Composable
private fun AvatarButton(avatar: String?, canPick: Boolean, onPick: () -> Unit, onRemove: () -> Unit) {
    val size = AppTheme.sizes.primary
    val bitmap = remember(avatar) { avatar?.let(::decodeDataUri) }
    val border = BorderStroke(if (avatar != null) 2.dp else 1.dp, if (avatar != null) MaterialTheme.colorScheme.primary else AppTheme.colors.borderStrong)
    val face: @Composable () -> Unit = {
        Box(contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap, null, Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
            else Glyph(AppIcon.PLUS, 20.dp)
        }
    }
    Box {
        if (canPick) {
            Surface(
                onClick = onPick,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                border = border,
                modifier = Modifier.size(size).semantics { contentDescription = if (avatar != null) "Change photo" else "Add a photo" },
                content = face,
            )
        } else {
            // Nothing to pick with (until P6): the photo is only shown, so it is not announced as a button.
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                border = border,
                modifier = Modifier.size(size).semantics { contentDescription = "Photo" },
                content = face,
            )
        }
        if (avatar != null) {
            Box(Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-10).dp)) {
                SupportIconButton(AppIcon.X, "Remove photo", onRemove, iconSize = 11.dp)
            }
        }
    }
}

/** A `data:image/…;base64,` avatar as a bitmap; null if it does not decode. */
private fun decodeDataUri(uri: String): ImageBitmap? = runCatching {
    val bytes = Base64.decode(uri.substringAfter("base64,"), Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

private fun strokeIcon(path: String) =
    androidx.compose.ui.graphics.vector.ImageVector.Builder("add", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            pathData = androidx.compose.ui.graphics.vector.addPathNodes(path),
            fill = null,
            stroke = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
        .build()

// The import rows' icons, inlined in #screen-add (www/index.html:71, :76); ui/sheets' AppIcon has neither.
// Arc flags are spelled out with spaces: addPathNodes reads "100-8" as one number, not flags 1 0 then 0.
private val PERSON = strokeIcon("M12 12a4 4 0 1 0 0 -8 4 4 0 0 0 0 8z M4 21a8 8 0 0 1 16 0")
private val PEOPLE = strokeIcon("M17 21v-2a4 4 0 0 0 -4 -4H5a4 4 0 0 0 -4 4v2 M9 11a4 4 0 1 0 0 -8 4 4 0 0 0 0 8z M23 21v-2a4 4 0 0 0 -3 -3.87 M16 3.13a4 4 0 0 1 0 7.75")
