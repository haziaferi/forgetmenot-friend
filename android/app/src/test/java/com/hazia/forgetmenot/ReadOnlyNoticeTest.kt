package com.hazia.forgetmenot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** I6's start toast: once per process, whether or not the activity was restored from saved state. */
class ReadOnlyNoticeTest {
    @Before fun newProcess() = ReadOnlyNotice.reset()

    @Test fun aWritableStartSaysNothing() {
        assertFalse(ReadOnlyNotice.claim(false))
        assertTrue("still owed when a later start is read-only", ReadOnlyNotice.claim(true))
    }

    @Test fun aReadOnlyStartSaysItOncePerProcess() {
        assertTrue(ReadOnlyNotice.claim(true))
        assertFalse("not again on a rotation or a second activity", ReadOnlyNotice.claim(true))
    }
}
