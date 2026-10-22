package com.hazia.forgetmenot.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// The storage half of www/js/main.js init() and refreshFromDisk(), pickSyncFolder() and
// runSyncConflictScan() (SPEC §2 "Loading", §3 "When a scan runs"). The toasts are the UI's;
// these return what happened.

/**
 * [readOnly]: the folder could not be read (I6); show "Couldn't read your sync folder — changes
 * won't be saved until the app restarts". The cold-start conflict scan runs after this, off the first render
 * ([SyncSession.runScan]; its outcome arrives on [SyncSession.lastScan]).
 */
data class StartResult(val readOnly: Boolean)

sealed interface RefreshResult {
    /** Read-only session: pull-to-refresh stands down (I6). */
    data object Skipped : RefreshResult
    /** "Couldn't read the sync folder — try again in a moment". */
    data object Failed : RefreshResult
    data class Done(val scan: ScanResult?) : RefreshResult
}

sealed interface PickResult {
    /** "No folder chosen": cancelled, or the provider would not grant lasting access. */
    data object NotChosen : PickResult
    /** "Sync folder set: <name>". */
    data class Adopted(val name: String?) : PickResult
    /**
     * [readOnly] true: "Couldn't read your data — changes won't be saved until the app
     * restarts"; false: "Couldn't read that folder — nothing was written to it".
     */
    data class Failed(val readOnly: Boolean) : PickResult
}

class SyncSession(
    val store: CachedStore,
    val records: Records,
    val conflicts: Conflicts,
) {
    private val _pending = MutableStateFlow<List<PendingConflict>>(emptyList())
    /** Record conflicts waiting for the user: the home banner, the Settings row, the resolution sheet. */
    val pendingConflicts: StateFlow<List<PendingConflict>> = _pending.asStateFlow()

    private val _lastScan = MutableStateFlow<ScanResult?>(null)
    /** The latest scan's outcome, for the "N sync conflicts resolved automatically" toast. */
    val lastScan: StateFlow<ScanResult?> = _lastScan.asStateFlow()

    /**
     * Cold start, awaited before the first render. A failed load makes the session read-only;
     * then nothing below the seed runs, and seeding itself stands down. The conflict scan is not
     * part of it: as in the JS init(), it is real I/O that the first frame does not wait for,
     * so the caller runs [runScan] afterwards unless the session is read-only.
     */
    suspend fun start(): StartResult {
        val loadFailed = try { store.load(); false } catch (e: CancellationException) { throw e } catch (e: Exception) {
            logError("could not load the sync folder", e)
            store.blockWrites()
            true
        }
        records.seedIfEmpty()
        if (loadFailed) return StartResult(readOnly = true)
        records.pushWidgets()
        records.rescheduleReminders()
        return StartResult(readOnly = false)
    }

    /** Pull-to-refresh: waits for in-flight writes, reloads, re-pushes, then scans. */
    suspend fun refreshFromDisk(): RefreshResult {
        if (store.writesBlocked) return RefreshResult.Skipped
        store.flushWrites()
        try { store.load() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            logError("refresh failed", e)
            return RefreshResult.Failed
        }
        records.pushWidgets()
        records.rescheduleReminders()
        return RefreshResult.Done(runScan())
    }

    /** Cold start, refresh and a stale resolution all report through this one place. */
    suspend fun runScan(): ScanResult? = try {
        conflicts.check().also {
            _pending.value = it.pending
            _lastScan.value = it
            records.applyTouched(it.touched)
        }
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        logError("sync conflict scan failed", e)
        null
    }

    /**
     * False when the record changed since the scan: toast "This entry changed since the conflict
     * was found — resolve it again to be safe"; the list is already rescanned.
     */
    suspend fun resolve(item: PendingConflict, keepConflicting: Boolean): Boolean {
        val ok = conflicts.resolve(item, keepConflicting)
        if (ok) _pending.update { list -> list.filter { it != item } } else runScan()
        return ok
    }

    /**
     * After the system picker: takes the grant ([grant] is [SafFolder.takeGrant]; it throws when
     * the provider refuses one), then adopts the folder. When adoption fails the grant is
     * reverted, so the app goes back to the folder it used before: left on the new one with
     * the old folder's cache, the next edit would write that cache's index over the new
     * folder's. Widgets and reminders keep what they had, rather than take a half-loaded cache.
     */
    suspend fun adoptPick(grant: () -> PendingGrant): PickResult {
        val pending = try { grant() } catch (e: FolderException) { return PickResult.NotChosen }
        try {
            records.adoptPickedFolder()
        } catch (e: Exception) {
            pending.revert()
            if (e is CancellationException) throw e
            logError("could not adopt the new sync folder", e)
            return PickResult.Failed(readOnly = store.writesBlocked)
        }
        pending.keep()
        records.pushWidgets()
        records.rescheduleReminders()
        return PickResult.Adopted(pending.status.name)
    }
}
