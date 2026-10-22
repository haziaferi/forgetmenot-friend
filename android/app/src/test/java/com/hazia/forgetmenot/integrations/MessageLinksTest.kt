package com.hazia.forgetmenot.integrations

import com.hazia.forgetmenot.domain.Friend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class MessageLinksTest {
    private val both = Friend(id = "a", name = "Ana María", phone = "+39 333 12-34 567", email = "ana@example.com")

    @Test fun everyChannelBuildsTheJsLink() {
        assertEquals("sms:+39 333 12-34 567", channelUrl("sms", both))
        assertEquals("https://wa.me/393331234567", channelUrl("whatsapp", both))
        assertEquals("https://t.me/+393331234567", channelUrl("telegram", both))
        assertEquals("https://signal.me/#p/+393331234567", channelUrl("signal", both))
        assertEquals("mailto:ana@example.com", channelUrl("email", both))
        assertNull(channelUrl("carrier-pigeon", both))
    }

    @Test fun aChannelWithoutItsFieldGivesNothing() {
        val noPhone = both.copy(phone = "")
        for (ch in listOf("sms", "whatsapp", "telegram", "signal")) assertNull(ch, channelUrl(ch, noPhone))
        assertNull(channelUrl("email", both.copy(email = null)))
    }

    @Test fun preferredThenSmsThenEmail() {
        assertEquals("https://wa.me/393331234567", messageUrl(both.copy(messageChannel = "whatsapp")))
        assertEquals("sms:+39 333 12-34 567", messageUrl(both))
        // Prefers Signal but has no phone: SMS needs one too, so email.
        assertEquals("mailto:ana@example.com", messageUrl(both.copy(phone = null, messageChannel = "signal")))
        // Prefers email but has none: SMS.
        assertEquals("sms:+39 333 12-34 567", messageUrl(both.copy(email = null, messageChannel = "email")))
        assertNull(messageUrl(both.copy(phone = null, email = null)))
    }

    @Test fun noAddressToastUsesTheFirstName() {
        assertEquals("Add a phone or email for Ana to enable this", noAddressToast(both))
    }

    @Test fun planIsNextWeekAtSixForAnHour() {
        val zone = ZoneId.of("Europe/Rome")
        // 20 Oct 23:30: a week later is 27 Oct, after the 25 Oct DST change (CET, UTC+1).
        val (start, end) = planWindow(ZonedDateTime.of(2026, 10, 20, 23, 30, 0, 0, zone))
        assertEquals(Instant.parse("2026-10-27T17:00:00Z"), start)
        assertEquals(Instant.parse("2026-10-27T18:00:00Z"), end)
        assertEquals("Time with Ana María", planTitle(both))
    }

    @Test fun icsFileNameIsSanitisedThenFallsBack() {
        assertEquals("time-with-Ana Mara.ics", icsFileName("Ana María"))
        assertEquals("time-with-Jo-Bo_2.ics", icsFileName("  Jo-Bo_2 "))
        assertEquals("time-with-etcpasswd.ics", icsFileName("../etc/passwd"))
        assertEquals("time-with-friend.ics", icsFileName("李小龙"))
        assertEquals("time-with-friend.ics", icsFileName(null))
    }
}
