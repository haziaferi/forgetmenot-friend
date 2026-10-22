package com.hazia.forgetmenot.integrations

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.domain.buildICS
import com.hazia.forgetmenot.domain.buildVCard
import com.hazia.forgetmenot.ui.screens.add.AddIntegrations
import com.hazia.forgetmenot.ui.screens.add.PickedContact
import com.hazia.forgetmenot.ui.screens.detail.DetailIntegrations
import com.hazia.forgetmenot.ui.sheets.ImportCandidate
import com.hazia.forgetmenot.ui.sheets.Toaster
import kotlinx.coroutines.CancellationException
import java.time.ZonedDateTime

private const val TAG = "Integrations"

/**
 * The real add-screen and detail-screen integrations: the Photo Picker, contacts import
 * (www/js/ui/avatar.js, www/js/features/contacts.js) and Message / Plan something / Share
 * (www/js/features/share.js). MainActivity provides one per activity through
 * LocalAddIntegrations and LocalDetailIntegrations. Failures are toasted here, as the seams ask.
 */
class DeviceIntegrations(
    private val bridge: DeviceBridge,
    private val contacts: ContactsSource,
    private val images: AvatarImages,
    private val now: () -> ZonedDateTime = ZonedDateTime::now,
    /** startActivity; a test swaps it to see the intents. */
    private val launch: (Context, Intent) -> Unit = { ctx, intent -> ctx.startActivity(intent) },
    /** writeShareFile; a test swaps it, as Robolectric has no FileProvider roots. */
    private val shareFile: (Context, String, String) -> Uri = ::writeShareFile,
) : AddIntegrations, DetailIntegrations {

    // ---- add screen ----

    override val canPickPhoto get() = true
    override val canImportContacts get() = true

    override suspend fun pickPhoto(): String? {
        val uri = bridge.pickImage() ?: return null
        return try {
            images.fromUri(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "photo", e)
            Toaster.show("Could not read that image")
            null
        }
    }

    override suspend fun pickContact(): PickedContact? {
        if (!ensureContactsPermission()) return null
        val uri = bridge.pickContact() ?: return null
        // As in the JS, a failed read after the pick says nothing: the form is simply left as it was.
        return try {
            val c = assembleContacts(contacts.readOne(uri)).firstOrNull() ?: return null
            // Through the same 128 px pipeline as a picked photo: contact photos can be full-size.
            val avatar = contacts.photo(uri)?.let { bytes ->
                try { images.fromBytes(bytes) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            }
            PickedContact(c.name, c.phone, c.email, c.birthday, avatar)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "pickContact", e)
            null
        }
    }

    override suspend fun readContacts(): List<ImportCandidate>? {
        if (!ensureContactsPermission()) return null
        return try {
            assembleContacts(contacts.readAll()).map { ImportCandidate(it.name, it.phone, it.email, it.birthday) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "readContacts", e)
            Toaster.show("Could not read your contacts")
            null
        }
    }

    /** Asked at use, as ensureContactsPermission() did. */
    private suspend fun ensureContactsPermission(): Boolean {
        if (bridge.requestPermission(Manifest.permission.READ_CONTACTS)) return true
        Toaster.show("Contacts permission denied")
        return false
    }

    // ---- detail screen ----

    override val available = true

    override fun message(context: Context, friend: Friend): Boolean {
        val url = messageUrl(friend) ?: run { Toaster.show(noAddressToast(friend)); return false }
        return try {
            launch(context, viewIntent(url))
            true
        } catch (e: ActivityNotFoundException) {
            Toaster.show("No app on this phone can open that")
            false
        }
    }

    override fun plan(context: Context, friend: Friend) {
        val (start, end) = planWindow(now())
        try {
            launch(context, calendarInsertIntent(planTitle(friend), start, end))
            return
        } catch (e: ActivityNotFoundException) {
            // No calendar app: share an .ics file instead. Calendar apps import from an attached
            // file, not pasted text.
        }
        try {
            val uri = shareFile(context, buildICS(friend, start, end), icsFileName(friend.name))
            launch(context, fileShareIntent(uri, "text/calendar", "Save this event"))
            return
        } catch (e: Exception) {
            Log.w(TAG, "ics", e)
        }
        Toaster.show("No calendar app found on this device")
    }

    override fun share(context: Context, friend: Friend) {
        try {
            launch(context, textShareIntent(buildVCard(friend), "Share " + friend.name))
        } catch (e: ActivityNotFoundException) {
            Toaster.show("No app on this phone can share that")
        }
    }
}
