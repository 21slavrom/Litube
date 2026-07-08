package com.hhst.youtubelite.player.controller

import androidx.annotation.DrawableRes
import com.hhst.youtubelite.R

/**
 * Immutable state snapshot for the playback controller. Each transition returns
 * a new instance so snapshots can be compared cheaply and rendered idempotently.
 */
class ControllerState private constructor(
    val mode: Mode,
    private val prevModePip: Mode,
    private val prevModeMini: Mode,
    val controlsVisible: Boolean,
) {

    val isFullscreen: Boolean
        get() = mode.isFullscreen

    val isLocked: Boolean
        get() = mode == Mode.FULLSCREEN_LOCK

    val isInPictureInPicture: Boolean
        get() = mode == Mode.PIP

    val isInMiniPlayer: Boolean
        get() = mode == Mode.MINI_PLAYER

    fun withControlsVisible(visible: Boolean): ControllerState {
        val nextVisible = !isInPictureInPicture && visible
        if (controlsVisible == nextVisible) return this
        return ControllerState(mode, prevModePip, prevModeMini, nextVisible)
    }

    fun enterFullscreen(): ControllerState =
        ControllerState(Mode.FULLSCREEN_UNLOCK, prevModePip, prevModeMini, true)

    fun exitFullscreen(): ControllerState =
        ControllerState(Mode.NORMAL, Mode.NORMAL, Mode.NORMAL, true)

    fun toggleLock(): ControllerState = when (mode) {
        Mode.FULLSCREEN_UNLOCK -> ControllerState(Mode.FULLSCREEN_LOCK, prevModePip, prevModeMini, true)
        Mode.FULLSCREEN_LOCK -> ControllerState(Mode.FULLSCREEN_UNLOCK, prevModePip, prevModeMini, true)
        else -> this
    }

    fun enterMiniPlayer(): ControllerState {
        if (mode == Mode.MINI_PLAYER) return this
        val restoreMode = if (mode == Mode.PIP) prevModePip else mode
        return ControllerState(Mode.MINI_PLAYER, prevModePip, restoreMode, true)
    }

    fun exitMiniPlayer(): ControllerState {
        if (mode != Mode.MINI_PLAYER) return this
        return ControllerState(prevModeMini, prevModePip, prevModeMini, true)
    }

    fun enterPip(): ControllerState {
        if (mode == Mode.PIP) return this
        return ControllerState(Mode.PIP, mode, prevModeMini, false)
    }

    fun exitPip(): ControllerState {
        if (mode != Mode.PIP) return this
        return ControllerState(prevModePip, prevModePip, prevModeMini, true)
    }

    fun renderState(buffering: Boolean, zoomed: Boolean, casting: Boolean): RenderState =
        toRenderState(this, buffering, zoomed, casting)

    fun uiState(buffering: Boolean, zoomed: Boolean, casting: Boolean): UiState {
        val locked = isLocked
        val fullscreen = mode.isFullscreen
        val fullscreenLayout = fullscreen || isInPictureInPicture
        val overlaysVisible = controlsVisible && !locked && !isInPictureInPicture && !isInMiniPlayer
        // While casting the phone is a remote: keep the center play/pause button
        // visible even during BUFFERING so the user can always control the receiver,
        // but hide the local progress bar since the phone timeline is irrelevant.
        return UiState(
            fullscreen = fullscreen,
            fullscreenLayout = fullscreenLayout,
            otherVisible = overlaysVisible,
            centerVisible = overlaysVisible && (casting || !buffering),
            progressVisible = overlaysVisible && !casting,
            lockVisible = controlsVisible && fullscreen,
            resetVisible = overlaysVisible && fullscreen && zoomed,
            miniVisible = controlsVisible && isInMiniPlayer,
            scrimVisible = controlsVisible && isInMiniPlayer,
            fullscreenIconRes = if (fullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen,
            lockIconRes = if (locked) R.drawable.ic_lock else R.drawable.ic_unlock,
        )
    }

    /**
     * Top-level player surface mode. Only one is active at a time; transitions
     * are driven by [ControllerState] factory methods so the prev-mode fields
     * stay consistent.
     */
    enum class Mode {
        NORMAL, FULLSCREEN_UNLOCK, FULLSCREEN_LOCK, MINI_PLAYER, PIP;

        val isFullscreen: Boolean
            get() = this == FULLSCREEN_UNLOCK || this == FULLSCREEN_LOCK
    }

    /** Derived visibility flags consumed by the layout pass. */
    data class UiState(
        val fullscreen: Boolean,
        val fullscreenLayout: Boolean,
        val otherVisible: Boolean,
        val centerVisible: Boolean,
        val progressVisible: Boolean,
        val lockVisible: Boolean,
        val resetVisible: Boolean,
        val miniVisible: Boolean,
        val scrimVisible: Boolean,
        @DrawableRes val fullscreenIconRes: Int,
        @DrawableRes val lockIconRes: Int,
    )

    /** Snapshot of every visibility bit [Controller.applyRenderState] needs. */
    data class RenderState(
        val controlsVisible: Boolean,
        val centerVisible: Boolean,
        val otherVisible: Boolean,
        val progressVisible: Boolean,
        val resetVisible: Boolean,
        val lockVisible: Boolean,
        val miniVisible: Boolean,
        val scrimVisible: Boolean,
        val locked: Boolean,
        val fullscreen: Boolean,
        val pip: Boolean,
        @DrawableRes val fullscreenIconRes: Int,
        @DrawableRes val lockIconRes: Int,
    )

    companion object {
        @JvmStatic
        fun initial(): ControllerState = ControllerState(Mode.NORMAL, Mode.NORMAL, Mode.NORMAL, false)

        private fun toRenderState(
            state: ControllerState,
            buffering: Boolean,
            zoomed: Boolean,
            casting: Boolean,
        ): RenderState {
            val uiState = state.uiState(buffering, zoomed, casting)
            return RenderState(
                controlsVisible = state.controlsVisible,
                centerVisible = uiState.centerVisible,
                otherVisible = uiState.otherVisible,
                progressVisible = uiState.progressVisible,
                resetVisible = uiState.resetVisible,
                lockVisible = uiState.lockVisible,
                miniVisible = uiState.miniVisible,
                scrimVisible = uiState.scrimVisible,
                locked = state.isLocked,
                fullscreen = uiState.fullscreen,
                pip = state.isInPictureInPicture,
                fullscreenIconRes = uiState.fullscreenIconRes,
                lockIconRes = uiState.lockIconRes,
            )
        }
    }
}
