package com.hazia.forgetmenot.integrations

import android.content.ContentResolver
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.Data
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads contacts; never writes them (SPEC §9). Every call needs READ_CONTACTS already granted. */
interface ContactsSource {
    /** Every contact's name, phone, email and event rows. */
    suspend fun readAll(): List<DataRow>
    /** The rows of the one contact a picker returned. */
    suspend fun readOne(contactUri: Uri): List<DataRow>
    /** That contact's photo, the high-resolution one when there is one; null when it has none. */
    suspend fun photo(contactUri: Uri): ByteArray?
}

/** [ContactsSource] over ContactsContract: one Data query instead of one per contact. */
class ProviderContacts(private val resolver: ContentResolver) : ContactsSource {
    private val projection = arrayOf(
        Data.CONTACT_ID, Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3, Data.IS_PRIMARY, Data.DISPLAY_NAME,
    )
    private val mimeFilter = "${Data.MIMETYPE} IN (${CONTACT_MIMES.joinToString(",") { "?" }})"

    override suspend fun readAll(): List<DataRow> = withContext(Dispatchers.IO) {
        query(mimeFilter, CONTACT_MIMES.toTypedArray())
    }

    override suspend fun readOne(contactUri: Uri): List<DataRow> = withContext(Dispatchers.IO) {
        val id = resolver.query(contactUri, arrayOf(ContactsContract.Contacts._ID), null, null, null)?.use {
            if (it.moveToFirst()) it.getLong(0) else null
        } ?: return@withContext emptyList()
        query("${Data.CONTACT_ID} = ? AND $mimeFilter", arrayOf(id.toString()) + CONTACT_MIMES)
    }

    override suspend fun photo(contactUri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        ContactsContract.Contacts.openContactPhotoInputStream(resolver, contactUri, true)?.use { it.readBytes() }
    }

    private fun query(selection: String, args: Array<String>): List<DataRow> =
        resolver.query(Data.CONTENT_URI, projection, selection, args, "${Data.CONTACT_ID}, ${Data._ID}")?.use { c ->
            buildList {
                while (c.moveToNext()) add(
                    DataRow(
                        contactId = c.getLong(0),
                        mimeType = c.getString(1).orEmpty(),
                        data1 = c.getString(2),
                        data2 = c.getString(3),
                        data3 = c.getString(4),
                        isPrimary = c.getInt(5) != 0,
                        contactName = c.getString(6),
                    )
                )
            }
        } ?: error("Contacts provider unavailable")
}
