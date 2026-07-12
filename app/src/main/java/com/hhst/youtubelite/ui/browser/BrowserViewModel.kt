package com.hhst.youtubelite.ui.browser

import androidx.lifecycle.ViewModel
import com.hhst.youtubelite.browser.PageKind
import com.hhst.youtubelite.browser.Tab
import com.hhst.youtubelite.browser.TabController
import com.hhst.youtubelite.browser.TabState
import com.hhst.youtubelite.browser.UrlPolicy
import com.hhst.youtubelite.browser.WebViewCallbacks
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Multi-tab browser state and routing. */
class BrowserViewModel : ViewModel() {

    private val tabs = TabController()
    private val canGoBackByTab = mutableMapOf<Long, Boolean>()

    private val _uiState = MutableStateFlow(BrowserUiState.from(tabs.state()))
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private val _loadRequests = MutableSharedFlow<Pair<Long, String>>(extraBufferCapacity = 16)
    /** Loads for an already-created host: `(tabId, url)`. */
    val loadRequests: SharedFlow<Pair<Long, String>> = _loadRequests.asSharedFlow()

    /** Active-tab history back; assign from the visible WebView after switches. */
    var canGoBack: Boolean
        get() = _uiState.value.canGoBack
        set(value) {
            canGoBackByTab[_uiState.value.activeId] = value
            _uiState.update { it.copy(canGoBack = value) }
        }

    fun openTab(url: String) {
        if (!UrlPolicy.isAllowedUrl(url)) return
        if (PageKind.of(url) == "unknown") return

        val previousActive = _uiState.value.activeId
        val existingIds = _uiState.value.tabs.mapTo(mutableSetOf()) { it.id }
        val (state, load) = tabs.openTab(url)
        val switched = state.activeId != previousActive
        publish(state, resetLoading = switched, forward = if (switched) true else null)
        // New tabs load on host create; existing tabs need an explicit load.
        if (load != null && load.first in existingIds) {
            _loadRequests.tryEmit(load)
        }
    }

    /** System Back: WebView history -> pop tab -> leave app. */
    fun onBack(webCanGoBack: Boolean): BackResult {
        if (webCanGoBack) return BackResult.GoWebBack
        if (tabs.canPop()) {
            val state = tabs.pop() ?: return BackResult.Finish
            publish(state, resetLoading = true, forward = false)
            return BackResult.Handled
        }
        return BackResult.Finish
    }

    fun onRefreshStarted(tabId: Long) {
        if (_uiState.value.activeId != tabId) return
        _uiState.update {
            it.copy(isRefreshing = true, isLoading = true, progress = 0.06f)
        }
    }

    fun callbacksFor(tabId: Long): WebViewCallbacks = object : WebViewCallbacks {
        override fun onPageStarted(url: String) {
            tabs.updateUrl(tabId, url)
            if (_uiState.value.activeId != tabId) return
            _uiState.update {
                it.copy(url = url, isLoading = true, progress = 0.06f)
            }
        }

        override fun onPageFinished(url: String) {
            tabs.updateUrl(tabId, url)
            if (_uiState.value.activeId != tabId) return
            _uiState.update {
                it.copy(
                    url = url,
                    isLoading = false,
                    progress = 1f,
                    isRefreshing = false,
                )
            }
        }

        override fun onProgressChanged(progress: Int) {
            if (_uiState.value.activeId != tabId) return
            val normalized = progress.coerceIn(0, 100) / 100f
            if (normalized >= 1f) {
                onPageFinished(_uiState.value.url)
                return
            }
            _uiState.update {
                it.copy(isLoading = true, progress = maxOf(it.progress, normalized))
            }
        }

        override fun onNavigationStateChanged(canGoBack: Boolean) {
            canGoBackByTab[tabId] = canGoBack
            if (_uiState.value.activeId != tabId) return
            _uiState.update { it.copy(canGoBack = canGoBack) }
        }

        override fun onHistoryChanged(url: String) {
            tabs.updateUrl(tabId, url)
            if (_uiState.value.activeId != tabId) return
            _uiState.update { it.copy(url = url) }
        }

        override fun onOpenTab(url: String) {
            openTab(url)
        }
    }

    private fun publish(
        state: TabState,
        resetLoading: Boolean,
        forward: Boolean? = null,
    ) {
        val liveIds = state.tabs.mapTo(mutableSetOf()) { it.id }
        canGoBackByTab.keys.retainAll(liveIds)
        val active = state.active
        _uiState.update {
            it.copy(
                tabs = state.tabs,
                activeId = state.activeId,
                url = active?.url ?: it.url,
                isLoading = if (resetLoading) false else it.isLoading,
                progress = if (resetLoading) 0f else it.progress,
                isRefreshing = if (resetLoading) false else it.isRefreshing,
                canGoBack = canGoBackByTab[state.activeId] ?: false,
                canPop = state.tabs.size > 1,
                forward = forward ?: it.forward,
            )
        }
    }
}

/** Outcome of [BrowserViewModel.onBack] for the host to apply. */
enum class BackResult {
    /** Tab popped; no further action needed. */
    Handled,
    /** WebView still has history; host should call [WebView.goBack]. */
    GoWebBack,
    /** Nothing left to go back to; host should leave the app. */
    Finish,
}

data class BrowserUiState(
    val tabs: List<Tab>,
    val activeId: Long,
    val url: String,
    val isLoading: Boolean = false,
    val progress: Float = 0f,
    val isRefreshing: Boolean = false,
    val canGoBack: Boolean = false,
    val canPop: Boolean = false,
    /** Slide direction for the next tab transition. */
    val forward: Boolean = true,
) {
    companion object {
        fun from(state: TabState): BrowserUiState {
            val active = state.active
            return BrowserUiState(
                tabs = state.tabs,
                activeId = state.activeId,
                url = active?.url.orEmpty(),
                canPop = state.tabs.size > 1,
            )
        }
    }
}
