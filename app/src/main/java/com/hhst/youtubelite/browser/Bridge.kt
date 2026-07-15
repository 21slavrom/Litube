package com.hhst.youtubelite.browser

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import com.google.gson.Gson
import com.hhst.youtubelite.extension.ExtensionManager

/** WebView JS bridge (`Bridge` / `lite`). */
class Bridge(
    private val onOpenTab: (url: String) -> Unit,
    private val onOpenExtension: () -> Unit,
    private val extensionManager: ExtensionManager,
    private val gson: Gson = Gson(),
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun openTab(url: String?) {
        if (url.isNullOrBlank()) return
        if (!UrlPolicy.isAllowedUrl(url)) return
        if (PageKind.of(url) == "unknown") return
        main.post { onOpenTab(url) }
    }

    @JavascriptInterface
    fun extension() {
        main.post(onOpenExtension)
    }

    @JavascriptInterface
    fun getPreferences(): String = gson.toJson(extensionManager.allPreferences())

    companion object {
        const val NAME = "Bridge"
    }
}
