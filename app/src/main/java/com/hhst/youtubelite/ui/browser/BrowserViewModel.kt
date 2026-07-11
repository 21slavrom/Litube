package com.hhst.youtubelite.ui.browser

import androidx.lifecycle.ViewModel
import com.hhst.youtubelite.browser.WebViewCallbacks
import com.hhst.youtubelite.core.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Browser UI state: loading progress, pull-to-refresh, back stack. */
class BrowserViewModel : ViewModel(), WebViewCallbacks {

    private val _uiState = MutableStateFlow(BrowserUiState(startUrl = Constants.HOME_URL))
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    override fun onPageStarted(url: String) {
        _uiState.update {
            it.copy(currentUrl = url, isLoading = true, progress = 0.06f)
        }
    }

    override fun onPageFinished(url: String) {
        _uiState.update {
            it.copy(
                currentUrl = url,
                isLoading = false,
                progress = 1f,
                isRefreshing = false,
            )
        }
    }

    override fun onProgressChanged(progress: Int) {
        val normalized = progress.coerceIn(0, 100) / 100f
        if (normalized >= 1f) {
            onPageFinished(_uiState.value.currentUrl)
            return
        }
        _uiState.update {
            it.copy(isLoading = true, progress = maxOf(it.progress, normalized))
        }
    }

    override fun onNavigationStateChanged(canGoBack: Boolean) {
        _uiState.update { it.copy(canGoBack = canGoBack) }
    }

    fun onRefreshStarted() {
        _uiState.update {
            it.copy(isRefreshing = true, isLoading = true, progress = 0.06f)
        }
    }
}

data class BrowserUiState(
    val startUrl: String,
    val currentUrl: String = startUrl,
    val isLoading: Boolean = false,
    val progress: Float = 0f,
    val isRefreshing: Boolean = false,
    val canGoBack: Boolean = false,
)
