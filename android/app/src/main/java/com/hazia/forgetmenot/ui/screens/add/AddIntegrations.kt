package com.hazia.forgetmenot.ui.screens.add

import androidx.compose.runtime.staticCompositionLocalOf
import com.hazia.forgetmenot.ui.sheets.ImportCandidate

/**
 * What the add screen needs from the device, which P6 (integrations) supplies: the photo picker
 * (pickAvatarPhoto(), www/js/ui/avatar.js) and the contacts reads (importFromContacts() and
 * importMultipleFromContacts(), www/js/features/contacts.js). P6 provides an implementation
 * through [LocalAddIntegrations]; until then the default hides the photo button and both import
 * rows, so the screen offers nothing it cannot do.
 *
 * Each call asks for any permission it needs and toasts its own failures ("Contacts permission
 * denied", "Could not reach the contacts plugin"); null means nothing to apply.
 */
interface AddIntegrations {
    /** Shows the "Add a photo" button. */
    val canPickPhoto: Boolean get() = false

    /** Shows "Import from contacts" and "Import several at once" (new friends only). */
    val canImportContacts: Boolean get() = false

    /** A picked photo, centre-cropped and scaled to 128×128, as `data:image/jpeg;base64,…`; null if cancelled. */
    suspend fun pickPhoto(): String? = null

    /** One contact from the system picker; null if cancelled or refused. Its photo goes through the same 128 px pipeline. */
    suspend fun pickContact(): PickedContact? = null

    /** Every contact, mapped as contactFields() did; null if refused or failed (already toasted). */
    suspend fun readContacts(): List<ImportCandidate>? = null
}

/** The fields importFromContacts() copies into the form. [birthday] as birthdayToISO() gives it. */
data class PickedContact(
    val name: String,
    val phone: String? = null,
    val email: String? = null,
    val birthday: String? = null,
    val avatar: String? = null,
)

/** Hides what needs P6. */
object NoAddIntegrations : AddIntegrations

/** P6 provides its implementation here, around AppNavHost (MainActivity). */
val LocalAddIntegrations = staticCompositionLocalOf<AddIntegrations> { NoAddIntegrations }
