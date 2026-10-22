package com.hazia.forgetmenot.integrations

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.ui.screens.add.PickedContact
import com.hazia.forgetmenot.ui.sheets.ImportCandidate
import com.hazia.forgetmenot.ui.sheets.Toaster
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceIntegrationsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.of("Europe/Rome")
    private val now = ZonedDateTime.of(2026, 10, 20, 10, 0, 0, 0, zone)
    private val ana = Friend(id = "ana", name = "Ana María", phone = "+39 333 1234567", email = "ana@example.com", birthday = "1900-03-05")

    private class FakeBridge(
        var grant: Boolean = true,
        var image: Uri? = null,
        var contact: Uri? = null,
    ) : DeviceBridge {
        val asked = mutableListOf<String>()
        override fun hasPermission(permission: String) = grant
        override suspend fun requestPermission(permission: String): Boolean { asked += permission; return grant }
        override suspend fun pickImage() = image
        override suspend fun pickContact() = contact
    }

    private class FakeContacts(
        val rows: List<DataRow> = emptyList(),
        val photoBytes: ByteArray? = null,
        val fail: Boolean = false,
    ) : ContactsSource {
        var reads = 0
        override suspend fun readAll(): List<DataRow> { reads++; if (fail) error("boom"); return rows }
        override suspend fun readOne(contactUri: Uri): List<DataRow> { reads++; if (fail) error("boom"); return rows }
        override suspend fun photo(contactUri: Uri) = photoBytes
    }

    private class FakeImages(val fail: Boolean = false) : AvatarImages {
        override suspend fun fromUri(uri: Uri): String = if (fail) error("bad image") else "data:image/jpeg;base64,URI"
        override suspend fun fromBytes(bytes: ByteArray): String = if (fail) error("bad image") else "data:image/jpeg;base64,BYTES"
    }

    private val launched = mutableListOf<Intent>()
    private var refuse: (Intent) -> Boolean = { false }
    private val launch: (Context, Intent) -> Unit = { _, i -> if (refuse(i)) throw ActivityNotFoundException(); launched += i }

    private fun integrations(
        bridge: DeviceBridge = FakeBridge(),
        contacts: ContactsSource = FakeContacts(),
        images: AvatarImages = FakeImages(),
    ) = DeviceIntegrations(
        bridge, contacts, images, now = { now }, launch = launch,
        shareFile = { ctx, text, name -> Uri.fromFile(prepareShareFile(ctx.cacheDir, text, name)) },
    )

    private fun toast() = Toaster.current.value?.text

    @Before fun setUp() { Toaster.reset() }

    // ---- photo ----

    @Test fun pickedPhotoBecomesAnAvatar() = runBlocking {
        val i = integrations(bridge = FakeBridge(image = Uri.parse("content://media/1")))
        assertTrue(i.canPickPhoto)
        assertEquals("data:image/jpeg;base64,URI", i.pickPhoto())
        assertNull(toast())
    }

    @Test fun backingOutOfThePhotoPickerDoesNothing() = runBlocking {
        assertNull(integrations(bridge = FakeBridge(image = null)).pickPhoto())
        assertNull(toast())
    }

    @Test fun anUnreadablePhotoIsToasted() = runBlocking {
        val i = integrations(bridge = FakeBridge(image = Uri.parse("content://media/1")), images = FakeImages(fail = true))
        assertNull(i.pickPhoto())
        assertEquals("Could not read that image", toast())
    }

    // ---- contacts ----

    private val anaRows = listOf(
        DataRow(5, MIME_NAME, "Ana María", "Ana", "María"),
        DataRow(5, MIME_PHONE, "+39 333 1234567"),
        DataRow(5, MIME_EMAIL, "ana@example.com"),
        DataRow(5, MIME_EVENT, "--03-05", "3"),
    )

    @Test fun pickContactAsksForThePermissionAndMapsTheFields() = runBlocking {
        val bridge = FakeBridge(contact = Uri.parse("content://com.android.contacts/contacts/lookup/x/5"))
        val i = integrations(bridge = bridge, contacts = FakeContacts(anaRows, photoBytes = byteArrayOf(1, 2)))
        assertTrue(i.canImportContacts)
        assertEquals(
            PickedContact("Ana María", "+39 333 1234567", "ana@example.com", "1900-03-05", "data:image/jpeg;base64,BYTES"),
            i.pickContact(),
        )
        assertEquals(listOf(Manifest.permission.READ_CONTACTS), bridge.asked)
    }

    @Test fun aContactWithoutAPhotoKeepsNone() = runBlocking {
        val bridge = FakeBridge(contact = Uri.parse("content://c/5"))
        assertNull(integrations(bridge = bridge, contacts = FakeContacts(anaRows)).pickContact()!!.avatar)
        // An unreadable contact photo is dropped, the rest still imported.
        val p = integrations(bridge = bridge, contacts = FakeContacts(anaRows, byteArrayOf(9)), images = FakeImages(fail = true)).pickContact()
        assertEquals("Ana María", p!!.name)
        assertNull(p.avatar)
    }

    @Test fun deniedContactsPermissionIsToastedAndNothingIsRead() = runBlocking {
        val contacts = FakeContacts(anaRows)
        val i = integrations(bridge = FakeBridge(grant = false, contact = Uri.parse("content://c/5")), contacts = contacts)
        assertNull(i.pickContact())
        assertEquals("Contacts permission denied", toast())
        Toaster.reset()
        assertNull(i.readContacts())
        assertEquals("Contacts permission denied", toast())
        assertEquals(0, contacts.reads)
    }

    @Test fun backingOutOfTheContactPickerIsSilent() = runBlocking {
        assertNull(integrations(bridge = FakeBridge(contact = null), contacts = FakeContacts(anaRows)).pickContact())
        assertNull(toast())
    }

    @Test fun aFailedPickedContactReadIsSilentAsInTheJs() = runBlocking {
        assertNull(integrations(bridge = FakeBridge(contact = Uri.parse("content://c/5")), contacts = FakeContacts(fail = true)).pickContact())
        assertNull(toast())
    }

    @Test fun readContactsMapsEveryContact() = runBlocking {
        val rows = anaRows + listOf(DataRow(9, MIME_NAME, "Bo"), DataRow(11, MIME_PHONE, "123"))
        assertEquals(
            listOf(
                ImportCandidate("Ana María", "+39 333 1234567", "ana@example.com", "1900-03-05"),
                ImportCandidate("Bo", null, null, null),
                ImportCandidate("", "123", null, null), // nameless: the screen's importCandidates() drops it
            ),
            integrations(contacts = FakeContacts(rows)).readContacts(),
        )
    }

    @Test fun aFailedBulkReadIsToasted() = runBlocking {
        assertNull(integrations(contacts = FakeContacts(fail = true)).readContacts())
        assertEquals("Could not read your contacts", toast())
    }

    // ---- message ----

    @Test fun messageOpensThePreferredChannel() {
        assertTrue("handed off", integrations().message(context, ana.copy(messageChannel = "telegram")))
        val i = launched.single()
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals("https://t.me/+393331234567", i.dataString)
    }

    @Test fun messageWithoutPhoneOrEmailIsToasted() {
        assertFalse("nothing opened", integrations().message(context, ana.copy(phone = null, email = "")))
        assertTrue(launched.isEmpty())
        assertEquals("Add a phone or email for Ana to enable this", toast())
    }

    @Test fun messageWithNoAppToTakeItIsToasted() {
        refuse = { true }
        assertFalse("nothing opened", integrations().message(context, ana))
        assertEquals("No app on this phone can open that", toast())
    }

    // ---- plan ----

    @Test fun planOpensTheCalendarsNewEventScreen() {
        integrations().plan(context, ana)
        val i = launched.single()
        assertEquals(Intent.ACTION_INSERT, i.action)
        assertEquals(CalendarContract.Events.CONTENT_URI, i.data)
        assertEquals("Time with Ana María", i.getStringExtra(CalendarContract.Events.TITLE))
        assertEquals(Instant.parse("2026-10-27T17:00:00Z").toEpochMilli(), i.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0))
        assertEquals(Instant.parse("2026-10-27T18:00:00Z").toEpochMilli(), i.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, 0))
        assertNull(toast())
    }

    @Test fun planWithoutACalendarSharesAnIcsFile() {
        refuse = { it.action == Intent.ACTION_INSERT }
        integrations().plan(context, ana)
        val chooser = launched.single()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals("Save this event", chooser.getCharSequenceExtra(Intent.EXTRA_TITLE).toString())
        @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/calendar", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        @Suppress("DEPRECATION") val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        val file = File(File(context.cacheDir, SHARE_DIR), "time-with-Ana Mara.ics")
        assertEquals(Uri.fromFile(file), uri)
        val ics = file.readText()
        assertTrue(ics.contains("\r\nDTSTART:20261027T170000Z\r\nDTEND:20261027T180000Z\r\nSUMMARY:Time with Ana María\r\n"))
        assertNull(toast())
    }

    @Test fun planWithNothingToTakeItIsToasted() {
        refuse = { true }
        integrations().plan(context, ana)
        assertEquals("No calendar app found on this device", toast())
    }

    // ---- share ----

    @Test fun shareSendsTheVCardAsText() {
        integrations().share(context, ana)
        val chooser = launched.single()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals("Share Ana María", chooser.getCharSequenceExtra(Intent.EXTRA_TITLE).toString())
        @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("text/plain", send.type)
        assertEquals(
            "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Ana María\r\nTEL;TYPE=CELL:+39 333 1234567\r\nEMAIL:ana@example.com\r\nBDAY:--0305\r\nEND:VCARD",
            send.getStringExtra(Intent.EXTRA_TEXT),
        )
    }

    @Test fun theDetailRowIsShown() { assertTrue(integrations().available) }

    // ---- share folder ----

    @Test fun shareFolderIsClearedAndNamesCannotEscapeIt() {
        val dir = File(context.cacheDir, SHARE_DIR)
        prepareShareFile(context.cacheDir, "old", "old.ics")
        val file = prepareShareFile(context.cacheDir, "new", "../../evil.ics")
        assertFalse("earlier invites are cleared", File(dir, "old.ics").exists())
        assertEquals(File(dir, "evil.ics"), file)
        assertEquals("new", file.readText())
        assertEquals(listOf("evil.ics"), dir.list()!!.toList())
    }
}
