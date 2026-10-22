package com.hazia.forgetmenot.ui.screens.settings

import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.hazia.forgetmenot.data.FolderStatus
import com.hazia.forgetmenot.data.PendingGrant
import com.hazia.forgetmenot.domain.Friend
import com.hazia.forgetmenot.domain.Tag
import com.hazia.forgetmenot.ui.sheets.RecentChanges
import com.hazia.forgetmenot.ui.sheets.TestEnv
import com.hazia.forgetmenot.ui.sheets.setThemed
import com.hazia.forgetmenot.ui.sheets.settle
import com.hazia.forgetmenot.ui.sheets.toastText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import androidx.compose.ui.test.assertIsFocused

/** Stands in for Android's notification, alarm and battery state; records the screens opened. */
class FakePlatform(
    var notif: NotifStatus = NotifStatus.ON,
    var canAsk: Boolean = false,
    var exact: Boolean = false,
    var battery: Boolean = false,
) : SettingsPlatform {
    val opened = mutableListOf<String>()
    override fun notifications() = notif
    override fun canAskNotifications() = canAsk
    override fun exactAlarmsGranted() = exact
    override fun batteryUnrestricted() = battery
    override fun openNotificationSettings() { opened += "notifications" }
    override fun openExactAlarmSettings() { opened += "exact" }
    override fun openBatterySettings() { opened += "battery" }
    override val appVersion = "9.9.9"
}

/** SPEC §1 "Screens", settings: every row, its value, and what a tap does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val env = TestEnv()
    private val r = env.records
    private val platform = FakePlatform()
    private var wentBack = 0
    private var wentHome = 0

    private fun show() {
        val deps = SettingsDeps(
            env.records, env.session, env.backup, platform,
            pickFolder = ActivityResultContracts.OpenDocumentTree(),
            takeGrant = { PendingGrant(FolderStatus(true, "test")) },
            readText = { null },
        )
        compose.setThemed { SettingsScreen(deps, onBack = { wentBack++ }, onGoHome = { wentHome++ }) }
    }

    private fun row(text: String) = compose.onNodeWithText(text).performScrollTo()

    @Test fun everyRowShowsItsValue() {
        r.saveTag(Tag(id = "t", name = "Family", cadenceDays = 30))
        r.saveFriend(Friend(id = "a", name = "Ann", archived = true))
        r.saveFriend(Friend(id = "b", name = "Bo", archived = true))
        show()
        compose.onNodeWithText("Settings").assertExists()
        row("Cyanotype, Light").assertExists()
        row("Smaller home").assertExists()
        row("Just the three people most worth a hello").assertExists()
        row("Recent changes").assertExists()
        row("Archived friends").assertExists()
        row("2").assertExists()
        row("Quiet mode").assertExists()
        row("Notifications").assertExists(); row("On").assertExists()
        row("Exact reminders").assertExists(); row("Not enabled").assertExists()
        row("Battery limits").assertExists(); row("May delay — tap to fix").assertExists()
        row("Tag check-in defaults").assertExists(); row("1 tag").assertExists()
        compose.waitUntil(5000) { compose.onAllNodes(androidx.compose.ui.test.hasText("test")).fetchSemanticsNodes().isNotEmpty() }
        row("Sync folder").assertExists()
        row("Point Syncthing-fork at this same folder to sync across devices. Tap to change.").assertExists()
        compose.onNodeWithText("Sync conflicts").assertDoesNotExist()
        row("Export backup").assertExists()
        row("Import backup").assertExists()
        row("Version").assertExists(); row("9.9.9").assertExists()
    }

    @Test fun withoutAFolderTheRowAndNoteSayNothingIsSaved() {
        env.fs.picked = false
        show()
        compose.waitUntil(5000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Not set — tap to choose")).fetchSemanticsNodes().isNotEmpty() }
        row("Your data isn’t saved until you pick a folder — ideally one Syncthing-fork already watches.").assertExists()
    }

    @Test fun theSwitchesSaveAndQuietModeReschedules() {
        show()
        val before = env.reschedules
        compose.onNodeWithTag("switch-Quiet mode").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("switch-Quiet mode").assertIsOn()
        assertTrue(r.getSettings().quietMode)
        assertEquals(before + 1, env.reschedules)
        compose.onNodeWithTag("switch-Smaller home").performScrollTo().performClick()
        compose.onNodeWithTag("switch-Smaller home").assertIsOn()
        assertTrue(r.getSettings().smallHome)
    }

    @Test fun aReadOnlySessionDoesNotReschedule() {
        env.store.blockWrites()
        show()
        compose.onNodeWithTag("switch-Quiet mode").performScrollTo().performClick()
        assertEquals(0, env.reschedules)
    }

    @Test fun theThemeRowOpensThePickerAndFollowsIt() {
        show()
        row("Cyanotype, Light").performClick()
        compose.onNodeWithText("Celadon").performClick()
        compose.onNodeWithText("OLED").performClick()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.settle()
        row("Celadon, OLED").assertExists()
    }

    @Test fun countsFollowRestoreAndUndo() {
        r.saveFriend(Friend(id = "a", name = "Ann", archived = true))
        RecentChanges.record("Note for Bo") {}
        show()
        row("Archived friends").performClick()
        compose.onNodeWithText("Restore").performClick()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.settle()
        assertEquals("Restored Ann", toastText())
        row("Recent changes").performClick()
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.settle()
        // Both counts are back to 0: no "1" left on the screen.
        compose.onNodeWithText("1").assertDoesNotExist()
    }

    @Test fun theTagsRowOpensTagDefaults() {
        r.saveTag(Tag(id = "t", name = "Family", cadenceDays = 30))
        show()
        row("Tag check-in defaults").performClick()
        compose.onNodeWithText("Tag defaults").assertExists()
        compose.onNodeWithText("Family").assertExists()
    }

    @Test fun theConflictsRowShowsOnlyWithConflictsAndOpensTheSheet() {
        env.plantFriendConflict("f1", "Ann", "Annie")
        show()
        compose.onNodeWithText("Sync conflicts").assertDoesNotExist()
        runBlocking { env.session.runScan() }
        compose.waitForIdle()
        row("Sync conflicts").performClick()
        compose.onNodeWithText("Keep this version").assertExists()
    }

    @Test fun statusRowsOpenWhereTheyAreFixed() {
        platform.notif = NotifStatus.BLOCKED
        show()
        row("Blocked").performClick()
        assertEquals("Notifications blocked in system settings", toastText())
        row("Exact reminders").performClick()
        row("Battery limits").performClick()
        assertEquals(listOf("notifications", "exact", "battery"), platform.opened)
    }

    @Test fun grantedNotificationsReschedule() {
        show()
        val before = env.reschedules
        row("Notifications").performClick()
        assertEquals("Notifications on", toastText())
        assertEquals(before + 1, env.reschedules)
    }

    @Test fun exportWritesToTheFolderAndSaysWhere() {
        show()
        row("Export backup").performClick()
        compose.waitUntil(5000) { toastText() != null }
        assertEquals("Backup saved to test/backups/forgetmenot-backup-${LocalDate.now()}.json", toastText())
        runBlocking { env.store.flushWrites() }
        assertTrue(env.fs.disk.keys.any { it.startsWith("backups/forgetmenot-backup-") })
    }

    @Test fun backGoesBack() {
        show()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, wentBack)
    }

    // SPEC §7: the screen's title takes focus when the screen comes up.
    @Test fun theHeadingTakesFocus() {
        show()
        compose.onNodeWithText("Settings").assertIsFocused()
    }
}
