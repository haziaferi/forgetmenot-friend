package com.hazia.forgetmenot.ui.sheets

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.hazia.forgetmenot.data.Backup
import com.hazia.forgetmenot.data.CachedStore
import com.hazia.forgetmenot.data.Conflicts
import com.hazia.forgetmenot.data.FakeFolder
import com.hazia.forgetmenot.data.Records
import com.hazia.forgetmenot.data.SyncSession
import com.hazia.forgetmenot.ui.theme.ForgetmenotTheme

/** The data layer over an in-memory folder, as the app wires it (AppContainer), for UI tests. */
class TestEnv {
    val fs = FakeFolder()
    val store = CachedStore(fs)
    val records = Records(store)
    val conflicts = Conflicts(store, records)
    val session = SyncSession(store, records, conflicts)
    val backup = Backup(store, records)

    /** Counts what Records hands to P3/P6, so tests can see a reschedule or push happen. */
    var reschedules = 0
    var pushes = 0

    init {
        records.effects = object : com.hazia.forgetmenot.data.DataEffects {
            override fun pushWidgets() { pushes++ }
            override fun rescheduleReminders() { reschedules++ }
        }
        Toaster.reset()
        RecentChanges.reset()
    }

    /** A friend and a sync-conflict copy of it with the same stamp: one pending conflict on scan. */
    fun plantFriendConflict(id: String, name: String, theirName: String, contacts: Int = 0) {
        val logs = (1..contacts).joinToString(",") { """{"id":"c$it","date":"2026-10-0${it}T10:00:00.000Z"}""" }
        fs.disk["friend_index.json"] = """["$id"]"""
        fs.disk["friends/$id.json"] = """{"id":"$id","name":"$name","contacts":[],"updatedAt":100}"""
        fs.disk["friends/$id.sync-conflict-20261012-101500-ABC.json"] = """{"id":"$id","name":"$theirName","contacts":[$logs],"updatedAt":100}"""
    }
}

fun ComposeContentTestRule.setThemed(content: @Composable () -> Unit) = setContent { ForgetmenotTheme { content() } }

/** The toast's text right now, or null. */
fun toastText(): String? = Toaster.current.value?.text

/** Lets a sheet's hide animation run to its end, so its onDismiss has fired. */
fun ComposeContentTestRule.settle() {
    mainClock.advanceTimeBy(1000)
    org.robolectric.shadows.ShadowLooper.idleMainLooper()
    waitForIdle()
}
