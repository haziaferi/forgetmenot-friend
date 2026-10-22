package com.hazia.forgetmenot.widget

import android.content.Context
import com.hazia.forgetmenot.WIDGET_PREFS_KEY
import com.hazia.forgetmenot.WIDGET_PUSHED_DAY_KEY
import com.hazia.forgetmenot.WIDGET_THEME_KEY
import com.hazia.forgetmenot.data.CachedStore
import com.hazia.forgetmenot.data.DataEffects
import com.hazia.forgetmenot.data.Records
import com.hazia.forgetmenot.domain.localDateISO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/**
 * syncWidgetsNative() and WidgetDataPlugin.saveWidgetData in one (SPEC §5 "The data path"): the
 * summary of every active friend and the active skin's four colours go into the widget prefs,
 * then all three widgets update. Called through [Records.pushWidgets] after every friend save,
 * load, refresh and folder adoption, and on the main thread like every Records read.
 *
 * A read-only session's cache may be partial, so then nothing is pushed and the widgets keep
 * the last good summary (I6).
 */
class WidgetPusher(
    private val context: Context,
    private val records: () -> Records,
    private val scope: CoroutineScope,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val update: suspend (Context) -> Unit = ::updateAllWidgets,
) : DataEffects {
    override fun rescheduleReminders() {}

    override fun pushWidgets() {
        val r = records()
        if (r.store.writesBlocked) return
        val now = clock()
        val summaries = encodeSummaries(summarise(r.activeFriends(), now))
        val theme = encodeTheme(widgetTheme(r.getSettings()))
        synchronized(widgetPrefsLock) {
            // commit(), not apply(): the tick's process may end as soon as it returns.
            widgetPrefs(context).edit()
                .putString(WIDGET_PREFS_KEY, summaries)
                .putString(WIDGET_THEME_KEY, theme)
                .putString(WIDGET_PUSHED_DAY_KEY, localDateISO(now))
                .commit()
        }
        scope.launch { update(context) }
    }
}

/**
 * The codes are formatted strings ("9d"), so a summary pushed yesterday is a day behind. The
 * JS re-pushed on every resume; here a widget that composes from a summary pushed on another
 * local day asks for a fresh push too ([refreshIfStale]).
 */
fun summaryIsStale(pushedDay: String?, now: ZonedDateTime): Boolean = pushedDay != localDateISO(now)

/**
 * Called from each widget's provideGlance(), which runs when a widget session starts: a
 * placement, a launcher reload, the three-hourly APPWIDGET_UPDATE. A stale summary is rebuilt
 * from the records once the cold-start load is done, gated on I6 by [Records.pushWidgets].
 * Not awaited: the widget draws what it has, and the push updates it a moment later.
 */
fun refreshIfStale(context: Context, now: ZonedDateTime = ZonedDateTime.now()) {
    val pushedDay = widgetPrefs(context).getString(WIDGET_PUSHED_DAY_KEY, null)
    if (!summaryIsStale(pushedDay, now)) return
    val container = com.hazia.forgetmenot.AppContainer.from(context)
    container.appScope.launch {
        if (!container.started.await().readOnly) container.records.pushWidgets()
    }
}

/**
 * applyTheme() pushed the widgets again on a skin or mode change; Records.saveSettings has no
 * effects, so this watches the cache instead. Only a change of the pair pushes: the first value
 * is the one the start-up push already used.
 */
fun pushOnThemeChange(scope: CoroutineScope, store: CachedStore, records: Records): Job = scope.launch {
    store.revision
        .map { records.getSettings().let { it.skin to it.mode } }
        .distinctUntilChanged()
        .drop(1)
        .collect { records.pushWidgets() }
}
