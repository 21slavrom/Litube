package com.hhst.youtubelite.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.ui.graphics.toArgb
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.hhst.youtubelite.core.LiteConstants
import com.hhst.youtubelite.ui.theme.YtRed
import androidx.core.graphics.createBitmap

/**
 * Factory for the core browser stack.
 *
 * Builds a [SwipeRefreshLayout] that hosts a configured [WebView] for YouTube
 * mobile browsing, including progress and navigation callbacks.
 */
object LiteWebViewFactory {

    /**
     * Creates the pull-to-refresh host and embedded WebView.
     *
     * @param context context used to construct views.
     * @param callbacks UI events for loading progress and back-stack state.
     * @param onRefresh called when the user triggers pull-to-refresh.
     * @return host pair used by the Compose interop layer.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun create(
        context: Context,
        callbacks: LiteWebViewCallbacks,
        onRefresh: (WebView) -> Unit,
    ): BrowserHost {
        val webView = WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            configureSettings()
            webViewClient = LiteBrowserWebViewClient(callbacks)
            webChromeClient = LiteBrowserChromeClient(callbacks)
        }

        val swipeRefresh = SwipeRefreshLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setColorSchemeColors(YtRed.toArgb())
            // Content is already inset by safeDrawing; keep a modest pull offset.
            setProgressViewOffset(/* scale= */ true, /* start= */ 24, /* end= */ 96)
            addView(webView)
            setOnRefreshListener { onRefresh(webView) }
        }

        return BrowserHost(container = swipeRefresh, webView = webView)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun WebView.configureSettings() {
        isFocusable = true
        isFocusableInTouchMode = true
        setLayerType(WebView.LAYER_TYPE_HARDWARE, null)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        settings.apply {
            // YouTube mobile web requires JavaScript and DOM storage.
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            loadWithOverviewMode = true
            useWideViewPort = true
            loadsImagesAutomatically = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = LiteConstants.USER_AGENT
        }
    }
}

/**
 * Pull-to-refresh container paired with its child [WebView].
 *
 * @property container Android view shown through Compose interop.
 * @property webView browser instance for navigation and lifecycle control.
 */
data class BrowserHost(
    val container: SwipeRefreshLayout,
    val webView: WebView,
)

/**
 * Restricts navigation to allowed hosts and reports page lifecycle events.
 */
private class LiteBrowserWebViewClient(
    private val callbacks: LiteWebViewCallbacks,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        val uri = request.url
        val url = uri.toString()

        if (uri.scheme.equals("intent", ignoreCase = true)) {
            return openExternal(view.context, uri)
        }
        if (LiteUrlPolicy.canLoadInWebView(url)) {
            return false
        }
        return openExternal(view.context, uri)
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        callbacks.onPageStarted(url)
        callbacks.onNavigationStateChanged(view.canGoBack())
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        callbacks.onPageFinished(url)
        callbacks.onNavigationStateChanged(view.canGoBack())
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        callbacks.onNavigationStateChanged(view.canGoBack())
    }

    private fun openExternal(context: Context, uri: Uri): Boolean {
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: ActivityNotFoundException) {
            // No handler for the URI; consume the navigation to avoid a blank page.
            true
        }
    }
}

/**
 * Forwards load progress and supplies a blank video poster bitmap.
 */
private class LiteBrowserChromeClient(
    private val callbacks: LiteWebViewCallbacks,
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        callbacks.onProgressChanged(newProgress)
        super.onProgressChanged(view, newProgress)
    }

    override fun getDefaultVideoPoster(): Bitmap {
        // Avoid the default WebView video poster flash on HTML5 media elements.
        return createBitmap(1, 1)
    }
}
