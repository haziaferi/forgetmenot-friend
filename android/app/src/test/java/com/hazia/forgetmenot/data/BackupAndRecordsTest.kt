package com.hazia.forgetmenot.data

import com.hazia.forgetmenot.domain.DEFAULT_TAGS
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.domain.Group
import com.hazia.forgetmenot.domain.Keys
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Backup export/import (SPEC §2) and the record helpers (www/js/storage/records.js). */
class BackupAndRecordsTest {
    private lateinit var fs: FakeFolder
    private lateinit var store: CachedStore
    private lateinit var records: Records
    private lateinit var backup: Backup

    @Before fun setUp() {
        fs = FakeFolder()
        store = CachedStore(fs)
        records = Records(store)
        backup = Backup(store, records)
    }

    private fun backupText(vararg entries: Pair<String, String>) =
        JsonObject(entries.associate { (k, v) -> k to json(v) }).toString()

    @Test fun exportWritesEveryRecordAsAJsonStringUnderTheLocalDate() = runBlocking {
        records.saveFriend(Friend(id = "f1", name = "Ann", avatar = "data:image/jpeg;base64,QUJD"))
        store.flushWrites()
        val now = ZonedDateTime.of(2026, 9, 2, 23, 30, 0, 0, ZoneId.of("Europe/Rome"))
        val r = backup.export(now) as ExportResult.Saved
        assertEquals("forgetmenot-backup-2026-09-02.json", r.fileName)
        val text = fs.disk.getValue("backups/forgetmenot-backup-2026-09-02.json")
        assertTrue(text.startsWith("{\n  \"version\": 1,\n  \""))
        val data = parseJson(text).jsonObject
        val f = parseJson(data.getValue(Keys.friend("f1")).jsonPrimitive.content).jsonObject
        assertEquals("a backup is self-contained", "data:image/jpeg;base64,QUJD", f["avatar"]!!.jsonPrimitive.content)
    }

    @Test fun exportRefusesWithoutAFolder() = runBlocking {
        fs.picked = false
        assertEquals(ExportResult.NoFolder, backup.export())
    }

    @Test fun importAcceptsTheLavenderSkinAndCoercesAnUnknownOne() {
        val ok = backup.parse(backupText(Keys.SETTINGS to """{"skin":"lavender","mode":"dark"}""")) as ImportCheck.Ready
        assertEquals("lavender", ok.data.getValue(Keys.SETTINGS).jsonObject["skin"]!!.jsonPrimitive.content)
        val coerced = backup.parse(backupText(Keys.SETTINGS to """{"skin":"rose","mode":"dim"}""")) as ImportCheck.Ready
        assertEquals("blue", coerced.data.getValue(Keys.SETTINGS).jsonObject["skin"]!!.jsonPrimitive.content)
        assertEquals("light", coerced.data.getValue(Keys.SETTINGS).jsonObject["mode"]!!.jsonPrimitive.content)
    }

    @Test fun importRefusesNewerEmptyCorruptAndUnreadableFiles() {
        assertEquals(ImportCheck.Newer, backup.parse("""{"version":2,"fm_friend_index":"[]"}"""))
        assertEquals(ImportCheck.NotABackup, backup.parse("""{"version":1,"other":"x"}"""))
        assertEquals(ImportCheck.Corrupt, backup.parse(backupText(Keys.FRIEND_INDEX to "[\"../x\"]")))
        assertEquals(ImportCheck.Unreadable, backup.parse("not json"))
    }

    @Test fun importWritesFirstThenPrunesWhatTheBackupLacked() = runBlocking {
        records.saveFriend(Friend(id = "old", name = "Old", avatar = "data:image/jpeg;base64,QUJD"))
        store.flushWrites()
        val effects = RecordingEffects()
        records.effects = effects
        val check = backup.parse(backupText(
            Keys.FRIEND_INDEX to "[\"f1\"]",
            Keys.friend("f1") to """{"id":"f1","name":"New"}""",
        )) as ImportCheck.Ready
        fs.calls.clear()
        backup.apply(check.data)
        store.flushWrites()
        assertEquals(listOf("f1"), records.listFriends().map { it.id })
        assertFalse("friends/old.json" in fs.disk)
        assertFalse("avatars/old.b64" in fs.disk)
        // (Writing f1 with no avatar deletes its sidecar on the way; the prune is the record's delete.)
        val prune = fs.calls.indexOf("delete" to "friends/old.json")
        val lastWrite = fs.calls.indexOfLast { it.first == "write" }
        assertTrue("every write lands before the prune", prune >= 0 && lastWrite < prune)
        assertTrue("push" in effects.calls && "reschedule" in effects.calls)
    }

    @Test fun seedingPlantsTheStarterTagsOnlyWhenThereIsNoTagIndex() {
        records.seedIfEmpty()
        assertEquals(DEFAULT_TAGS.map { it.name }, records.listTags().map { it.name })
        val ids = records.listTags().map { it.id }
        ids.forEach(records::deleteTag)
        records.seedIfEmpty()
        assertEquals("deleting every tag stays deleted", emptyList<String>(), records.listTags())
        Records(CachedStore(FakeFolder()).also { it.blockWrites() }).let { it.seedIfEmpty(); assertTrue(it.listTags().isEmpty()) }
    }

    @Test fun savingStampsAndDeletingAFriendLeavesNoDanglingMembers() {
        val f = records.saveFriend(Friend(id = "f1", name = "Ann"))
        assertTrue(f.updatedAt != null && f.createdAt == f.updatedAt)
        records.saveFriend(Friend(id = "f2", name = "Bo"))
        records.saveGroup(Group(id = "g1", name = "Club", memberIds = listOf("f1", "f2")))
        records.deleteFriend("f1")
        assertNull(records.getFriend("f1"))
        assertEquals(listOf("f2"), records.getGroup("g1")!!.memberIds)
        assertEquals(listOf("f2"), records.listFriends().map { it.id })
    }

    @Test fun resolvingKeepsTheCanonicalSideOnlyIfItHasNotChangedSince() = runBlocking {
        val conflicts = Conflicts(store, records)
        fs.disk["friends/f1.json"] = friend("f1", "name" to "a", "updatedAt" to 100).toString()
        fs.disk["friends/f1.sync-conflict-20261012-101500-X.json"] = friend("f1", "name" to "b", "updatedAt" to 100,
            "notes" to listOf(mapOf("id" to "n9", "text" to "theirs"))).toString()
        val item = conflicts.check().pending.single()
        fs.disk["friends/f1.json"] = friend("f1", "name" to "edited", "updatedAt" to 150).toString()
        assertFalse(conflicts.resolve(item, keepConflicting = false))
        val again = conflicts.check().pending
        assertTrue("now the stamps differ, so the newer edit wins on its own", again.isEmpty())
        fs.disk["friends/f2.json"] = friend("f2", "name" to "a", "updatedAt" to 100).toString()
        fs.disk["friends/f2.sync-conflict-20261012-101500-X.json"] = friend("f2", "name" to "b", "updatedAt" to 100,
            "notes" to listOf(mapOf("id" to "n9", "text" to "theirs"))).toString()
        val item2 = conflicts.check().pending.single()
        assertTrue(conflicts.resolve(item2, keepConflicting = false))
        val kept = parseJson(fs.disk.getValue("friends/f2.json")).jsonObject
        assertEquals("a", kept["name"]!!.jsonPrimitive.content)
        assertTrue("the other side's notes survive", kept.toString().contains("n9"))
        assertFalse("friends/f2.sync-conflict-20261012-101500-X.json" in fs.disk)
    }
}
