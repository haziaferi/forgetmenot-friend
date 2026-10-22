package com.hazia.forgetmenot.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

// Not ported cases: values printed by the shipped JS (node, TZ=Europe/Rome), pinned so the
// Kotlin port cannot drift from what installed phones already scheduled. Notification ids and
// the per-friend spread come from hashToId, so a different hash would move every reminder.
class JsParityTest {
    private val zone: ZoneId = ZoneId.of("Europe/Rome")

    @Test fun hashToIdMatchesJs() {
        assertEquals(0, hashToId(""))
        assertEquals(120, hashToId("x"))
        assertEquals(1391249301, hashToId("checkin:abc"))
        assertEquals(12281112, hashToId("spread:friend-0"))
        assertEquals(11764392, hashToId("birthday:f1"))
        assertEquals("UTF-16 code units, as charCodeAt", 17318035, hashToId("ü€😀"))
        assertEquals(343071315, hashToId("checkin:" + "z".repeat(500)))
    }

    @Test fun spreadMatchesJsAcrossTheMarchDstChange() {
        val created = ZonedDateTime.of(2026, 1, 1, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val now = ZonedDateTime.of(2026, 1, 2, 9, 0, 0, 0, zone)
        val expected = listOf(1, 8, 7, 6, 5, 4) // April day, friend-0..5
        for ((i, day) in expected.withIndex()) {
            val next = nextCheckIn(Friend(id = "friend-$i", cadenceDays = 90, createdAt = created), now)!!
            assertEquals("friend-$i", LocalDate.of(2026, 4, day), next.toLocalDate())
            assertEquals(9, next.hour)
        }
    }

    @Test fun modelsRoundTripSpecFieldNames() {
        val json = """{"id":"f1","name":"Ana","tags":["t1"],"cadenceDays":30,"paused":false,"pausedUntil":null,""" +
            """"askNext":"the move","howWeMet":null,"dates":[{"id":"d1","label":"Interview","dateISO":"2026-11-02","yearly":false}],""" +
            """"snoozedUntil":null,"archived":false,"phone":"+39 123","email":null,"birthday":"1900-02-09","anniversary":null,""" +
            """"messageChannel":"signal","avatar":null,"contacts":[{"id":"c1","date":"2026-10-01T10:00:00.000Z","kind":"call"},""" +
            """{"id":"c2","date":"2026-10-02T10:00:00.000Z"}],"notes":[{"id":"n1","date":"2026-10-01T10:00:00.000Z","text":"hi"}],""" +
            """"gifts":[{"id":"g1","text":"socks","addedAt":"2025-10-09T08:53:20.000Z"}],"createdAt":1750000000000,"updatedAt":1760000000000}"""
        val f = DomainJson.decodeFromString(Friend.serializer(), json)
        assertEquals("signal", f.messageChannel)
        assertNull(f.contacts[1].kind)
        assertEquals(json, DomainJson.encodeToString(Friend.serializer(), f))
        // Unknown fields from a newer build do not break the read.
        assertEquals("f1", DomainJson.decodeFromString(Friend.serializer(), """{"id":"f1","later":1}""").id)
        // A gift's addedAt: ISO text as the JS wrote it, epoch ms as early native builds did, and
        // anything else as null — never a failed decode, which would drop the whole friend.
        fun giftAt(raw: String) = DomainJson.decodeFromString(Friend.serializer(),
            """{"id":"f1","gifts":[{"id":"g1","text":"x","addedAt":$raw}]}""").gifts.single().addedAt
        assertEquals("2026-10-01T12:00:00.000Z", giftAt("\"2026-10-01T12:00:00.000Z\""))
        assertEquals("2025-10-09T08:53:20.000Z", giftAt("1760000000000"))
        assertNull(giftAt("true"))
        assertNull(giftAt("{}"))
        assertNull(giftAt("null"))
        // A backup may carry a quoted cadence, which validateBackupData accepts.
        assertEquals(30, DomainJson.decodeFromString(Friend.serializer(), """{"id":"f1","cadenceDays":"30"}""").cadenceDays)
        assertTrue(uid().let { it.length in 9..20 && isValidId(it) })
    }
}
