package com.hhst.youtubelite.browser

/** Navigation / loading events from [WebViewFactory] to the UI layer. */
interface WebViewCallbacks {
    fun onPageStarted(url: String)
    fun onPageFinished(url: String)
    fun onProgressChanged(progress: Int)
    fun onNavigationStateChanged(canGoBack: Boolean)
}
