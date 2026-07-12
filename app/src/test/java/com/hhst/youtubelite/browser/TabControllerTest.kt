package com.hhst.youtubelite.browser

import com.hhst.youtubelite.core.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabControllerTest {

    @Test
    fun openNav_keepsHomeAndTargetOnly() {
        val tabs = TabController()
        tabs.openTab("https://m.youtube.com/shorts")
        val state = tabs.openTab("https://m.youtube.com/feed/subscriptions").first

        assertEquals(
            setOf(Constants.PAGE_HOME, Constants.PAGE_SUBSCRIPTIONS),
            state.tabs.map { it.kind }.toSet(),
        )
        assertEquals(Constants.PAGE_SUBSCRIPTIONS, state.active?.kind)
    }

    @Test
    fun openWatch_reusesTab_popReturnsPrevious() {
        val tabs = TabController()
        tabs.openTab("https://m.youtube.com/@A")
        tabs.openTab("https://m.youtube.com/watch?v=a")
        tabs.openTab("https://m.youtube.com/@B")
        tabs.openTab("https://m.youtube.com/@C")
        val (state, load) = tabs.openTab("https://m.youtube.com/watch?v=b")

        assertEquals(1, state.tabs.count { it.kind == Constants.PAGE_WATCH })
        assertEquals(state.activeId to "https://m.youtube.com/watch?v=b", load)
        assertEquals("https://m.youtube.com/@C", tabs.pop()?.active?.url)
        assertEquals("https://m.youtube.com/@B", tabs.pop()?.active?.url)
    }

    @Test
    fun pop_onHomeOnly_isNoOp() {
        val tabs = TabController()
        assertFalse(tabs.canPop())
        assertNull(tabs.pop())
    }
}
