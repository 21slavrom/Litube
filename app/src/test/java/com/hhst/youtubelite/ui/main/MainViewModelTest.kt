package com.hhst.youtubelite.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainViewModelTest {

    @Test
    fun initialState_exposesTitleAndLabel() {
        val state = MainViewModel().uiState.value

        assertEquals("Litube", state.title)
        assertTrue(state.label.startsWith("v"))
        assertTrue(state.label.contains("·"))
    }
}
