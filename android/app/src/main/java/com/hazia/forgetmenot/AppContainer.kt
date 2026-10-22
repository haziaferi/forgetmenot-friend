package com.hazia.forgetmenot

import android.content.Context
import com.hazia.forgetmenot.data.Backup
import com.hazia.forgetmenot.data.CachedStore
import com.hazia.forgetmenot.data.Conflicts
import com.hazia.forgetmenot.data.Records
import com.hazia.forgetmenot.data.SafFolder
import com.hazia.forgetmenot.data.StartResult
import com.hazia.forgetmenot.data.SyncSession
import com.hazia.forgetmenot.reminders.CombinedEffects
import com.hazia.forgetmenot.reminders.ReminderScheduler
import com.hazia.forgetmenot.widget.WidgetPusher
import com.hazia.forgetmenot.widget.pushOnThemeChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Manual dependency injection: one instance per process, holding the app-wide objects that
 * screens, receivers and widgets share. Later phases add their own lazily built members here
 * ([reminders], P3, and [widgets], P6, which [records]' effects drive).
 *
 * Reached through [from] rather than a custom Application class, so broadcast receivers and
 * Glance widgets, which can run without MainActivity, get the same instance.
 */
class AppContainer private constructor(val appContext: Context) {
    /** Process-long work: the cold-start load outlives any one activity. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val syncFolder: SafFolder by lazy { SafFolder(appContext) }
    val store: CachedStore by lazy { CachedStore(syncFolder) }
    val records: Records by lazy { Records(store).also { it.effects = CombinedEffects(reminders, widgets) } }
    /** Takes [records] as a function: the two lazies refer to each other. */
    val reminders: ReminderScheduler by lazy { ReminderScheduler(appContext, { records }) }
    /** The widget summary and theme (SPEC §5); same shape as [reminders]. */
    val widgets: WidgetPusher by lazy { WidgetPusher(appContext, { records }, appScope) }
    val conflicts: Conflicts by lazy { Conflicts(store, records) }
    val backup: Backup by lazy { Backup(store, records) }
    val session: SyncSession by lazy { SyncSession(store, records, conflicts) }

    /**
     * The cold-start load, once per process (an activity recreated later finds it done). Await it
     * before the first render; a failed load leaves the session read-only (I6). Widgets write
     * their reach-outs through [records] after awaiting this, so there is no widget queue to
     * drain here. The conflict scan follows in the background, so the first frame does not wait
     * for its directory listings; a skin or mode change re-pushes the widgets from then on.
     */
    val started: Deferred<StartResult> by lazy {
        appScope.async {
            session.start().also {
                pushOnThemeChange(appScope, store, records)
                if (!it.readOnly) appScope.launch { session.runScan() }
            }
        }
    }

    companion object {
        @Volatile private var instance: AppContainer? = null

        fun from(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }
    }
}
