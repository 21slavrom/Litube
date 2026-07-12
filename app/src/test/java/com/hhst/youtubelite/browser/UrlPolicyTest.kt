package com.hhst.youtubelite.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {

    @Test
    fun allowsYoutubeAndAccounts() {
        assertTrue(UrlPolicy.isAllowedUrl("https://m.youtube.com"))
        assertTrue(UrlPolicy.isAllowedUrl("https://www.youtube.com/watch?v=abc"))
        assertTrue(UrlPolicy.isAllowedUrl("https://accounts.google.com/ServiceLogin"))
    }

    @Test
    fun canLoadLocalSchemes() {
        assertTrue(UrlPolicy.canLoad("about:blank"))
    }

    @Test
    fun rejectsOtherHosts() {
        assertFalse(UrlPolicy.isAllowedUrl("https://example.com"))
        assertFalse(UrlPolicy.canLoad("https://evil.example"))
    }
}
