package com.hazia.forgetmenot.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

// Port of www/js/lib/validate.js (SPEC §2 "Backup export and import").
//
// It works on parsed JSON rather than the model classes on purpose: it has to see a record's
// shape as the file carries it (a list that is a string, a field of the wrong type) to decide
// between rejecting the backup and dropping one field, and decoding would already have
// coerced both away.

private val ISO_DAY = Regex("\\d{4}-\\d{2}-\\d{2}")
private val INDEX_KEYS = setOf(Keys.FRIEND_INDEX, Keys.TAG_INDEX, Keys.GROUP_INDEX)
private val RECORD_PREFIXES = listOf(Keys.FRIEND_PREFIX, Keys.TAG_PREFIX, Keys.GROUP_PREFIX)

/**
 * Checks an imported backup's shape before anything is written: [data] maps each key to its
 * JSON text. Returns the parsed records, with droppable faults dropped, or null to reject the
 * backup whole. Ids become storage keys and path segments, so a bad id rejects it, while a bad
 * avatar is only dropped. A null record (older exports wrote one for a missing record) is
 * left out of the result.
 */
fun validateBackupData(data: Map<String, String>, keys: Collection<String>): Map<String, JsonElement>? {
    val parsed = LinkedHashMap<String, JsonElement>()
    for (k in keys) {
        val text = data[k] ?: return null
        val v = try { Json.parseToJsonElement(text) } catch (e: Exception) { return null }

        when {
            k in INDEX_KEYS -> {
                if (v !is JsonArray || !v.all { isValidId(it.stringOrNull()) }) return null
                parsed[k] = v
            }
            RECORD_PREFIXES.any { k.startsWith(it) } -> {
                val id = k.substringAfter(':')
                if (!isValidId(id)) return null
                if (v is JsonNull) continue
                parsed[k] = validateRecord(k, id, v) ?: return null
            }
            k == Keys.SETTINGS && v is JsonObject -> {
                // An unknown skin or mode would leave Settings without a theme. Coerce rather
                // than reject: a stale theme name isn't a reason to refuse someone's data.
                val s = v.toMutableMap()
                if (v["skin"].stringOrNull() !in THEMES) s["skin"] = JsonPrimitive("blue")
                if (v["mode"].stringOrNull() != "light" && v["mode"].stringOrNull() != "dark") s["mode"] = JsonPrimitive("light")
                parsed[k] = JsonObject(s)
            }
            else -> parsed[k] = v
        }
    }
    return parsed
}

/** One friend, tag or group record, with droppable faults dropped; null rejects the backup. */
private fun validateRecord(k: String, id: String, v: JsonElement): JsonObject? {
    // A plain object whose id is the one in its key (row navigation goes by record id).
    if (v !is JsonObject || v["id"].stringOrNull() != id) return null
    val r = v.toMutableMap()

    if ("avatar" in r && r["avatar"] !is JsonNull && !isValidAvatar(r["avatar"].stringOrNull())) r["avatar"] = JsonNull
    // A malformed hold date is dropped, not a reason to refuse the backup.
    for (d in listOf("pausedUntil", "snoozedUntil")) {
        if (d in r && r[d] !is JsonNull && !isIsoDay(r[d])) r[d] = JsonNull
    }
    // Free-text fields that are not text are dropped; so is a dated entry without a valid date.
    for (t in listOf("askNext", "howWeMet")) {
        if (t in r && r[t] !is JsonNull && r[t].stringOrNull() == null) r[t] = JsonNull
    }
    (r["dates"] as? JsonArray)?.let { arr ->
        r["dates"] = JsonArray(arr.filter { d -> d is JsonObject && isValidId(d["id"].stringOrNull()) && isIsoDay(d["dateISO"]) })
    }
    (r["gifts"] as? JsonArray)?.let { arr ->
        r["gifts"] = JsonArray(arr.filter { g -> g is JsonObject && isValidId(g["id"].stringOrNull()) && !g["text"].stringOrNull().isNullOrBlank() })
    }
    // An unknown kind on a reach-out is dropped; the reach-out itself still counts.
    (r["contacts"] as? JsonArray)?.let { arr ->
        r["contacts"] = JsonArray(arr.map { c ->
            if (c is JsonObject && "kind" in c && c["kind"].stringOrNull() !in CONTACT_KINDS) JsonObject(c - "kind") else c
        })
    }

    val arrays = when {
        k.startsWith(Keys.FRIEND_PREFIX) -> listOf("contacts", "notes", "tags", "dates", "gifts")
        k.startsWith(Keys.GROUP_PREFIX) -> listOf("memberIds", "events")
        else -> emptyList()
    }
    if (arrays.any { it in r && r[it] !is JsonArray }) return null
    if ("cadenceDays" in r && !(jsNumber(r["cadenceDays"]) > 0)) return null
    // (v.events || []).some(ev => !ev || !/^\d{4}-\d{2}-\d{2}$/.test(ev.dateISO)): a falsy
    // events is no events; anything else that is not a list would throw in the JS, so it rejects.
    when (val events = r["events"]) {
        null -> {}
        is JsonArray -> if (events.any { ev -> jsFalsy(ev) || ev !is JsonObject || !isIsoDay(ev["dateISO"]) }) return null
        else -> if (!jsFalsy(events)) return null
    }
    return JsonObject(r)
}

private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun isIsoDay(e: JsonElement?): Boolean = e.stringOrNull()?.let(ISO_DAY::matches) == true

private fun jsFalsy(e: JsonElement): Boolean = when (e) {
    is JsonNull -> true
    is JsonPrimitive -> if (e.isString) e.content.isEmpty() else e.booleanOrNull == false || e.doubleOrNull.let { it == 0.0 || it?.isNaN() == true }
    else -> false
}

/** JS `Number(v)` for a JSON value; NaN where JS gives NaN. */
private fun jsNumber(e: JsonElement?): Double = when (e) {
    null -> Double.NaN
    is JsonNull -> 0.0
    is JsonPrimitive -> when {
        e.isString -> e.content.trim().let { if (it.isEmpty()) 0.0 else it.toDoubleOrNull() ?: Double.NaN }
        e.booleanOrNull != null -> if (e.booleanOrNull == true) 1.0 else 0.0
        else -> e.doubleOrNull ?: Double.NaN
    }
    // [] is 0 and [x] is Number(String(x)); longer lists and objects are NaN.
    is JsonArray -> when (e.size) {
        0 -> 0.0
        1 -> (e[0] as? JsonPrimitive)?.let { jsNumber(if (it is JsonNull) JsonPrimitive("") else JsonPrimitive(it.content)) } ?: Double.NaN
        else -> Double.NaN
    }
    is JsonObject -> Double.NaN
}
