package com.hazia.forgetmenot.widget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.glance.state.GlanceStateDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

// The summaries and the theme are Glance state, not a plain read in provideGlance():
// a live Glance session (45s after its last event) answers update() by re-reading its state
// definition and recomposing what observed it, so a plain read observed nothing and the widget
// kept showing stale data. Every read of this store is a fresh parse of the widget prefs.
object FriendSummaryStateDefinition : GlanceStateDefinition<WidgetState> {
    override suspend fun getDataStore(context: Context, fileKey: String): DataStore<WidgetState> =
        PrefsBackedStore(context.applicationContext)

    // Glance deletes this file whenever any widget is removed, so it must not be the real prefs
    // file (that would wipe the summaries the other placed widgets draw from). Nothing ever
    // reads or writes it.
    override fun getLocation(context: Context, fileKey: String): File =
        File(context.noBackupFilesDir, "glance_state_unused_$fileKey")
}

// Read-only: WidgetPusher owns the prefs file.
private class PrefsBackedStore(private val context: Context) : DataStore<WidgetState> {
    override val data: Flow<WidgetState> = flow { emit(readWidgetState(context)) }

    override suspend fun updateData(transform: suspend (WidgetState) -> WidgetState): WidgetState =
        throw UnsupportedOperationException("widget data is written by WidgetPusher, not through Glance state")
}
