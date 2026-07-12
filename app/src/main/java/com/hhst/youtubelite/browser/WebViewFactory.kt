package com.hhst.youtubelite.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.hhst.youtubelite.core.Constants
import com.hhst.youtubelite.ui.theme.YtRed
import java.nio.charset.StandardCharsets

/** Builds a swipe-refresh + mobile YouTube WebView host. */
object WebViewFactory {

    private const val TAG = "WebViewFactory"
    private const val NAV_SCRIPT_ASSET = "script/nav.js"

    @Volatile
    private var navScript: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun create(
        context: Context,
        callbacks: WebViewCallbacks,
        onRefresh: (WebView) -> Unit,
    ): BrowserHost {
        val appContext = context.applicationContext
        val webView = WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            isFocusable = true
            isFocusableInTouchMode = true
            setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            settings.apply {
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
                userAgentString = Constants.USER_AGENT
            }
            addJavascriptInterface(Bridge(callbacks::onOpenTab), Bridge.NAME)
            webViewClient = BrowserWebViewClient(appContext, callbacks)
            webChromeClient = BrowserChromeClient(callbacks)
        }

        val swipeRefresh = SwipeRefreshLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setColorSchemeColors(YtRed.toArgb())
            setProgressViewOffset(true, 24, 96)
            addView(webView)
            setOnRefreshListener { onRefresh(webView) }
        }

        return BrowserHost(swipeRefresh, webView)
    }

    internal fun injectNavScript(context: Context, webView: WebView) {
        val script = loadNavScript(context) ?: return
        webView.evaluateJavascript(script, null)
    }

    private fun loadNavScript(context: Context): String? {
        navScript?.let { return it }
        return try {
            context.assets.open(NAV_SCRIPT_ASSET)
                .bufferedReader(StandardCharsets.UTF_8)
                .use { it.readText() }
                .also { navScript = it }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $NAV_SCRIPT_ASSET", e)
            null
        }
    }
}

data class BrowserHost(
    val container: SwipeRefreshLayout,
    val webView: WebView,
)

private class BrowserWebViewClient(
    private val appContext: Context,
    private val callbacks: WebViewCallbacks,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        val url = uri.toString()
        if (uri.scheme.equals("intent", ignoreCase = true)) {
            return openExternal(view.context, uri)
        }
        if (!UrlPolicy.canLoad(url)) {
            return openExternal(view.context, uri)
        }
        val current = view.url
        if (!current.isNullOrBlank()) {
            val nextKind = PageKind.of(url)
            if (nextKind != "unknown" && nextKind != PageKind.of(current)) {
                callbacks.onOpenTab(url)
                return true
            }
        }
        return false
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        WebViewFactory.injectNavScript(appContext, view)
        callbacks.onPageStarted(url)
        callbacks.onNavigationStateChanged(view.canGoBack())
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        WebViewFactory.injectNavScript(appContext, view)
        callbacks.onPageFinished(url)
        callbacks.onNavigationStateChanged(view.canGoBack())
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        callbacks.onHistoryChanged(url)
        callbacks.onNavigationStateChanged(view.canGoBack())
    }

    private fun openExternal(context: Context, uri: Uri): Boolean {
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: ActivityNotFoundException) {
            true
        }
    }
}

private class BrowserChromeClient(
    private val callbacks: WebViewCallbacks,
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        callbacks.onProgressChanged(newProgress)
        super.onProgressChanged(view, newProgress)
    }

    override fun getDefaultVideoPoster(): Bitmap = createBitmap(1, 1)
}
