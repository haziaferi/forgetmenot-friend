package com.hazia.forgetmenot.ui.screens.add

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hazia.forgetmenot.data.Records
import com.hazia.forgetmenot.domain.CADENCE_PRESETS
import com.hazia.forgetmenot.domain.DEFAULT_CADENCE_DAYS
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.domain.Note
import com.hazia.forgetmenot.domain.Tag
import com.hazia.forgetmenot.domain.isoTimestamp
import com.hazia.forgetmenot.domain.plural
import com.hazia.forgetmenot.domain.uid
import com.hazia.forgetmenot.ui.sheets.Toaster
import java.time.Instant

// The add/edit form's logic from www/js/screens/add.js, kept apart from Compose so it can be
// tested and so the form survives recomposition, rotation (the destination keeps the model in a
// ViewModel) and the sheets drawn over it.

/** sms, whatsapp, telegram, signal, email with their chip labels (MESSAGE_CHANNEL_LABEL, www/js/features/share.js:16). */
val MESSAGE_CHANNELS: List<Pair<String, String>> = listOf(
    "sms" to "Message",
    "whatsapp" to "WhatsApp",
    "telegram" to "Telegram",
    "signal" to "Signal",
    "email" to "Email",
)

/**
 * Everything the form edits (readForm() in the JS). Dates are ISO `yyyy-MM-dd` or "", text
 * fields as typed; trimming happens on save.
 */
data class AddForm(
    val name: String = "",
    val note: String = "",
    val phone: String = "",
    val email: String = "",
    val howWeMet: String = "",
    val birthday: String = "",
    val anniversary: String = "",
    val avatar: String? = null,
    val channel: String? = null,
    val cadence: Int = DEFAULT_CADENCE_DAYS,
    val tags: List<String> = emptyList(),
)

/** A changed form left by Cancel or back, for the friend it was editing ("new" when adding). */
data class LeftDraft(val key: String, val form: AddForm)

/** state.addLeftDraft: one draft at a time, in memory only, so it does not outlive the process. */
object AddDrafts {
    @Volatile var left: LeftDraft? = null
        internal set

    fun reset() { left = null }
}

/** What a save did, so the destination knows where to go. */
sealed interface SaveResult {
    data object NeedsName : SaveResult
    data class Added(val name: String) : SaveResult
    data class Saved(val id: String) : SaveResult
    /** The friend being edited no longer exists. */
    data object Gone : SaveResult
}

/**
 * One visit to the add screen. Construction is seedAddForm(): it runs once per entry, never on
 * recomposition, so a resume or a conflict resolve cannot clobber half-typed fields.
 */
@Stable
class AddFormModel(
    private val records: Records,
    val editId: String?,
    private val now: () -> Instant = Instant::now,
) {
    val editing: Boolean = editId != null
    private val key = editId ?: "new"

    /** Editing a friend that is not there (deleted elsewhere, a stale route): the screen leaves. */
    val friendMissing: Boolean = editId != null && records.getFriend(editId) == null

    private var seeded: AddForm = seed()
    var form: AddForm by mutableStateOf(seeded)
        private set

    /** True while the form shows a draft picked up from last time ("Start over" is offered). */
    var restored: Boolean by mutableStateOf(false)
        private set

    init {
        val left = AddDrafts.left
        if (left != null && left.key == key) {
            form = left.form
            restored = true
            Toaster.show("Picked up where you left off")
        } else {
            AddDrafts.left = null
        }
    }

    private fun seed(): AddForm {
        val f = editId?.let(records::getFriend) ?: return AddForm()
        return AddForm(
            name = f.name,
            phone = f.phone.orEmpty(),
            email = f.email.orEmpty(),
            howWeMet = f.howWeMet.orEmpty(),
            birthday = f.birthday.orEmpty(),
            anniversary = f.anniversary.orEmpty(),
            avatar = f.avatar,
            channel = f.messageChannel,
            cadence = f.cadenceDays?.takeIf { it > 0 } ?: DEFAULT_CADENCE_DAYS,
            tags = f.tags,
        )
    }

    fun update(change: (AddForm) -> AddForm) { form = change(form) }

    /** A channel chip: picks it, or clears it when it is the one already picked. */
    fun toggleChannel(id: String) = update { it.copy(channel = if (it.channel == id) null else id) }

    /** Picking a tag copies its cadence onto the form once; dropping it leaves the cadence. */
    fun toggleTag(id: String) = update { f ->
        if (id in f.tags) f.copy(tags = f.tags - id)
        else f.copy(tags = f.tags + id, cadence = records.getTag(id)?.cadenceDays?.takeIf { it > 0 } ?: f.cadence)
    }

    fun setCadence(days: Int) = update { it.copy(cadence = days) }

    /** "New tag": saved at once with the form's cadence, and selected. */
    fun addTag(name: String) {
        val id = uid()
        records.saveTag(Tag(id = id, name = name, cadenceDays = form.cadence))
        update { it.copy(tags = it.tags + id) }
    }

    /** A contact picked through P6: fills name, phone, email and birthday; a photo only if it has one. */
    fun applyContact(c: PickedContact) {
        update {
            it.copy(
                name = c.name, phone = c.phone.orEmpty(), email = c.email.orEmpty(),
                birthday = c.birthday.orEmpty(), avatar = c.avatar ?: it.avatar,
            )
        }
        Toaster.show("Imported " + c.name.ifEmpty { "contact" })
    }

    /** leaveAddForm(): an untouched form is simply left; a changed one is kept for next time. */
    fun leave() {
        if (saved != null) return // a back press during the exit animation must not bring the saved form back as a draft
        AddDrafts.left = if (form != seeded) LeftDraft(key, form) else null
    }

    /** startAddFormOver(). */
    fun startOver() {
        AddDrafts.left = null
        seeded = seed()
        form = seeded
        restored = false
    }

    /** saveFriendFromAdd(). Saves go through Records, which reschedules (and does nothing on disk when read-only, I6). */
    fun save(): SaveResult {
        // The screen still takes taps while it animates away, so a quick second Save must not add
        // the friend twice: once saved, the visit only repeats its result.
        saved?.let { return it }
        return saveOnce().also { if (it is SaveResult.Added || it is SaveResult.Saved) saved = it }
    }

    /** The result of this visit's save, once it succeeded. */
    private var saved: SaveResult? = null

    private fun saveOnce(): SaveResult {
        val f = form
        val name = f.name.trim()
        if (name.isEmpty()) {
            Toaster.show("Give them a name first")
            return SaveResult.NeedsName
        }
        fun opt(s: String) = s.trim().ifEmpty { null }
        val tags = f.tags.filter { records.getTag(it) != null } // a tag deleted while the form was open
        val cadence = f.cadence.takeIf { it > 0 } ?: DEFAULT_CADENCE_DAYS

        if (editId != null) {
            // Deleted elsewhere while the form was open: there is nothing left to save into.
            val existing = records.getFriend(editId) ?: return SaveResult.Gone.also { AddDrafts.left = null }
            records.saveFriend(
                existing.copy(
                    name = name, tags = tags, cadenceDays = cadence,
                    phone = opt(f.phone), email = opt(f.email), howWeMet = opt(f.howWeMet),
                    birthday = f.birthday.ifEmpty { null }, anniversary = f.anniversary.ifEmpty { null },
                    messageChannel = f.channel, avatar = f.avatar,
                ),
            )
            AddDrafts.left = null
            Toaster.show("Saved")
            return SaveResult.Saved(editId)
        }

        val note = f.note.trim()
        records.saveFriend(
            Friend(
                id = uid(),
                name = name, tags = tags, cadenceDays = cadence,
                phone = opt(f.phone), email = opt(f.email), howWeMet = opt(f.howWeMet),
                birthday = f.birthday.ifEmpty { null }, anniversary = f.anniversary.ifEmpty { null },
                messageChannel = f.channel, avatar = f.avatar,
                paused = false, archived = false,
                // A note is memory, not proof of contact: a new friend starts at classify()'s "new".
                contacts = emptyList(),
                notes = if (note.isEmpty()) emptyList() else listOf(Note(uid(), isoTimestamp(now()), note)),
            ),
        )
        AddDrafts.left = null
        Toaster.show("Added $name")
        return SaveResult.Added(name)
    }
}
