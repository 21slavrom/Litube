package com.hhst.youtubelite.ui.browser

import com.hhst.youtubelite.core.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserViewModelTest {

    @Test
    fun openTab_rejectsDisallowedUrl() {
        val vm = BrowserViewModel()
        vm.openTab("https://evil.example/phish")

        assertEquals(1, vm.uiState.value.tabs.size)
        assertEquals(Constants.PAGE_HOME, activeKind(vm))
    }

    @Test
    fun openTab_opensWatchForward() {
        val vm = BrowserViewModel()
        vm.openTab("https://m.youtube.com/watch?v=1")

        assertEquals(Constants.PAGE_WATCH, activeKind(vm))
        assertTrue(vm.uiState.value.forward)
        assertTrue(vm.uiState.value.canPop)
    }

    @Test
    fun openTab_reusesNavAndUpdatesUrl() {
        val vm = BrowserViewModel()
        vm.openTab("https://m.youtube.com/shorts/a")
        val id = vm.uiState.value.activeId

        vm.openTab("https://m.youtube.com/shorts/b")

        assertEquals(id, vm.uiState.value.activeId)
        assertEquals("https://m.youtube.com/shorts/b", vm.uiState.value.url)
    }

    @Test
    fun onBack_webBackBeforePopBeforeFinish() {
        val vm = BrowserViewModel()
        vm.openTab("https://m.youtube.com/watch?v=1")
        assertTrue(vm.uiState.value.canPop)

        // WebView still has history -> consume it first, don't drop the tab.
        assertEquals(BackResult.GoWebBack, vm.onBack(webCanGoBack = true))
        assertEquals(Constants.PAGE_WATCH, activeKind(vm))
        assertTrue(vm.uiState.value.canPop)

        // WebView history drained -> now drop the watch tab back to home.
        assertEquals(BackResult.Handled, vm.onBack(webCanGoBack = false))
        assertEquals(Constants.PAGE_HOME, activeKind(vm))
        assertFalse(vm.uiState.value.canPop)

        // Only home remains, no web history -> leave the app.
        assertEquals(BackResult.Finish, vm.onBack(webCanGoBack = false))
    }

    private fun activeKind(vm: BrowserViewModel): String? {
        val state = vm.uiState.value
        return state.tabs.find { it.id == state.activeId }?.kind
    }
}
