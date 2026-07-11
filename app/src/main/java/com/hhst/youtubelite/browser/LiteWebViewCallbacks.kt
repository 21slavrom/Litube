package com.hhst.youtubelite.browser

/**
 * Browser events consumed by the UI layer.
 *
 * Keep this free of Android view types so ViewModels can implement it without
 * depending on [android.webkit.WebView].
 */
interface LiteWebViewCallbacks {

    /** Called when a main-frame navigation starts. */
    fun onPageStarted(url: String)

    /** Called when a main-frame navigation finishes. */
    fun onPageFinished(url: String)

    /**
     * Called as the page load progresses.
     *
     * @param progress value in the range `0..100`.
     */
    fun onProgressChanged(progress: Int)

    /** Called when the WebView back stack availability changes. */
    fun onNavigationStateChanged(canGoBack: Boolean)
}
