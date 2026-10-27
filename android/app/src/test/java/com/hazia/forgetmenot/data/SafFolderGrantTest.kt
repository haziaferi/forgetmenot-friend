package com.hazia.forgetmenot.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Where the picked folder is remembered (I1): prefs "sync_folder"/"tree_uri" plus a persisted
 * grant, as the web build's plugin left them. Robolectric has no documents provider, so a found
 * grant shows as FOLDER_UNAVAILABLE (the folder itself can't be reached) and a missing one as
 * NO_FOLDER_SELECTED; that difference is what these tests read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SafFolderGrantTest {
    private val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    private val old = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FOld")
    private val new = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FNew")
    private lateinit var context: Context
    private lateinit var folder: SafFolder

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        folder = SafFolder(context)
    }

    private fun prefs() = context.getSharedPreferences(SafFolder.PREFS, Context.MODE_PRIVATE)
    private fun granted() = context.contentResolver.persistedUriPermissions.map { it.uri }.toSet()
    private fun code(): String = try { folder.readdir(); "OK" } catch (e: FolderException) { e.code }

    @Test fun nothingStoredMeansNoFolder() {
        assertEquals(FolderError.NO_FOLDER_SELECTED, code())
    }

    // The web build wrote exactly this; a native update over it must find the folder again.
    @Test fun aGrantTheWebBuildStoredIsFound() {
        context.contentResolver.takePersistableUriPermission(old, rw)
        prefs().edit().putString("tree_uri", old.toString()).commit()
        assertEquals(FolderError.FOLDER_UNAVAILABLE, code())
    }

    @Test fun aStoredUriWhoseGrantIsGoneMeansNoFolder() {
        prefs().edit().putString("tree_uri", old.toString()).commit()
        assertEquals(FolderError.NO_FOLDER_SELECTED, code())
    }

    @Test fun keepReleasesTheOldGrant() {
        context.contentResolver.takePersistableUriPermission(old, rw)
        prefs().edit().putString("tree_uri", old.toString()).commit()
        val pending = folder.takeGrant(new)
        assertEquals(new.toString(), prefs().getString("tree_uri", null))
        assertEquals(setOf(old, new), granted())
        pending.keep()
        assertEquals(setOf(new), granted())
    }

    @Test fun revertGoesBackToTheOldFolderAndLetsTheNewGrantGo() {
        context.contentResolver.takePersistableUriPermission(old, rw)
        prefs().edit().putString("tree_uri", old.toString()).commit()
        folder.takeGrant(new).revert()
        assertEquals(old.toString(), prefs().getString("tree_uri", null))
        assertEquals(setOf(old), granted())
    }

    @Test fun revertWithNoEarlierFolderForgetsThePick() {
        folder.takeGrant(new).revert()
        assertNull(prefs().getString("tree_uri", null))
        assertTrue(granted().isEmpty())
    }
}
