package com.hhst.youtubelite.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StringUtilsTest {

	@Test
	public void parseHeight_extractsVisibleResolutionHeight() {
		assertEquals(1080, StringUtils.parseHeight("1080p"));
		assertEquals(1080, StringUtils.parseHeight("1080p60"));
		assertEquals(720, StringUtils.parseHeight("hd720"));
	}

	@Test
	public void parseHeight_returnsZeroWhenThereIsNoHeight() {
		assertEquals(0, StringUtils.parseHeight(null));
		assertEquals(0, StringUtils.parseHeight(""));
		assertEquals(0, StringUtils.parseHeight("unknown"));
	}
}
