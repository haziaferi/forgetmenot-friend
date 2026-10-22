package com.hazia.forgetmenot.data

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory stand-in for the SAF folder, following the SyncFolder contract (SPEC §6) the way
 * tools/storage.test.mjs's fake does: same methods, same error codes, absent paths omitted from
 * readFiles, directories implicit.
 */
class FakeFolder : FolderFs {
    /** path -> text, in insertion order. */
    val disk: MutableMap<String, String> = java.util.Collections.synchronizedMap(LinkedHashMap())
    /** Injected failures: readFile path -> code, readdir path -> code. */
    val readFileFaults = ConcurrentHashMap<String, String>()
    val readdirFaults = ConcurrentHashMap<String, String>()
    @Volatile var picked = true
    /** Every write and delete, as ("write" | "delete", path). */
    val calls: MutableList<Pair<String, String>> = java.util.Collections.synchronizedList(ArrayList())
    /** Runs before each write lands; a test can slow one down. */
    @Volatile var beforeWrite: (String) -> Unit = {}

    fun listing(dir: String): List<String> {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val names = LinkedHashSet<String>()
        synchronized(disk) {
            for (p in disk.keys) if (p.startsWith(prefix)) names += p.removePrefix(prefix).substringBefore('/')
        }
        return names.toList()
    }

    /** The picked folder is still granted but no longer there (moved, deleted, storage unmounted). */
    @Volatile var rootGone = false

    private fun requirePicked() {
        if (!picked) throw FolderException(FolderError.NO_FOLDER_SELECTED)
        if (rootGone) throw FolderException(FolderError.FOLDER_UNAVAILABLE)
    }

    override fun status() = FolderStatus(picked && !rootGone, if (picked && !rootGone) "test" else null)

    override fun writeFile(path: String, data: String) {
        calls += "write" to path
        requirePicked()
        beforeWrite(path)
        disk[path] = data
    }

    override fun readFile(path: String): String {
        requirePicked()
        readFileFaults[path]?.let { throw FolderException(it) }
        return disk[path] ?: throw FolderException(FolderError.NOT_FOUND)
    }

    override fun readFiles(paths: List<String>): Map<String, String> {
        requirePicked()
        val out = LinkedHashMap<String, String>()
        for (p in paths) { val v = disk[p]; if (v != null && !readFileFaults.containsKey(p)) out[p] = v }
        return out
    }

    override fun deleteFile(path: String) {
        calls += "delete" to path
        requirePicked()
        disk.remove(path)
    }

    override fun readdir(path: String): List<String> {
        requirePicked()
        readdirFaults[path]?.let { throw FolderException(it) }
        return listing(path)
    }
}
