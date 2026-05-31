package com.hhst.youtubelite.player.controller;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.res.Configuration;

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
						false));
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
}
