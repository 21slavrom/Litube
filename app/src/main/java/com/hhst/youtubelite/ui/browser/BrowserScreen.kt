package com.hhst.youtubelite.ui.browser

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hhst.youtubelite.browser.LiteWebViewFactory
import org.koin.androidx.compose.koinViewModel

/**
 * Primary browser screen: WebView, top loading bar, and pull-to-refresh.
 *
 * Applies [WindowInsets.safeDrawing] so content clears the status bar, cutout,
 * and navigation bar while edge-to-edge is enabled.
 *
 * @param viewModel browser chrome state and WebView callbacks.
 */
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val browserHost = remember(context) {
        LiteWebViewFactory.create(
            context = context,
            callbacks = viewModel,
            onRefresh = { webView ->
                viewModel.onRefreshStarted()
                webView.reload()
            },
        ).also { host ->
            host.webView.loadUrl(uiState.startUrl)
        }
    }

    DisposableEffect(lifecycleOwner, browserHost) {
        val webView = browserHost.webView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    webView.onResume()
                    webView.resumeTimers()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    webView.onPause()
                    webView.pauseTimers()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            browserHost.container.isRefreshing = false
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

    BackHandler(enabled = uiState.canGoBack) {
        browserHost.webView.goBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        AndroidView(
            factory = { browserHost.container },
            modifier = Modifier.fillMaxSize(),
            update = { container ->
                container.isRefreshing = uiState.isRefreshing
            },
        )
        LoadingBar(
            progress = uiState.progress,
            visible = uiState.isLoading,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}
