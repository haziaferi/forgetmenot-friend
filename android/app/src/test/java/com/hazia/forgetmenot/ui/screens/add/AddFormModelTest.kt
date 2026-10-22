package com.hazia.forgetmenot.ui.screens.add

import com.hazia.forgetmenot.domain.Keys
import com.hazia.forgetmenot.domain.Tag
import com.hazia.forgetmenot.ui.sheets.TestEnv
import com.hazia.forgetmenot.ui.sheets.toastText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

// www/js/screens/add.js without the Compose layer: seedAddForm, leaveAddForm, startAddFormOver,
// renderAddTags' tag and New tag handling, and saveFriendFromAdd.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AddFormModelTest {
    private val env = TestEnv()
    private val now = Instant.parse("2026-10-19T08:30:00Z")
    private fun model(editId: String? = null) = AddFormModel(env.records, editId, now = { now })

    @After fun clearDraft() = AddDrafts.reset()

    // ── adding ───────────────────────────────────────────────────────────────

    @Test fun aNewFriendGetsTheWholeShape() {
        val m = model()
        m.update { it.copy(name = "  Ana  ") }
        val r = m.save()
        assertEquals(SaveResult.Added("Ana"), r)
        val f = env.records.listFriends().single()
        assertEquals("Ana", f.name)
        assertEquals(90, f.cadenceDays)
        assertEquals(emptyList<String>(), f.tags)
        assertFalse(f.paused); assertFalse(f.archived)
        assertTrue(f.contacts.isEmpty()); assertTrue(f.notes.isEmpty())
        assertNull(f.phone); assertNull(f.email); assertNull(f.howWeMet); assertNull(f.birthday)
        assertNull(f.anniversary); assertNull(f.messageChannel); assertNull(f.avatar)
        assertNotNull(f.createdAt)
        assertEquals("Added Ana", toastText())
        assertEquals("one reschedule through records", 1, env.reschedules)
    }

    @Test fun aSecondSaveDoesNotAddTheFriendTwice() {
        val m = model()
        m.update { it.copy(name = "Ana") }
        val first = m.save()
        assertEquals(first, m.save())
        assertEquals(1, env.records.listFriends().size)
        assertEquals(1, env.reschedules)
    }

    @Test fun leavingAfterASaveKeepsNoDraft() {
        val m = model()
        m.update { it.copy(name = "Ana") }
        m.save()
        m.leave() // a back press while the screen animates away
        assertFalse(model().restored)
    }

    @Test fun aRefusedSaveCanBeRetried() {
        val m = model()
        assertEquals(SaveResult.NeedsName, m.save())
        m.update { it.copy(name = "Bea") }
        assertEquals(SaveResult.Added("Bea"), m.save())
    }

    @Test fun aBlankNameSavesNothing() {
        val m = model()
        m.update { it.copy(name = "   ", phone = "123") }
        assertEquals(SaveResult.NeedsName, m.save())
        assertTrue(env.records.listFriends().isEmpty())
        assertEquals("Give them a name first", toastText())
        assertEquals(0, env.reschedules)
    }

    @Test fun optionalTextIsTrimmedAndBlankBecomesNull() {
        val m = model()
        m.update { it.copy(name = "Bo", phone = " 555 12 ", email = "  ", howWeMet = " school ", birthday = "1990-04-02", anniversary = "") }
        m.save()
        val f = env.records.listFriends().single()
        assertEquals("555 12", f.phone)
        assertNull(f.email)
        assertEquals("school", f.howWeMet)
        assertEquals("1990-04-02", f.birthday)
        assertNull(f.anniversary)
    }

    @Test fun aFirstNoteIsANoteNotAContact() {
        val m = model()
        m.update { it.copy(name = "Cy", note = "  Met at the climbing gym ") }
        m.save()
        val f = env.records.listFriends().single()
        assertTrue(f.contacts.isEmpty())
        assertEquals("Met at the climbing gym", f.notes.single().text)
        assertEquals("2026-10-19T08:30:00.000Z", f.notes.single().date)
    }

    @Test fun messageChannelTogglesOffWhenPickedAgain() {
        val m = model()
        m.toggleChannel("whatsapp")
        assertEquals("whatsapp", m.form.channel)
        m.toggleChannel("signal")
        assertEquals("signal", m.form.channel)
        m.toggleChannel("signal")
        assertNull(m.form.channel)
    }

    // ── tags and cadence ─────────────────────────────────────────────────────

    @Test fun pickingATagCopiesItsCadenceAndDroppingItKeepsTheCadence() {
        env.records.saveTag(Tag("t1", "Family", 30))
        val m = model()
        m.toggleTag("t1")
        assertEquals(listOf("t1"), m.form.tags)
        assertEquals(30, m.form.cadence)
        m.toggleTag("t1")
        assertEquals(emptyList<String>(), m.form.tags)
        assertEquals(30, m.form.cadence)
    }

    @Test fun aTagDeletedWhileTheFormIsOpenIsDroppedOnSave() {
        env.records.saveTag(Tag("t1", "Family", 30))
        env.records.saveTag(Tag("t2", "Work", 60))
        val m = model()
        m.toggleTag("t1"); m.toggleTag("t2")
        env.records.deleteTag("t1")
        m.update { it.copy(name = "Di") }
        m.save()
        assertEquals(listOf("t2"), env.records.listFriends().single().tags)
    }

    @Test fun aNewTagTakesTheFormsCadenceAndIsSelected() {
        val m = model()
        m.setCadence(14)
        m.addTag("Climbing")
        val t = env.records.listTags().single()
        assertEquals("Climbing", t.name)
        assertEquals(14, t.cadenceDays)
        assertEquals(listOf(t.id), m.form.tags)
    }

    @Test fun aPickedCadenceIsAppliedOnlyOnSave() {
        val m = model()
        m.setCadence(182)
        assertTrue(env.records.listFriends().isEmpty())
        m.update { it.copy(name = "Ed") }
        m.save()
        assertEquals(182, env.records.listFriends().single().cadenceDays)
    }

    // ── editing ──────────────────────────────────────────────────────────────

    private fun seedFriend() {
        env.store.write(Keys.friend("x1"), kotlinx.serialization.json.Json.parseToJsonElement(
            """{"id":"x1","name":"Flo","tags":[],"cadenceDays":30,"paused":true,"askNext":"the move",
               "phone":"1","email":"flo@example.org","birthday":"1900-05-06","messageChannel":"sms",
               "contacts":[{"id":"c1","date":"2026-10-01T10:00:00.000Z"}],"notes":[{"id":"n1","date":"2026-09-01T10:00:00.000Z","text":"hi"}],
               "gifts":[{"id":"g1","text":"book","addedAt":1}],"createdAt":5,"updatedAt":6,"futureField":{"keep":true}}"""))
        env.records.saveFriend(env.records.getFriend("x1")!!) // puts it in the index
        env.reschedules = 0
    }

    @Test fun editingSeedsTheFormFromTheFriend() {
        seedFriend()
        val m = model("x1")
        assertTrue(m.editing)
        assertEquals("Flo", m.form.name)
        assertEquals("flo@example.org", m.form.email)
        assertEquals("1900-05-06", m.form.birthday)
        assertEquals("sms", m.form.channel)
        assertEquals(30, m.form.cadence)
        assertFalse(m.restored)
    }

    @Test fun savingAnEditKeepsEverythingTheFormDoesNotShow() {
        seedFriend()
        val m = model("x1")
        m.update { it.copy(name = "Flora", phone = "") }
        assertEquals(SaveResult.Saved("x1"), m.save())
        val f = env.records.getFriend("x1")!!
        assertEquals("Flora", f.name)
        assertNull(f.phone)
        assertEquals("the move", f.askNext)
        assertTrue(f.paused)
        assertEquals(1, f.contacts.size); assertEquals(1, f.notes.size); assertEquals(1, f.gifts.size)
        assertEquals(5L, f.createdAt)
        val raw = env.store.read(Keys.friend("x1")) as JsonObject
        assertEquals("true", (raw["futureField"] as JsonObject)["keep"]!!.jsonPrimitive.content)
        assertEquals("Saved", toastText())
        assertEquals(1, env.reschedules)
    }

    @Test fun anUnknownEditIdIsReportedMissing() {
        assertTrue(model("nobody").friendMissing)
        assertFalse(model().friendMissing)
    }

    // ── the left draft ───────────────────────────────────────────────────────

    @Test fun anUntouchedFormLeavesNoDraft() {
        model().leave()
        val again = model()
        assertFalse(again.restored)
        assertEquals("", again.form.name)
    }

    @Test fun aChangedFormIsPickedUpWhereItWasLeft() {
        env.records.saveTag(Tag("t1", "Family", 30))
        val m = model()
        m.update { it.copy(name = "Gu", note = "tall") }
        m.toggleTag("t1")
        m.leave()
        val again = model()
        assertTrue(again.restored)
        assertEquals("Gu", again.form.name)
        assertEquals("tall", again.form.note)
        assertEquals(30, again.form.cadence)
        assertEquals(listOf("t1"), again.form.tags)
        assertEquals("Picked up where you left off", toastText())
    }

    @Test fun startOverEmptiesTheForm() {
        val m = model()
        m.update { it.copy(name = "Ha") }
        m.leave()
        val again = model()
        again.startOver()
        assertFalse(again.restored)
        assertEquals("", again.form.name)
        assertFalse(model().restored)
    }

    @Test fun aNewFriendsDraftIsNotOfferedWhenEditingAndIsThenGone() {
        seedFriend()
        val m = model()
        m.update { it.copy(name = "Io") }
        m.leave()
        val edit = model("x1")
        assertFalse(edit.restored)
        assertEquals("Flo", edit.form.name)
        // seedAddForm drops a draft for another key, as the JS did: one draft at a time.
        assertFalse(model().restored)
    }

    @Test fun anEditDraftComesBackOnlyForThatFriend() {
        seedFriend()
        val m = model("x1")
        m.update { it.copy(howWeMet = "uni") }
        m.leave()
        val again = model("x1")
        assertTrue(again.restored)
        assertEquals("uni", again.form.howWeMet)
    }

    @Test fun savingClearsTheDraft() {
        val m = model()
        m.update { it.copy(name = "Jo") }
        m.leave()
        val again = model()
        again.save()
        assertFalse(model().restored)
    }

    // ── I6 ───────────────────────────────────────────────────────────────────

    @Test fun aReadOnlySessionKeepsTheSaveInMemoryOnly() = runBlocking {
        env.store.flushWrites()
        env.fs.calls.clear()
        env.store.blockWrites()
        val m = model()
        m.update { it.copy(name = "Ky") }
        m.addTag("New")
        m.save()
        env.store.flushWrites()
        assertEquals("Ky", env.records.listFriends().single().name)
        assertEquals(emptyList<Pair<String, String>>(), env.fs.calls.toList())
        assertEquals("no reschedule in a read-only session", 0, env.reschedules)
    }

    // ── contacts (P6 fills it) ───────────────────────────────────────────────

    @Test fun aPickedContactFillsTheFields() {
        val m = model()
        m.update { it.copy(phone = "old", email = "old@x") }
        m.applyContact(PickedContact(name = "Lu", phone = "777", email = null, birthday = "1900-01-02", avatar = "data:image/jpeg;base64,AA"))
        assertEquals("Lu", m.form.name)
        assertEquals("777", m.form.phone)
        assertEquals("", m.form.email)
        assertEquals("1900-01-02", m.form.birthday)
        assertEquals("data:image/jpeg;base64,AA", m.form.avatar)
        assertEquals("Imported Lu", toastText())
        m.applyContact(PickedContact(name = "", avatar = null))
        assertEquals("data:image/jpeg;base64,AA", m.form.avatar) // no photo leaves the one there
        assertEquals("Imported contact", toastText())
    }

    @Test fun theAvatarIsSavedAndCanBeRemoved() {
        val m = model()
        m.update { it.copy(name = "Mo", avatar = "data:image/jpeg;base64,AA") }
        m.save()
        assertEquals("data:image/jpeg;base64,AA", env.records.listFriends().single().avatar)
        val id = env.records.listFriends().single().id
        val e = model(id)
        e.update { it.copy(avatar = null) }
        e.save()
        assertNull(env.records.getFriend(id)!!.avatar)
    }
}
