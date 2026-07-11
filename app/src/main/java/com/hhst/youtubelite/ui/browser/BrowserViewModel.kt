package com.hhst.youtubelite.ui.browser

import androidx.lifecycle.ViewModel
import com.hhst.youtubelite.browser.LiteWebViewCallbacks
import com.hhst.youtubelite.core.LiteConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Holds browser chrome state for the Compose UI.
 *
 * Implements [LiteWebViewCallbacks] so WebView clients can update progress and
 * navigation state without holding a Compose reference.
 */
class BrowserViewModel : ViewModel(), LiteWebViewCallbacks {

    private val _uiState = MutableStateFlow(
        BrowserUiState(startUrl = LiteConstants.HOME_URL),
    )

    /** Observable browser chrome state. */
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    override fun onPageStarted(url: String) {
        _uiState.update { state ->
            state.copy(
                currentUrl = url,
                isLoading = true,
                progress = INITIAL_PROGRESS,
            )
        }
    }

    override fun onPageFinished(url: String) {
        _uiState.update { state ->
            state.copy(
                currentUrl = url,
                isLoading = false,
                progress = COMPLETE_PROGRESS,
                isRefreshing = false,
            )
        }
    }

    override fun onProgressChanged(progress: Int) {
        val normalized = progress.coerceIn(0, 100) / 100f
        if (normalized >= COMPLETE_PROGRESS) {
            onPageFinished(_uiState.value.currentUrl)
            return
        }
        _uiState.update { state ->
            state.copy(
                isLoading = true,
                progress = maxOf(state.progress, normalized),
            )
        }
    }

    override fun onNavigationStateChanged(canGoBack: Boolean) {
        _uiState.update { state -> state.copy(canGoBack = canGoBack) }
    }

    /** Starts pull-to-refresh chrome until the next [onPageFinished]. */
    fun onRefreshStarted() {
        _uiState.update { state ->
            state.copy(
                isRefreshing = true,
                isLoading = true,
                progress = INITIAL_PROGRESS,
            )
        }
    }

    private companion object {
        const val INITIAL_PROGRESS = 0.06f
        const val COMPLETE_PROGRESS = 1f
    }
}

/**
 * Immutable browser chrome model for Compose.
 *
 * @property startUrl initial URL loaded into the WebView.
 * @property currentUrl last observed navigation URL.
 * @property isLoading whether the top loading bar should be visible.
 * @property progress loading fraction in the range `0f..1f`.
 * @property isRefreshing whether the pull-to-refresh indicator is active.
 * @property canGoBack whether WebView history can navigate back.
 */
data class BrowserUiState(
    val startUrl: String,
    val currentUrl: String = startUrl,
    val isLoading: Boolean = false,
    val progress: Float = 0f,
    val isRefreshing: Boolean = false,
    val canGoBack: Boolean = false,
)
