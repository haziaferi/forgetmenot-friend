package com.hazia.forgetmenot.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactRowsTest {
    @Test fun oneEntryPerContactInProviderOrder() {
        val rows = listOf(
            DataRow(7, MIME_NAME, "Bo Lindqvist", "Bo", "Lindqvist"),
            DataRow(3, MIME_NAME, "Ana", "Ana", null),
            DataRow(7, MIME_PHONE, "+46 70 1"),
            DataRow(3, MIME_EMAIL, "ana@example.com"),
        )
        assertEquals(
            listOf(
                ContactFields("Bo Lindqvist", "+46 70 1", null, null),
                ContactFields("Ana", null, "ana@example.com", null),
            ),
            assembleContacts(rows),
        )
    }

    @Test fun nameFallsBackToGivenFamilyThenTheContactName() {
        assertEquals("Cy Ode", assembleContacts(listOf(DataRow(1, MIME_NAME, " ", "Cy", "Ode"))).single().name)
        assertEquals("Di", assembleContacts(listOf(DataRow(1, MIME_NAME, null, null, "Di"))).single().name)
        assertEquals("+1 555", assembleContacts(listOf(DataRow(1, MIME_PHONE, "+1 555", contactName = "+1 555"))).single().name)
        assertEquals("", assembleContacts(listOf(DataRow(1, MIME_EMAIL, "x@y.z"))).single().name)
    }

    @Test fun primaryPhoneAndEmailWinElseTheFirst() {
        val rows = listOf(
            DataRow(1, MIME_NAME, "Ana"),
            DataRow(1, MIME_PHONE, "111"),
            DataRow(1, MIME_PHONE, "222", isPrimary = true),
            DataRow(1, MIME_EMAIL, ""),
            DataRow(1, MIME_EMAIL, "first@x.y"),
            DataRow(1, MIME_EMAIL, "second@x.y"),
        )
        val c = assembleContacts(rows).single()
        assertEquals("222", c.phone)
        assertEquals("first@x.y", c.email)
    }

    @Test fun onlyBirthdayEventsCount() {
        val rows = listOf(
            DataRow(1, MIME_NAME, "Ana"),
            DataRow(1, MIME_EVENT, "2010-06-01", "1"), // anniversary
            DataRow(1, MIME_EVENT, "garbage", "3"),
            DataRow(1, MIME_EVENT, "--03-05", "3"),
        )
        assertEquals("1900-03-05", assembleContacts(rows).single().birthday)
    }

    @Test fun birthdayFormats() {
        assertEquals("1990-03-05", parseContactBirthday("1990-03-05"))
        assertEquals("1990-03-05", parseContactBirthday("19900305"))
        assertEquals("1990-03-05", parseContactBirthday("1990-03-05T00:00:00Z"))
        assertEquals("1900-12-24", parseContactBirthday("--12-24"))
        assertEquals("1900-12-24", parseContactBirthday("--1224"))
        assertNull(parseContactBirthday("1990-13-05"))
        assertNull(parseContactBirthday("1990-02-00"))
        assertNull(parseContactBirthday("5 March"))
        assertNull(parseContactBirthday("199003051"))
        assertNull(parseContactBirthday(null))
    }
}
