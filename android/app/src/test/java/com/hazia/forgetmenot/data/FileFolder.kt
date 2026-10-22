package com.hazia.forgetmenot.data

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A real directory behind the SyncFolder contract, for the round-trip test: what SafFolder does
 * on a device, on the JVM's file system. Writes go through "<name>.tmp" and a rename, as
 * replaceAtomically() does, and a delete takes the .tmp/.bak leftovers with it.
 */
class FileFolder(private val root: File) : FolderFs {
    private fun file(path: String): File {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { it == "." || it == ".." }) throw FolderException(FolderError.MISSING_PATH)
        return File(root, parts.joinToString(File.separator))
    }

    override fun status() = FolderStatus(root.isDirectory && root.canWrite(), root.name)

    override fun writeFile(path: String, data: String) {
        val target = file(path)
        try {
            target.parentFile?.mkdirs()
            val tmp = File(target.path + ".tmp")
            tmp.writeBytes(data.toByteArray(Charsets.UTF_8))
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            throw FolderException(FolderError.WRITE_FAILED, "Could not write $path", e)
        }
    }

    override fun readFile(path: String): String {
        val f = file(path)
        if (!f.isFile) throw FolderException(FolderError.NOT_FOUND)
        return try { f.readBytes().toString(Charsets.UTF_8) } catch (e: IOException) {
            throw FolderException(FolderError.READ_FAILED, "Could not read $path", e)
        }
    }

    override fun readFiles(paths: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (p in paths) try { out[p] = readFile(p) } catch (e: FolderException) { /* omitted */ }
        return out
    }

    override fun deleteFile(path: String) {
        val f = file(path)
        for (suffix in listOf("", ".tmp", ".bak")) File(f.path + suffix).delete()
    }

    override fun readdir(path: String): List<String> {
        val parts = path.split('/').filter { it.isNotEmpty() }
        val dir = if (parts.isEmpty()) root else file(path)
        return dir.list()?.toList() ?: emptyList()
    }
}
