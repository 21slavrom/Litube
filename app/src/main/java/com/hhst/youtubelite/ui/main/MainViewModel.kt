package com.hhst.youtubelite.ui.main

import androidx.lifecycle.ViewModel
import com.hhst.youtubelite.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds UI state for the scaffold home screen.
 *
 * Exists to verify Koin ViewModel injection and Compose state collection.
 */
class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(
        MainUiState(
            title = "Litube",
            label = "v${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_TYPE}",
        ),
    )

    /** Immutable stream of home UI state. */
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
}

/**
 * Render model for the scaffold home screen.
 *
 * @property title primary product name.
 * @property label secondary build metadata (version and build type).
 */
data class MainUiState(
    val title: String,
    val label: String,
)
