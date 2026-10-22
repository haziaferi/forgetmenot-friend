package com.hazia.forgetmenot.widget

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.hazia.forgetmenot.AppContainer
import com.hazia.forgetmenot.WIDGET_PREFS_KEY
import com.hazia.forgetmenot.data.Records
import com.hazia.forgetmenot.data.StartResult
import com.hazia.forgetmenot.domain.addContact
import com.hazia.forgetmenot.domain.isoTimestamp
import com.hazia.forgetmenot.domain.latestContact
import com.hazia.forgetmenot.domain.parseInstant
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant

// The id travels as a String: Glance serialises ActionParameters through a Bundle, where a
// Float does not round-trip exactly.
val FriendIdKey = ActionParameters.Key<String>("friendId")

sealed interface TickOutcome {
    data class Logged(val name: String) : TickOutcome
    /** A reach-out within [WidgetTick.DUPLICATE_WINDOW] of this tap is already on the record. */
    data class AlreadyLogged(val name: String) : TickOutcome
    /** The sync folder did not load: nothing may be written (I6). */
    data object ReadOnly : TickOutcome
    /** The id is no longer an active friend. */
    data object Gone : TickOutcome
    /** The cold-start load did not finish in time; nothing was written. */
    data object TimedOut : TickOutcome
}

/**
 * The widget tick (SPEC §5 "The reverse path"): logs a reach-out without opening the app. The app
 * ships as a fresh install, so there is no queue for the app to drain: the reach-out is written
 * straight to the friend's record, as the app's own Log button would, once the cold-start load
 * ([started]) is done. Runs on the main thread, as every Records call does; the save pushes the
 * widgets and reschedules. Writes are flushed before returning, since the process may end then.
 */
object WidgetTick {
    /**
     * A tap this close to the friend's latest reach-out is the same one again: a cold load takes
     * seconds, and a second tap in that time must not log twice.
     */
    val DUPLICATE_WINDOW: Duration = Duration.ofSeconds(60)

    /** Only the wait for the load is capped; once the save starts, it finishes. */
    const val WAIT_MS = 45_000L

    suspend fun log(
        started: Deferred<StartResult>,
        records: Records,
        id: String,
        now: Instant = Instant.now(),
        waitMs: Long = WAIT_MS,
    ): TickOutcome {
        val start = withTimeoutOrNull(waitMs) { started.await() } ?: return TickOutcome.TimedOut
        if (start.readOnly || records.store.writesBlocked) return TickOutcome.ReadOnly
        val f = records.getFriend(id)?.takeIf { !it.archived } ?: return TickOutcome.Gone
        val last = parseInstant(latestContact(f)?.date)
        if (last != null && Duration.between(last, now).abs() < DUPLICATE_WINDOW) return TickOutcome.AlreadyLogged(f.name)
        records.saveFriend(addContact(f, isoTimestamp(now)).first)
        records.store.flushWrites()
        return TickOutcome.Logged(f.name)
    }
}

/** That friend repainted as just contacted: what the save will push, shown before it lands. */
fun markToday(list: List<FriendSummary>, id: String): List<FriendSummary> =
    list.map { if (it.id == id) it.copy(code = "today", weight = 0.0) else it }

/** What [markTappedToday] replaced, to put back if the tap could not be saved. */
class TapMark(private val before: String?, private val marked: String) {
    /** Puts the old summary back, unless a real push has replaced the marked one since. */
    fun restore(context: Context) = synchronized(widgetPrefsLock) {
        val prefs = widgetPrefs(context)
        if (prefs.getString(WIDGET_PREFS_KEY, null) == marked) prefs.edit().putString(WIDGET_PREFS_KEY, before).commit()
    }
}

/**
 * The tap's own repaint, before the cold-start load: without it a widget tapped in a cold process
 * showed nothing for seconds. The save's push overwrites it with the real summary.
 */
fun markTappedToday(context: Context, id: String): TapMark = synchronized(widgetPrefsLock) {
    val prefs = widgetPrefs(context)
    val before = prefs.getString(WIDGET_PREFS_KEY, null)
    val marked = encodeSummaries(markToday(decodeSummaries(before), id))
    prefs.edit().putString(WIDGET_PREFS_KEY, marked).commit()
    TapMark(before, marked)
}

class LogReachOutAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[FriendIdKey] ?: return
        val app = context.applicationContext
        val container = AppContainer.from(app)
        // The date is stamped at the tap, not when the load finishes.
        val now = Instant.now()
        val mark = markTappedToday(app, id)
        container.appScope.launch { updateAllWidgets(app) }
        withContext(Dispatchers.Main) {
            val out = try {
                WidgetTick.log(container.started, container.records, id, now)
            } catch (e: Exception) {
                Log.e("forgetmenot", "widget reach-out failed", e)
                null
            }
            when (out) {
                is TickOutcome.Logged -> toast(app, "Logged — nice.")
                is TickOutcome.AlreadyLogged -> toast(app, "Already logged — nice.")
                TickOutcome.ReadOnly -> {
                    undo(app, mark)
                    toast(app, "Couldn’t save — open forgetmenot to check the sync folder")
                }
                // The summary named someone since archived or deleted: repaint without them.
                TickOutcome.Gone -> { undo(app, mark); container.records.pushWidgets() }
                TickOutcome.TimedOut, null -> {
                    undo(app, mark)
                    toast(app, "Couldn’t save — try again from the app")
                }
            }
        }
    }

    private suspend fun undo(context: Context, mark: TapMark) {
        mark.restore(context)
        updateAllWidgets(context)
    }

    private fun toast(context: Context, message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
