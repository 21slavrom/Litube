package com.hhst.youtubelite.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserViewModelTest {

    @Test
    fun onPageStarted_showsLoadingBar() {
        val viewModel = BrowserViewModel()
        viewModel.onPageStarted("https://m.youtube.com")

        val state = viewModel.uiState.value
        assertTrue(state.isLoading)
        assertTrue(state.progress > 0f)
        assertEquals("https://m.youtube.com", state.currentUrl)
    }

    @Test
    fun onProgressChanged_advancesAndFinishes() {
        val viewModel = BrowserViewModel()
        viewModel.onPageStarted("https://m.youtube.com/watch?v=1")
        viewModel.onProgressChanged(40)

        assertTrue(viewModel.uiState.value.isLoading)
        assertTrue(viewModel.uiState.value.progress >= 0.4f)

        viewModel.onProgressChanged(100)
        val finished = viewModel.uiState.value
        assertFalse(finished.isLoading)
        assertEquals(1f, finished.progress, 0.001f)
        assertFalse(finished.isRefreshing)
    }

    @Test
    fun onRefreshStarted_setsRefreshingFlag() {
        val viewModel = BrowserViewModel()
        viewModel.onRefreshStarted()

        val state = viewModel.uiState.value
        assertTrue(state.isRefreshing)
        assertTrue(state.isLoading)
    }

    @Test
    fun onNavigationStateChanged_updatesBackAvailability() {
        val viewModel = BrowserViewModel()
        viewModel.onNavigationStateChanged(true)
        assertTrue(viewModel.uiState.value.canGoBack)

        viewModel.onNavigationStateChanged(false)
        assertFalse(viewModel.uiState.value.canGoBack)
    }
}
