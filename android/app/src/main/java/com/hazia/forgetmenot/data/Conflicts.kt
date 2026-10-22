package com.hazia.forgetmenot.data

import com.hazia.forgetmenot.domain.Keys
import com.hazia.forgetmenot.domain.isValidId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Port of www/js/storage/conflicts.js (SPEC §3 "What a conflict looks like on disk", "Four
// treatment classes", "Resolving a record conflict").
//
// Every read goes by known name, so a Syncthing conflict copy
// ("<name>.sync-conflict-<date>-<time>-<device>.<ext>") is invisible unless scanned for. This
// scan is the app's only awareness of conflicts. The treatments:
//   - index files are id lists: merged (union); ids without a record are already skipped.
//   - settings.json and avatar .b64 copies: low stakes, the conflict copy is dropped.
//   - friend/tag/group records: the newer updatedAt wins and the loser is kept beside it; the
//     user picks only when the stamps cannot decide. Either way a friend keeps both sides'
//     reach-outs, notes, dates and gifts.

/**
 * Every record type in [StoragePaths.RECORD_DIRS] and every index needs an entry here, or its
 * conflict copies pile up unread.
 */
enum class RecordKind(val dir: String, val index: String, val indexKey: String, val prefix: String) {
    FRIEND("friends", "friend_index", Keys.FRIEND_INDEX, Keys.FRIEND_PREFIX),
    TAG("tags", "tag_index", Keys.TAG_INDEX, Keys.TAG_PREFIX),
    GROUP("groups", "group_index", Keys.GROUP_INDEX, Keys.GROUP_PREFIX);

    fun recordKey(id: String) = prefix + id
}

/** A record conflict the stamps could not decide; the user keeps one side. */
data class PendingConflict(
    val kind: RecordKind,
    val id: String,
    val conflictPath: String,
    val canonical: JsonObject,
    val conflicting: JsonObject,
) {
    val dir: String get() = kind.dir
    val recordKey: String get() = kind.recordKey(id)
}

/** [touched]: record kinds rewritten on disk without the user, for [Records.applyTouched]. */
data class ScanResult(
    val pending: List<PendingConflict>,
    val autoResolved: Int,
    val lostKept: Int,
    val touched: Set<RecordKind>,
)

private val CONFLICT_RE = Regex("(.+)\\.sync-conflict-\\d{8}-\\d{6}(?:-[A-Za-z0-9]+)?\\.(json|b64)", RegexOption.IGNORE_CASE)

private data class ConflictName(val base: String, val ext: String)

/** Base name and lower-cased extension of a conflict copy, or null. */
private fun conflictOf(name: String): ConflictName? =
    CONFLICT_RE.matchEntire(name)?.let { ConflictName(it.groupValues[1], it.groupValues[2].lowercase()) }

/** The lists on a friend that only grow, one entry with its own id at a time (domain/Merge.kt). */
private val FRIEND_LOGS = listOf("contacts", "notes", "dates", "gifts")

private fun entryId(e: JsonElement): String = (e as? JsonObject)?.get("id").str() ?: ""

/**
 * mergeFriendLogs() on the JSON: [winner]'s fields, plus every entry of [other]'s logs that the
 * winner lacks, by id. Done on the raw records so the entries keep fields the models drop.
 */
internal fun mergeFriendLogsJson(winner: JsonObject, other: JsonObject?): JsonObject {
    if (other == null) return winner
    val out = LinkedHashMap<String, JsonElement>(winner)
    for (field in FRIEND_LOGS) {
        val mine = winner[field].asArray()
        val have = mine.mapTo(HashSet(), ::entryId)
        val extra = other[field].asArray().filter { entryId(it).let { id -> id.isNotEmpty() && id !in have } }
        if (extra.isNotEmpty()) out[field] = JsonArray(mine + extra)
    }
    return JsonObject(out)
}

/** True when merging added something, so the merged copy has to be written. */
internal fun logsDifferJson(a: JsonObject, b: JsonObject): Boolean = FRIEND_LOGS.any { a[it].asArray().size != b[it].asArray().size }

class Conflicts(private val store: CachedStore, private val records: Records) {

    private suspend fun scanDir(subpath: String): List<String> =
        try { store.fsReaddir(subpath) } catch (e: Exception) { emptyList() } // not there yet, or no folder chosen

    private suspend fun hydrate(kind: RecordKind, record: JsonObject): JsonElement =
        if (kind == RecordKind.FRIEND) store.hydrateAvatar(record) ?: record else record

    private fun indexAdd(kind: RecordKind, id: String) {
        val ids = store.read(kind.indexKey).asArray()
        if (ids.none { it.str() == id }) store.write(kind.indexKey, JsonArray(ids + JsonPrimitive(id)))
    }

    /** Runs the whole scan. A read-only session (I6) writes nothing, so it scans nothing. */
    suspend fun check(): ScanResult {
        if (store.writesBlocked) return ScanResult(emptyList(), 0, 0, emptySet())
        val pending = ArrayList<PendingConflict>()
        var autoResolved = 0
        var lostKept = 0 // how many of those left a .lost- copy behind, for the toast
        val touched = LinkedHashSet<RecordKind>()

        // Makes the record current: written to the canonical name (un-hydrated: the file keeps
        // its avatar marker), hydrated into the cache, and listed in the index if it was not.
        suspend fun adopt(kind: RecordKind, id: String, record: JsonObject) {
            store.fsWrite("${kind.dir}/$id.json", record.toJsonText())
            store.put(kind.recordKey(id), hydrate(kind, record))
            indexAdd(kind, id)
            touched += kind
        }

        for (kind in RecordKind.entries) {
            for (name in scanDir(kind.dir)) {
                val c = conflictOf(name) ?: continue
                if (c.ext != "json") continue
                val id = c.base
                val conflictPath = "${kind.dir}/$name"
                if (!isValidId(id)) { store.fsDelete(conflictPath); continue } // not one of ours
                val conflicting = store.readFileJson(conflictPath) as? JsonObject
                val canonical = store.readFileJson("${kind.dir}/$id.json") as? JsonObject

                if (conflicting == null) { store.fsDelete(conflictPath); continue } // unreadable, nothing to salvage
                if (canonical == null) {
                    // No current version on this device: the conflict copy is the only one left.
                    adopt(kind, id, conflicting)
                    store.fsDelete(conflictPath)
                    autoResolved++
                    continue
                }

                // Every save stamps updatedAt, so differing stamps name the later edit. The loser
                // is written beside the record as <id>.lost-<updatedAt>.json, a name nothing reads
                // or scans, recoverable by hand. Equal or missing stamps fall through to the user.
                val a = canonical["updatedAt"].stamp()
                val b = conflicting["updatedAt"].stamp()
                if (a != 0.0 && b != 0.0 && a != b) {
                    val conflictingWins = b > a
                    val winner = if (conflictingWins) conflicting else canonical
                    val loser = if (conflictingWins) canonical else conflicting
                    val loserStamp = (loser["updatedAt"] as JsonPrimitive).content
                    store.fsWrite("${kind.dir}/$id.lost-$loserStamp.json", loser.toJsonText())
                    // A friend keeps both sides' logs, whichever side wins the rest.
                    val merged = if (kind == RecordKind.FRIEND) mergeFriendLogsJson(winner, loser) else winner
                    if (conflictingWins || logsDifferJson(merged, canonical)) adopt(kind, id, merged)
                    store.fsDelete(conflictPath)
                    autoResolved++
                    lostKept++
                    continue
                }
                pending += PendingConflict(kind, id, conflictPath, canonical, conflicting)
            }
        }

        // Avatar image conflicts: just a photo, not worth a resolution screen.
        for (name in scanDir("avatars")) {
            if (conflictOf(name)?.ext == "b64") { store.fsDelete("avatars/$name"); autoResolved++ }
        }

        // Index and settings conflicts at the sync root: lists are merged, settings dropped.
        val rootConflicts = scanDir("").mapNotNull { n -> conflictOf(n)?.takeIf { it.ext == "json" }?.let { n to it } }
        for (indexName in RecordKind.entries.map { it.index } + "settings") {
            val kind = RecordKind.entries.firstOrNull { it.index == indexName }
            for ((conflictName, _) in rootConflicts.filter { it.second.base.lowercase() == indexName }) {
                val conflicting = store.readFileJson(conflictName)
                if (kind != null && conflicting is JsonArray) mergeIndex(kind, conflicting, touched)
                store.fsDelete(conflictName)
                autoResolved++
            }
        }

        return ScanResult(pending, autoResolved, lostKept, touched)
    }

    private suspend fun mergeIndex(kind: RecordKind, conflicting: JsonArray, touched: MutableSet<RecordKind>) {
        val merged = (store.read(kind.indexKey).asArray() + conflicting).distinct()
        store.put(kind.indexKey, JsonArray(merged))
        store.fsWrite("${kind.index}.json", JsonArray(merged).toJsonText())
        // The index may have been written again (a friend added) while that write was in
        // flight; merge that in too rather than clobber it on disk.
        val reconciled = (merged + store.read(kind.indexKey).asArray()).distinct()
        if (reconciled.size != merged.size) {
            store.put(kind.indexKey, JsonArray(reconciled))
            store.fsWrite("${kind.index}.json", JsonArray(reconciled).toJsonText())
        }
        // Ids the merge brought in have records this device never loaded.
        val added = merged.mapNotNull { it.str() }.filter { isValidId(it) && kind.recordKey(it) !in store }
        if (added.isEmpty()) return
        val files = store.fsReadMany(added.map { "${kind.dir}/$it.json" })
        for (id in added) {
            val rec = try { files["${kind.dir}/$id.json"]?.let(::parseJson) as? JsonObject } catch (e: Exception) { null } ?: continue
            store.put(kind.recordKey(id), hydrate(kind, rec))
            touched += kind
        }
    }

    /**
     * Keeps one side of [item]. Returns false, writing nothing, when the record changed on disk
     * since the scan: "Keep this version" must never discard an edit made after the banner
     * appeared, so the caller rescans and asks again.
     */
    suspend fun resolve(item: PendingConflict, keepConflicting: Boolean): Boolean {
        if (store.writesBlocked) return false
        val path = "${item.dir}/${item.id}.json"
        val currentOnDisk = store.readFileJson(path) as? JsonObject ?: item.canonical
        if (!keepConflicting && currentOnDisk != item.canonical) return false
        val chosen = if (keepConflicting) item.conflicting else currentOnDisk
        // The other side's logged entries are kept either way; only its other fields are dropped.
        val winner = if (item.kind == RecordKind.FRIEND) {
            mergeFriendLogsJson(chosen, if (keepConflicting) currentOnDisk else item.conflicting)
        } else chosen
        store.fsWrite(path, winner.toJsonText())
        store.put(item.recordKey, hydrate(item.kind, winner))
        store.fsDelete(item.conflictPath)
        records.applyTouched(setOf(item.kind))
        return true
    }
}
