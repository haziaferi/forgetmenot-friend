package com.hazia.forgetmenot.integrations

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant

// Intents for Message, Plan something and Share (what SharePlugin.kt and the calendar and
// browser plugins did). Each hands the work to another app; this app opens no connection (I7).

/** window.open(url, '_system'): the messaging app (or a browser, for wa.me etc.) claims the link. */
fun viewIntent(url: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))

/** createEventWithPrompt(): the calendar app's own new-event screen, prefilled; no calendar permission. */
fun calendarInsertIntent(title: String, start: Instant, end: Instant): Intent =
    Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, title)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.toEpochMilli())
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.toEpochMilli())

/** SharePlugin.share(): the chooser with plain text (a vCard pastes fine into contact apps). */
fun textShareIntent(text: String, title: String): Intent =
    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), title)

/** SharePlugin.shareFile()'s folder: cacheDir/shared, the only path file_paths.xml exposes. */
const val SHARE_DIR = "shared"

/**
 * Writes [text] as cacheDir/shared/<name>, clearing the folder first so earlier invites don't
 * pile up, and returns its content:// URI. File(name).name strips any "../" or "/" in [filename].
 */
fun writeShareFile(context: Context, text: String, filename: String): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", prepareShareFile(context.cacheDir, text, filename))

/** writeShareFile() without the FileProvider (which Robolectric cannot configure). */
internal fun prepareShareFile(cacheDir: File, text: String, filename: String): File {
    val dir = File(cacheDir, SHARE_DIR).apply { deleteRecursively(); mkdirs() }
    return File(dir, File(filename).name).apply { writeText(text, Charsets.UTF_8) }
}

/** SharePlugin.shareFile(): the file as an attachment, readable by the target for this share only. */
fun fileShareIntent(uri: Uri, mimeType: String, title: String): Intent {
    val send = Intent(Intent.ACTION_SEND).setType(mimeType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri("", uri)
    return Intent.createChooser(send, title)
}
