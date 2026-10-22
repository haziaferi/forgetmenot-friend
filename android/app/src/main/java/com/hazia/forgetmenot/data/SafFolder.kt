package com.hazia.forgetmenot.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

/**
 * The sync folder: a Storage Access Framework tree the user picked (e.g. their Syncthing-fork
 * root), with named-file read/write/delete/list scoped to it. Port of the web build's
 * SyncFolderPlugin.kt (SPEC §3 "The SAF grant"), with the JS bridge removed and
 * the reject codes kept as [FolderException.code]. No storage permission is needed:
 * ACTION_OPEN_DOCUMENT_TREE grants access through the system picker.
 *
 * The grant lives where the web build kept it, prefs file "sync_folder", key "tree_uri"
 * (invariant I1). The app ships as a fresh install, so this costs nothing and is kept.
 *
 * Not thread-safe by design: CachedStore calls it one call at a time, which the .bak recovery
 * in [children] relies on.
 */
class SafFolder(private val context: Context) : FolderFs {

    companion object {
        const val PREFS = "sync_folder"
        const val KEY_TREE_URI = "tree_uri"
        private const val RW = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        private const val TMP = ".tmp"
        private const val BAK = ".bak"
    }

    /**
     * The picker: ACTION_OPEN_DOCUMENT_TREE, as the plugin launched it. Register it with
     * rememberLauncherForActivityResult, launch with `null`, and hand the result to
     * [SyncSession.adoptPick] as `{ takeGrant(uri) }`.
     */
    fun pickContract() = ActivityResultContracts.OpenDocumentTree()

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Null if nothing's chosen yet, or if a previously granted permission was revoked
    // (reinstall, system reclaiming a grant under storage pressure): the user picks again.
    // Re-checked on every call, not cached, since a grant can die mid-session.
    //
    // A folder still granted but gone (moved, deleted, storage unmounted) or no longer writable
    // throws FOLDER_UNAVAILABLE instead. Read as "no folder" it loaded as empty, and the first
    // edit after it came back wrote a partial index over the real one; as a failure the load
    // makes the session read-only (I6, SPEC §2).
    private fun currentRoot(): DocumentFile? {
        val stored = prefs().getString(KEY_TREE_URI, null) ?: return null
        val uri = stored.toUri()
        val stillGranted = context.contentResolver.persistedUriPermissions
            .any { it.uri == uri && it.isWritePermission }
        if (!stillGranted) return null
        val doc = DocumentFile.fromTreeUri(context, uri)
        if (doc != null && doc.isDirectory && doc.canWrite()) return doc
        throw FolderException(FolderError.FOLDER_UNAVAILABLE, "The sync folder is missing or can't be written")
    }

    private fun requireRoot(): DocumentFile =
        currentRoot() ?: throw FolderException(FolderError.NO_FOLDER_SELECTED, "No sync folder selected")

    override fun status(): FolderStatus =
        try { currentRoot() } catch (e: FolderException) { null }.let { FolderStatus(it != null, it?.name) }

    /**
     * Takes the picked tree: a lasting read+write grant, stored as the one folder. The previous
     * folder keeps its grant until [PendingGrant.keep] releases every other tree grant (grants
     * are capped per app); [PendingGrant.revert] stores the previous folder again and lets the
     * new grant go. [uri] null is a cancelled pick. Throws PICKER_CANCELLED, or
     * PERMISSION_NOT_PERSISTED for a provider that shows a tree it will not grant a lasting
     * write on.
     */
    fun takeGrant(uri: Uri?): PendingGrant {
        if (uri == null) throw FolderException(FolderError.PICKER_CANCELLED, "No folder was picked")
        val resolver = context.contentResolver
        try {
            resolver.takePersistableUriPermission(uri, RW)
        } catch (e: SecurityException) {
            throw FolderException(FolderError.PERMISSION_NOT_PERSISTED, "The provider would not grant lasting access to that folder", e)
        }
        val previous = prefs().getString(KEY_TREE_URI, null)
        prefs().edit { putString(KEY_TREE_URI, uri.toString()) }
        return PendingGrant(
            FolderStatus(true, DocumentFile.fromTreeUri(context, uri)?.name),
            keep = { releaseTreeGrants(except = uri) },
            revert = {
                prefs().edit { if (previous == null) remove(KEY_TREE_URI) else putString(KEY_TREE_URI, previous) }
                if (uri.toString() != previous) {
                    try { resolver.releasePersistableUriPermission(uri, RW) } catch (e: SecurityException) { /* already gone */ }
                }
            },
        )
    }

    private fun releaseTreeGrants(except: Uri) {
        val resolver = context.contentResolver
        for (p in resolver.persistedUriPermissions) {
            if (p.uri == except || !DocumentsContract.isTreeUri(p.uri)) continue
            val flags = (if (p.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (p.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            try { resolver.releasePersistableUriPermission(p.uri, flags) } catch (e: SecurityException) { /* already gone */ }
        }
    }

    // The name a SAF provider actually creates is derived from the (mimeType, displayName)
    // pair, not from displayName alone: AOSP's FileUtils.splitFileName keeps the name as
    // given only when its extension already maps to the mime being passed, and otherwise
    // appends the mime's own extension instead. Deriving the mime from the extension keeps
    // "<id>.json" as-is on every provider; .b64 has no registered type, so octet-stream is
    // its match.
    private fun mimeFor(fileName: String) =
        if (fileName.substringAfterLast('.', "") == "json") "application/json"
        else "application/octet-stream"

    private fun segments(path: String) = path.split("/").filter { it.isNotEmpty() }

    private fun readText(uri: Uri): String? =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }

    // A directory's children as name -> document Uri, from one query. DocumentFile.listFiles()
    // plus .name costs a query per child, and findFile() pays that on every lookup. First match
    // wins on a duplicate name, as findFile() did; an unlistable directory is empty, as
    // listFiles() treated it.
    private fun children(dir: Uri): LinkedHashMap<String, Uri> {
        val out = LinkedHashMap<String, Uri>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dir, DocumentsContract.getDocumentId(dir))
        try {
            context.contentResolver.query(
                childrenUri, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    out.putIfAbsent(name, DocumentsContract.buildDocumentUriUsingTree(dir, c.getString(0)))
                }
            }
        } catch (e: Exception) {
            // Not a directory, or the provider failed the listing.
        }
        recoverInterrupted(out)
        return out
    }

    // A "<name>.bak" with no "<name>" beside it is a write that died between moving the old
    // file aside and moving the new one in (see replaceAtomically). The old copy is complete,
    // so it goes back under its own name. CachedStore runs this folder's calls one at a time,
    // so no write of this app is ever mid-swap here.
    private fun recoverInterrupted(listing: LinkedHashMap<String, Uri>) {
        for ((name, uri) in listing.entries.toList()) {
            if (!name.endsWith(BAK)) continue
            val base = name.removeSuffix(BAK)
            if (base.isEmpty() || listing.containsKey(base)) continue
            val restored = rename(uri, base) ?: continue
            listing.remove(name)
            listing[base] = restored
        }
    }

    // Null unless the provider renamed the document to exactly `name`. A provider that cannot
    // rename throws; one that finds the name taken may pick "name (1)", which is a failure too.
    // External storage's document ids are paths, which answers that without another query;
    // any other id is asked for its display name.
    private fun rename(doc: Uri, name: String): Uri? = try {
        DocumentsContract.renameDocument(context.contentResolver, doc, name)?.takeIf {
            DocumentsContract.getDocumentId(it).endsWith("/$name") || DocumentFile.fromSingleUri(context, it)?.name == name
        }
    } catch (e: Exception) { null }

    private fun delete(doc: Uri) {
        try { DocumentsContract.deleteDocument(context.contentResolver, doc) } catch (e: Exception) { /* already gone */ }
    }

    private fun writeBytes(file: Uri, bytes: ByteArray) {
        val out = context.contentResolver.openOutputStream(file, "wt") ?: throw IOException("openOutputStream returned null")
        out.use { it.write(bytes) }
    }

    // Replaces dir/fileName so that a reader or a sync app sees the old file or the new one,
    // never a truncated one, and a process killed at any step leaves a complete copy behind:
    // write "<name>.tmp", move the old file to "<name>.bak", move the .tmp into place, drop the
    // .bak. If the move in fails, the .bak goes back. A provider without rename support gets the
    // old truncate-and-write. Stray .tmp/.bak files from an earlier crash are reused or cleared
    // first, because a rename onto a taken name would make "<name> (1)".
    private fun replaceAtomically(dir: Uri, fileName: String, bytes: ByteArray) {
        val listing = children(dir)
        val existing = listing[fileName]
        listing[fileName + BAK]?.let { delete(it) }
        val tmp = listing[fileName + TMP] ?: create(dir, mimeFor(fileName + TMP), fileName + TMP)
            ?: throw IOException("could not create $fileName$TMP")
        writeBytes(tmp, bytes)
        if (existing == null) {
            if (rename(tmp, fileName) != null) return
            delete(tmp)
            val file = create(dir, mimeFor(fileName), fileName) ?: throw IOException("could not create $fileName")
            return writeBytes(file, bytes)
        }
        val bak = rename(existing, fileName + BAK)
        if (bak == null) {
            delete(tmp)
            return writeBytes(existing, bytes)
        }
        if (rename(tmp, fileName) == null) {
            rename(bak, fileName)
            delete(tmp)
            throw IOException("could not move $fileName$TMP into place")
        }
        delete(bak)
    }

    private fun create(dir: Uri, mimeType: String, name: String): Uri? =
        try { DocumentsContract.createDocument(context.contentResolver, dir, mimeType, name) } catch (e: Exception) { null }

    // Walks (and, if create=true, creates) every segment but the last —
    // used to reach the parent directory of a relative "a/b/c.json" path.
    private fun resolveDirs(root: Uri, segments: List<String>, create: Boolean): Uri? {
        var dir = root
        for (seg in segments) {
            dir = children(dir)[seg] ?: (if (create) create(dir, Document.MIME_TYPE_DIR, seg) else null) ?: return null
        }
        return dir
    }

    override fun writeFile(path: String, data: String) {
        val root = requireRoot()
        val parts = segments(path)
        if (parts.isEmpty()) throw FolderException(FolderError.MISSING_PATH, "path is required")
        val dir = resolveDirs(root.uri, parts.dropLast(1), create = true)
            ?: throw FolderException(FolderError.WRITE_FAILED, "Could not create the folder for $path")
        try {
            replaceAtomically(dir, parts.last(), data.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            throw FolderException(FolderError.WRITE_FAILED, "Could not write $path", e)
        }
    }

    override fun readFile(path: String): String {
        val root = requireRoot()
        val parts = segments(path)
        if (parts.isEmpty()) throw FolderException(FolderError.MISSING_PATH, "path is required")
        val dir = resolveDirs(root.uri, parts.dropLast(1), create = false)
            ?: throw FolderException(FolderError.NOT_FOUND, "$path not found")
        val file = children(dir)[parts.last()] ?: throw FolderException(FolderError.NOT_FOUND, "$path not found")
        return try {
            readText(file) ?: throw FolderException(FolderError.READ_FAILED, "Could not read $path")
        } catch (e: FolderException) {
            throw e
        } catch (e: Exception) {
            throw FolderException(FolderError.READ_FAILED, "Could not read $path", e)
        }
    }

    // Many files in one call, listing each directory once rather than once per record, so
    // cold start does not scale as (records x directory size). The listing lives only for
    // this call: a sync app writes this folder while the app runs, so a longer-lived cache
    // could serve a stale directory. An absent or unreadable path is omitted.
    override fun readFiles(paths: List<String>): Map<String, String> {
        val root = requireRoot()
        val listings = HashMap<String, Map<String, Uri>>()
        val files = LinkedHashMap<String, String>()
        for (path in paths) {
            val parts = segments(path)
            if (parts.isEmpty()) continue
            val dirs = parts.dropLast(1)
            val listing = listings.getOrPut(dirs.joinToString("/")) {
                resolveDirs(root.uri, dirs, create = false)?.let { children(it) } ?: emptyMap()
            }
            val file = listing[parts.last()] ?: continue
            try {
                files[path] = readText(file) ?: continue
            } catch (e: Exception) {
                // Unreadable is reported the same way as absent: by omission.
            }
        }
        return files
    }

    override fun deleteFile(path: String) {
        val root = requireRoot()
        val parts = segments(path)
        if (parts.isEmpty()) return
        // The leftovers go too, or a stray .bak would bring the deleted file back.
        resolveDirs(root.uri, parts.dropLast(1), create = false)?.let { dir ->
            val listing = children(dir)
            for (suffix in listOf("", TMP, BAK)) listing[parts.last() + suffix]?.let { delete(it) }
        }
    }

    override fun readdir(path: String): List<String> {
        val root = requireRoot()
        val dir = resolveDirs(root.uri, segments(path), create = false)
        return dir?.let { children(it).keys.toList() } ?: emptyList()
    }
}
