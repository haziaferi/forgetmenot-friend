package com.hazia.forgetmenot.integrations

import com.hazia.forgetmenot.domain.birthdayToISO

/**
 * One row of ContactsContract.Data, reduced to what contactFields() (www/js/features/contacts.js)
 * reads. [data1]..[data3] keep the provider's meaning per [mimeType]: a structured name's display
 * name / given / family, a phone's or email's value, an event's start date and type.
 */
data class DataRow(
    val contactId: Long,
    val mimeType: String,
    val data1: String? = null,
    val data2: String? = null,
    val data3: String? = null,
    val isPrimary: Boolean = false,
    /** The contact's own display name (Data.DISPLAY_NAME), used when it has no structured name. */
    val contactName: String? = null,
)

/** A contact as the add screen takes it. */
data class ContactFields(val name: String, val phone: String?, val email: String?, val birthday: String?)

const val MIME_NAME = "vnd.android.cursor.item/name"
const val MIME_PHONE = "vnd.android.cursor.item/phone_v2"
const val MIME_EMAIL = "vnd.android.cursor.item/email_v2"
const val MIME_EVENT = "vnd.android.cursor.item/contact_event"
val CONTACT_MIMES = listOf(MIME_NAME, MIME_PHONE, MIME_EMAIL, MIME_EVENT)

/** ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY. */
private const val EVENT_TYPE_BIRTHDAY = "3"

/**
 * contactFields() over provider rows, one entry per contact in first-seen order. Name: the
 * structured display name, else given + family, else the contact's display name. Phone and email:
 * the primary one, else the first. Birthday: the first birthday event that parses.
 */
fun assembleContacts(rows: List<DataRow>): List<ContactFields> =
    rows.groupBy { it.contactId }.values.map { rs ->
        val n = rs.firstOrNull { it.mimeType == MIME_NAME }
        val name = n?.data1?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(n?.data2, n?.data3).filter { it.isNotEmpty() }.joinToString(" ").takeIf { it.isNotEmpty() }
            ?: rs.firstNotNullOfOrNull { it.contactName?.takeIf(String::isNotBlank) }
            ?: ""
        fun firstOf(mime: String): String? {
            val values = rs.filter { it.mimeType == mime && !it.data1.isNullOrEmpty() }
            return (values.firstOrNull { it.isPrimary } ?: values.firstOrNull())?.data1
        }
        val birthday = rs.asSequence()
            .filter { it.mimeType == MIME_EVENT && it.data2 == EVENT_TYPE_BIRTHDAY }
            .mapNotNull { parseContactBirthday(it.data1) }
            .firstOrNull()
        ContactFields(name, firstOf(MIME_PHONE), firstOf(MIME_EMAIL), birthday)
    }

// Contacts apps write "1990-03-05", "--03-05" (no year), "19900305" or "--0305", sometimes with a
// time after it.
private val BIRTHDAY = Regex("^(\\d{4}|-)-?(\\d{2})-?(\\d{2})(?:$|[T ])")

/** A provider birthday as birthdayToISO() gives it (1900 for an unknown year); null if unreadable. */
fun parseContactBirthday(s: String?): String? {
    val m = BIRTHDAY.find(s?.trim().orEmpty()) ?: return null
    val (y, mo, d) = m.destructured
    val month = mo.toInt()
    val day = d.toInt()
    if (month !in 1..12 || day !in 1..31) return null
    return birthdayToISO(month, day, y.toIntOrNull())
}
