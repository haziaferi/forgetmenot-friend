package com.hazia.forgetmenot.data

import com.hazia.forgetmenot.domain.Keys
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Kotlin values to JSON, for building records in tests. */
fun json(v: Any?): JsonElement = when (v) {
    null -> kotlinx.serialization.json.JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is List<*> -> JsonArray(v.map(::json))
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k as String to json(x) })
    else -> error("no JSON for $v")
}

fun obj(vararg pairs: Pair<String, Any?>): JsonObject = json(linkedMapOf(*pairs)) as JsonObject

fun friend(id: String, vararg extra: Pair<String, Any?>): JsonObject =
    JsonObject(linkedMapOf<String, JsonElement>("id" to JsonPrimitive(id), "name" to JsonPrimitive("F $id"), "contacts" to JsonArray(emptyList())) +
        extra.associate { (k, v) -> k to json(v) })

/**
 * Port of tools/storage.test.mjs: the storage layer and the conflict scan against an in-memory
 * fake of the sync folder, so load, its failure paths, avatar sidecars, folder adoption and every
 * conflict treatment run without a device. One @Test per JS test(), in the same order.
 */
class StorageTest {
    private lateinit var fs: FakeFolder
    private lateinit var store: CachedStore
    private lateinit var records: Records
    private lateinit var conflicts: Conflicts

    private fun put(path: String, v: Any?) { fs.disk[path] = if (v is String) v else json(v).toString() }
    private fun onDisk(path: String): JsonElement? = fs.disk[path]?.let { parseJson(it) }
    private fun name(e: JsonElement?) = e!!.jsonObject["name"]!!.jsonPrimitive.content
    private fun ids(e: JsonElement?) = e!!.jsonArray.map { it.jsonPrimitive.content }
    private fun cached(key: String) = store.read(key)

    @Before fun setUp() {
        fs = FakeFolder()
        store = CachedStore(fs)
        records = Records(store)
        conflicts = Conflicts(store, records)
    }

    // ── paths and the write queue ────────────────────────────────────────────

    @Test fun keyToPathRecordsGoInTheirDirectoryIndexesAtTheRootBadIdsThrow() {
        assertEquals("friends/abc123.json", StoragePaths.keyToPath(Keys.friend("abc123")))
        assertEquals("friend_index.json", StoragePaths.keyToPath(Keys.FRIEND_INDEX))
        assertEquals("settings.json", StoragePaths.keyToPath(Keys.SETTINGS))
        try { StoragePaths.keyToPath(Keys.friend("../etc")); fail() } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("invalid id"))
        }
    }

    @Test fun twoQuickWritesOfOneKeyLandInOrderEvenWhenTheFirstIsSlower() = runBlocking {
        var first = true
        fs.beforeWrite = { if (first) { first = false; Thread.sleep(30) } }
        store.write(Keys.friend("q1"), friend("q1", "name" to "first"))
        store.write(Keys.friend("q1"), friend("q1", "name" to "second"))
        store.flushWrites()
        assertEquals("second", name(onDisk("friends/q1.json")))
    }

    // ── what counts as absent ────────────────────────────────────────────────

    @Test fun readFileStrictOnlyNotFoundAndNoFolderSelectedAreAbsent() = runBlocking {
        assertNull(store.readFileStrict("friend_index.json"))
        fs.readFileFaults["friend_index.json"] = FolderError.READ_FAILED
        try { store.readFileStrict("friend_index.json"); fail() } catch (e: FolderException) { assertEquals(FolderError.READ_FAILED, e.code) }
        fs.readFileFaults.clear()
        put("friend_index.json", "[\"a\", \"b")
        try { store.readFileStrict("friend_index.json"); fail() } catch (e: SerializationException) { /* truncated */ }
        fs.picked = false
        assertNull(store.readFileStrict("friend_index.json"))
    }

    // ── load ─────────────────────────────────────────────────────────────────

    @Test fun loadIndexesRecordsSettingsAndAvatarSidecarsComeBackIntoMemory() = runBlocking {
        put("friend_index.json", listOf("f1", "f2"))
        put("tag_index.json", listOf("t1"))
        put("group_index.json", listOf("g1"))
        put("settings.json", mapOf("skin" to "rose"))
        put("friends/f1.json", friend("f1", "avatar" to "file:avatars/f1.b64"))
        put("friends/f2.json", friend("f2"))
        put("avatars/f1.b64", "QUJD")
        put("tags/t1.json", mapOf("id" to "t1", "name" to "Close"))
        put("groups/g1.json", mapOf("id" to "g1", "name" to "Club", "memberIds" to emptyList<String>()))
        store.load()
        assertEquals(listOf("f1", "f2"), ids(cached(Keys.FRIEND_INDEX)))
        assertEquals("data:image/jpeg;base64,QUJD", cached(Keys.friend("f1"))!!.jsonObject["avatar"]!!.jsonPrimitive.content)
        assertEquals("F f2", name(cached(Keys.friend("f2"))))
        assertEquals("Close", name(cached(Keys.tag("t1"))))
        assertEquals("Club", name(cached(Keys.group("g1"))))
        assertEquals("rose", cached(Keys.SETTINGS)!!.jsonObject["skin"]!!.jsonPrimitive.content)
    }

    @Test fun loadAnIndexIdWithNoRecordLeavesNoKeyNotANull() = runBlocking {
        put("friend_index.json", listOf("f1", "gone"))
        put("friends/f1.json", friend("f1"))
        store.load()
        assertTrue(Keys.friend("f1") in store)
        assertFalse(Keys.friend("gone") in store)
    }

    @Test fun loadAnAbsentIndexLeavesItsKeyOutSoSeedingCanTellANewInstall() = runBlocking {
        store.load()
        assertFalse(Keys.FRIEND_INDEX in store)
        assertFalse(Keys.TAG_INDEX in store)
    }

    @Test fun loadInvalidIdsInAnIndexAreDropped() = runBlocking {
        put("friend_index.json", listOf("f1", "../x", 42))
        put("friends/f1.json", friend("f1"))
        store.load()
        assertEquals(listOf("f1"), ids(cached(Keys.FRIEND_INDEX)))
    }

    @Test fun loadATruncatedIndexIsRebuiltFromItsDirectoryAndWrittenBack() = runBlocking {
        put("friend_index.json", "[\"f1\", \"f")
        put("friends/f1.json", friend("f1"))
        put("friends/f2.json", friend("f2"))
        put("friends/f2.lost-123.json", friend("f2")) // not a record name
        store.load()
        store.flushWrites()
        assertEquals(listOf("f1", "f2"), ids(cached(Keys.FRIEND_INDEX)).sorted())
        assertEquals(listOf("f1", "f2"), ids(onDisk("friend_index.json")).sorted())
    }

    @Test fun loadAnUnreadableIndexWhoseDirectoryCannotBeListedEitherThrows() = runBlocking {
        fs.readFileFaults["friend_index.json"] = FolderError.READ_FAILED
        fs.readdirFaults["friends"] = FolderError.READ_FAILED
        try { store.load(); fail() } catch (e: FolderException) { /* expected */ }
    }

    @Test fun loadAGroupMissingFromGroupsIsFoundAtItsOldRootLocation() = runBlocking {
        put("fm_group_index.json", listOf("g1"))
        put("fm_group_g1.json", mapOf("id" to "g1", "name" to "Old place"))
        store.load()
        assertEquals("Old place", name(cached(Keys.group("g1"))))
    }

    @Test fun loadAnUnreadableAvatarSidecarReadsAsNoAvatar() = runBlocking {
        put("friend_index.json", listOf("f1"))
        put("friends/f1.json", friend("f1", "avatar" to "file:avatars/f1.b64"))
        store.load()
        assertEquals(kotlinx.serialization.json.JsonNull, cached(Keys.friend("f1"))!!.jsonObject["avatar"])
    }

    // ── persisting ───────────────────────────────────────────────────────────

    @Test fun persistAnAvatarGoesToItsSidecarAndTheRecordKeepsOnlyTheMarker() = runBlocking {
        store.persistKey(Keys.friend("f1"), friend("f1", "avatar" to "data:image/jpeg;base64,QUJD"))
        assertEquals("QUJD", fs.disk["avatars/f1.b64"])
        assertEquals("file:avatars/f1.b64", onDisk("friends/f1.json")!!.jsonObject["avatar"]!!.jsonPrimitive.content)
    }

    @Test fun persistClearingAnAvatarDeletesItsSidecar() = runBlocking {
        put("avatars/f1.b64", "QUJD")
        store.persistKey(Keys.friend("f1"), friend("f1", "avatar" to null))
        assertFalse("avatars/f1.b64" in fs.disk)
    }

    @Test fun persistDeletingAFriendDeletesTheRecordAndItsSidecar() = runBlocking {
        put("friends/f1.json", friend("f1"))
        put("avatars/f1.b64", "QUJD")
        store.deleteKey(Keys.friend("f1"))
        assertFalse("friends/f1.json" in fs.disk)
        assertFalse("avatars/f1.b64" in fs.disk)
    }

    // ── conflict scan ────────────────────────────────────────────────────────

    private fun conflict(id: String) = "friends/$id.sync-conflict-20261012-101500-ABCDEFG.json"

    @Test fun conflictTheNewerConflictCopyWinsAndTheOlderRecordIsKeptAsLost() = runBlocking {
        put("friend_index.json", listOf("f1"))
        put("friends/f1.json", friend("f1", "name" to "old", "updatedAt" to 100))
        put(conflict("f1"), friend("f1", "name" to "new", "updatedAt" to 200))
        store.put(Keys.FRIEND_INDEX, json(listOf("f1")))
        val r = conflicts.check()
        assertEquals(1, r.autoResolved)
        assertEquals(1, r.lostKept)
        assertEquals("new", name(onDisk("friends/f1.json")))
        assertEquals("old", name(onDisk("friends/f1.lost-100.json")))
        assertEquals("new", name(cached(Keys.friend("f1"))))
        assertFalse(conflict("f1") in fs.disk)
        assertTrue(RecordKind.FRIEND in r.touched)
    }

    @Test fun conflictAnOlderConflictCopyLosesAndTheRecordIsUntouched() = runBlocking {
        put("friends/f1.json", friend("f1", "name" to "current", "updatedAt" to 300))
        put(conflict("f1"), friend("f1", "name" to "stale", "updatedAt" to 200))
        val r = conflicts.check()
        assertEquals("current", name(onDisk("friends/f1.json")))
        assertEquals("stale", name(onDisk("friends/f1.lost-200.json")))
        assertFalse(fs.calls.any { it == ("write" to "friends/f1.json") })
        assertEquals(0, r.touched.size)
    }

    @Test fun conflictAFriendKeepsBothSidesReachOutsAndNotesWhicheverCopyWins() = runBlocking {
        put("friends/f1.json", friend("f1", "name" to "current", "updatedAt" to 300,
            "contacts" to listOf(mapOf("id" to "c1", "date" to "x")), "notes" to listOf(mapOf("id" to "n1", "text" to "a"))))
        put(conflict("f1"), friend("f1", "name" to "stale", "updatedAt" to 200,
            "contacts" to listOf(mapOf("id" to "c1", "date" to "x"), mapOf("id" to "c2", "date" to "y"))))
        store.put(Keys.FRIEND_INDEX, json(listOf("f1")))
        val r = conflicts.check()
        val saved = onDisk("friends/f1.json")!!.jsonObject
        assertEquals("current", name(saved))
        assertEquals(listOf("c1", "c2"), saved["contacts"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(listOf("n1"), saved["notes"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(listOf("c1", "c2"), cached(Keys.friend("f1"))!!.jsonObject["contacts"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertTrue(RecordKind.FRIEND in r.touched)
        assertEquals("stale", name(onDisk("friends/f1.lost-200.json")))
    }

    @Test fun conflictANewerCopyThatLacksTheOlderOnesNoteStillEndsUpWithIt() = runBlocking {
        put("friends/f1.json", friend("f1", "updatedAt" to 100, "notes" to listOf(mapOf("id" to "n1", "text" to "only here"))))
        put(conflict("f1"), friend("f1", "name" to "newer", "updatedAt" to 200))
        store.put(Keys.FRIEND_INDEX, json(listOf("f1")))
        conflicts.check()
        val saved = onDisk("friends/f1.json")!!.jsonObject
        assertEquals("newer", name(saved))
        assertEquals(listOf("n1"), saved["notes"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
    }

    @Test fun conflictEqualOrMissingStampsAreLeftForTheUser() = runBlocking {
        put("friends/f1.json", friend("f1", "name" to "a", "updatedAt" to 100))
        put(conflict("f1"), friend("f1", "name" to "b", "updatedAt" to 100))
        put("friends/f2.json", friend("f2", "name" to "a"))
        put(conflict("f2"), friend("f2", "name" to "b", "updatedAt" to 100))
        val r = conflicts.check()
        assertEquals(listOf("f1", "f2"), r.pending.map { it.id }.sorted())
        assertTrue(conflict("f1") in fs.disk && conflict("f2") in fs.disk)
        assertEquals(0, r.autoResolved)
    }

    @Test fun conflictWithNoCurrentRecordTheConflictCopyIsAdoptedAndIndexed() = runBlocking {
        store.put(Keys.FRIEND_INDEX, JsonArray(emptyList()))
        put(conflict("f9"), friend("f9", "updatedAt" to 5))
        conflicts.check()
        store.flushWrites()
        assertEquals("f9", onDisk("friends/f9.json")!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(listOf("f9"), ids(cached(Keys.FRIEND_INDEX)))
        assertEquals(listOf("f9"), ids(onDisk("friend_index.json")))
    }

    @Test fun conflictAnUnreadableCopyOrOneWithAForeignIdIsDeleted() = runBlocking {
        put(conflict("f1"), "{\"broken")
        put("friends/not ours.sync-conflict-20261012-101500-ABCDEFG.json", "{}")
        val r = conflicts.check()
        assertEquals(0, fs.listing("friends").size)
        assertEquals(0, r.pending.size)
    }

    @Test fun conflictIndexCopiesAreMergedAndRecordsTheyBringInAreLoaded() = runBlocking {
        store.put(Keys.FRIEND_INDEX, json(listOf("f1")))
        put("friend_index.json", listOf("f1"))
        put("friend_index.sync-conflict-20261012-101500-ABCDEFG.json", listOf("f2"))
        put("friends/f2.json", friend("f2"))
        val r = conflicts.check()
        assertEquals(listOf("f1", "f2"), ids(onDisk("friend_index.json")))
        assertEquals(listOf("f1", "f2"), ids(cached(Keys.FRIEND_INDEX)))
        assertEquals("F f2", name(cached(Keys.friend("f2"))))
        assertFalse("friend_index.sync-conflict-20261012-101500-ABCDEFG.json" in fs.disk)
        assertTrue(RecordKind.FRIEND in r.touched)
    }

    @Test fun conflictSettingsAndAvatarCopiesAreDroppedWithoutAsking() = runBlocking {
        put("settings.json", mapOf("skin" to "blue"))
        put("settings.sync-conflict-20261012-101500-ABCDEFG.json", mapOf("skin" to "rose"))
        put("avatars/f1.sync-conflict-20261012-101500-ABCDEFG.b64", "QUJD")
        val r = conflicts.check()
        assertEquals(2, r.autoResolved)
        assertEquals("blue", onDisk("settings.json")!!.jsonObject["skin"]!!.jsonPrimitive.content)
        assertEquals(emptyList<String>(), fs.listing("avatars"))
    }

    // ── adopting a picked folder ─────────────────────────────────────────────

    @Test fun adoptAnEmptyFolderReceivesEverythingInMemory() = runBlocking {
        store.put(Keys.FRIEND_INDEX, json(listOf("f1")))
        store.put(Keys.friend("f1"), friend("f1"))
        records.adoptPickedFolder()
        assertEquals(listOf("f1"), ids(onDisk("friend_index.json")))
        assertEquals("f1", onDisk("friends/f1.json")!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test fun adoptAFolderWithDataWinsAndFriendsMadeBeforeThePickAreAddedToIt() = runBlocking {
        fs.picked = false
        records.seedIfEmpty() // the starter tags a first launch plants before any folder exists
        store.flushWrites()
        val starterIds = records.seededTagIds.toList()
        assertTrue(starterIds.isNotEmpty())
        store.put(Keys.FRIEND_INDEX, json(listOf("early")))
        store.put(Keys.friend("early"), friend("early"))
        store.put(Keys.SETTINGS, obj("skin" to "rose"))

        fs.picked = true
        put("friend_index.json", listOf("f1"))
        put("friends/f1.json", friend("f1"))
        put("tag_index.json", listOf("t1"))
        put("tags/t1.json", mapOf("id" to "t1", "name" to "Theirs"))
        records.adoptPickedFolder()
        store.flushWrites()
        assertEquals(listOf("f1", "early"), ids(onDisk("friend_index.json")))
        assertEquals("early", onDisk("friends/early.json")!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("starter tags are not copied over the folder's own", listOf("t1"), ids(onDisk("tag_index.json")))
        assertTrue(starterIds.none { Keys.tag(it) in store })
        assertEquals("the folder had no settings, so memory's carry over",
            "rose", onDisk("settings.json")!!.jsonObject["skin"]!!.jsonPrimitive.content)
    }

    @Test fun adoptALoadThatFailsPartwayLeavesTheSessionReadOnly() = runBlocking {
        put("friend_index.json", listOf("f1"))
        fs.readFileFaults["tag_index.json"] = FolderError.READ_FAILED
        fs.readdirFaults["tags"] = FolderError.READ_FAILED
        try { records.adoptPickedFolder(); fail() } catch (e: FolderException) { /* expected */ }
        assertTrue(store.writesBlocked)
        fs.calls.clear()
        store.persistKey(Keys.friend("f1"), friend("f1"))
        assertEquals("a read-only session writes nothing", 0, fs.calls.size)
    }

    @Test fun adoptAReadOnlySessionRefusesToCopyItsPartialCacheIntoAnEmptyFolder() = runBlocking {
        store.blockWrites() // as a failed start-up leaves it
        store.put(Keys.FRIEND_INDEX, json(listOf("f1")))
        try { records.adoptPickedFolder(); fail() } catch (e: ReadOnlySessionException) {
            assertTrue(e.message!!.contains("read-only"))
        }
        assertEquals(0, fs.disk.size)
    }

    @Test fun aReadOnlySessionPushesNothingToTheWidgetsAndLeavesTheRemindersAlone() = runBlocking {
        val effects = RecordingEffects()
        records.effects = effects
        put("friend_index.json", listOf("f1"))
        put("friends/f1.json", friend("f1"))
        records.adoptPickedFolder()
        effects.calls.clear()
        records.pushWidgets()
        records.rescheduleReminders()
        assertTrue("a normal session reschedules", "reschedule" in effects.calls)
        assertTrue("a normal session pushes", "push" in effects.calls)
        effects.calls.clear()
        store.blockWrites()
        records.pushWidgets()
        records.rescheduleReminders()
        records.saveFriend(records.getFriend("f1")!!.copy(name = "edited"))
        assertEquals(emptyList<String>(), effects.calls)
    }
}

class RecordingEffects : DataEffects {
    val calls = java.util.Collections.synchronizedList(ArrayList<String>())
    override fun pushWidgets() { calls += "push" }
    override fun rescheduleReminders() { calls += "reschedule" }
}
