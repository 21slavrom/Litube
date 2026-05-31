package com.hhst.youtubelite.player.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.List;

public class PlayerUtilsTest {

	@Test
	public void filterBestStreams_keepsOneChoicePerVisibleQuality() {
		VideoStream p1080 = createVideoStream("avc1", 1080, 30, 5_000_000);
		VideoStream duplicate = createVideoStream("vp9", 1080, 30, 4_000_000);
		VideoStream p720 = createVideoStream("avc1", 720, 60, 3_000_000);

		List<VideoStream> filtered = PlayerUtils.filterBestStreams(List.of(duplicate, p720, p1080));

		assertEquals(2, filtered.size());
		assertSame(p1080, filtered.get(0));
		assertSame(p720, filtered.get(1));
	}

	@Test
	public void filterBestStreams_sortsVisibleQualitiesByHeightThenFps() {
		VideoStream p1080 = createVideoStream("avc1", 0, "1080p30", 30, 5_000_000);
		VideoStream p1440 = createVideoStream("avc1", 0, "1440p", 30, 7_000_000);
		VideoStream p720 = createVideoStream("avc1", 0, "720p60", 60, 4_000_000);

		List<VideoStream> filtered = PlayerUtils.filterBestStreams(List.of(p1080, p1440, p720));

		assertEquals("1440p", filtered.get(0).getResolution());
		assertEquals("1080p30", filtered.get(1).getResolution());
		assertEquals("720p60", filtered.get(2).getResolution());
	}

	@Test
	public void sortResolutionLabels_usesHeightBeforeFpsSuffix() {
		List<String> sorted = PlayerUtils.sortResolutionLabels(List.of(
						"1080p30",
						"720p60",
						"1440p",
						"1080p60",
						"2160p",
						"720p30"));

		assertEquals(List.of(
						"2160p",
						"1440p",
						"1080p60",
						"1080p30",
						"720p60",
						"720p30"), sorted);
	}

	@Test
	public void filterBestStreams_handlesNullAndEmptyInputs() {
		assertTrue(PlayerUtils.filterBestStreams(null).isEmpty());
		assertTrue(PlayerUtils.filterBestStreams(new ArrayList<>()).isEmpty());
	}

	@Test
	public void selectVideoStream_usesSavedQualityWhenAvailable() {
		VideoStream p1080 = createVideoStream("avc1", 1080, 30, 5_000_000);
		VideoStream p720 = createVideoStream("avc1", 720, 30, 3_000_000);
		List<VideoStream> streams = List.of(p1080, p720);

		assertSame(p720, PlayerUtils.selectVideoStream(streams, "720p"));
		assertSame(p1080, PlayerUtils.selectVideoStream(streams, null));
		assertSame(p1080, PlayerUtils.selectVideoStream(streams, "2160p"));
		assertNull(PlayerUtils.selectVideoStream(null, "720p"));
	}

	private VideoStream createVideoStream(String codec, int height, int fps, int bitrate) {
		return createVideoStream(codec, height, height + "p", fps, bitrate);
	}

	private VideoStream createVideoStream(String codec, int height, String resolution, int fps, int bitrate) {
		VideoStream stream = mock(VideoStream.class);
		when(stream.getCodec()).thenReturn(codec);
		when(stream.getHeight()).thenReturn(height);
		when(stream.getFps()).thenReturn(fps);
		when(stream.getBitrate()).thenReturn(bitrate);
		when(stream.getResolution()).thenReturn(resolution);
		return stream;
	}

}
