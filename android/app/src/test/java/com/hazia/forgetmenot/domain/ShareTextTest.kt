package com.hazia.forgetmenot.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

// Expected strings printed by the shipped www/js/lib/text.js under Node (Date.now pinned to
// 2026-10-20T12:34:56.789Z, Math.random to 0.5), so the Kotlin port cannot drift from what
// contact and calendar apps already accepted.
class ShareTextTest {
    private val vcards = listOf(
        "nameOnly" to ("{\"name\":\"Ana\"}" to "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Ana\r\nEND:VCARD"),
        "phoneAndEmail" to ("{\"name\":\"Bo Lindqvist\",\"phone\":\"+46 70 123 45 67\",\"email\":\"bo@example.com\"}" to "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Bo Lindqvist\r\nTEL;TYPE=CELL:+46 70 123 45 67\r\nEMAIL:bo@example.com\r\nEND:VCARD"),
        "birthdayWithYear" to ("{\"name\":\"Cy\",\"birthday\":\"1990-03-05\"}" to "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Cy\r\nBDAY:19900305\r\nEND:VCARD"),
        "birthdayNoYear" to ("{\"name\":\"Di\",\"birthday\":\"1900-12-24\"}" to "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Di\r\nBDAY:--1224\r\nEND:VCARD"),
        "escaped" to ("{\"name\":\"Smith, Jo; \\\"J\\\\R\\\"\\nline2\\r\\nline3\\rline4\",\"phone\":\"1;2,3\",\"email\":\"a,b@x.y\"}" to "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Smith\\, Jo\\; \"J\\\\R\"\\nline2\\nline3\\nline4\r\nTEL;TYPE=CELL:1\\;2\\,3\r\nEMAIL:a\\,b@x.y\r\nEND:VCARD"),
        "unicode" to ("{\"name\":\"Zoë Martin ✿\",\"phone\":null,\"email\":\"\",\"birthday\":null}" to "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Zoë Martin ✿\r\nEND:VCARD"),
    )

    @Test fun vCardMatchesJs() {
        for ((case, io) in vcards) {
            val friend = DomainJson.decodeFromString(Friend.serializer(), io.first)
            assertEquals(case, io.second, buildVCard(friend))
        }
    }

    private val now = Instant.parse("2026-10-20T12:34:56.789Z")
    private val start = Instant.parse("2026-10-27T16:00:00Z")
    private val end = start.plusSeconds(3600)
    private val jsUid = "mvgo1dw5i"

    @Test fun icsMatchesJs() {
        assertEquals(
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//forgetmenot//EN\r\nBEGIN:VEVENT\r\nUID:mvgo1dw5i@forgetmenot\r\nDTSTAMP:20261020T123456Z\r\nDTSTART:20261027T160000Z\r\nDTEND:20261027T170000Z\r\nSUMMARY:Time with Ana María\r\nEND:VEVENT\r\nEND:VCALENDAR",
            buildICS(Friend(name = "Ana María"), start, end, uid = jsUid, now = now),
        )
        assertEquals(
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//forgetmenot//EN\r\nBEGIN:VEVENT\r\nUID:mvgo1dw5i@forgetmenot\r\nDTSTAMP:20261020T123456Z\r\nDTSTART:20261027T160000Z\r\nDTEND:20261027T170000Z\r\nSUMMARY:Time with Jo\\, the\\; \\\\boss\\\\\\nx\r\nEND:VEVENT\r\nEND:VCALENDAR",
            buildICS(Friend(name = "Jo, the; \\boss\\\nx"), start, end, uid = jsUid, now = now),
        )
    }

    @Test fun icsDefaultsToAFreshUidAndTheClock() {
        val ics = buildICS(Friend(name = "Ana"), start, end)
        assertTrue(ics, Regex("UID:[a-z0-9]+@forgetmenot\r\nDTSTAMP:\\d{8}T\\d{6}Z\r\n").containsMatchIn(ics))
    }

    @Test fun icsDropsMilliseconds() {
        val ics = buildICS(Friend(name = "A"), start.plusMillis(999), end, uid = "u", now = now)
        assertTrue(ics, ics.contains("DTSTART:20261027T160000Z\r\n"))
    }
}
