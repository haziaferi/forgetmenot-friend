package com.hazia.forgetmenot.reminders

import com.hazia.forgetmenot.domain.Contact
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.domain.FriendDate
import com.hazia.forgetmenot.domain.Gift
import com.hazia.forgetmenot.domain.Group
import com.hazia.forgetmenot.domain.GroupEvent
import com.hazia.forgetmenot.domain.MAX_SCHEDULED_NOTIFICATIONS
import com.hazia.forgetmenot.domain.Recurring
import com.hazia.forgetmenot.domain.Settings
import com.hazia.forgetmenot.domain.hashToId
import com.hazia.forgetmenot.domain.nextCheckIn
import com.hazia.forgetmenot.domain.nextYearlyOccurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** buildReminders() of www/js/features/notifications.js: the five kinds, 09:00, ids, cap (SPEC §4). */
class ReminderPlanTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val now = ZonedDateTime.of(2026, 10, 18, 12, 0, 0, 0, zone)
    private val created = now.minusDays(200).toInstant().toEpochMilli()

    private fun plan(friends: List<Friend>, groups: List<Group> = emptyList(), settings: Settings = Settings()) =
        planReminders(settings, friends, groups, now)

    private fun loggedOn(day: String) = listOf(Contact(id = "c", date = "${day}T10:00:00.000Z"))
    private fun at(r: Reminder): ZonedDateTime = Instant.ofEpochMilli(r.atMs).atZone(zone)

    @Test fun aSingleCheckInIsTheDomainsNextCheckInAtNineWithTwoButtons() {
        val ana = Friend(id = "f1", name = "Ana", cadenceDays = 30, contacts = loggedOn("2026-10-01"), createdAt = created)
        val r = plan(listOf(ana)).single()
        assertEquals(ReminderKind.CHECKIN, r.kind)
        assertEquals(hashToId("checkin:f1"), r.id)
        assertEquals(nextCheckIn(ana, now)!!.toInstant().toEpochMilli(), r.atMs)
        assertEquals(9, at(r).hour); assertEquals(0, at(r).minute)
        assertEquals("Thinking of Ana?", r.title)
        assertEquals("It’s been a little while — maybe say hi.", r.body)
        assertEquals(listOf(ReminderAction.LOGGED, ReminderAction.LATER), r.actions)
        assertEquals(listOf("f1"), r.friendIds)
        assertEquals("f1", r.openFriendId)
    }

    @Test fun somethingToAskAboutReplacesTheGenericLine() {
        val ana = Friend(id = "f1", name = "Ana", askNext = "the new flat", createdAt = created)
        assertEquals("Maybe ask about the new flat.", plan(listOf(ana)).single().body)
    }

    @Test fun checkInsDueTheSameMorningShareOneNotificationWithOnlyNextWeek() {
        // Cadence 7: no spread, so both land on the same 09:00.
        val ana = Friend(id = "f1", name = "Ana", cadenceDays = 7, createdAt = created)
        val bo = Friend(id = "f2", name = "Bo", cadenceDays = 7, createdAt = created)
        val r = plan(listOf(ana, bo)).single()
        assertEquals(ReminderKind.CHECKINS, r.kind)
        assertEquals(hashToId("checkins:" + r.atMs), r.id)
        assertEquals("A few people you might think of", r.title)
        assertEquals("Ana and Bo. Maybe say hi.", r.body)
        assertEquals(listOf(ReminderAction.LATER), r.actions)
        assertEquals(listOf("f1", "f2"), r.friendIds)
        assertNull("a grouped nudge opens home", r.openFriendId)
    }

    @Test fun pausingDropsTheCheckInButKeepsBirthdayAndAnniversary() {
        val ana = Friend(id = "f1", name = "Ana", paused = true, birthday = "1990-03-14", anniversary = "2015-06-20",
            gifts = listOf(Gift(id = "g", text = "a book")), createdAt = created)
        val byKind = plan(listOf(ana)).associateBy { it.kind }
        assertEquals(setOf(ReminderKind.BIRTHDAY, ReminderKind.ANNIVERSARY), byKind.keys)
        val b = byKind.getValue(ReminderKind.BIRTHDAY)
        assertEquals(hashToId("birthday:f1"), b.id)
        assertEquals("🎂 Ana’s birthday", b.title)
        assertEquals("Today’s the day. Gift ideas: a book.", b.body)
        assertEquals(listOf(ReminderAction.WISHED), b.actions)
        assertEquals(nextYearlyOccurrence("1990-03-14", now)!!.toInstant().toEpochMilli(), b.atMs)
        val a = byKind.getValue(ReminderKind.ANNIVERSARY)
        assertEquals(hashToId("anniversary:f1"), a.id)
        assertEquals("💞 Anniversary with Ana", a.title)
        assertEquals("Today marks it — worth a message.", a.body)
        assertEquals(emptyList<ReminderAction>(), a.actions)
        assertNull("the JS anniversary carried no friend, so its tap opens home", a.openFriendId)
    }

    @Test fun aFriendsOwnDatesRecurOrFireOnceAndAPastOneOffIsSkipped() {
        val ana = Friend(id = "f1", name = "Ana", paused = true, createdAt = created, dates = listOf(
            FriendDate(id = "d1", label = "Moved in", dateISO = "2020-11-02", yearly = true),
            FriendDate(id = "d2", label = "", dateISO = "2026-12-01"),
            FriendDate(id = "d3", label = "Interview", dateISO = "2026-10-01"),
        ))
        val rs = plan(listOf(ana)).sortedBy { it.atMs }
        assertEquals(listOf(hashToId("date:f1:d1"), hashToId("date:f1:d2")), rs.map { it.id })
        assertEquals(ZonedDateTime.of(2026, 11, 2, 9, 0, 0, 0, zone), at(rs[0]))
        assertEquals("📅 Moved in · Ana", rs[0].title)
        assertEquals("📅 A date · Ana", rs[1].title)
        assertEquals(ZonedDateTime.of(2026, 12, 1, 9, 0, 0, 0, zone), at(rs[1]))
        assertEquals("Today’s the day — maybe check in.", rs[1].body)
        assertTrue(rs.all { it.actions.isEmpty() && it.openFriendId == "f1" })
    }

    @Test fun groupEventsNameLiveMembersAndSkipAPastOneOff() {
        val ana = Friend(id = "f1", name = "Ana", paused = true, createdAt = created)
        val bo = Friend(id = "f2", name = "Bo", paused = true, createdAt = created)
        val cy = Friend(id = "f3", name = "Cy", paused = true, createdAt = created)
        val gone = Friend(id = "f4", name = "Gone", archived = true, createdAt = created)
        val book = Group(id = "g1", name = "Book club", memberIds = listOf("f1", "f4", "f2"), events = listOf(
            GroupEvent(id = "e1", title = "Meetup", dateISO = "2026-01-05", recurring = Recurring.MONTHLY),
            GroupEvent(id = "e2", title = "Old", dateISO = "2026-10-17", recurring = Recurring.ONCE),
        ))
        val big = Group(id = "g2", name = "", memberIds = listOf("f1", "f2", "f3"),
            events = listOf(GroupEvent(id = "e3", title = "Trip", dateISO = "2026-12-24")))
        val empty = Group(id = "g3", name = "Solo", memberIds = listOf("nobody"),
            events = listOf(GroupEvent(id = "e4", title = "Me time", dateISO = "2027-01-01", recurring = Recurring.YEARLY)))
        val rs = plan(listOf(ana, bo, cy, gone), listOf(book, big, empty)).associateBy { it.id }
        assertEquals(3, rs.size)
        val meetup = rs.getValue(hashToId("gevent:g1:e1"))
        assertEquals(ReminderKind.GROUP_EVENT, meetup.kind)
        assertEquals("🎉 Meetup", meetup.title)
        assertEquals("With Ana & Bo (Book club)", meetup.body)
        assertEquals(ZonedDateTime.of(2026, 11, 5, 9, 0, 0, 0, zone), at(meetup))
        assertEquals("With Ana, Bo + 1 more", rs.getValue(hashToId("gevent:g2:e3")).body)
        assertEquals("Today’s the day.", rs.getValue(hashToId("gevent:g3:e4")).body)
    }

    @Test fun archivedFriendsAreOutOfEveryKind() {
        val gone = Friend(id = "f1", name = "Gone", archived = true, birthday = "1990-03-14", createdAt = created)
        assertEquals(emptyList<Reminder>(), plan(listOf(gone)))
    }

    @Test fun quietModeSchedulesNothing() {
        val ana = Friend(id = "f1", name = "Ana", birthday = "1990-03-14", createdAt = created)
        assertEquals(emptyList<Reminder>(), plan(listOf(ana), settings = Settings(quietMode = true)))
    }

    @Test fun overTheCapTheSoonestFourHundredAreKept() {
        val start = LocalDate.of(2027, 1, 1)
        val friends = (0 until 450).map { i ->
            Friend(id = "f$i", name = "F$i", paused = true, birthday = start.plusDays((i * 7L) % 365).toString(), createdAt = created)
        }
        val all = buildReminders(friends, emptyList(), now, cap = Int.MAX_VALUE)
        val kept = plan(friends)
        assertEquals(MAX_SCHEDULED_NOTIFICATIONS, kept.size)
        val cutoff = all.map { it.atMs }.sorted()[MAX_SCHEDULED_NOTIFICATIONS - 1]
        assertTrue(kept.all { it.atMs <= cutoff })
        assertEquals(kept.map { it.atMs }.sorted(), kept.map { it.atMs })
    }

    @Test fun idsAreStableAcrossRebuilds() {
        val friends = listOf(
            Friend(id = "f1", name = "Ana", birthday = "1990-03-14", createdAt = created),
            Friend(id = "f2", name = "Bo", cadenceDays = 14, contacts = loggedOn("2026-10-10"), createdAt = created),
        )
        assertEquals(plan(friends), plan(friends))
    }

    @Test fun oneBadRecordSkipsOnlyItsOwnReminder() {
        val bad = Friend(id = "f1", name = "Bad", birthday = "garbage", anniversary = "1990-02-30x",
            dates = listOf(FriendDate(id = "d", dateISO = "not a date")), createdAt = created)
        val ok = Friend(id = "f2", name = "Ok", paused = true, birthday = "1990-03-14", createdAt = created)
        val rs = plan(listOf(bad, ok))
        assertTrue(rs.any { it.id == hashToId("birthday:f2") })
        assertTrue("the bad friend still gets its check-in", rs.any { it.friendIds == listOf("f1") && it.kind == ReminderKind.CHECKIN })
        assertTrue(rs.none { it.id == hashToId("birthday:f1") || it.id == hashToId("date:f1:d") })
    }
}
