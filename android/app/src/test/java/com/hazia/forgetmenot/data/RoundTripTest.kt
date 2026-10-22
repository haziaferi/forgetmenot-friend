package com.hazia.forgetmenot.data

import com.hazia.forgetmenot.domain.Keys
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A sync folder written by the JS layer (src/test/resources/data/js-folder, made by running
 * www/js/storage through a disk-backed fake) read and written back through a real directory.
 * The app ships as a fresh install but adopts that build's folder, so its files must survive untouched.
 */
class RoundTripTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var root: File
    private lateinit var source: File

    @Before fun copyFixture() {
        source = File(javaClass.classLoader!!.getResource("data/js-folder")!!.toURI())
        root = tmp.newFolder("sync")
        source.copyRecursively(root)
    }

    private fun files(dir: File) = dir.walkTopDown().filter { it.isFile }.associateBy { it.relativeTo(dir).invariantSeparatorsPath }

    @Test fun everyRecordWrittenBackUnchangedIsByteIdentical() = runBlocking {
        val store = CachedStore(FileFolder(root))
        store.load()
        for ((key, value) in store.snapshot()) store.persistKey(key, value)
        val before = files(source)
        val after = files(root)
        assertEquals(before.keys, after.keys)
        for ((path, f) in before) assertArrayEquals(path, f.readBytes(), after.getValue(path).readBytes())
    }

    @Test fun anEditKeepsFieldsTheModelsDoNotKnowAndTheAvatarStaysASidecar() = runBlocking {
        val store = CachedStore(FileFolder(root))
        store.load()
        val records = Records(store)
        val f = records.getFriend("mfr1abc")!!
        assertEquals("data:image/jpeg;base64,/9j/4AAQSkZJRgABAQ==", f.avatar)
        records.saveFriend(f.copy(askNext = "new job"))
        store.flushWrites()
        val text = File(root, "friends/mfr1abc.json").readText()
        assertTrue(text, text.contains("\"futureField\":{\"nested\":[1,2.5,1e+21,-0.000001,true,null],\"s\":\"kept\"}"))
        val saved = parseJson(text).jsonObject
        assertEquals("new job", saved["askNext"]!!.jsonPrimitive.content)
        assertEquals("file:avatars/mfr1abc.b64", saved["avatar"]!!.jsonPrimitive.content)
        assertEquals("/9j/4AAQSkZJRgABAQ==", File(root, "avatars/mfr1abc.b64").readText())
        // Keys keep their places; only the stamp changed.
        assertEquals(parseJson(File(source, "friends/mfr1abc.json").readText()).jsonObject.keys.toList(), saved.keys.toList())
    }

    // friends/mfr3gft.json was written by www/js/storage/records.js with gifts added the way
    // detail.js:207 adds them ({ id, text, addedAt: new Date().toISOString() }). Before
    // JsIsoTimestampSerializer an ISO addedAt failed the decode and the whole friend vanished.
    @Test fun giftsTheJsAddedKeepTheirFriend() = runBlocking {
        val store = CachedStore(FileFolder(root))
        store.load()
        val records = Records(store)
        assertEquals(listOf("mfr1abc", "mfr2xyz", "mfr3gft"), records.listFriends().map { it.id })
        val f = records.getFriend("mfr3gft")!!
        assertEquals(listOf("The book they mentioned", "Concert tickets"), f.gifts.map { it.text })
        assertEquals("2026-10-20T20:14:44.184Z", f.gifts.first().addedAt)
    }

    // mfr1abc's gift carries epoch ms, as native builds before the fix wrote it.
    @Test fun aGiftStampedInMillisecondsStillLoads() = runBlocking {
        val store = CachedStore(FileFolder(root))
        store.load()
        assertEquals("2025-10-09T08:53:20.000Z", Records(store).getFriend("mfr1abc")!!.gifts.single().addedAt)
    }

    @Test fun settingsCarryTheLavenderSkinAndOledMode() = runBlocking {
        val store = CachedStore(FileFolder(root))
        store.load()
        val s = Records(store).getSettings()
        assertEquals("lavender", s.skin)
        assertEquals("dark", s.mode)
        assertTrue(Keys.SETTINGS in store)
    }
}
