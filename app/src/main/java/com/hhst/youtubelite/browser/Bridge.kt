package com.hhst.youtubelite.browser

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/** Posts tab opens from page JS onto the main thread. */
class Bridge(
    private val onOpenTab: (url: String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun openTab(url: String?) {
        if (url.isNullOrBlank()) return
        if (!UrlPolicy.isAllowedUrl(url)) return
        if (PageKind.of(url) == "unknown") return
        main.post { onOpenTab(url) }
    }

    companion object {
        const val NAME = "Bridge"
    }
}
