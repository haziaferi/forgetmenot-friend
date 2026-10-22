package com.hazia.forgetmenot.data

import java.io.IOException

// The sync folder's file surface, the web build's SyncFolder plugin contract minus its JS
// bridge. Paths are "/"-separated and relative to the folder the user picked.
// Every method blocks; CachedStore runs them one at a time on its own serial dispatcher.
// Every method except status() also throws FOLDER_UNAVAILABLE when the picked folder is gone.

/** The plugin's reject codes, kept so the absent/failed distinction (SPEC §2) reads the same. */
object FolderError {
    const val NO_FOLDER_SELECTED = "NO_FOLDER_SELECTED"
    /**
     * A folder was picked and its grant is still held, but it is not there (moved, deleted,
     * storage unmounted) or can no longer be written. Not absent: a load that meets it fails,
     * and the session goes read-only (SPEC §2).
     */
    const val FOLDER_UNAVAILABLE = "FOLDER_UNAVAILABLE"
    const val NOT_FOUND = "NOT_FOUND"
    const val READ_FAILED = "READ_FAILED"
    const val WRITE_FAILED = "WRITE_FAILED"
    const val MISSING_PATH = "MISSING_PATH"
    const val PICKER_CANCELLED = "PICKER_CANCELLED"
    const val PERMISSION_NOT_PERSISTED = "PERMISSION_NOT_PERSISTED"
}

class FolderException(val code: String, message: String = code, cause: Throwable? = null) : IOException(message, cause)

data class FolderStatus(val granted: Boolean, val name: String?)

/**
 * A picked folder taken but not yet kept: the app already reads and writes it, so adoption can
 * try it. [keep] once adoption worked (releases every other folder's grant); [revert] when it
 * failed (back to the folder used before, so later edits never land on the one that failed).
 */
class PendingGrant(val status: FolderStatus, val keep: () -> Unit = {}, val revert: () -> Unit = {})

interface FolderFs {
    /** Always answers; `granted` is false with no folder, one whose grant has died, or one that is gone. */
    fun status(): FolderStatus

    /** Creates missing directories; replaces the file atomically. Throws NO_FOLDER_SELECTED or WRITE_FAILED. */
    fun writeFile(path: String, data: String)

    /** Throws NO_FOLDER_SELECTED, NOT_FOUND or READ_FAILED. */
    fun readFile(path: String): String

    /** Many files, each directory listed once. Absent and unreadable paths are omitted. Throws NO_FOLDER_SELECTED. */
    fun readFiles(paths: List<String>): Map<String, String>

    /** Deleting something already gone is not an error. Throws NO_FOLDER_SELECTED. */
    fun deleteFile(path: String)

    /** Bare names; a missing directory is empty. Throws NO_FOLDER_SELECTED. */
    fun readdir(path: String = ""): List<String>
}
