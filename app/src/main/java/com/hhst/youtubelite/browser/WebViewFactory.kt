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
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.hhst.youtubelite.core.Constants
import com.hhst.youtubelite.extension.ExtensionInjector
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extractor.Extractor
import com.hhst.youtubelite.extractor.PlayerCache
import com.hhst.youtubelite.extractor.VideoId
import com.hhst.youtubelite.net.NetTracer
import com.hhst.youtubelite.net.PageScript
import com.hhst.youtubelite.ui.theme.YtRed
import java.io.ByteArrayInputStream

/** Builds a swipe-refresh + mobile YouTube WebView host. */
object WebViewFactory {

    private const val NAV_JS = "script/nav.js"
    private const val WATCH_ID_JS = "script/watch-id.js"
    private const val INNERTUBE_JS = "script/innertube.js"
    /** Master-branch interface name kept for injected page scripts. */
    private const val LITE_ALIAS = "lite"

    private val navScript = PageScript(NAV_JS, "WebViewFactory")

    @SuppressLint("SetJavaScriptEnabled")
    fun create(
        context: Context,
        callbacks: WebViewCallbacks,
        extensionManager: ExtensionManager,
        onOpenExtension: () -> Unit,
        onRefresh: (WebView) -> Unit,
        extractor: Extractor,
        playerCache: PlayerCache,
    ): BrowserHost {
        val appContext = context.applicationContext
        val injector = ExtensionInjector(appContext)
        val bridge = Bridge(
            onOpenTab = callbacks::onOpenTab,
            onOpenExtension = onOpenExtension,
            extensionManager = extensionManager,
            extractor = extractor,
        )
        val netTracer = NetTracer()
        val watchId = PageScript(WATCH_ID_JS, "WatchId")
        val innertube = PageScript(INNERTUBE_JS, "Innertube")
        val swipeRefresh = SwipeRefreshLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setColorSchemeColors(YtRed.toArgb())
            setProgressViewOffset(true, 24, 96)
        }
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
            addJavascriptInterface(bridge, Bridge.NAME)
            addJavascriptInterface(bridge, LITE_ALIAS)
            netTracer.install(appContext, this)
            watchId.install(appContext, this)
            innertube.install(appContext, this)
            webViewClient = BrowserWebViewClient(
                appContext = appContext,
                callbacks = callbacks,
                injector = injector,
                netTracer = netTracer,
                watchId = watchId,
                innertube = innertube,
                bridge = bridge,
                playerCache = playerCache,
                swipeRefresh = swipeRefresh,
            )
            webChromeClient = BrowserChromeClient(callbacks)
        }

        swipeRefresh.addView(webView)
        swipeRefresh.setOnRefreshListener { onRefresh(webView) }

        return BrowserHost(swipeRefresh, webView)
    }

    internal fun injectNavScript(context: Context, webView: WebView) {
        navScript.inject(context, webView)
    }
}

data class BrowserHost(
    val container: SwipeRefreshLayout,
    val webView: WebView,
)

private class BrowserWebViewClient(
    private val appContext: Context,
    private val callbacks: WebViewCallbacks,
    private val injector: ExtensionInjector,
    private val netTracer: NetTracer,
    private val watchId: PageScript,
    private val innertube: PageScript,
    private val bridge: Bridge,
    private val playerCache: PlayerCache,
    private val swipeRefresh: SwipeRefreshLayout,
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        if (isPlayerPost(request)) {
            // Prefer the id parsed from the POST body; fall back to the referer,
            // which Chromium stamps after pushState on SPA navigations.
            val videoId = bridge.pendingVideoId
                ?: videoIdFromReferer(request.requestHeaders)
            if (videoId != null) {
                respondPlayer(videoId)?.let { return it }
                if (playerCache.await(videoId, PLAYER_WAIT_MS)) {
                    respondPlayer(videoId)?.let { return it }
                }
            }
        }
        return super.shouldInterceptRequest(view, request)
    }

    private fun videoIdFromReferer(headers: Map<String, String>?): String? {
        val referer = headers?.entries
            ?.firstOrNull { it.key.equals("Referer", ignoreCase = true) }
            ?.value
        return VideoId.parse(referer)
    }

    private fun isPlayerPost(request: WebResourceRequest): Boolean =
        request.method.equals("POST", ignoreCase = true) &&
            request.url.toString().contains(PLAYER_PATH, ignoreCase = true)

    private fun respondPlayer(videoId: String): WebResourceResponse? {
        val bytes = playerCache.get(videoId) ?: return null
        Log.d(TAG, "player cache hit $videoId")
        return WebResourceResponse(
            "application/json",
            "utf-8",
            200,
            "OK",
            emptyMap(),
            ByteArrayInputStream(bytes),
        )
    }

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
        updateRefreshEnabled(url)
        WebViewFactory.injectNavScript(appContext, view)
        netTracer.inject(appContext, view)
        watchId.inject(appContext, view)
        innertube.inject(appContext, view)
        injector.inject(view)
        callbacks.onPageStarted(url)
        callbacks.onNavigationStateChanged(view.canGoBack())
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        WebViewFactory.injectNavScript(appContext, view)
        netTracer.inject(appContext, view)
        watchId.inject(appContext, view)
        innertube.inject(appContext, view)
        injector.inject(view)
        callbacks.onPageFinished(url)
        callbacks.onNavigationStateChanged(view.canGoBack())
        netTracer.dump()
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        // SPA navigations often skip full reloads; re-run inject for settings.
        updateRefreshEnabled(url)
        injector.inject(view)
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

    /** Pull-to-refresh only on pages without conflicting vertical gestures. */
    private fun updateRefreshEnabled(url: String) {
        swipeRefresh.isEnabled = PageKind.of(url) in REFRESH_KINDS
    }

    private companion object {
        const val TAG = "WebViewClient"
        const val PLAYER_PATH = "/youtubei/v1/player"
        const val PLAYER_WAIT_MS = 2000L
        val REFRESH_KINDS = setOf(
            Constants.PAGE_HOME,
            Constants.PAGE_SUBSCRIPTIONS,
            Constants.PAGE_LIBRARY,
            "@",
        )
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
