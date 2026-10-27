package com.hazia.forgetmenot.data

import com.hazia.forgetmenot.domain.Keys
import com.hazia.forgetmenot.domain.isValidId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Port of www/js/storage/store.js (SPEC §2 "Keys, files and the cache", "Loading, and what
 * counts as absent", "Avatar indirection").
 *
 * The app reads and writes the cache synchronously. It is filled from the sync folder once at
 * startup ([load]), and every write is persisted to its file in the background, in order.
 * Values are the records' JSON as the files hold it (friends with their avatar hydrated to a
 * data: URI); [Records] decodes them into the domain models.
 *
 * Every folder call runs on [io], a one-thread view: the atomic .tmp/.bak swap's crash recovery
 * assumes no write of this app is mid-swap while a directory is listed, which the web build's
 * bridge gave the plugin by running its calls one at a time, and this keeps.
 */
class CachedStore(
    val fs: FolderFs,
    private val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {
    private val lock = Any()
    private val cache = LinkedHashMap<String, JsonElement>()

    private val _revision = MutableStateFlow(0L)
    /** Bumped on every cache change, so UI can re-read what it shows. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val _readOnly = MutableStateFlow(false)
    /**
     * Invariant I6. Set when the sync folder could not be read: writing from a half-loaded cache
     * would overwrite good files with partial ones, so persisting, deleting, seeding, the widget
     * drain, pull-to-refresh, rescheduling and widget pushes all stand down until a folder with
     * data is adopted.
     */
    val readOnly: StateFlow<Boolean> = _readOnly.asStateFlow()
    val writesBlocked: Boolean get() = _readOnly.value
    fun blockWrites() { _readOnly.value = true }
    internal fun unblockWrites() { _readOnly.value = false }

    // ── the cache ────────────────────────────────────────────────────────────

    operator fun contains(key: String): Boolean = synchronized(lock) { key in cache }
    fun read(key: String): JsonElement? = synchronized(lock) { cache[key] }
    fun keys(): List<String> = synchronized(lock) { cache.keys.toList() }
    fun snapshot(): Map<String, JsonElement> = synchronized(lock) { LinkedHashMap(cache) }

    /** Sets the key now and queues its file write. */
    fun write(key: String, value: JsonElement) {
        put(key, value)
        enqueue { persistKey(key, value) }
    }

    /** Drops the key now and queues its file delete. */
    fun remove(key: String) {
        drop(key)
        enqueue { deleteKey(key) }
    }

    /** Cache only, no file write: for values just read from disk. */
    internal fun put(key: String, value: JsonElement) {
        synchronized(lock) { cache[key] = value }
        _revision.update { it + 1 }
    }

    internal fun drop(key: String) {
        synchronized(lock) { cache.remove(key) }
        _revision.update { it + 1 }
    }

    internal fun clear() {
        synchronized(lock) { cache.clear() }
        _revision.update { it + 1 }
    }

    // ── the write queue ──────────────────────────────────────────────────────
    // Writes stay fire-and-forget for callers but run one after another, so two quick saves of
    // a record cannot land on disk swapped. flushWrites() lets a reload wait for them instead of
    // reading back the state from before them. A write still queued when the process dies is
    // lost, as in the JS.

    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        CoroutineScope(SupervisorJob() + io).launch {
            for (task in queue) {
                try { task() } catch (e: CancellationException) { throw e } catch (e: Exception) { logError("queued write failed", e) }
            }
        }
    }

    private fun enqueue(task: suspend () -> Unit) { queue.trySend(task) }

    suspend fun flushWrites() {
        val done = CompletableDeferred<Unit>()
        queue.send { done.complete(Unit) }
        done.await()
    }

    // ── the folder, one call at a time ───────────────────────────────────────

    suspend fun fsWrite(path: String, text: String) = withContext(io) { fs.writeFile(path, text) }
    suspend fun fsRead(path: String): String = withContext(io) { fs.readFile(path) }
    suspend fun fsDelete(path: String) = withContext(io) {
        try { fs.deleteFile(path) } catch (e: Exception) { /* already gone, or no folder */ }
    }
    suspend fun fsReaddir(path: String): List<String> = withContext(io) { fs.readdir(path) }
    suspend fun fsStatus(): FolderStatus = withContext(io) { fs.status() }

    /** Many files in batches of [READ_BATCH]; an absent or unreadable path is missing from the result. */
    suspend fun fsReadMany(paths: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (chunk in paths.chunked(READ_BATCH)) out += withContext(io) { fs.readFiles(chunk) }
        return out
    }

    // ── persisting ───────────────────────────────────────────────────────────

    /**
     * Writes one key's file. A friend's data: avatar goes to the sibling avatars/<id>.b64 and the
     * record keeps the marker "file:avatars/<id>.b64" (base64 inside a record syncs and diffs
     * badly); a null avatar deletes the sidecar, which would otherwise keep syncing forever.
     * Failures are logged, not surfaced; with no folder picked yet a write is expected to fail
     * (SPEC §9 "Nothing is saved until a folder is picked"), so that one is not logged.
     */
    suspend fun persistKey(key: String, value: JsonElement) {
        if (writesBlocked) return
        try {
            var toWrite = value
            val friendId = if (key.startsWith(Keys.FRIEND_PREFIX) && value is JsonObject) key.removePrefix(Keys.FRIEND_PREFIX) else null
            if (friendId != null) {
                val avatar = (value as JsonObject)["avatar"]
                val s = avatar.str()
                if (s != null) {
                    if (s.startsWith("data:image")) {
                        val path = StoragePaths.avatarPath(friendId)
                        fsWrite(path, s.split(',').getOrElse(1) { "" })
                        toWrite = JsonObject(value + ("avatar" to JsonPrimitive(StoragePaths.AVATAR_MARKER + path)))
                    }
                } else if (avatar == null || avatar is JsonNull) {
                    fsDelete(StoragePaths.avatarPath(friendId))
                }
            }
            fsWrite(StoragePaths.keyToPath(key), toWrite.toJsonText())
        } catch (e: CancellationException) { throw e } catch (e: Exception) { if (!e.isNoFolder()) logError("persist failed for $key", e) }
    }

    suspend fun deleteKey(key: String) {
        if (writesBlocked) return
        try {
            fsDelete(StoragePaths.keyToPath(key))
            if (key.startsWith(Keys.FRIEND_PREFIX)) fsDelete(StoragePaths.avatarPath(key.removePrefix(Keys.FRIEND_PREFIX)))
        } catch (e: CancellationException) { throw e } catch (e: Exception) { if (!e.isNoFolder()) logError("delete failed for $key", e) }
    }

    private fun Exception.isNoFolder() = this is FolderException && code == FolderError.NO_FOLDER_SELECTED

    // ── reading ──────────────────────────────────────────────────────────────

    /** Any failure, or a JSON null, reads as null. */
    suspend fun readFileJson(path: String): JsonElement? =
        try { parseJson(fsRead(path)).takeUnless { it is JsonNull } } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    /**
     * Strict read for files whose absence means something: null means absent, and only
     * NOT_FOUND (or no folder picked yet) is absent. A failed read or a truncated file throws,
     * because mistaking those for absent is how an empty index gets written over a real one.
     */
    suspend fun readFileStrict(path: String): JsonElement? {
        val text = try { fsRead(path) } catch (e: FolderException) {
            if (e.code == FolderError.NOT_FOUND || e.code == FolderError.NO_FOLDER_SELECTED) return null
            throw e
        }
        return parseJson(text)
    }

    /** Record ids in a directory, from the file names; throws if the listing fails. */
    suspend fun listRecordIds(dir: String): List<String> =
        fsReaddir(dir).mapNotNull { n -> n.takeIf { it.endsWith(".json") }?.removeSuffix(".json")?.takeIf(::isValidId) }

    /**
     * Turns a friend record's "file:" avatar marker back into a data: URI, or null when the
     * sidecar is unreadable. Every path that loads a friend record from disk into the cache must
     * call this; it is the only decoder of the marker. [preread] is an optional path-to-contents
     * map from [fsReadMany], for batch loads; a path missing from it reads as unreadable.
     */
    suspend fun hydrateAvatar(f: JsonElement?, preread: Map<String, String>? = null): JsonElement? {
        val obj = f as? JsonObject ?: return f
        val marker = obj["avatar"].str()?.takeIf { it.startsWith(StoragePaths.AVATAR_MARKER) } ?: return f
        val path = marker.removePrefix(StoragePaths.AVATAR_MARKER)
        val raw = if (preread != null) preread[path] else try { fsRead(path) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        val avatar = if (!raw.isNullOrEmpty()) JsonPrimitive("data:image/jpeg;base64,$raw") else JsonNull
        return JsonObject(obj + ("avatar" to avatar))
    }

    /**
     * Fills the cache from the folder, awaited before the first render; every record type that
     * [write] persists must be read back here, or the next launch loses it. Throws when an index
     * exists but neither it nor its directory listing can be read; the caller then makes the
     * session read-only.
     */
    suspend fun load() {
        // An absent index leaves its key out, so seeding can tell a new install from one whose
        // tags were all deleted. An index that exists but cannot be read is rebuilt from its
        // directory listing and written back; if the listing fails too, this throws.
        suspend fun hydrateIndex(key: String, dir: String, vararg files: String) {
            for (f in files) {
                val v = try { readFileStrict(f) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    logError("unreadable $f, rebuilding from $dir/", e)
                    write(key, JsonArray(listRecordIds(dir).map(::JsonPrimitive)))
                    return
                }
                if (v != null) {
                    put(key, JsonArray(v.asArray().filter { isValidId(it.str()) }))
                    return
                }
            }
        }

        hydrateIndex(Keys.FRIEND_INDEX, "friends", "friend_index.json")
        hydrateIndex(Keys.TAG_INDEX, "tags", "tag_index.json")
        // Second path: groups once lived loose in the sync root; the next group save moves them.
        hydrateIndex(Keys.GROUP_INDEX, "groups", "group_index.json", "fm_group_index.json")
        (readFileJson("settings.json") as? JsonObject)?.let { put(Keys.SETTINGS, it) }

        // Records are read by exact name from the index, in one batch rather than a call each.
        val friendIds = ids(Keys.FRIEND_INDEX)
        val tagIds = ids(Keys.TAG_INDEX)
        val groupIds = ids(Keys.GROUP_INDEX)
        val files = fsReadMany(
            friendIds.map { "friends/$it.json" } + tagIds.map { "tags/$it.json" } + groupIds.map { "groups/$it.json" },
        )
        fun parse(path: String, from: Map<String, String> = files): JsonObject? =
            try { from[path]?.let(::parseJson) as? JsonObject } catch (e: Exception) { null }

        // Avatar sidecars are a second batch, handed to hydrateAvatar.
        val friends = friendIds.map { parse("friends/$it.json") }
        val markers = friends.mapNotNull { f ->
            f?.get("avatar").str()?.takeIf { it.startsWith(StoragePaths.AVATAR_MARKER) }?.removePrefix(StoragePaths.AVATAR_MARKER)
        }
        val b64 = if (markers.isNotEmpty()) fsReadMany(markers) else emptyMap()

        // An index id whose record is missing (deleted elsewhere, not synced yet) leaves no key
        // at all: a null in the cache would be exported into every backup and make it unimportable.
        fun putRecord(key: String, rec: JsonElement?) { if (rec is JsonObject) put(key, rec) else if (key in this) drop(key) }
        friendIds.forEachIndexed { i, id -> putRecord(Keys.friend(id), hydrateAvatar(friends[i], b64)) }
        for (id in tagIds) putRecord(Keys.tag(id), parse("tags/$id.json"))
        for (id in groupIds) putRecord(Keys.group(id), parse("groups/$id.json"))

        // Only groups missing from groups/ are looked for at the legacy root location.
        val strays = groupIds.filter { Keys.group(it) !in this }
        if (strays.isNotEmpty()) {
            val old = fsReadMany(strays.map { "fm_group_$it.json" })
            for (id in strays) putRecord(Keys.group(id), parse("fm_group_$id.json", old))
        }
    }

    /** An index's ids as stored (strings only). */
    internal fun ids(indexKey: String): List<String> = read(indexKey).asArray().mapNotNull { it.str() }

    companion object {
        /** Paths per readFiles call: the bridge's bound in the JS, kept to bound one call's memory. */
        const val READ_BATCH = 100
    }
}
