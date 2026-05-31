package com.hhst.youtubelite.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class YoutubeWebviewUrlTest {

	@Test
	public void canLoad_keepsAppAndLocalPagesInWebView() {
		assertTrue(YoutubeWebview.canLoad("https://m.youtube.com/watch?v=test"));
		assertTrue(YoutubeWebview.canLoad("file:///android_asset/page/error.html"));
	}

	@Test
	public void externalHttpPagesOpenOutsideTheWebView() {
		assertFalse(YoutubeWebview.canLoad("https://example.com/blocked"));
		assertTrue(YoutubeWebview.canOpenExternal("https://example.com/blocked"));
	}

	@Test
	public void queueModeRemovesPlaylistShellFromLoadedWatchUrl() {
		assertEquals(
						"https://m.youtube.com/watch?v=new&start_radio=1",
						YoutubeWebview.sanitizeLoadUrl(
										"https://m.youtube.com/watch?v=new&list=RDnew&start_radio=1",
										true));
	}
}
