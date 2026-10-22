package com.hazia.forgetmenot.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hazia.forgetmenot.data.Records
import com.hazia.forgetmenot.ui.sheets.AppSheet
import com.hazia.forgetmenot.ui.sheets.InlineTextButton
import com.hazia.forgetmenot.ui.sheets.SheetIntro
import com.hazia.forgetmenot.ui.sheets.SheetSaveButton
import com.hazia.forgetmenot.ui.theme.AppTheme
import kotlinx.coroutines.CancellationException

// Port of www/js/ui/onboarding.js (SPEC §1 "What it deliberately is not"): the two permission
// asks, queued behind home rather than in front of it. Each step checks its own status and skips
// itself if satisfied, and is marked seen in settings.onboarded however it is dismissed, so it is
// asked once per install. Settings keeps a tap-to-fix row for each; exact alarms are asked for
// only there.

enum class OnboardingStep(val key: String, val title: String, val body: String, val primaryLabel: String) {
    SYNC_FOLDER(
        "syncFolder",
        "Choose where to save your data",
        "forgetmenot keeps everything in a folder you pick yourself — ideally one your sync app (like a Syncthing fork) is already watching, so your data quietly syncs across your devices. Nothing is written anywhere until you choose one, and you can change it later in Settings.",
        "Choose folder",
    ),
    NOTIFICATIONS(
        "notifications",
        "Get a nudge when it’s time to reach out",
        "Turn on notifications so forgetmenot can remind you when it’s been a while, and flag birthdays and anniversaries on the day. Quiet mode and per-person pausing are always in Settings if you want less of it.",
        "Enable notifications",
    ),
}

/** The steps still to ask, in order: not yet seen, and not already satisfied. Rebuilt per run. */
suspend fun onboardingQueue(
    seen: Map<String, Boolean>,
    folderGranted: suspend () -> Boolean,
    notificationsOn: () -> Boolean,
): List<OnboardingStep> = OnboardingStep.entries.filter { step ->
    if (seen[step.key] == true) return@filter false
    when (step) {
        // A failed check does not nag; a cancelled one (home left meanwhile) is not a result at all.
        OnboardingStep.SYNC_FOLDER -> try { !folderGranted() } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
        OnboardingStep.NOTIFICATIONS -> !notificationsOn()
    }
}

/**
 * The queue and the step on screen. A primary tap hides the sheet while the system picker or
 * permission dialog is up; the step is marked seen when that returns ([primaryDone]), so the
 * settings write lands after an adopted folder's own settings, as the JS awaited run() first.
 */
class OnboardingController(val records: Records) {
    var current by mutableStateOf<OnboardingStep?>(null)
        private set
    private val queue = ArrayDeque<OnboardingStep>()
    private var inFlight: OnboardingStep? = null

    fun start(steps: List<OnboardingStep>) {
        queue.clear(); queue.addAll(steps)
        advance()
    }

    /** "Not now", Close, back, or a swipe. */
    fun dismiss() {
        val s = current ?: return
        current = null
        finish(s)
    }

    /** The primary button: hides the sheet and returns the step to run. */
    fun beginPrimary(): OnboardingStep? {
        val s = current ?: return null
        current = null
        inFlight = s
        return s
    }

    /** The picker or dialog came back, however it ended. */
    fun primaryDone(step: OnboardingStep) {
        if (inFlight != step) return
        inFlight = null
        finish(step)
    }

    private fun finish(step: OnboardingStep) {
        val s = records.getSettings()
        records.saveSettings(s.copy(onboarded = s.onboarded + (step.key to true)))
        advance()
    }

    private fun advance() { current = queue.removeFirstOrNull() }
}

@Composable
fun OnboardingSheet(step: OnboardingStep, onPrimary: () -> Unit, onDismiss: () -> Unit) {
    AppSheet(step.title, onDismiss) { close ->
        SheetIntro(step.body)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InlineTextButton("Not now", color = AppTheme.colors.textMuted) { close() }
            SheetSaveButton(step.primaryLabel, Modifier.weight(1f), onClick = onPrimary)
        }
    }
}
