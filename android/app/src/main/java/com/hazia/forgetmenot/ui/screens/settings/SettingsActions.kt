package com.hazia.forgetmenot.ui.screens.settings

import com.hazia.forgetmenot.data.Backup
import com.hazia.forgetmenot.data.ExportResult
import com.hazia.forgetmenot.data.ImportCheck
import com.hazia.forgetmenot.data.PendingGrant
import com.hazia.forgetmenot.data.PickResult
import com.hazia.forgetmenot.data.Records
import com.hazia.forgetmenot.data.SyncSession
import com.hazia.forgetmenot.ui.sheets.AskState
import com.hazia.forgetmenot.ui.sheets.Toaster
import kotlinx.coroutines.CancellationException

/** Export and import in a read-only session (I6): neither would reach the folder. */
private const val READ_ONLY_BACKUP = "Your sync folder couldn’t be read — restart the app, then try again"

/**
 * What the Settings rows do, apart from drawing: the two switches, backup export and import, and
 * adopting a picked sync folder. Kept out of the composable so each toast path is testable
 * without the system pickers. Ports www/js/main.js's settings wiring, www/js/features/backup.js
 * and pickSyncFolder() (www/js/storage/store.js).
 */
class SettingsActions(
    private val records: Records,
    private val session: SyncSession,
    private val backup: Backup,
) {
    /** Quiet mode tears down or rebuilds every pending alarm (gated on I6 by Records). */
    fun toggleQuiet() {
        val s = records.getSettings()
        records.saveSettings(s.copy(quietMode = !s.quietMode))
        records.rescheduleReminders()
    }

    fun toggleSmallHome() {
        val s = records.getSettings()
        records.saveSettings(s.copy(smallHome = !s.smallHome))
    }

    suspend fun export() {
        Toaster.show(
            when (val r = backup.export()) {
                is ExportResult.Saved -> "Backup saved to ${r.folderName ?: "your sync folder"}/backups/${r.fileName}"
                ExportResult.NoFolder -> "Choose a sync folder first (in Settings), then try exporting again"
                ExportResult.Failed -> "Backup failed — could not write to the sync folder"
                ExportResult.ReadOnly -> READ_ONLY_BACKUP
            },
        )
    }

    /**
     * Checks a picked backup's [text] (null: the file could not be read), asks before replacing
     * anything, and applies it. True when it was imported: the caller goes home.
     */
    suspend fun importText(text: String?, ask: AskState): Boolean {
        val check = if (text == null) ImportCheck.Unreadable else backup.parse(text)
        when (check) {
            ImportCheck.Newer -> Toaster.show("This backup is from a newer forgetmenot — update the app first")
            ImportCheck.NotABackup -> Toaster.show("That file doesn’t look like a forgetmenot backup")
            ImportCheck.Corrupt -> Toaster.show("That backup file looks corrupted or tampered with — nothing was imported")
            ImportCheck.Unreadable -> Toaster.show("Could not read that file")
            is ImportCheck.Ready -> {
                // Asked before the question: a read-only session would replace only the cache.
                if (records.store.writesBlocked) { Toaster.show(READ_ONLY_BACKUP); return false }
                if (!ask.confirm("Replace this device’s data?", "Everything here is replaced by the backup. This can’t be undone.", "Replace")) return false
                try { backup.apply(check.data) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    Toaster.show("Could not read that file")
                    return false
                }
                Toaster.show("Backup imported")
                return true
            }
        }
        return false
    }

    /** After the system folder picker: [grant] takes the lasting grant (SafFolder.takeGrant). */
    suspend fun adoptPick(grant: () -> PendingGrant) {
        Toaster.show(
            when (val r = session.adoptPick(grant)) {
                PickResult.NotChosen -> "No folder chosen"
                is PickResult.Adopted -> "Sync folder set" + (r.name?.let { ": $it" } ?: "")
                is PickResult.Failed -> if (r.readOnly) "Couldn’t read your data — changes won’t be saved until the app restarts"
                else "Couldn’t read that folder — nothing was written to it"
            },
        )
    }
}
