package com.hazia.forgetmenot.domain

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

// Port of tools/lib.test.mjs: classification, escaping, id and avatar checks, backup
// validation, local dates, merging. One @Test per JS test(), in the same order.
class LibTest {
    private fun daysAgo(n: Long): String = isoTimestamp(ZonedDateTime.now().minusDays(n).toInstant())
    private fun c(date: String, id: String = "") = Contact(id = id, date = date)

    @Test fun classifyANeverLoggedFriendIsNewNotOverdue() {
        assertEquals(Classification(0, 0.0, Section.RECENT, "new"), classify(Friend(cadenceDays = 30)))
    }

    @Test fun classifySectionsSplitAtAThirdOfTheCadenceAndAtTheCadence() {
        fun at(n: Long) = classify(Friend(cadenceDays = 30, contacts = listOf(c(daysAgo(n)))))
        assertEquals(Section.RECENT, at(5).section)
        assertEquals(Section.COMING, at(10).section)
        assertEquals(Section.THINKING, at(30).section)
        assertEquals("pct is capped, since it is the ring fill", 1.0, at(90).pct, 0.0)
        assertEquals(Section.RECENT, classify(Friend(cadenceDays = 30, paused = true, contacts = listOf(c(daysAgo(90))))).section)
    }

    @Test fun classifyAFriendWithNoCadenceIsJudgedOnThe90DayDefaultNotAsOverdue() {
        assertEquals(90, cadenceOf(Friend()))
        // A non-numeric cadence decodes to null (JS Number('x') is NaN), so it falls back too.
        assertEquals(90, cadenceOf(DomainJson.decodeFromString(Friend.serializer(), """{"id":"f","cadenceDays":"x"}""")))
        assertEquals(14, cadenceOf(Friend(cadenceDays = 14)))
        val f = Friend(contacts = listOf(c(daysAgo(10))))
        assertEquals(Section.RECENT, classify(f).section)
        assertTrue(classify(f).pct.isFinite())
    }

    @Test fun latestContactPicksTheNewestDateNotTheFirstEntry() {
        val f = Friend(contacts = listOf(c(daysAgo(10)), c(daysAgo(2)), c(daysAgo(5))))
        assertEquals(2, daysSince(latestContact(f)!!.date))
    }

    @Test fun hashToIdIsStableAndInThePluginIdRange() {
        assertEquals(hashToId("checkin:abc"), hashToId("checkin:abc"))
        for (s in listOf("", "x", "checkin:" + "z".repeat(500))) {
            val h = hashToId(s)
            assertTrue(h in 0 until 2_000_000_000)
        }
    }

    @Test fun escCoversTextAndQuotedAttributeContexts() {
        assertEquals("&lt;a href=&quot;x&quot; title=&#39;y&#39;&gt;&amp;&lt;/a&gt;", esc("<a href=\"x\" title='y'>&</a>"))
        assertEquals("", esc(null))
    }

    @Test fun isValidIdRejectsAnythingThatCouldSteerAPath() {
        assertTrue(isValidId("abc123XYZ"))
        for (bad in listOf<Any?>("", "../x", "a/b", "a.b", "a".repeat(41), 7, null)) assertFalse(bad.toString(), isValidId(bad))
    }

    @Test fun isValidAvatarAcceptsOnlyBase64PngJpegDataUris() {
        assertTrue(isValidAvatar("data:image/jpeg;base64,AAAA"))
        assertFalse(isValidAvatar("data:image/svg+xml;base64,AAAA"))
        assertFalse(isValidAvatar("file:avatars/x.b64"))
    }

    @Test fun birthdayToIsoUses1900ForAnUnknownYear() {
        assertEquals("1900-02-09", birthdayToISO(month = 2, day = 9))
        assertNull(birthdayToISO(month = 2, day = null))
    }

    @Test fun localDateIsoIsTheLocalCalendarDay() {
        val d = ZonedDateTime.of(2027, 1, 5, 23, 59, 0, 0, ZoneId.of("Europe/Rome"))
        assertEquals("2027-01-05", localDateISO(d))
    }

    @Test fun aMonthlyOrYearlySeriesThatHasNotStartedYetReturnsItsStartDate() {
        val now = ZonedDateTime.of(2026, 10, 10, 12, 0, 0, 0, ZoneId.of("Europe/Rome"))
        for (recurring in listOf(Recurring.MONTHLY, Recurring.YEARLY)) {
            val next = computeNextEventDate(GroupEvent(dateISO = "2027-01-15", recurring = recurring), now)!!
            assertEquals(recurring, "2027-01-15", localDateISO(next))
        }
    }

    // data[k] is each record's JSON text, as the backup file carries it.
    private fun backup(vararg recs: Pair<String, String>) = validateBackupData(recs.toMap(), recs.map { it.first })

    @Test fun validateBackupDataAcceptsAWellFormedBackup() {
        val parsed = backup(
            "fm_friend_index" to """["f1"]""",
            "fm_friend:f1" to """{"id":"f1","name":"A","cadenceDays":30,"contacts":[],"notes":[],"tags":[]}""",
            "fm_group_index" to """["g1"]""",
            "fm_group:g1" to """{"id":"g1","memberIds":["f1"],"events":[{"id":"e","dateISO":"2027-01-01","recurring":"once"}]}""",
            "fm_settings" to """{"skin":"nope","mode":"dim"}""",
        )
        assertNotNull(parsed)
        val s = parsed!!.getValue("fm_settings").jsonObject
        assertEquals("blue", s["skin"]!!.jsonPrimitive.content)
        assertEquals("light", s["mode"]!!.jsonPrimitive.content)
    }

    @Test fun validateBackupDataSkipsNullRecordsFromOlderExportsInsteadOfRefusingTheFile() {
        val parsed = backup("fm_friend_index" to """["f1","f2"]""", "fm_friend:f1" to """{"id":"f1"}""", "fm_friend:f2" to "null")
        assertNotNull(parsed)
        assertFalse("fm_friend:f2" in parsed!!)
    }

    @Test fun validateBackupDataRejectsMalformedRecords() {
        val bad = listOf(
            arrayOf("fm_friend:f1" to """{"id":"other"}"""),
            arrayOf("fm_friend:f1" to """{"id":"f1","contacts":"x"}"""),
            arrayOf("fm_friend:f1" to """{"id":"f1","cadenceDays":0}"""),
            // JS { dateISO: undefined } stringifies to an event with no dateISO at all.
            arrayOf("fm_group:g1" to """{"id":"g1","events":[{}]}"""),
            arrayOf("fm_friend:../x" to """{"id":"../x"}"""),
            arrayOf("fm_friend_index" to """["ok","../x"]"""),
        )
        for (recs in bad) assertNull(recs.joinToString(), backup(*recs))
    }

    @Test fun validateBackupDataDropsAMalformedPauseOrSnoozeDateButKeepsTheFriend() {
        val parsed = backup(
            "fm_friend_index" to """["f1"]""",
            "fm_friend:f1" to """{"id":"f1","pausedUntil":"next week","snoozedUntil":"2026-11-02"}""",
        )!!
        val f = parsed.getValue("fm_friend:f1").jsonObject
        assertEquals(JsonNull, f["pausedUntil"])
        assertEquals("2026-11-02", f["snoozedUntil"]!!.jsonPrimitive.content)
    }

    @Test fun foldTextMatchesAcrossCaseAndAccents() {
        assertEquals("zoe", foldText("Zoë"))
        assertEquals("elodie bronte", foldText("ÉLODIE Brontë"))
        assertEquals("", foldText(null))
        assertTrue(foldText("Chloé, book club").contains(foldText("CHLOE")))
    }

    @Test fun mergeFriendLogsKeepsBothSidesEntriesTheWinnersFieldsAndAddsNothingTwice() {
        val a = Friend(id = "f", name = "Ana (phone A)", contacts = listOf(c("2026-10-01", "c1")))
        val b = Friend(
            id = "f", name = "Ana (phone B)", contacts = listOf(c("2026-10-01", "c1"), c("2026-10-03", "c2")),
            notes = listOf(Note(id = "n1", text = "moving")), dates = listOf(FriendDate(id = "d1", label = "Interview", dateISO = "2026-11-02")),
        )
        val m = mergeFriendLogs(a, b)
        assertEquals("Ana (phone A)", m.name)
        assertEquals(listOf("c1", "c2"), m.contacts.map { it.id })
        assertEquals(listOf("n1"), m.notes.map { it.id })
        assertEquals(listOf("d1"), m.dates.map { it.id })
        assertTrue(logsDiffer(m, a))
        assertFalse(logsDiffer(mergeFriendLogs(b, a), b))
        assertEquals(1, mergeFriendLogs(a, null).contacts.size)
        assertTrue(mergeFriendLogs(a, Friend(id = "f")).dates.isEmpty())
    }

    @Test fun validateBackupDataDropsMalformedNewFriendFieldsButKeepsTheFriend() {
        val parsed = backup(
            "fm_friend_index" to """["f1"]""",
            "fm_friend:f1" to """{"id":"f1","askNext":42,"howWeMet":"Uni","dates":[{"id":"d1","label":"x","dateISO":"2026-11-02"},{"id":"d2","dateISO":"soon"}]}""",
        )!!
        val f = parsed.getValue("fm_friend:f1").jsonObject
        assertEquals(JsonNull, f["askNext"])
        assertEquals("Uni", f["howWeMet"]!!.jsonPrimitive.content)
        assertEquals(listOf("d1"), f["dates"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
    }

    @Test fun addContactKeepsAKnownKindAndIgnoresAnythingElse() {
        var f = Friend()
        val (f1, e1) = addContact(f, daysAgo(0), "meet"); f = f1
        assertEquals("meet", e1.kind)
        val (f2, e2) = addContact(f, daysAgo(0)); f = f2
        assertNull(e2.kind)
        val (f3, e3) = addContact(f, daysAgo(0), "toString"); f = f3
        assertNull("own keys only", e3.kind)
        assertEquals(3, f.contacts.size)
        assertEquals("newest first, as JS unshift", e3.id, f.contacts.first().id)
    }

    @Test fun validateBackupDataDropsAnUnknownKindButKeepsTheReachOut() {
        val parsed = backup(
            "fm_friend_index" to """["f1"]""",
            "fm_friend:f1" to """{"id":"f1","contacts":[{"id":"c1","date":"${daysAgo(1)}","kind":"call"},{"id":"c2","date":"${daysAgo(2)}","kind":"pigeon"}]}""",
        )!!
        val cs = parsed.getValue("fm_friend:f1").jsonObject["contacts"]!!.jsonArray.map { it.jsonObject }
        assertEquals("call", cs[0]["kind"]!!.jsonPrimitive.content)
        assertEquals(2, cs.size)
        assertFalse("kind" in cs[1])
    }

    @Test fun giftIdeasMergeByIdAndMalformedOnesAreDroppedFromABackup() {
        val m = mergeFriendLogs(Friend(id = "f", gifts = listOf(Gift(id = "g1", text = "a"))), Friend(id = "f", gifts = listOf(Gift(id = "g2", text = "b"))))
        assertEquals(listOf("g1", "g2"), m.gifts.map { it.id })
        val parsed = backup(
            "fm_friend_index" to """["f1"]""",
            "fm_friend:f1" to """{"id":"f1","gifts":[{"id":"g1","text":"Book"},{"id":"g2","text":"  "},{"id":"g3","text":7},null]}""",
        )!!
        assertEquals(listOf("g1"), (parsed.getValue("fm_friend:f1") as JsonObject)["gifts"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertNull(backup("fm_friend_index" to """["f1"]""", "fm_friend:f1" to """{"id":"f1","gifts":"x"}"""))
    }
}
