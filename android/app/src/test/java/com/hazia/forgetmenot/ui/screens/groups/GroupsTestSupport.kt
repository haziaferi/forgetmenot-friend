package com.hazia.forgetmenot.ui.screens.groups

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.hazia.forgetmenot.data.CachedStore
import com.hazia.forgetmenot.data.DataEffects
import com.hazia.forgetmenot.data.FakeFolder
import com.hazia.forgetmenot.data.Records
import java.time.ZoneId
import java.time.ZonedDateTime

/** 2026-10-18 12:00 in the JVM's zone: the screens' "now" in every groups test. */
val TEST_NOW: ZonedDateTime = ZonedDateTime.of(2026, 10, 18, 12, 0, 0, 0, ZoneId.systemDefault())

/** Counts what the screens ask of the derived surfaces, so a test can see a reschedule. */
class CountingEffects : DataEffects {
    var reschedules = 0
    var widgetPushes = 0
    override fun pushWidgets() { widgetPushes++ }
    override fun rescheduleReminders() { reschedules++ }
}

class GroupsFixture {
    val fs = FakeFolder()
    val store = CachedStore(fs)
    val effects = CountingEffects()
    val records = Records(store).also { it.effects = effects }
}

/** A node by test tag in the unmerged tree (tags sit inside clickable, merged rows). */
fun ComposeContentTestRule.tagged(tag: String) = onNode(hasTestTag(tag), useUnmergedTree = true)
