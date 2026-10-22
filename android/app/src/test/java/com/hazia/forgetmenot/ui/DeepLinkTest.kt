package com.hazia.forgetmenot.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Parity with openFriendFromUrl() in www/js/main.js: MainActivity is exported, so anything
// that is not forgetmenot://friend/<valid id> must open nothing.
class DeepLinkTest {
    @Test fun plainFriendUrlGivesId() =
        assertEquals("abc123", friendIdFromUrl("forgetmenot://friend/abc123"))

    @Test fun trailingPathQueryAndFragmentAreIgnored() {
        assertEquals("abc", friendIdFromUrl("forgetmenot://friend/abc/extra"))
        assertEquals("abc", friendIdFromUrl("forgetmenot://friend/abc?x=1"))
        assertEquals("abc", friendIdFromUrl("forgetmenot://friend/abc#top"))
    }

    @Test fun otherSchemesAndHostsGiveNothing() {
        assertNull(friendIdFromUrl(null))
        assertNull(friendIdFromUrl("https://friend/abc"))
        assertNull(friendIdFromUrl("forgetmenot://group/abc"))
    }

    @Test fun invalidIdsGiveNothing() {
        assertNull(friendIdFromUrl("forgetmenot://friend/"))
        assertNull(friendIdFromUrl("forgetmenot://friend/../etc"))
        assertNull(friendIdFromUrl("forgetmenot://friend/a-b"))
        assertNull(friendIdFromUrl("forgetmenot://friend/" + "a".repeat(41)))
        assertEquals("a".repeat(40), friendIdFromUrl("forgetmenot://friend/" + "a".repeat(40)))
    }
}
