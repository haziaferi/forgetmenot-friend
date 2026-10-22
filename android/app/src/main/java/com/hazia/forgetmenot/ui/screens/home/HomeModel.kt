package com.hazia.forgetmenot.ui.screens.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hazia.forgetmenot.data.Records
import com.hazia.forgetmenot.data.ScanResult
import com.hazia.forgetmenot.domain.Classification
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.domain.Section
import com.hazia.forgetmenot.domain.addContact
import com.hazia.forgetmenot.domain.cadenceOf
import com.hazia.forgetmenot.domain.classify
import com.hazia.forgetmenot.domain.foldText
import com.hazia.forgetmenot.domain.isPaused
import com.hazia.forgetmenot.domain.isoTimestamp
import com.hazia.forgetmenot.domain.plural
import com.hazia.forgetmenot.ui.sheets.EntryList
import com.hazia.forgetmenot.ui.sheets.RecentChanges
import com.hazia.forgetmenot.ui.sheets.Toaster
import com.hazia.forgetmenot.ui.sheets.undoAddedEntry
import java.time.Instant
import java.time.ZonedDateTime

// What the home screen shows, apart from drawing: renderHome() and logContactQuick() in
// www/js/screens/home.js (SPEC §1 "Screens", §4 "Classifying a friend").

/** The section headings, in the order they are shown. An empty section shows no heading. */
val SECTION_TITLES = linkedMapOf(
    Section.THINKING to "Been a while",
    Section.COMING to "In between",
    Section.RECENT to "Recently in touch",
)

/** The smaller home, for low days: this many people, the ones most worth a hello. */
const val SMALL_HOME_COUNT = 3

/** One home row: the friend and what classify() says about them. */
data class HomeRow(val friend: Friend, val cl: Classification, val paused: Boolean)

data class HomeSection(val section: Section, val title: String, val rows: List<HomeRow>)

sealed interface HomeContent {
    /** No active friends at all: "Nobody here yet". */
    data object Empty : HomeContent
    /** A search that matched nobody; [query] as typed. */
    data class NoMatch(val query: String) : HomeContent
    /** The smaller home: "Maybe today" ([few] may be empty), then "Show everyone ([total])". */
    data class Small(val few: List<HomeRow>, val total: Int) : HomeContent
    data class Full(val sections: List<HomeSection>) : HomeContent
}

/**
 * Case and accents folded, so "zoe" finds "Zoë"; a note or a tag name can match as well as the
 * name. [query] is already folded and trimmed.
 */
fun matchesQuery(f: Friend, query: String, tagName: (String) -> String?): Boolean =
    foldText(f.name).contains(query) ||
        f.notes.any { foldText(it.text).contains(query) } ||
        f.tags.any { foldText(tagName(it)).contains(query) }

/**
 * renderHome(): [friends] are the active ones. Within a section the widgets' order, by how far
 * into their own cadence each friend is, highest first; "In between" puts the soonest due first.
 * A search always looks through everyone, past the smaller home.
 */
fun buildHome(
    friends: List<Friend>,
    tagName: (String) -> String?,
    search: String,
    smallHome: Boolean,
    showAll: Boolean,
    now: ZonedDateTime,
): HomeContent {
    if (friends.isEmpty()) return HomeContent.Empty
    val q = foldText(search.trim())
    val filtered = if (q.isNotEmpty()) friends.filter { matchesQuery(it, q, tagName) } else friends
    if (q.isNotEmpty() && filtered.isEmpty()) return HomeContent.NoMatch(search)

    // classify() once per friend, not once per sort comparison.
    val rows = filtered.map { HomeRow(it, classify(it, now), isPaused(it, now)) }
    val bySection = rows.groupBy { it.cl.section }
    fun ratio(r: HomeRow) = r.cl.days.toDouble() / cadenceOf(r.friend)
    fun left(r: HomeRow) = cadenceOf(r.friend) - r.cl.days
    val thinking = bySection[Section.THINKING].orEmpty().sortedByDescending(::ratio)
    val coming = bySection[Section.COMING].orEmpty().sortedBy(::left)
    val recent = bySection[Section.RECENT].orEmpty().sortedByDescending(::ratio)

    if (q.isEmpty() && smallHome && !showAll) {
        return HomeContent.Small((thinking + coming).take(SMALL_HOME_COUNT), friends.size)
    }
    val ordered = mapOf(Section.THINKING to thinking, Section.COMING to coming, Section.RECENT to recent)
    return HomeContent.Full(
        SECTION_TITLES.mapNotNull { (s, title) -> ordered[s]?.takeIf { it.isNotEmpty() }?.let { HomeSection(s, title, it) } },
    )
}

/** runSyncConflictScan()'s toast for conflicts settled without the user, or null. */
fun autoResolvedText(scan: ScanResult): String? {
    if (scan.autoResolved <= 0) return null
    val n = plural(scan.autoResolved, " sync conflict", " sync conflicts") + " resolved automatically"
    return if (scan.lostKept > 0) "$n; the older edits are kept beside their records" else n
}

/**
 * Home's state that lives until the app restarts, as the JS `state` object did: it outlasts the
 * screen's composition, which Navigation drops whenever another screen is on top.
 */
object HomeSession {
    /** "Show everyone (N)" was tapped: the full list until restart, or until Smaller home is toggled. */
    var showAll by mutableStateOf(false)
    /** The smallHome value last seen, so a toggle in Settings resets [showAll]. */
    var lastSmallHome: Boolean? = null
    /** Once a folder has been seen, the no-folder banner stops asking. */
    var folderSeen = false
    /**
     * The onboarding queue runs once per process, after home is first up. Set only when the
     * queue has actually started, so leaving home while the status checks run asks again on return.
     */
    var onboardingRan = false
    /** The scan whose auto-resolved toast was already shown (scans are new objects each time). */
    var toastedScan: ScanResult? = null

    private var onboarding: OnboardingController? = null

    /**
     * The one onboarding queue, outliving home's composition: a rotation, or leaving home while
     * the picker is up, keeps the step in flight, so the picker's result still marks it seen.
     */
    fun onboarding(records: Records): OnboardingController =
        onboarding?.takeIf { it.records === records } ?: OnboardingController(records).also { onboarding = it }

    /** Tests only. */
    fun reset() {
        showAll = false; lastSmallHome = null; folderSeen = false; onboardingRan = false; toastedScan = null
        onboarding = null
    }
}
