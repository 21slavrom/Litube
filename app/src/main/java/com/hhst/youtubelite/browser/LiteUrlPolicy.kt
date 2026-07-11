package com.hhst.youtubelite.browser

import com.hhst.youtubelite.core.LiteConstants
import java.net.URI
import java.util.Locale

/**
 * URL admission policy for the in-app browser.
 *
 * YouTube content and Google sign-in hosts load inside the WebView. Other
 * HTTP(S) destinations should open in an external app.
 */
object LiteUrlPolicy {

    private val allowedHosts = setOf(
        LiteConstants.YOUTUBE_DOMAIN,
        "youtu.be",
        "youtube.googleapis.com",
        "googlevideo.com",
        "ytimg.com",
        "accounts.google.com",
        "googleusercontent.com",
        "apis.google.com",
        "gstatic.com",
    )

    /** Returns whether [url] points at an allowed host. */
    fun isAllowedUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) {
            return false
        }
        return isAllowedHost(hostOf(url))
    }

    /** Returns whether [host] is a YouTube or Google sign-in host. */
    fun isAllowedHost(host: String?): Boolean {
        if (host.isNullOrBlank()) {
            return false
        }
        val normalized = host.lowercase(Locale.ROOT)
        return allowedHosts.any { domain ->
            normalized == domain || normalized.endsWith(".$domain")
        }
    }

    /**
     * Returns whether [url] should load in the WebView.
     *
     * Local schemes used by WebView itself are always permitted.
     */
    fun canLoadInWebView(url: String): Boolean {
        val scheme = schemeOf(url)
        if (
            scheme == "file" ||
            scheme == "about" ||
            scheme == "data" ||
            scheme == "javascript"
        ) {
            return true
        }
        return isAllowedUrl(url)
    }

    private fun hostOf(url: String): String? {
        return try {
            URI(url).host
        } catch (_: Exception) {
            null
        }
    }

    private fun schemeOf(url: String): String? {
        return try {
            URI(url).scheme?.lowercase(Locale.ROOT)
        } catch (_: Exception) {
            null
        }
    }
}
