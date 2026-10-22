package com.hazia.forgetmenot.integrations

import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The system dialogs the integrations wait on, as suspend calls. */
interface DeviceBridge {
    fun hasPermission(permission: String): Boolean
    /** The runtime permission dialog; true when granted (at once if it already was). */
    suspend fun requestPermission(permission: String): Boolean
    /** The system Photo Picker, images only (no storage permission); null = backed out. */
    suspend fun pickImage(): Uri?
    /** The contacts app's picker; null = backed out. */
    suspend fun pickContact(): Uri?
}

/**
 * [DeviceBridge] over ActivityResult launchers. Construct it while the activity is being created
 * (a field initialiser), as registerForActivityResult requires. One dialog at a time: a second
 * call waits for the first. A result lost to process death leaves nothing waiting; the user just
 * picks again.
 */
class ActivityResultsBridge(private val activity: ComponentActivity) : DeviceBridge {
    private val mutex = Mutex()

    private class Slot<I, O>(activity: ComponentActivity, contract: ActivityResultContract<I, O>) {
        private var waiting: CompletableDeferred<O>? = null
        private val launcher: ActivityResultLauncher<I> =
            activity.registerForActivityResult(contract) { waiting?.complete(it); waiting = null }

        suspend fun run(input: I): O {
            val d = CompletableDeferred<O>()
            waiting = d
            launcher.launch(input)
            return d.await()
        }
    }

    private val permission = Slot(activity, ActivityResultContracts.RequestPermission())
    private val image = Slot(activity, ActivityResultContracts.PickVisualMedia())
    private val contact = Slot(activity, ActivityResultContracts.PickContact())

    override fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    override suspend fun requestPermission(permission: String): Boolean =
        hasPermission(permission) || mutex.withLock { this.permission.run(permission) }

    override suspend fun pickImage(): Uri? = mutex.withLock {
        image.run(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    override suspend fun pickContact(): Uri? = mutex.withLock { contact.run(null) }
}
