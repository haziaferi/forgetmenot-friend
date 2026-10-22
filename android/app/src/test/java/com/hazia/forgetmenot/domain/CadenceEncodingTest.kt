package com.hazia.forgetmenot.domain

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/** A missing cadence stays missing on disk, so backups holding such a record still validate. */
class CadenceEncodingTest {
    @Test fun aNullCadenceIsLeftOutAndASetOneIsWritten() {
        val none = DomainJson.encodeToJsonElement(Friend.serializer(), Friend(id = "f1", name = "A")) as JsonObject
        assertFalse("cadenceDays" in none)
        val set = DomainJson.encodeToJsonElement(Friend.serializer(), Friend(id = "f1", cadenceDays = 30)) as JsonObject
        assertEquals("30", set["cadenceDays"].toString())
        val tag = DomainJson.encodeToJsonElement(Tag.serializer(), Tag(id = "t1", name = "T")) as JsonObject
        assertFalse("cadenceDays" in tag)
    }

    @Test fun aBackupOfAFriendWithoutCadenceValidates() {
        val text = DomainJson.encodeToString(Friend.serializer(), Friend(id = "f1", name = "A"))
        assertNotNull(validateBackupData(mapOf("fm_friend:f1" to text), listOf("fm_friend:f1")))
    }
}
