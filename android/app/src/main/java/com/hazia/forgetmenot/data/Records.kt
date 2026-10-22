package com.hazia.forgetmenot.data

import com.hazia.forgetmenot.domain.DEFAULT_TAGS
import com.hazia.forgetmenot.domain.DomainJson
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.domain.Group
import com.hazia.forgetmenot.domain.Keys
import com.hazia.forgetmenot.domain.Settings
import com.hazia.forgetmenot.domain.Tag
import com.hazia.forgetmenot.domain.uid
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The surfaces derived from the records that the storage layer cannot recompute itself: the
 * widgets (P6) and the scheduled reminders (P3). They set [Records.effects]. Both stand down in
 * a read-only session (I6): the widgets keep the last good summary, and the schedule, which every
 * pass cancels and rebuilds, keeps the reminders of friends that did not load.
 */
interface DataEffects {
    fun pushWidgets()
    fun rescheduleReminders()

    object None : DataEffects {
        override fun pushWidgets() {}
        override fun rescheduleReminders() {}
    }
}

class ReadOnlySessionException(message: String) : IllegalStateException(message)

/**
 * Port of www/js/storage/records.js plus adoptPickedFolder() from store.js: typed access to the
 * cache. Reads decode the cached JSON into the domain models; saves encode the model over the
 * cached JSON, so fields the models do not know (from a newer build) are kept, in their places.
 * Call from one thread (the main thread in the app), as the JS ran on one.
 */
class Records(
    val store: CachedStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile var effects: DataEffects = DataEffects.None

    // ── derived surfaces, gated on I6 ────────────────────────────────────────

    fun pushWidgets() { if (!store.writesBlocked) effects.pushWidgets() }
    fun rescheduleReminders() { if (!store.writesBlocked) effects.rescheduleReminders() }

    /** A friend feeds the widgets and the reminders, a group only the reminders, a tag neither. */
    fun applyTouched(kinds: Set<RecordKind>) {
        if (RecordKind.FRIEND in kinds) { pushWidgets(); rescheduleReminders() }
        else if (RecordKind.GROUP in kinds) rescheduleReminders()
    }

    // ── decoding, memoised per cached value ──────────────────────────────────

    private val memo = ConcurrentHashMap<String, Pair<JsonElement, Any>>()

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> decode(key: String, serializer: KSerializer<T>): T? {
        val e = store.read(key) as? JsonObject ?: return null
        memo[key]?.let { (src, v) -> if (src === e) return v as T }
        return try {
            DomainJson.decodeFromJsonElement(serializer, e).also { memo[key] = e to it }
        } catch (ex: Exception) {
            logError("unreadable record $key", ex) // e.g. a non-numeric updatedAt from a foreign writer
            null
        }
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T): JsonObject =
        DomainJson.encodeToJsonElement(serializer, value).jsonObject

    /** [model]'s fields over [existing]'s: existing keys keep their places, unknown ones stay, new ones go last. */
    private fun mergeOver(existing: JsonElement?, model: JsonObject): JsonObject =
        if (existing is JsonObject) JsonObject(LinkedHashMap<String, JsonElement>(existing).apply { putAll(model) }) else model

    private fun truthy(e: JsonElement?) = e != null && e !is JsonNull && e.stamp() != 0.0

    // ── indexes ──────────────────────────────────────────────────────────────

    private fun addToIndex(indexKey: String, id: String) {
        val ids = store.read(indexKey).asArray()
        if (ids.none { it.str() == id }) store.write(indexKey, JsonArray(ids + JsonPrimitive(id)))
    }

    private fun removeFromIndex(indexKey: String, id: String) {
        store.write(indexKey, JsonArray(store.read(indexKey).asArray().filter { it.str() != id }))
    }

    /** Stamps updatedAt (and createdAt, when asked and missing), writes, and indexes the record. */
    private fun saveJson(prefix: String, indexKey: String, record: JsonObject, stampCreated: Boolean): JsonObject {
        val id = requireNotNull(record["id"].str()) { "record without an id" }
        val now = JsonPrimitive(clock())
        val out = LinkedHashMap<String, JsonElement>(record)
        out["updatedAt"] = now
        if (stampCreated && !truthy(record["createdAt"])) out["createdAt"] = now // first-nudge anchor for a never-logged friend
        val saved = JsonObject(out)
        store.write(prefix + id, saved)
        addToIndex(indexKey, id)
        return saved
    }

    // ── settings ─────────────────────────────────────────────────────────────

    /** The stored settings over the defaults `{ skin: 'blue', mode: 'light', quietMode: false, smallHome: false }`. */
    fun getSettings(): Settings = decode(Keys.SETTINGS, Settings.serializer()) ?: Settings()

    fun saveSettings(s: Settings) = store.write(Keys.SETTINGS, mergeOver(store.read(Keys.SETTINGS), encode(Settings.serializer(), s)))

    // ── friends ──────────────────────────────────────────────────────────────

    fun listFriends(): List<Friend> = store.ids(Keys.FRIEND_INDEX).mapNotNull(::getFriend)
    fun getFriend(id: String): Friend? = decode(Keys.friend(id), Friend.serializer())

    /** Archived friends stay in storage but drop out of every list, reminder and widget. */
    fun activeFriends() = listFriends().filter { !it.archived }
    fun archivedFriends() = listFriends().filter { it.archived }

    /** Returns the friend as saved, stamped. Bulk callers pass [skipSync] and sync once after their loop. */
    fun saveFriend(f: Friend, skipSync: Boolean = false): Friend {
        saveFriendJson(mergeOver(store.read(Keys.friend(f.id)), encode(Friend.serializer(), f)), skipSync)
        return getFriend(f.id) ?: f
    }

    internal fun saveFriendJson(record: JsonObject, skipSync: Boolean) {
        saveJson(Keys.FRIEND_PREFIX, Keys.FRIEND_INDEX, record, stampCreated = true)
        if (!skipSync) { pushWidgets(); rescheduleReminders() }
    }

    fun deleteFriend(id: String) {
        store.remove(Keys.friend(id))
        removeFromIndex(Keys.FRIEND_INDEX, id)
        for (gid in store.ids(Keys.GROUP_INDEX)) {
            val g = store.read(Keys.group(gid)) as? JsonObject ?: continue
            val members = g["memberIds"].asArray()
            if (members.any { it.str() == id }) {
                // The single reschedule below covers all of them.
                saveGroupJson(JsonObject(g + ("memberIds" to JsonArray(members.filter { it.str() != id }))), skipSync = true)
            }
        }
        pushWidgets()
        rescheduleReminders()
    }

    // ── tags ─────────────────────────────────────────────────────────────────

    fun listTags(): List<Tag> = store.ids(Keys.TAG_INDEX).mapNotNull(::getTag)
    fun getTag(id: String): Tag? = decode(Keys.tag(id), Tag.serializer())

    fun saveTag(t: Tag): Tag {
        saveTagJson(mergeOver(store.read(Keys.tag(t.id)), encode(Tag.serializer(), t)))
        return getTag(t.id) ?: t
    }

    private fun saveTagJson(record: JsonObject) { saveJson(Keys.TAG_PREFIX, Keys.TAG_INDEX, record, stampCreated = false) }

    /** Strips the tag from every friend; one widget push and reschedule, not one per friend. */
    fun deleteTag(id: String) {
        store.remove(Keys.tag(id))
        removeFromIndex(Keys.TAG_INDEX, id)
        var touched = false
        for (fid in store.ids(Keys.FRIEND_INDEX)) {
            val f = store.read(Keys.friend(fid)) as? JsonObject ?: continue
            val tags = f["tags"].asArray()
            if (tags.any { it.str() == id }) {
                saveFriendJson(JsonObject(f + ("tags" to JsonArray(tags.filter { it.str() != id }))), skipSync = true)
                touched = true
            }
        }
        if (touched) { pushWidgets(); rescheduleReminders() }
    }

    // ── groups ───────────────────────────────────────────────────────────────

    fun listGroups(): List<Group> = store.ids(Keys.GROUP_INDEX).mapNotNull(::getGroup)
    fun getGroup(id: String): Group? = decode(Keys.group(id), Group.serializer())

    fun saveGroup(g: Group, skipSync: Boolean = false): Group {
        saveGroupJson(mergeOver(store.read(Keys.group(g.id)), encode(Group.serializer(), g)), skipSync)
        return getGroup(g.id) ?: g
    }

    internal fun saveGroupJson(record: JsonObject, skipSync: Boolean) {
        saveJson(Keys.GROUP_PREFIX, Keys.GROUP_INDEX, record, stampCreated = false)
        if (!skipSync) rescheduleReminders()
    }

    /** Also clears any reminders scheduled for the deleted group's events. */
    fun deleteGroup(id: String) {
        store.remove(Keys.group(id))
        removeFromIndex(Keys.GROUP_INDEX, id)
        rescheduleReminders()
    }

    // ── seeding ──────────────────────────────────────────────────────────────

    private val _seededTagIds = LinkedHashSet<String>()
    /** Starter tags planted this process, which a folder with its own tags does not take. */
    val seededTagIds: Set<String> get() = _seededTagIds

    /**
     * Plants the starter tags on a first run. The guard is "no tag index at all", not "empty
     * index", so deleting every tag on purpose stays deleted. The friend index is never seeded:
     * writing [] would replace a real index whose read had failed.
     */
    fun seedIfEmpty() {
        if (store.writesBlocked || Keys.TAG_INDEX in store) return
        for (t in DEFAULT_TAGS) {
            val id = uid(clock())
            _seededTagIds += id
            saveTag(Tag(id = id, name = t.name, cadenceDays = t.cadenceDays))
        }
    }

    // ── adopting a newly picked folder ───────────────────────────────────────

    /**
     * A newly picked folder either already holds forgetmenot data (a reinstall, a second device)
     * or does not. Existing data wins and is loaded; anything created in memory before the pick
     * is added to it, except the starter tags, since the folder has its own. An empty folder
     * receives everything currently in memory. Throws if the folder cannot be read; a load that
     * fails partway leaves the session read-only.
     */
    suspend fun adoptPickedFolder() {
        store.flushWrites()
        val hasData = store.readFileStrict("friend_index.json") != null || store.readFileStrict("tag_index.json") != null
        if (!hasData) {
            // A read-only session's cache is partial; copying it would seed the new folder with gaps.
            if (store.writesBlocked) throw ReadOnlySessionException("read-only session: not copying a partial cache")
            for ((k, v) in store.snapshot()) store.persistKey(k, v)
            return
        }
        val before = store.snapshot()
        store.clear()
        store.unblockWrites()
        try { store.load() } catch (e: Exception) { store.blockWrites(); throw e }
        seedIfEmpty()
        if (Keys.SETTINGS !in store) before[Keys.SETTINGS]?.let { store.write(Keys.SETTINGS, it) }
        for ((k, v) in before) {
            if (k in store || v !is JsonObject) continue
            when {
                k.startsWith(Keys.FRIEND_PREFIX) -> saveFriendJson(v, skipSync = true)
                k.startsWith(Keys.GROUP_PREFIX) -> saveGroupJson(v, skipSync = true)
                k.startsWith(Keys.TAG_PREFIX) && v["id"].str() !in _seededTagIds -> saveTagJson(v)
            }
        }
    }
}
