package com.hhst.youtubelite.ui.extension

import androidx.lifecycle.ViewModel
import com.hhst.youtubelite.extension.Extension
import com.hhst.youtubelite.extension.ExtensionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Expandable section state for the extension catalog. */
class ExtensionViewModel(
    private val manager: ExtensionManager,
) : ViewModel() {

    private val sections = Extension.catalog()

    private val _uiState = MutableStateFlow(
        ExtensionUiState(
            sections = sections,
            expanded = emptySet(),
            toggles = snapshotToggles(sections),
        ),
    )
    val uiState: StateFlow<ExtensionUiState> = _uiState.asStateFlow()

    fun toggleExpanded(id: String) {
        _uiState.update { state ->
            val next = if (id in state.expanded) {
                state.expanded - id
            } else {
                state.expanded + id
            }
            state.copy(expanded = next)
        }
    }

    fun setEnabled(key: String, enabled: Boolean) {
        manager.setEnabled(key, enabled)
        _uiState.update { it.copy(toggles = snapshotToggles(sections)) }
    }

    fun resetToDefault() {
        manager.resetToDefault()
        _uiState.update { it.copy(toggles = snapshotToggles(sections)) }
    }

    private fun snapshotToggles(nodes: List<Extension>): Map<String, Boolean> {
        val out = linkedMapOf<String, Boolean>()
        fun walk(list: List<Extension>) {
            for (node in list) {
                val key = node.key
                if (key != null) {
                    out[key] = manager.isEnabled(key)
                } else {
                    walk(node.children)
                }
            }
        }
        walk(nodes)
        return out
    }
}

data class ExtensionUiState(
    val sections: List<Extension>,
    val expanded: Set<String>,
    val toggles: Map<String, Boolean>,
)
