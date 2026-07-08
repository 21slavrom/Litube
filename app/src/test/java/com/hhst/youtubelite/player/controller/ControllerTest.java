package com.hhst.youtubelite.player.controller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.res.Configuration;

import androidx.media3.common.Player;

import com.hhst.youtubelite.player.common.PlayerLoopMode;

import org.junit.Test;

public class ControllerTest {

	@Test
	public void rotationCanEnterFullscreenFromVisiblePortraitPlayback() {
		assertTrue(Controller.shouldEnterFs(
						true,
						true,
						true,
						false,
						false,
						false,
						Configuration.ORIENTATION_PORTRAIT,
						Configuration.ORIENTATION_LANDSCAPE,
						true,
						false,
						false));
	}

	@Test
	public void castingBlocksAutoFullscreen() {
		// Rotating to landscape while casting must not auto-enter fullscreen: the
		// phone has no video surface while acting as a Cast remote.
		assertFalse(Controller.shouldEnterFs(
						true,
						true,
						true,
						false,
						false,
						false,
						Configuration.ORIENTATION_PORTRAIT,
						Configuration.ORIENTATION_LANDSCAPE,
						true,
						false,
						true));
	}

	@Test
	public void portraitRotationCanExitAutoFullscreen() {
		assertTrue(Controller.shouldExitFs(
						true,
						true,
						Configuration.ORIENTATION_UNDEFINED,
						Configuration.ORIENTATION_PORTRAIT));
	}

	@Test
	public void manualExitRequestsPortraitOnlyFromLandscapeFullscreen() {
		assertTrue(Controller.shouldRequestPortraitOnManualExit(
						true,
						Configuration.ORIENTATION_LANDSCAPE));
		assertFalse(Controller.shouldRequestPortraitOnManualExit(
						true,
						Configuration.ORIENTATION_PORTRAIT));
	}

	@Test
	public void qualityLabelShowsAutoOnlyWhenUnrememberedAndUnselected() {
		assertTrue(Controller.shouldShowAutoQualityPrefix(null, false));
		assertFalse(Controller.shouldShowAutoQualityPrefix("1080p", false));
		assertFalse(Controller.shouldShowAutoQualityPrefix(null, true));
	}

	@Test
	public void qualitySelectionIndexUsesCurrentQualityAfterManualSelection() {
		String[] values = {"480p", "720p", "1080p"};
		assertEquals(-1, Controller.qualitySelectionIndex(null, false, "1080p", values));
		assertEquals(2, Controller.qualitySelectionIndex(null, true, "1080p", values));
		assertEquals(1, Controller.qualitySelectionIndex("720p", false, "720p", values));
	}

	@Test
	public void castingNeverOffersReplayWhenPausedAtEnd() {
		// While casting, STATE_ENDED must not surface REPLAY — the receiver owns
		// end-of-stream and the phone is just a remote. The center button should
		// only offer PLAY so it toggles play/pause against the delegate.
		assertEquals(PlaybackPrimaryAction.PLAY,
				PlaybackPrimaryAction.get(
						false,
						Player.STATE_ENDED,
						PlayerLoopMode.PAUSE_AT_END,
						false,
						false,
						false,
						true));
	}

	@Test
	public void castingShowsPauseWhilePlaying() {
		assertEquals(PlaybackPrimaryAction.PAUSE,
				PlaybackPrimaryAction.get(
						true,
						Player.STATE_READY,
						PlayerLoopMode.PAUSE_AT_END,
						false,
						false,
						false,
						true));
	}

	@Test
	public void notCastingOffersReplayWhenPausedAtEndWithoutQueueOrPlaylist() {
		assertEquals(PlaybackPrimaryAction.REPLAY,
				PlaybackPrimaryAction.get(
						false,
						Player.STATE_ENDED,
						PlayerLoopMode.PAUSE_AT_END,
						false,
						false,
						false,
						false));
	}
}
