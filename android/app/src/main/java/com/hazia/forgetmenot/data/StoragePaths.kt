package com.hazia.forgetmenot.data

import android.util.Log
import com.hazia.forgetmenot.domain.Keys
import com.hazia.forgetmenot.domain.isValidId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

// Port of keyToPath() and its tables (www/js/storage/store.js; SPEC §2 "Keys, files and the
// cache", "Sync folder layout").

object StoragePaths {
    /**
     * One directory per record type. A type missing here falls through to keyToPath's
     * sanitising fallback, which lands it in the sync root and skips the isValidId() check, and
     * its conflict copies go unscanned unless [RecordKind] gains it too.
     */
    val RECORD_DIRS: Map<String, String> = linkedMapOf(
        Keys.FRIEND_PREFIX to "friends",
        Keys.TAG_PREFIX to "tags",
        Keys.GROUP_PREFIX to "groups",
    )

    val INDEX_FILES: Map<String, String> = linkedMapOf(
        Keys.FRIEND_INDEX to "friend_index.json",
        Keys.TAG_INDEX to "tag_index.json",
        Keys.GROUP_INDEX to "group_index.json",
        Keys.SETTINGS to "settings.json",
    )

    const val AVATAR_MARKER = "file:"

    private val UNSAFE = Regex("[^a-zA-Z0-9_.-]")

    /** A key's file. A record key's id is checked first: it becomes a path segment. */
    fun keyToPath(key: String): String {
        for ((prefix, dir) in RECORD_DIRS) {
            if (!key.startsWith(prefix)) continue
            val id = key.substring(prefix.length)
            require(isValidId(id)) { "keyToPath: invalid id \"$id\" in key \"$key\"" }
            return "$dir/$id.json"
        }
        return INDEX_FILES[key] ?: (key.replace(UNSAFE, "_") + ".json")
    }

    fun avatarPath(id: String) = "avatars/$id.b64"
}

// JSON as the files hold it. Records stay JsonElements in the cache, so a field this build does
// not know survives a load and save, and an unchanged record re-encodes to the bytes it was read
// from: toString() writes compact JSON, as JSON.stringify does, and prints each literal as it
// was parsed. Not Json.encodeToString: JsonElement's serializer re-types number literals and
// prints them as Kotlin does, so 1e+21 would come back as 1.0E21 (RoundTripTest).

internal fun parseJson(text: String): JsonElement = Json.parseToJsonElement(text)

internal fun JsonElement.toJsonText(): String = toString()

/** The string a JSON value holds, or null for anything that is not a string. */
internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** JS `Number(x) || 0` for a stamp: a number or numeric string, else 0. */
internal fun JsonElement?.stamp(): Double =
    (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0

internal fun JsonElement?.asArray(): List<JsonElement> = (this as? JsonArray) ?: emptyList()

internal fun logError(message: String, e: Throwable? = null) {
    try { Log.e("forgetmenot", message, e) } catch (_: RuntimeException) { /* JVM tests without android.util */ }
}
