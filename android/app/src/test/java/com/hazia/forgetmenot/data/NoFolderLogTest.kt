package com.hazia.forgetmenot.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Before a folder is picked every write fails by design (SPEC §9), and the starter tags are seeded
 * into the cache on each launch: logged, that was six E-level stack traces per first launch.
 * A real write failure is still logged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoFolderLogTest {
    private val fs = FakeFolder()
    private val store = CachedStore(fs)

    @Before fun clearLog() { ShadowLog.clear() }

    private fun errors() = ShadowLog.getLogsForTag("forgetmenot").filter { it.type == android.util.Log.ERROR }
    private val tag = JsonObject(mapOf("id" to JsonPrimitive("t1"), "name" to JsonPrimitive("Family")))

    @Test fun aWriteWithNoFolderPickedIsNotLogged() = runBlocking {
        fs.picked = false
        store.persistKey("fm_tag:t1", tag)
        store.deleteKey("fm_tag:t1")
        Records(store).seedIfEmpty()
        store.flushWrites()
        assertEquals(emptyList<Any>(), errors().map { it.msg })
    }

    @Test fun aWriteThatFailsInAPickedFolderIsStillLogged() = runBlocking {
        fs.beforeWrite = { throw FolderException(FolderError.WRITE_FAILED) }
        store.persistKey("fm_tag:t1", tag)
        assertEquals(listOf("persist failed for fm_tag:t1"), errors().map { it.msg })
    }
}
