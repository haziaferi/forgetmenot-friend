package com.hazia.forgetmenot.widget

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartActivity as actionStartIntent
import androidx.glance.appwidget.updateAll
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider as GlanceColorProvider
import com.hazia.forgetmenot.MainActivity
import com.hazia.forgetmenot.WIDGET_PREFS_KEY
import com.hazia.forgetmenot.WIDGET_PREFS_NAME
import com.hazia.forgetmenot.WIDGET_THEME_KEY
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.domain.Settings
import com.hazia.forgetmenot.domain.cadenceOf
import com.hazia.forgetmenot.domain.classify
import com.hazia.forgetmenot.domain.isPaused
import com.hazia.forgetmenot.ui.theme.ThemeTokens
import com.hazia.forgetmenot.ui.theme.WidgetTokens
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * What a widget needs to render one friend, not the full record. [WidgetPusher] builds it from
 * the records (summarise()) and stores the list as JSON under [WIDGET_PREFS_KEY]; the widgets
 * read it back through [FriendSummaryStateDefinition].
 */
data class FriendSummary(
    val id: String,     // the record id; what a tap or a logged reach-out names the friend by
    val name: String,
    val code: String,   // e.g. "3m", "9d", "new" — same compact code Home shows
    val weight: Double,  // elapsed/cadence ratio, uncapped — used for weighted-random pick
    val paused: Boolean
)

/**
 * The summary of one friend, as syncWidgetsNative() built it. weight is days / cadence
 * uncapped, not classify()'s pct: as a ranking key the pct's cap at 1 would tie every overdue
 * friend, and the stable sort would then name whoever was added first.
 */
fun summarise(f: Friend, now: ZonedDateTime): FriendSummary {
    val cl = classify(f, now)
    return FriendSummary(f.id, f.name, cl.code, cl.days.toDouble() / maxOf(1, cadenceOf(f)), isPaused(f, now))
}

/** Active friends only, in record order; paused ones are kept (the pickers drop them). */
fun summarise(friends: List<Friend>, now: ZonedDateTime): List<FriendSummary> =
    friends.filter { !it.archived }.map { summarise(it, now) }

fun encodeSummaries(list: List<FriendSummary>): String = JsonArray(list.map { s ->
    buildJsonObject {
        put("id", s.id); put("name", s.name); put("code", s.code); put("weight", s.weight); put("paused", s.paused)
    }
}).toString()

// Lenient field by field, as the org.json opt* reads were: a missing field takes its default,
// and anything that is not a JSON array of objects reads as no friends rather than a crash.
fun decodeSummaries(json: String?): List<FriendSummary> {
    if (json == null) return emptyList()
    return try {
        (Json.parseToJsonElement(json) as? JsonArray ?: return emptyList()).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun prim(k: String) = o[k] as? JsonPrimitive
            FriendSummary(
                id = prim("id")?.contentOrNull.orEmpty(),
                name = prim("name")?.contentOrNull.orEmpty(),
                code = prim("code")?.contentOrNull.orEmpty(),
                weight = prim("weight")?.doubleOrNull ?: 0.0,
                paused = prim("paused")?.booleanOrNull ?: false,
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * The app's active skin and mode as the four colours a card uses: bg, and text, muted and
 * accent on it, which clear 7:1, 4.5:1 and 7:1 in all ten palettes (ThemeTokensTest).
 */
data class WidgetTheme(val background: Color, val text: Color, val muted: Color, val accent: Color)

fun widgetTheme(t: WidgetTokens) = WidgetTheme(Color(t.background), Color(t.text), Color(t.muted), Color(t.accent))

/** The theme the settings name; an unknown skin or mode gives the whole of Cyanotype light. */
fun widgetTheme(s: Settings): WidgetTheme = widgetTheme(ThemeTokens.widget(ThemeTokens.resolve(s.skin, s.mode)))

// Cyanotype light, the skin a fresh install starts on: what a widget shows until the app has
// pushed a theme, and whenever the stored one is unreadable.
val DEFAULT_WIDGET_THEME = widgetTheme(ThemeTokens.widget(ThemeTokens.DEFAULT))

private fun hex(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)

fun encodeTheme(t: WidgetTheme): String = buildJsonObject {
    put("background", hex(t.background)); put("text", hex(t.text)); put("muted", hex(t.muted)); put("accent", hex(t.accent))
}.toString()

private val HEX = Regex("#([0-9A-Fa-f]{6})")

// Any unreadable colour falls back to the whole default, never a mix of two skins.
fun decodeTheme(json: String?): WidgetTheme {
    if (json == null) return DEFAULT_WIDGET_THEME
    return try {
        val o = Json.parseToJsonElement(json).jsonObject
        fun col(key: String): Color {
            val m = HEX.matchEntire(o.getValue(key).jsonPrimitive.content) ?: throw IllegalArgumentException(key)
            return Color(0xFF000000.toInt() or m.groupValues[1].toInt(16))
        }
        WidgetTheme(col("background"), col("text"), col("muted"), col("accent"))
    } catch (e: Exception) {
        DEFAULT_WIDGET_THEME
    }
}

// What every widget composes from. One value, so a theme change alone also recomposes.
data class WidgetState(val friends: List<FriendSummary>, val theme: WidgetTheme)

fun readWidgetState(context: Context): WidgetState = WidgetState(readFriendSummaries(context), readWidgetTheme(context))

fun readWidgetTheme(context: Context): WidgetTheme = decodeTheme(widgetPrefs(context).getString(WIDGET_THEME_KEY, null))

fun readFriendSummaries(context: Context): List<FriendSummary> =
    decodeSummaries(widgetPrefs(context).getString(WIDGET_PREFS_KEY, null))

// Held around every write of the widget prefs: the pusher runs on the main thread, but a
// SharedPreferences edit of two keys is only consistent if no other edit interleaves.
val widgetPrefsLock = Any()

fun widgetPrefs(context: Context): SharedPreferences =
    context.getSharedPreferences(WIDGET_PREFS_NAME, Context.MODE_PRIVATE)

// Per-widget try: one that is not placed (or fails to render) must not stop the others.
suspend fun updateAllWidgets(context: Context) {
    for (widget in listOf(SpotlightWidget(), TopThreeWidget(), RandomWidget())) {
        try { widget.updateAll(context) } catch (e: Exception) { /* not placed, or failed to render */ }
    }
}

/**
 * Opens the app at this friend. Explicit (the class is set), so it needs no intent-filter
 * and MainActivity accepts nothing it did not already; the URI is only a payload, read by
 * friendIdFromUrl() in ui/AppNavHost.kt. The shape is forgetmenot://friend/<id> (I3).
 */
fun friendIntent(context: Context, friend: FriendSummary): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("forgetmenot://friend/" + friend.id))
        .setClass(context, MainActivity::class.java)

/** Opens the named friend, or just the app when there is no one to name. */
fun openFriendAction(context: Context, friend: FriendSummary?): Action =
    if (friend == null) actionStartActivity<MainActivity>() else actionStartIntent(friendIntent(context, friend))

/**
 * What a widget says when it has no one to name: nobody added yet, or everyone paused.
 * Never "all caught up" — there is nothing to be caught up on.
 */
fun emptyLabel(friends: List<FriendSummary>, short: Boolean = false): String = when {
    friends.isNotEmpty() -> "Everyone's paused"
    short -> "Add someone"
    else -> "Add someone to think of"
}

/** Highest weight (most lapsed relative to their own cadence) first. Paused friends excluded. */
fun topByWeight(friends: List<FriendSummary>, count: Int): List<FriendSummary> =
    friends.filter { !it.paused }.sortedByDescending { it.weight }.take(count)

/**
 * A dice roll gently biased toward friends who've gone longer — not "always the most
 * overdue", which would duplicate Spotlight. Every friend keeps a small chance.
 */
fun weightedRandom(friends: List<FriendSummary>, rng: Random = Random.Default): FriendSummary? {
    val pool = friends.filter { !it.paused }
    if (pool.isEmpty()) return null
    val weights = pool.map { 0.15 + it.weight.coerceAtLeast(0.0) }
    val total = weights.sum() // >= 0.15 for a non-empty pool, given the floor above
    var roll = rng.nextDouble() * total
    for (i in pool.indices) {
        roll -= weights[i]
        if (roll <= 0.0) return pool[i]
    }
    return pool.last()
}

// Shared by the widgets. They follow the app's own light/dark choice, not the system's, so
// day and night are the same colour on purpose.
fun fixedColor(color: Color): GlanceColorProvider = ColorProvider(day = color, night = color)

// Spotlight and Random share one size ladder: TINY is one 40dp cell, REGULAR anything taller.
val SMALL_CARD_TINY = DpSize(110.dp, 40.dp)
val SMALL_CARD_REGULAR = DpSize(110.dp, 68.dp)
