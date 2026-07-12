package com.hhst.youtubelite.ui.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ViewGroup
import android.webkit.WebView
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hhst.youtubelite.R
import com.hhst.youtubelite.browser.Bridge
import com.hhst.youtubelite.browser.BrowserHost
import com.hhst.youtubelite.browser.Tab
import com.hhst.youtubelite.browser.WebViewFactory
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel

/** Multi-tab WebView browser with a short fade + slide on tab switch. */
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current

    val hosts = remember { mutableStateMapOf<Long, BrowserHost>() }
    val activeId = uiState.activeId
    val forward = uiState.forward
    val tabs = uiState.tabs
    val tabIds = remember(tabs) { tabs.map { it.id }.toSet() }

    // Host create/destroy in effects so composition stays free of WebView side effects.
    LaunchedEffect(tabIds) {
        createHosts(tabs, hosts, context, viewModel)
        delay(TAB_DESTROY_DELAY_MS)
        hosts.keys.filter { it !in tabIds }.forEach { id ->
            hosts.remove(id)?.let { destroyHost(it) }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.loadRequests.collect { (tabId, url) ->
            hosts[tabId]?.webView?.loadUrl(url)
        }
    }

    // History is per WebView; re-read after switch (inactive tabs skip nav callbacks).
    LaunchedEffect(activeId, hosts[activeId]) {
        val webView = hosts[activeId]?.webView ?: return@LaunchedEffect
        viewModel.canGoBack = webView.canGoBack()
        hosts.forEach { (id, host) ->
            if (id == activeId) host.webView.onResume() else host.webView.onPause()
        }
    }

    val currentActiveId by rememberUpdatedState(activeId)
    val currentHosts by rememberUpdatedState(hosts)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val active = currentHosts[currentActiveId]?.webView
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    active?.onResume()
                    active?.resumeTimers()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    currentHosts.values.forEach { it.webView.onPause() }
                    active?.pauseTimers()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            hosts.values.forEach { destroyHost(it) }
            hosts.clear()
        }
    }

    val latestHosts = rememberUpdatedState(hosts)
    val latestActiveId = rememberUpdatedState(activeId)
    val exitHint = stringResource(R.string.back_again_to_exit)
    var lastFinishAt by remember { mutableLongStateOf(0L) }
    // Always consume Back. Decide from live WebView + live tab stack (not lagged ui flags).
    BackHandler {
        val webView = latestHosts.value[latestActiveId.value]?.webView
        when (viewModel.onBack(webCanGoBack = webView?.canGoBack() == true)) {
            BackResult.GoWebBack -> {
                webView?.goBack()
                viewModel.canGoBack = webView?.canGoBack() == true
            }
            BackResult.Handled -> Unit
            BackResult.Finish -> {
                val now = SystemClock.elapsedRealtime()
                if (now - lastFinishAt < 2000L) {
                    activity?.finish()
                } else {
                    lastFinishAt = now
                    Toast.makeText(context, exitHint, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        AnimatedContent(
            targetState = activeId,
            transitionSpec = {
                val offset = { width: Int -> width / 14 }
                if (forward) {
                    (
                        fadeIn(animationSpec = tween(TAB_FADE_MS)) +
                            slideInHorizontally(tween(TAB_SLIDE_MS), offset)
                        ) togetherWith (
                        fadeOut(animationSpec = tween(TAB_FADE_MS)) +
                            slideOutHorizontally(tween(TAB_SLIDE_MS)) { -offset(it) }
                        )
                } else {
                    (
                        fadeIn(animationSpec = tween(TAB_FADE_MS)) +
                            slideInHorizontally(tween(TAB_SLIDE_MS)) { -offset(it) }
                        ) togetherWith (
                        fadeOut(animationSpec = tween(TAB_FADE_MS)) +
                            slideOutHorizontally(tween(TAB_SLIDE_MS), offset)
                        )
                }
            },
            label = "tabSwitch",
            modifier = Modifier.fillMaxSize(),
        ) { tabId ->
            val host = hosts[tabId]
            if (host != null) {
                AndroidView(
                    factory = {
                        (host.container.parent as? ViewGroup)?.removeView(host.container)
                        host.container
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { container ->
                        container.isRefreshing =
                            tabId == uiState.activeId && uiState.isRefreshing
                    },
                )
            } else {
                Box(modifier = Modifier.fillMaxSize())
            }
        }

        LoadingBar(
            progress = uiState.progress,
            visible = uiState.isLoading,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

private fun createHosts(
    tabs: List<Tab>,
    hosts: SnapshotStateMap<Long, BrowserHost>,
    context: Context,
    viewModel: BrowserViewModel,
) {
    for (tab in tabs) {
        if (tab.id in hosts) continue
        val tabId = tab.id
        hosts[tabId] = WebViewFactory.create(
            context = context,
            callbacks = viewModel.callbacksFor(tabId),
            onRefresh = { webView ->
                viewModel.onRefreshStarted(tabId)
                webView.reload()
            },
        ).also { it.webView.loadUrl(tab.url) }
    }
}

private fun destroyHost(host: BrowserHost) {
    host.container.isRefreshing = false
    val webView: WebView = host.webView
    webView.stopLoading()
    webView.removeJavascriptInterface(Bridge.NAME)
    webView.loadUrl("about:blank")
    (webView.parent as? ViewGroup)?.removeView(webView)
    (host.container.parent as? ViewGroup)?.removeView(host.container)
    webView.destroy()
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return ctx as? Activity
}

private const val TAB_FADE_MS = 180
private const val TAB_SLIDE_MS = 220
private val TAB_DESTROY_DELAY_MS = maxOf(TAB_FADE_MS, TAB_SLIDE_MS) + 40L
