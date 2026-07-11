package com.hhst.youtubelite.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {

    @Test
    fun allowsYoutubeAndGoogleAccounts() {
        assertTrue(UrlPolicy.isAllowedUrl("https://m.youtube.com"))
        assertTrue(UrlPolicy.isAllowedUrl("https://www.youtube.com/watch?v=abc"))
        assertTrue(UrlPolicy.isAllowedUrl("https://accounts.google.com/ServiceLogin"))
        assertTrue(UrlPolicy.canLoadInWebView("about:blank"))
    }

    @Test
    fun rejectsUnrelatedHosts() {
        assertFalse(UrlPolicy.isAllowedUrl("https://example.com"))
        assertFalse(UrlPolicy.canLoadInWebView("https://evil.example"))
    }
}
