@file:OptIn(ExperimentalSerializationApi::class)

package com.hazia.forgetmenot.domain

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

// The records of SPEC §2 "Records". Property names are the JSON field names and properties are
// declared in the SPEC table's order, so an encoded record reads like one the JS wrote.
// Read paths in the JS guard every list (`f.contacts || []`); here a missing or null list
// decodes to empty instead (coerceInputValues), so callers never need the guard.

/**
 * How records are read and written. Unknown fields from a newer build are ignored rather than
 * failing the read; nulls are written out, as JSON.stringify does for the JS's null fields.
 */
val DomainJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

@Serializable
data class Contact(
    val id: String = "",
    /** Full ISO timestamp, as `toISOString()` writes it ([isoTimestamp]). */
    val date: String = "",
    /** Optional `call`, `meet` or `text` ([CONTACT_KINDS]); absent rather than null on disk. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val kind: String? = null,
)

@Serializable
data class Note(val id: String = "", val date: String = "", val text: String = "")

/** A friend's own dated entry (an interview, a move). */
@Serializable
data class FriendDate(val id: String = "", val label: String = "", val dateISO: String = "", val yearly: Boolean = false)

@Serializable
data class Gift(
    val id: String = "",
    val text: String = "",
    /** ISO timestamp, as the JS's `new Date().toISOString()` wrote it ([JsIsoTimestampSerializer]). */
    @Serializable(with = JsIsoTimestampSerializer::class) val addedAt: String? = null,
)

@Serializable
data class Friend(
    val id: String = "",
    val name: String = "",
    val tags: List<String> = emptyList(),
    /**
     * Null when the record carries none; read it through [cadenceOf], which falls back to 90.
     * Absent rather than null on disk: the JS always wrote a number, and validateBackupData
     * rejects a cadenceDays that is present and not > 0, so a written null would make the
     * whole backup unimportable.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @Serializable(with = JsNumberIntSerializer::class) val cadenceDays: Int? = null,
    val paused: Boolean = false,
    val pausedUntil: String? = null,
    val askNext: String? = null,
    val howWeMet: String? = null,
    val dates: List<FriendDate> = emptyList(),
    val snoozedUntil: String? = null,
    val archived: Boolean = false,
    val phone: String? = null,
    val email: String? = null,
    val birthday: String? = null,
    val anniversary: String? = null,
    /** `sms`, `whatsapp`, `telegram`, `signal`, `email` or null. */
    val messageChannel: String? = null,
    val avatar: String? = null,
    val contacts: List<Contact> = emptyList(),
    val notes: List<Note> = emptyList(),
    val gifts: List<Gift> = emptyList(),
    /** Epoch ms; the anchor for a never-logged friend's first nudge. */
    val createdAt: Long? = null,
    /** Epoch ms; decides record conflicts (SPEC §3). */
    val updatedAt: Long? = null,
)

@Serializable
data class Tag(
    val id: String = "",
    val name: String = "",
    /** Absent rather than null on disk, as on [Friend.cadenceDays]. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @Serializable(with = JsNumberIntSerializer::class) val cadenceDays: Int? = null,
    val updatedAt: Long? = null,
)

@Serializable
data class GroupEvent(
    val id: String = "",
    val title: String = "",
    val dateISO: String = "",
    /** [Recurring.ONCE], [Recurring.MONTHLY] or [Recurring.YEARLY]. */
    val recurring: String = Recurring.ONCE,
)

@Serializable
data class Group(
    val id: String = "",
    val name: String = "",
    val memberIds: List<String> = emptyList(),
    val events: List<GroupEvent> = emptyList(),
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
)

/** Defaults are getSettings()'s: `{ skin: 'blue', mode: 'light', quietMode: false, smallHome: false }`. */
@Serializable
data class Settings(
    val skin: String = "blue",
    val mode: String = "light",
    val quietMode: Boolean = false,
    val smallHome: Boolean = false,
    /** Onboarding step key to `true`, written when its card is dismissed. */
    val onboarded: Map<String, Boolean> = emptyMap(),
)

/**
 * A cadence read the way JS `Number()` reads it: a number or a numeric string (a backup may
 * carry `"30"`, which validateBackupData accepts) gives its integer part; anything else is null,
 * so [cadenceOf] falls back to the default exactly as the JS does for NaN.
 */
object JsNumberIntSerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("JsNumberInt", PrimitiveKind.INT).nullable

    override fun deserialize(decoder: Decoder): Int? {
        if (decoder !is JsonDecoder) return decoder.decodeInt()
        val p = decoder.decodeJsonElement() as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        val n = p.booleanOrNull?.let { if (it) 1.0 else 0.0 } ?: p.content.trim().toDoubleOrNull()
        return n?.takeIf { it.isFinite() }?.toInt()
    }

    override fun serialize(encoder: Encoder, value: Int?) {
        if (value == null) encoder.encodeNull() else encoder.encodeInt(value)
    }
}

/**
 * A timestamp stored as the JS's `toISOString()` text. The JS app wrote gift dates that way; a
 * native build before this serializer wrote epoch milliseconds, so a number is accepted too and
 * read as that instant. Anything else (a boolean, an object) reads as null rather than failing
 * the whole record: an undecodable field must never make a friend disappear. Written as text.
 */
object JsIsoTimestampSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("JsIsoTimestamp", PrimitiveKind.STRING).nullable

    override fun deserialize(decoder: Decoder): String? {
        if (decoder !is JsonDecoder) return decoder.decodeString()
        val p = decoder.decodeJsonElement() as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        if (p.isString) return p.content
        val ms = p.content.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        return isoTimestamp(java.time.Instant.ofEpochMilli(ms.toLong()))
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}
