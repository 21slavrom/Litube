package com.hhst.youtubelite.browser

/** WebView events to the UI layer. */
interface WebViewCallbacks {
    fun onPageStarted(url: String)
    fun onPageFinished(url: String)
    fun onProgressChanged(progress: Int)
    fun onNavigationStateChanged(canGoBack: Boolean)
    fun onHistoryChanged(url: String)
    fun onOpenTab(url: String)
}
