package com.hhst.youtubelite.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteUrlPolicyTest {

    @Test
    fun allowsYoutubeAndGoogleAccounts() {
        assertTrue(LiteUrlPolicy.isAllowedUrl("https://m.youtube.com"))
        assertTrue(LiteUrlPolicy.isAllowedUrl("https://www.youtube.com/watch?v=abc"))
        assertTrue(LiteUrlPolicy.isAllowedUrl("https://accounts.google.com/ServiceLogin"))
        assertTrue(LiteUrlPolicy.canLoadInWebView("about:blank"))
    }

    @Test
    fun rejectsUnrelatedHosts() {
        assertFalse(LiteUrlPolicy.isAllowedUrl("https://example.com"))
        assertFalse(LiteUrlPolicy.canLoadInWebView("https://evil.example"))
    }
}
