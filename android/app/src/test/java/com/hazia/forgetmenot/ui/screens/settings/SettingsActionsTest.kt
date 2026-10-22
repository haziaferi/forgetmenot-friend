package com.hazia.forgetmenot.ui.screens.settings

import com.hazia.forgetmenot.data.FolderError
import com.hazia.forgetmenot.data.FolderException
import com.hazia.forgetmenot.data.FolderStatus
import com.hazia.forgetmenot.data.PendingGrant
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.domain.Settings
import com.hazia.forgetmenot.ui.sheets.AskState
import com.hazia.forgetmenot.ui.sheets.TestEnv
import com.hazia.forgetmenot.ui.sheets.toastText
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The toasts of backup export/import and the folder pick (www/js/features/backup.js, pickSyncFolder()). */
class SettingsActionsTest {
    private val env = TestEnv()
    private val actions = SettingsActions(env.records, env.session, env.backup)
    private val ask = AskState()

    /** A real backup's text: Bo, green/dark, written by another device's export. */
    private fun backupText(): String = runBlocking {
        val other = TestEnv()
        // No cadenceDays: it must be left out on disk, not written as null, or the import refuses it.
        other.records.saveFriend(Friend(id = "bo", name = "Bo"))
        other.records.saveSettings(Settings(skin = "green", mode = "dark"))
        other.backup.export()
        other.store.flushWrites()
        other.fs.disk.entries.single { it.key.startsWith("backups/") }.value
    }

    /** Runs an import, answering the "Replace this device's data?" question with [yes]. */
    private fun import(text: String?, yes: Boolean): Boolean = runBlocking {
        val job = async { actions.importText(text, ask) }
        repeat(100) { if (ask.request == null && !job.isCompleted) yield() }
        ask.request?.let {
            assertEquals("Replace this device’s data?", it.title)
            assertEquals("Replace", it.confirmLabel)
            ask.answer(yes)
        }
        job.await()
    }

    @Test fun importRefusesWhatItCannotUse() {
        assertFalse(import(null, yes = true)); assertEquals("Could not read that file", toastText())
        assertFalse(import("not json", yes = true)); assertEquals("Could not read that file", toastText())
        assertFalse(import("""{"version":99,"fm_settings":"{}"}""", yes = true))
        assertEquals("This backup is from a newer forgetmenot — update the app first", toastText())
        assertFalse(import("""{"version":1}""", yes = true))
        assertEquals("That file doesn’t look like a forgetmenot backup", toastText())
        assertFalse(import("""{"version":1,"fm_friend_index":"{not json"}""", yes = true))
        assertEquals("That backup file looks corrupted or tampered with — nothing was imported", toastText())
    }

    @Test fun importAsksAndADeclineChangesNothing() {
        env.records.saveFriend(Friend(id = "ann", name = "Ann"))
        assertFalse(import(backupText(), yes = false))
        assertEquals(listOf("Ann"), env.records.listFriends().map { it.name })
    }

    @Test fun anAcceptedImportReplacesTheDataAndGoesHome() {
        env.records.saveFriend(Friend(id = "ann", name = "Ann"))
        val ok = import(backupText(), yes = true)
        assertEquals("Backup imported", toastText())
        assertTrue(ok)
        assertEquals(listOf("Bo"), env.records.listFriends().map { it.name })
        assertEquals("green", env.records.getSettings().skin)
        assertTrue(env.reschedules > 0 && env.pushes > 0)
    }

    @Test fun exportWithoutAFolderSaysWhereToPickOne() = runBlocking {
        env.fs.picked = false
        actions.export()
        assertEquals("Choose a sync folder first (in Settings), then try exporting again", toastText())
    }

    // I6: the cache may be half-loaded. Export would replace that day's good backup with it;
    // import would replace only the cache, and say "Backup imported" for data lost on restart.
    @Test fun aReadOnlySessionNeitherExportsNorImports() = runBlocking {
        val text = backupText()
        env.records.saveFriend(Friend(id = "ann", name = "Ann"))
        env.store.flushWrites()
        env.store.blockWrites()
        val before = env.fs.calls.size
        actions.export()
        assertEquals("Your sync folder couldn’t be read — restart the app, then try again", toastText())
        assertFalse(import(text, yes = true))
        assertEquals("Your sync folder couldn’t be read — restart the app, then try again", toastText())
        assertEquals(listOf("Ann"), env.records.listFriends().map { it.name })
        env.store.flushWrites()
        assertEquals(before, env.fs.calls.size)
    }

    @Test fun aCancelledPickChoosesNothing() = runBlocking {
        actions.adoptPick { throw FolderException(FolderError.PICKER_CANCELLED) }
        assertEquals("No folder chosen", toastText())
    }

    /** A grant that counts what adoption did with it. */
    private class Grant(name: String?) {
        var kept = 0; var reverted = 0
        val pending = PendingGrant(FolderStatus(true, name), keep = { kept++ }, revert = { reverted++ })
    }

    @Test fun aPickedFolderIsAdoptedNamedAndKept() = runBlocking {
        val g = Grant("Sync")
        actions.adoptPick { g.pending }
        assertEquals("Sync folder set: Sync", toastText())
        assertEquals(1 to 0, g.kept to g.reverted)
        actions.adoptPick { Grant(null).pending }
        assertEquals("Sync folder set", toastText())
    }

    // Kept, the grant would leave the app on the unreadable folder with the old folder's cache,
    // and the next edit would write that cache's friend_index.json over the new folder's.
    @Test fun aFolderThatCannotBeReadIsReportedNothingWrittenAndTheGrantReverted() = runBlocking {
        env.fs.readFileFaults["friend_index.json"] = FolderError.READ_FAILED
        val g = Grant("Broken")
        actions.adoptPick { g.pending }
        assertEquals("Couldn’t read that folder — nothing was written to it", toastText())
        assertNull(env.fs.disk["friend_index.json"])
        assertEquals(0 to 1, g.kept to g.reverted)
    }

    @Test fun aFolderWhoseLoadFailsIsRevertedAndTheSessionGoesReadOnly() = runBlocking {
        // The folder holds data, but its tag index is truncated and tags/ cannot be listed.
        env.fs.disk["friend_index.json"] = """["ann"]"""
        env.fs.disk["tag_index.json"] = "[\"t"
        env.fs.readdirFaults["tags"] = FolderError.READ_FAILED
        val g = Grant("Half")
        actions.adoptPick { g.pending }
        assertEquals("Couldn’t read your data — changes won’t be saved until the app restarts", toastText())
        assertTrue(env.store.writesBlocked)
        assertEquals(0 to 1, g.kept to g.reverted)
    }
}
