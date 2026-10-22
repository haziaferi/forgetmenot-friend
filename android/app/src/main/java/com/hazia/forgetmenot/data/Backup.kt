package com.hazia.forgetmenot.data

import com.hazia.forgetmenot.domain.BACKUP_VERSION
import com.hazia.forgetmenot.domain.localDateISO
import com.hazia.forgetmenot.domain.validateBackupData
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.ZonedDateTime

// Port of www/js/features/backup.js (SPEC §2 "Backup export and import"). The toasts and the
// "Replace this device's data?" confirmation are the settings screen's (P5); these return
// what happened.

sealed interface ExportResult {
    /** Saved to `<folderName>/backups/<fileName>`. */
    data class Saved(val folderName: String?, val fileName: String) : ExportResult
    /** "Choose a sync folder first (in Settings), then try exporting again". */
    data object NoFolder : ExportResult
    /** "Backup failed — could not write to the sync folder". */
    data object Failed : ExportResult
    /**
     * Read-only session (I6): the cache may be half-loaded, and the file would replace a good
     * backup saved earlier that day under the same name.
     */
    data object ReadOnly : ExportResult
}

sealed interface ImportCheck {
    /** Valid: ask the user, then hand [data] to [Backup.apply]. */
    data class Ready(val data: Map<String, JsonElement>) : ImportCheck
    /** "This backup is from a newer forgetmenot — update the app first". */
    data object Newer : ImportCheck
    /** "That file doesn't look like a forgetmenot backup". */
    data object NotABackup : ImportCheck
    /** "That backup file looks corrupted or tampered with — nothing was imported". */
    data object Corrupt : ImportCheck
    /** "Could not read that file". */
    data object Unreadable : ImportCheck
}

@OptIn(ExperimentalSerializationApi::class)
private val BackupJson = Json { prettyPrint = true; prettyPrintIndent = "  " } // JSON.stringify(data, null, 2)

class Backup(private val store: CachedStore, private val records: Records) {

    /**
     * Serialises the cache into a flat object mapping storage key to the stringified record — a
     * JSON file of JSON strings — and writes it to backups/forgetmenot-backup-<local date>.json.
     * `version` lacks the fm_ prefix, which keeps it out of the import's record list. Avatars are
     * inline data URIs, so a backup is self-contained.
     */
    suspend fun export(now: ZonedDateTime = ZonedDateTime.now()): ExportResult {
        val data = LinkedHashMap<String, JsonElement>()
        data["version"] = JsonPrimitive(BACKUP_VERSION)
        for ((k, v) in store.snapshot()) if (v !is JsonNull) data[k] = JsonPrimitive(v.toJsonText())
        val text = BackupJson.encodeToString(JsonElement.serializer(), JsonObject(data))
        val fileName = "forgetmenot-backup-${localDateISO(now)}.json"

        val status = store.fsStatus()
        if (!status.granted) return ExportResult.NoFolder
        if (store.writesBlocked) return ExportResult.ReadOnly
        return try {
            store.fsWrite("backups/$fileName", text)
            ExportResult.Saved(status.name, fileName)
        } catch (e: Exception) {
            logError("backup failed", e)
            ExportResult.Failed
        }
    }

    /** Checks a backup file's text; nothing is touched. One fault rejects the whole file. */
    fun parse(text: String): ImportCheck {
        val data = try { parseJson(text) } catch (e: Exception) { return ImportCheck.Unreadable } as? JsonObject
            ?: return ImportCheck.Unreadable
        val version = (data["version"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.trim()?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it != 0.0 } ?: 1.0
        if (version > BACKUP_VERSION) return ImportCheck.Newer
        val keys = data.keys.filter { it.startsWith("fm_") }
        if (keys.isEmpty()) return ImportCheck.NotABackup
        // Each value is a record's JSON text. JS parsed a non-string through String(v): a
        // number or null survives that, an object or a list does not.
        val texts = LinkedHashMap<String, String>()
        for (k in keys) texts[k] = when (val v = data.getValue(k)) {
            is JsonPrimitive -> v.content
            is JsonObject, is JsonArray -> return ImportCheck.Corrupt
        }
        val parsed = validateBackupData(texts, keys) ?: return ImportCheck.Corrupt
        return ImportCheck.Ready(parsed)
    }

    /**
     * Replaces this device's data with a checked backup. Writes the new set first, then drops
     * what it did not contain: the other order has a moment with nothing on disk, and a process
     * killed there leaves the device empty. The theme follows the settings on its own; the
     * widgets and reminders are pushed again, since neither recomputes by itself.
     */
    fun apply(data: Map<String, JsonElement>) {
        // Read-only, the writes below would only reach the cache and be lost on restart (I6).
        if (store.writesBlocked) throw ReadOnlySessionException("read-only session: not importing")
        for ((k, v) in data) store.write(k, v)
        for (k in store.keys()) if (k !in data) store.remove(k)
        records.pushWidgets()
        records.rescheduleReminders()
    }
}
