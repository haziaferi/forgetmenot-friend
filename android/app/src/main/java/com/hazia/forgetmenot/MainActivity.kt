package com.hazia.forgetmenot

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.hazia.forgetmenot.integrations.ActivityResultsBridge
import com.hazia.forgetmenot.integrations.DecoderAvatarImages
import com.hazia.forgetmenot.integrations.DeviceIntegrations
import com.hazia.forgetmenot.integrations.ProviderContacts
import com.hazia.forgetmenot.ui.AppNavHost
import com.hazia.forgetmenot.ui.friendIdFromUrl
import com.hazia.forgetmenot.ui.screens.add.LocalAddIntegrations
import com.hazia.forgetmenot.ui.screens.detail.LocalDetailIntegrations
import com.hazia.forgetmenot.ui.sheets.ToastHost
import com.hazia.forgetmenot.ui.sheets.Toaster
import com.hazia.forgetmenot.ui.theme.AppFrame
import com.hazia.forgetmenot.ui.theme.ForgetmenotTheme
import com.hazia.forgetmenot.ui.theme.bottomInsetPadding

class MainActivity : ComponentActivity() {
    // A widget's forgetmenot://friend/<id> not yet opened (invariant I3). The intent is
    // explicit (friendIntent() in widget/WidgetData.kt), so no intent-filter is involved.
    private var pendingFriendId by mutableStateOf<String?>(null)
    private var loaded by mutableStateOf(false)
    // The Photo Picker, contacts picker and permission dialog. Registered here, while the activity
    // is being created, as registerForActivityResult requires.
    private val bridge = ActivityResultsBridge(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        // API 31+ always shows a system splash; this styles it (values-v31/styles.xml).
        // Below 31 the launch theme has no splash at all, so there is nothing to install.
        val container = AppContainer.from(this)
        // The sync folder is read before the first render, as the JS awaited it in init(); the
        // splash stays up meanwhile (API 31+), and below 31 the frame is the bare theme.
        val started = container.started
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            installSplashScreen().setKeepOnScreenCondition { !started.isCompleted }
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a fresh start: after recreation the back stack already holds the friend.
        val fresh = savedInstanceState == null
        if (fresh) pendingFriendId = friendIdFromUrl(intent?.dataString)
        lifecycleScope.launch {
            val result = started.await()
            loaded = true
            // I6: said once per process, as init() did after the first render. Not keyed on a fresh
            // activity: a task restored after the process died is the usual way back, and it is a new load.
            if (ReadOnlyNotice.claim(result.readOnly)) {
                Toaster.show("Couldn’t read your sync folder — changes won’t be saved until the app restarts")
            }
        }
        // SPEC §4: a failed scheduling pass is never silent. Collected only while started, which
        // covers onResume's pass; a failure while stopped is redone by the next resume anyway.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.reminders.failures.collect {
                    Toaster.show("Reminders couldn’t be scheduled — check notification permission in Settings")
                }
            }
        }

        // Photo Picker and contacts for add; Message / Plan something / Share for detail.
        val integrations = DeviceIntegrations(bridge, ProviderContacts(contentResolver), DecoderAvatarImages(contentResolver))

        setContent {
            // Any cache change (a settings save, a backup import, an adopted folder) re-reads
            // the theme; ForgetmenotTheme falls back for an unknown skin or mode.
            val revision by container.store.revision.collectAsState()
            val settings = remember(revision) { container.records.getSettings() }
            ForgetmenotTheme(skin = settings.skin, mode = settings.mode) {
                AppFrame {
                    Box(Modifier.fillMaxSize()) {
                        if (loaded) {
                            CompositionLocalProvider(
                                LocalAddIntegrations provides integrations,
                                LocalDetailIntegrations provides integrations,
                            ) {
                                AppNavHost(
                                    pendingFriendId = pendingFriendId,
                                    onFriendOpened = { pendingFriendId = null },
                                )
                            }
                        }
                        // The one toast for every screen, so a toast raised just before navigating
                        // (Backup imported, then home) survives it; an open sheet draws its own.
                        ToastHost(Modifier.align(Alignment.BottomCenter).bottomInsetPadding())
                    }
                }
            }
        }
    }

    // Every resume rebuilds the reminders (the first one is init()'s): an exact-alarm or
    // notification grant made in Settings only lands when the user comes back. Gated on I6.
    override fun onResume() {
        super.onResume()
        val container = AppContainer.from(this)
        // The widget summary too, as the JS pushed it on every resume, so day codes ("9d") roll over.
        lifecycleScope.launch {
            container.started.await()
            container.records.rescheduleReminders()
            container.records.pushWidgets()
        }
    }

    // launchMode singleTask: a widget tap while the app is running arrives here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        friendIdFromUrl(intent.dataString)?.let { pendingFriendId = it }
    }
}

/** The I6 start toast's once-per-process guard: [claim] is true for the first read-only start only. */
internal object ReadOnlyNotice {
    private val shown = java.util.concurrent.atomic.AtomicBoolean(false)

    fun claim(readOnly: Boolean): Boolean = readOnly && shown.compareAndSet(false, true)

    /** For tests: a new process. */
    fun reset() = shown.set(false)
}
