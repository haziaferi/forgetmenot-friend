package com.hazia.forgetmenot.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.hazia.forgetmenot.domain.Contact
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.domain.Settings
import com.hazia.forgetmenot.ui.theme.Mode
import com.hazia.forgetmenot.ui.theme.Skin
import com.hazia.forgetmenot.ui.theme.ThemeTokens
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/** SPEC §5 "The data path", "Selection rules" and "Colours and backgrounds", without Android. */
class WidgetSummaryTest {
    private val now = ZonedDateTime.of(2026, 10, 20, 12, 0, 0, 0, ZoneId.of("Europe/Rome"))

    private fun friend(id: String, daysAgo: Long?, cadence: Int? = 30, paused: Boolean = false,
                       pausedUntil: String? = null, archived: Boolean = false) = Friend(
        id = id, name = id.uppercase(), cadenceDays = cadence, paused = paused, pausedUntil = pausedUntil,
        archived = archived,
        contacts = daysAgo?.let { listOf(Contact(id = "c$id", date = now.minusDays(it).toInstant().toString())) } ?: emptyList(),
    )

    private fun s(id: String, weight: Double, paused: Boolean = false) = FriendSummary(id, id, "x", weight, paused)

    // ── the summary ──────────────────────────────────────────────────────────

    @Test fun summaryHasTheFiveFieldsOfSyncWidgetsNative() {
        val json = Json.parseToJsonElement(encodeSummaries(summarise(listOf(friend("ana", 9)), now))).jsonArray
        assertEquals(setOf("id", "name", "code", "weight", "paused"), json[0].jsonObject.keys)
    }

    @Test fun weightIsDaysOverCadenceUncapped() {
        val (ana, bo) = summarise(listOf(friend("ana", 90, cadence = 30), friend("bo", 15, cadence = 30)), now)
        assertEquals(3.0, ana.weight, 1e-9)   // the ring's pct would be capped at 1
        assertEquals(0.5, bo.weight, 1e-9)
        assertEquals("3m", ana.code)
        assertEquals("15d", bo.code)
    }

    @Test fun noCadenceUsesTheDefaultOf90() {
        assertEquals(1.0, summarise(friend("cy", 90, cadence = null), now).weight, 1e-9)
    }

    @Test fun neverLoggedIsNewWithWeightZero() {
        val s = summarise(friend("di", null), now)
        assertEquals("new", s.code)
        assertEquals(0.0, s.weight, 0.0)
    }

    @Test fun loggedTodayReadsToday() {
        assertEquals("today", summarise(friend("ed", 0), now).code)
    }

    @Test fun archivedDroppedPausedKept() {
        val list = summarise(listOf(friend("a", 3, archived = true), friend("b", 3, paused = true),
            friend("c", 3, pausedUntil = "2026-11-01"), friend("d", 3, pausedUntil = "2026-10-01")), now)
        assertEquals(listOf("b", "c", "d"), list.map { it.id })
        assertEquals(listOf(true, true, false), list.map { it.paused })
    }

    @Test fun encodeDecodeRoundTrip() {
        val list = summarise(listOf(friend("ana", 40), friend("bo", null, paused = true)), now)
        assertEquals(list, decodeSummaries(encodeSummaries(list)))
    }

    @Test fun decodeIsLenient() {
        assertEquals(emptyList<FriendSummary>(), decodeSummaries(null))
        assertEquals(emptyList<FriendSummary>(), decodeSummaries("not json"))
        assertEquals(emptyList<FriendSummary>(), decodeSummaries("""{"id":"a"}"""))
        assertEquals(listOf(FriendSummary("a", "", "", 0.0, false)), decodeSummaries("""[{"id":"a"}, 3, "x"]"""))
    }

    // ── the theme ────────────────────────────────────────────────────────────

    @Test fun everyPaletteGivesItsWidgetTokens() {
        for (skin in Skin.entries) for (mode in Mode.entries) {
            val w = ThemeTokens.widget(ThemeTokens.palette(skin, mode))
            val t = widgetTheme(Settings(skin = skin.key, mode = mode.key))
            assertEquals("${skin.key}/${mode.key}", listOf(w.background, w.text, w.muted, w.accent),
                listOf(t.background, t.text, t.muted, t.accent).map { it.toArgb() })
            assertEquals(t, decodeTheme(encodeTheme(t)))
        }
    }

    @Test fun defaultIsCyanotypeLight() {
        assertEquals(listOf(0xFFE9F1F7, 0xFF142F44, 0xFF526A7C, 0xFF0A5188).map { Color(it) },
            DEFAULT_WIDGET_THEME.let { listOf(it.background, it.text, it.muted, it.accent) })
        assertEquals(DEFAULT_WIDGET_THEME, widgetTheme(Settings(skin = "skylight", mode = "dark")))
        assertEquals("""{"background":"#E9F1F7","text":"#142F44","muted":"#526A7C","accent":"#0A5188"}""",
            encodeTheme(DEFAULT_WIDGET_THEME))
    }

    @Test fun anUnreadableColourFallsBackToTheWholeDefault() {
        val dusk = encodeTheme(widgetTheme(Settings(skin = "pink", mode = "dark")))
        assertEquals(DEFAULT_WIDGET_THEME, decodeTheme(null))
        assertEquals(DEFAULT_WIDGET_THEME, decodeTheme("garbage"))
        assertEquals(DEFAULT_WIDGET_THEME, decodeTheme(dusk.replace("\"muted\"", "\"mute\"")))
        assertEquals(DEFAULT_WIDGET_THEME, decodeTheme(dusk.replace(Regex("\"accent\":\"[^\"]*\""), "\"accent\":\"red\"")))
    }

    // ── selection rules ──────────────────────────────────────────────────────

    @Test fun topByWeightDropsPausedAndSortsDescending() {
        val list = listOf(s("a", 0.5), s("b", 3.0, paused = true), s("c", 2.0), s("d", 1.0), s("e", 0.1))
        assertEquals(listOf("c", "d", "a"), topByWeight(list, 3).map { it.id })
        assertEquals(listOf("c"), topByWeight(list, 1).map { it.id })
        // Top 3 is a superset of Spotlight.
        assertTrue(topByWeight(list, 3).containsAll(topByWeight(list, 1)))
    }

    @Test fun weightedRandomKeepsEveryoneReachable() {
        val list = listOf(s("just", 0.0), s("over", 5.0), s("off", 9.0, paused = true))
        val picks = (0 until 2000).map { weightedRandom(list, Random(it))!!.id }.groupingBy { it }.eachCount()
        assertFalse("off" in picks)
        assertTrue("the 0.15 floor keeps a just-contacted friend reachable", (picks["just"] ?: 0) > 20)
        assertTrue((picks["over"] ?: 0) > (picks["just"] ?: 0))
    }

    @Test fun weightedRandomIsNullWhenEveryoneIsPaused() {
        assertNull(weightedRandom(listOf(s("a", 1.0, paused = true))))
        assertNull(weightedRandom(emptyList()))
    }

    @Test fun seededRollIsStablePerSummary() {
        val list = (1..8).map { s("f$it", it / 3.0) }
        val first = weightedRandom(list, Random(list.hashCode()))
        repeat(5) { assertEquals(first, weightedRandom(list, Random(list.hashCode()))) }
    }

    @Test fun emptyLabelsSayWhy() {
        assertEquals("Add someone to think of", emptyLabel(emptyList()))
        assertEquals("Add someone", emptyLabel(emptyList(), short = true))
        assertEquals("Everyone's paused", emptyLabel(listOf(s("a", 1.0, paused = true))))
    }

    // ── staleness ────────────────────────────────────────────────────────────

    @Test fun summaryFromAnotherDayIsStale() {
        assertTrue(summaryIsStale(null, now))
        assertTrue(summaryIsStale("2026-10-19", now))
        assertFalse(summaryIsStale("2026-10-20", now))
    }
}
