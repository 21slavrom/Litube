package com.hhst.youtubelite.browser

import com.hhst.youtubelite.core.Constants
import java.net.URI
import java.util.Locale

/** Allowlist for hosts that may load in the in-app WebView. */
object UrlPolicy {

    private val allowedHosts = setOf(
        Constants.YOUTUBE_DOMAIN,
        "youtu.be",
        "youtube.googleapis.com",
        "googlevideo.com",
        "ytimg.com",
        "accounts.google.com",
        "googleusercontent.com",
        "apis.google.com",
        "gstatic.com",
    )

    fun isAllowedUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return isAllowedHost(hostOf(url))
    }

    fun isAllowedHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val normalized = host.lowercase(Locale.ROOT)
        return allowedHosts.any { domain ->
            normalized == domain || normalized.endsWith(".$domain")
        }
    }

    fun canLoadInWebView(url: String): Boolean {
        val scheme = schemeOf(url)
        if (scheme == "file" || scheme == "about" || scheme == "data" || scheme == "javascript") {
            return true
        }
        return isAllowedUrl(url)
    }

    private fun hostOf(url: String): String? = try {
        URI(url).host
    } catch (_: Exception) {
        null
    }

    private fun schemeOf(url: String): String? = try {
        URI(url).scheme?.lowercase(Locale.ROOT)
    } catch (_: Exception) {
        null
    }
}
