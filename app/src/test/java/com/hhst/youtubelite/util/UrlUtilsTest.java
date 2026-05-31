package com.hhst.youtubelite.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UrlUtilsTest {

	@Test
	public void isAllowedUrl_acceptsAppHostsAndRejectsEverythingElse() {
		assertFalse(UrlUtils.isAllowedUrl(null));
		assertFalse(UrlUtils.isAllowedUrl(""));
		assertFalse(UrlUtils.isAllowedUrl("invalid-url"));

		assertTrue(UrlUtils.isAllowedUrl("https://m.youtube.com/watch?v=abc"));
		assertTrue(UrlUtils.isAllowedUrl("https://i.ytimg.com/img/placeholder.png"));
		assertTrue(UrlUtils.isAllowedUrl("https://GSTaTIC.COM/resources"));

		assertFalse(UrlUtils.isAllowedUrl("https://malicious.com/phishing"));
	}
}
