package com.hhst.youtubelite.player.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import com.hhst.youtubelite.extension.ExtensionManager;
import com.tencent.mmkv.MMKV;

import org.junit.Test;

public class PlayerPreferencesTest {

	@Test
	public void getPreferredQuality_isEmptyUntilRememberQualityIsEnabled() {
		TestPrefs testPrefs = createPrefs();
		ExtensionManager extensions = testPrefs.extensions;
		PlayerPreferences prefs = testPrefs.prefs;
		when(extensions.isEnabled(com.hhst.youtubelite.extension.Constant.REMEMBER_QUALITY)).thenReturn(false);

		assertNull(prefs.getPreferredQuality());
	}

	@Test
	public void getPreferredQuality_returnsStoredVisibleQuality() {
		TestPrefs testPrefs = createPrefs();
		ExtensionManager extensions = testPrefs.extensions;
		MMKV mmkv = testPrefs.mmkv;
		PlayerPreferences prefs = testPrefs.prefs;
		when(extensions.isEnabled(com.hhst.youtubelite.extension.Constant.REMEMBER_QUALITY)).thenReturn(true);
		when(mmkv.decodeString("video_quality", null)).thenReturn("1080p");

		assertEquals("1080p", prefs.getPreferredQuality());
	}

	@Test
	public void getPreferredQuality_treatsBlankStoredQualityAsEmpty() {
		TestPrefs testPrefs = createPrefs();
		ExtensionManager extensions = testPrefs.extensions;
		MMKV mmkv = testPrefs.mmkv;
		PlayerPreferences prefs = testPrefs.prefs;
		when(extensions.isEnabled(com.hhst.youtubelite.extension.Constant.REMEMBER_QUALITY)).thenReturn(true);
		when(mmkv.decodeString("video_quality", null)).thenReturn(" ");

		assertNull(prefs.getPreferredQuality());
	}

	private TestPrefs createPrefs() {
		ExtensionManager extensions = mock(ExtensionManager.class);
		MMKV mmkv = mock(MMKV.class);
		return new TestPrefs(extensions, mmkv, new PlayerPreferences(extensions, mmkv, new Gson()));
	}

	private record TestPrefs(ExtensionManager extensions, MMKV mmkv, PlayerPreferences prefs) {
	}
}
