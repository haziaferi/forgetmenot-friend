package com.hazia.forgetmenot.data

import com.hazia.forgetmenot.domain.Friend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SPEC §2 "Loading, and what counts as absent": a folder that was picked and is still granted,
 * but is no longer there, is a failed load (read-only, I6), not an empty one. Read as empty, the
 * first edit after the folder came back (storage remounted, a sync app recreating it) would
 * write a partial index over the real one.
 */
class MissingFolderTest {
    private lateinit var fs: FakeFolder
    private lateinit var store: CachedStore
    private lateinit var records: Records
    private lateinit var session: SyncSession
    private var pushes = 0
    private var reschedules = 0

    @Before fun setUp() {
        fs = FakeFolder()
        fs.disk["friend_index.json"] = """["ana","bo"]"""
        fs.disk["friends/ana.json"] = """{"id":"ana","name":"Ana","contacts":[],"updatedAt":1}"""
        fs.disk["friends/bo.json"] = """{"id":"bo","name":"Bo","contacts":[],"updatedAt":1}"""
        fs.disk["tag_index.json"] = """["t1"]"""
        fs.disk["tags/t1.json"] = """{"id":"t1","name":"Family"}"""
        store = CachedStore(fs)
        records = Records(store)
        session = SyncSession(store, records, Conflicts(store, records))
        records.effects = object : DataEffects {
            override fun pushWidgets() { pushes++ }
            override fun rescheduleReminders() { reschedules++ }
        }
    }

    private fun writes() = fs.calls.filter { it.first == "write" || it.first == "delete" }

    @Test fun aVanishedFolderMakesTheSessionReadOnly() = runBlocking {
        fs.rootGone = true
        assertTrue(session.start().readOnly)
        assertTrue(store.writesBlocked)
        assertEquals(0, pushes)       // the widgets keep their last good summary
        assertEquals(0, reschedules)  // the alarms stay as they were
        store.flushWrites()
        assertEquals(emptyList<Pair<String, String>>(), writes())
    }

    @Test fun anEditAfterTheFolderComesBackDoesNotOverwriteIt() = runBlocking {
        val before = fs.disk.toMap()
        fs.rootGone = true
        session.start()
        fs.rootGone = false // remounted mid-session
        records.saveFriend(Friend(id = "cy", name = "Cy"))
        records.saveSettings(records.getSettings().copy(skin = "green"))
        store.flushWrites()
        assertEquals(before, fs.disk.toMap())
        assertEquals(emptyList<Pair<String, String>>(), writes())
    }

    @Test fun noFolderPickedYetIsStillAbsentNotFailed() = runBlocking {
        fs.picked = false
        assertFalse(session.start().readOnly)
        assertFalse(store.writesBlocked)
        assertNull(records.getFriend("ana"))
    }

    @Test fun aPresentFolderLoadsAsBefore() = runBlocking {
        assertFalse(session.start().readOnly)
        assertEquals(listOf("Ana", "Bo"), records.listFriends().map { it.name })
        assertEquals(1, pushes)
    }

    @Test fun refreshWhileTheFolderIsGoneKeepsTheCache() = runBlocking {
        session.start()
        fs.rootGone = true
        assertEquals(RefreshResult.Failed, session.refreshFromDisk())
        assertEquals(listOf("Ana", "Bo"), records.listFriends().map { it.name })
    }
}
