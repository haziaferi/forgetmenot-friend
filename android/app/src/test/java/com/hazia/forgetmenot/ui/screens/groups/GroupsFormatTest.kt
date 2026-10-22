package com.hazia.forgetmenot.ui.screens.groups

import com.hazia.forgetmenot.domain.Group
import com.hazia.forgetmenot.domain.GroupEvent
import com.hazia.forgetmenot.domain.Recurring
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

// The text rules of renderGroups()/renderGroupDetail() (www/js/screens/groups.js:15-90), apart
// from any layout. Robolectric because the date format comes from android.text.format.DateFormat.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GroupsFormatTest {
    private val us = Locale.US

    @Test fun eventDateIsShortMonthAndDayInTheLocalesOrder() {
        assertEquals("Oct 25", fmtEventDate(TEST_NOW.withDayOfMonth(25), us))
        assertEquals("25 Oct", fmtEventDate(TEST_NOW.withDayOfMonth(25), Locale.UK))
    }

    @Test fun teaserCountsPeopleAndNamesTheSoonestUpcomingEvent() {
        val g = Group(
            id = "g1", name = "Book club", memberIds = listOf("a", "b"),
            events = listOf(
                GroupEvent("e1", "Picnic", "2026-12-01", Recurring.ONCE),
                GroupEvent("e2", "Meetup", "2026-10-25", Recurring.ONCE),
                GroupEvent("e3", "Old", "2026-01-01", Recurring.ONCE), // a past one-off is never the teaser
            ),
        )
        assertEquals("2 people · Meetup Oct 25", groupTeaser(g, TEST_NOW, us))
        assertEquals("1 person", groupTeaser(Group(id = "g2", name = "x", memberIds = listOf("a")), TEST_NOW, us))
        assertEquals("0 people", groupTeaser(Group(id = "g3", name = "y"), TEST_NOW, us))
    }

    @Test fun eventLineSaysWasForAPastOneOffAndNamesARepeat() {
        assertEquals("Was Oct 1", eventLine(GroupEvent("e", "t", "2026-10-01", Recurring.ONCE), TEST_NOW, us))
        assertEquals("Oct 18", eventLine(GroupEvent("e", "t", "2026-10-18", Recurring.ONCE), TEST_NOW, us)) // today is not "Was"
        assertEquals("Nov 3 · monthly", eventLine(GroupEvent("e", "t", "2026-01-03", Recurring.MONTHLY), TEST_NOW, us))
        assertEquals("Mar 9 · yearly", eventLine(GroupEvent("e", "t", "2020-03-09", Recurring.YEARLY), TEST_NOW, us))
    }

    @Test fun eventsSortByNextDateWithMalformedOnesLast() {
        val events = listOf(
            GroupEvent("bad", "Bad", "someday", Recurring.ONCE),
            GroupEvent("late", "Late", "2026-12-01", Recurring.ONCE),
            GroupEvent("past", "Past", "2026-02-01", Recurring.ONCE),
            GroupEvent("soon", "Soon", "2026-01-20", Recurring.MONTHLY), // next: Oct 20
        )
        assertEquals(listOf("past", "soon", "late", "bad"), sortedEvents(events, TEST_NOW).map { it.id })
    }

    @Test fun groupsSortByNameIgnoringCase() {
        val gs = listOf(Group(id = "1", name = "book club"), Group(id = "2", name = "Alpha"), Group(id = "3", name = "Cousins"))
        assertEquals(listOf("Alpha", "book club", "Cousins"), sortedGroups(gs).map { it.name })
    }
}
