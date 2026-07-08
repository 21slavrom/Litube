package com.hhst.youtubelite.cast;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import androidx.media3.extractor.ChunkIndex;

import com.hhst.youtubelite.extractor.StreamCandidate;

import org.junit.Test;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.services.youtube.ItagItem;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import okhttp3.HttpUrl;

/**
 * Regression tests for {@link DashManifestBuilder}. These pin the cast-streaming
 * invariants documented in project_memory.md:
 * <ul>
 *   <li>SegmentList is emitted when a sidx-derived {@link ChunkIndex} is supplied
 *       and its segment count is within {@code MAX_SEGMENT_LIST_ENTRIES}.</li>
 *   <li>Each {@code SegmentURL} carries a {@code ?seg=START-END} query parameter
 *       (the receiver cannot parse mediaRange / sidx itself).</li>
 *   <li>When no {@code ChunkIndex} is available or the threshold is exceeded,
 *       the builder falls back to {@code BaseURL + SegmentBase + indexRange}.</li>
 *   <li>{@code AdaptationSet@id} is unique within the Period (0 for video,
 *       1 for audio) so dash.js can address them unambiguously.</li>
 *   <li>{@code mimeType} is inherited from the itag's {@link MediaFormat} rather
 *       than hardcoded, so webm/opus fallbacks are not mislabeled.</li>
 * </ul>
 * A regression on any of these causes a stall or full-stream download on the
 * Chromecast default receiver — the assertions below fail loudly in that case.
 */
public class DashManifestBuilderTest {

	private static final HttpUrl PROXY_BASE = HttpUrl.parse("http://192.168.1.5:8080");

	private static ItagItem videoItag() {
		// itag 137 = 1080p H.264 fmp4. The codec / mime type come from the
		// MediaFormat passed to the constructor.
		ItagItem itag = new ItagItem(137, ItagItem.ItagType.VIDEO,
				MediaFormat.MPEG_4, "avc1.4d401f");
		itag.setContentLength(10_000_000L);
		itag.setApproxDurationMs(120_000L);
		itag.setBitrate(500_000);
		itag.setWidth(1280);
		itag.setHeight(720);
		itag.setFps(30);
		itag.setInitStart(0);
		itag.setInitEnd(875);
		itag.setIndexStart(876);
		itag.setIndexEnd(1239);
		return itag;
	}

	private static ItagItem audioItag() {
		// itag 140 = AAC 128 kbps m4a.
		ItagItem itag = new ItagItem(140, ItagItem.ItagType.AUDIO,
				MediaFormat.M4A, "mp4a.40.2");
		itag.setContentLength(2_000_000L);
		itag.setApproxDurationMs(120_000L);
		itag.setBitrate(128_000);
		itag.setInitStart(0);
		itag.setInitEnd(279);
		itag.setIndexStart(280);
		itag.setIndexEnd(879);
		return itag;
	}

	private static StreamCandidate videoCandidate(ItagItem itag) {
		VideoStream stream = mock(VideoStream.class);
		when(stream.getContent()).thenReturn("https://example.com/video");
		when(stream.getItagItem()).thenReturn(itag);
		return StreamCandidate.videoOnly(stream, null, false, false, false);
	}

	private static StreamCandidate audioCandidate(ItagItem itag) {
		AudioStream stream = mock(AudioStream.class);
		when(stream.getContent()).thenReturn("https://example.com/audio");
		when(stream.getItagItem()).thenReturn(itag);
		return StreamCandidate.audioOnly(stream, null, false, false, false);
	}

	/** Three 5-second segments, each 100 KB, starting at the video itag's indexEnd+1. */
	private static ChunkIndex smallChunkIndex() {
		return new ChunkIndex(
				new int[]{100_000, 100_000, 100_000},
				new long[]{1240L, 1340L, 1440L},
				new long[]{5_000_000L, 5_000_000L, 5_000_000L},
				new long[]{0L, 5_000_000L, 10_000_000L});
	}

	private static ChunkIndex hugeChunkIndex() {
		// Force the SegmentBase fallback path by exceeding MAX_SEGMENT_LIST_ENTRIES (3000).
		int n = 3001;
		int[] sizes = new int[n];
		long[] offsets = new long[n];
		long[] durations = new long[n];
		long[] times = new long[n];
		long offset = 1240L;
		for (int i = 0; i < n; i++) {
			sizes[i] = 50_000;
			offsets[i] = offset;
			durations[i] = 5_000_000L;
			times[i] = i * 5_000_000L;
			offset += 50_000;
		}
		return new ChunkIndex(sizes, offsets, durations, times);
	}

	@Test
	public void returnsNullWhenBothCandidatesNull() {
		assertNull(DashManifestBuilder.build(null, null, null, null,
				120L, PROXY_BASE, "v", "a"));
	}

	@Test
	public void returnsNullWhenCandidateHasNoStream() {
		// A bare StreamCandidate has no VideoStream/AudioStream/ItagItem —
		// build() must bail gracefully rather than NPE.
		StreamCandidate empty = new StreamCandidate();
		assertNull(DashManifestBuilder.build(empty, null, null, null,
				120L, PROXY_BASE, "v", "a"));
	}

	@Test
	public void segmentListEmittedWhenChunkIndexProvided() {
		StreamCandidate video = videoCandidate(videoItag());
		StreamCandidate audio = audioCandidate(audioItag());
		ChunkIndex vIdx = smallChunkIndex();
		ChunkIndex aIdx = smallChunkIndex();

		String mpd = DashManifestBuilder.build(video, audio, vIdx, aIdx,
				120L, PROXY_BASE, "vtok", "atok");

		assertNotNull(mpd);
		assertTrue("SegmentList element missing:\n" + mpd,
				mpd.contains("<SegmentList"));
		assertTrue("SegmentURL element missing:\n" + mpd,
				mpd.contains("<SegmentURL"));
		// Each segment URL must carry the ?seg=START-END query parameter that
		// the proxy uses to serve byte-range segments to the receiver. The end
		// byte is inclusive: end = start + size - 1. For segment 0 that is
		// 1240 + 100_000 - 1 = 101239.
		assertTrue("expected ?seg=1240-101239 for video segment 0:\n" + mpd,
				mpd.contains("/stream/vtok?seg=1240-101239"));
		assertTrue("expected ?seg=1440-101439 for video segment 2:\n" + mpd,
				mpd.contains("/stream/vtok?seg=1440-101439"));
		assertTrue("expected ?seg= for audio segment:\n" + mpd,
				mpd.contains("/stream/atok?seg="));
	}

	@Test
	public void segmentBaseFallbackWhenChunkIndexNull() {
		StreamCandidate video = videoCandidate(videoItag());
		StreamCandidate audio = audioCandidate(audioItag());

		String mpd = DashManifestBuilder.build(video, audio, null, null,
				120L, PROXY_BASE, "vtok", "atok");

		assertNotNull(mpd);
		assertTrue("expected BaseURL in fallback mode:\n" + mpd,
				mpd.contains("<BaseURL"));
		assertTrue("expected SegmentBase in fallback mode:\n" + mpd,
				mpd.contains("<SegmentBase"));
		assertTrue("expected indexRange from video itag (876-1239):\n" + mpd,
				mpd.contains("indexRange=\"876-1239\""));
		assertTrue("SegmentURL must not appear in fallback mode:\n" + mpd,
				!mpd.contains("<SegmentURL"));
	}

	@Test
	public void segmentBaseFallbackWhenChunkCountExceedsThreshold() {
		// A >3000-segment video must fall back to SegmentBase so the manifest
		// stays small enough to fit in a single Chromecast load() payload.
		StreamCandidate video = videoCandidate(videoItag());
		StreamCandidate audio = audioCandidate(audioItag());
		ChunkIndex huge = hugeChunkIndex();

		String mpd = DashManifestBuilder.build(video, audio, huge, null,
				3600L, PROXY_BASE, "vtok", "atok");

		assertNotNull(mpd);
		assertTrue("expected SegmentBase fallback when chunk count > MAX_SEGMENT_LIST_ENTRIES:\n" + mpd,
				mpd.contains("<SegmentBase"));
		assertTrue("SegmentList must not appear when threshold exceeded:\n" + mpd,
				!mpd.contains("<SegmentList"));
	}

	@Test
	public void initializationRangeFromItag() {
		StreamCandidate video = videoCandidate(videoItag());
		ChunkIndex vIdx = smallChunkIndex();

		String mpd = DashManifestBuilder.build(video, null, vIdx, null,
				120L, PROXY_BASE, "vtok", "atok");

		assertNotNull(mpd);
		// initStart=0, initEnd=875 from videoItag().
		assertTrue("expected Initialization range 0-875:\n" + mpd,
				mpd.contains("range=\"0-875\""));
	}

	@Test
	public void adaptationSetIdsAreUniquePerPeriod() {
		StreamCandidate video = videoCandidate(videoItag());
		StreamCandidate audio = audioCandidate(audioItag());
		ChunkIndex vIdx = smallChunkIndex();
		ChunkIndex aIdx = smallChunkIndex();

		String mpd = DashManifestBuilder.build(video, audio, vIdx, aIdx,
				120L, PROXY_BASE, "vtok", "atok");

		assertNotNull(mpd);
		// AdaptationSet@id must be unique within a Period — duplicate IDs
		// confuse dash.js addressing. Video=0, audio=1. The XML serializer
		// emits attributes alphabetically (contentType before id), so we match
		// the bare id attribute. Representation ids are 137 and 140, so there
		// is no collision with the AdaptationSet ids 0 and 1.
		assertTrue("video AdaptationSet should have id=0:\n" + mpd,
				mpd.contains("id=\"0\""));
		assertTrue("audio AdaptationSet should have id=1:\n" + mpd,
				mpd.contains("id=\"1\""));
	}

	@Test
	public void mimeTypeInheritedFromItagMediaFormat() {
		StreamCandidate video = videoCandidate(videoItag());
		StreamCandidate audio = audioCandidate(audioItag());
		ChunkIndex vIdx = smallChunkIndex();
		ChunkIndex aIdx = smallChunkIndex();

		String mpd = DashManifestBuilder.build(video, audio, vIdx, aIdx,
				120L, PROXY_BASE, "vtok", "atok");

		assertNotNull(mpd);
		// MPEG_4.getMimeType() = "video/mp4"; M4A.getMimeType() = "audio/mp4".
		assertTrue("expected video/mp4 mimeType from MPEG_4 itag:\n" + mpd,
				mpd.contains("mimeType=\"video/mp4\""));
		assertTrue("expected audio/mp4 mimeType from M4A itag:\n" + mpd,
				mpd.contains("mimeType=\"audio/mp4\""));
	}

	@Test
	public void mediaPresentationDurationFormattedAsIso8601() {
		StreamCandidate video = videoCandidate(videoItag());
		ChunkIndex vIdx = smallChunkIndex();

		// 3661 seconds = 1h 1m 1s -> PT1H1M1S.
		String mpd = DashManifestBuilder.build(video, null, vIdx, null,
				3661L, PROXY_BASE, "vtok", "atok");

		assertNotNull(mpd);
		assertTrue("expected PT1H1M1S duration:\n" + mpd,
				mpd.contains("mediaPresentationDuration=\"PT1H1M1S\""));
	}

	@Test
	public void segmentListIncludesSegmentTimelineWithRepeat() {
		// All three segments have the same 5_000_000 us duration. The
		// SegmentTimeline must collapse them into a single <S> element with
		// r="2" (2 additional repeats after the first occurrence).
		StreamCandidate video = videoCandidate(videoItag());
		ChunkIndex vIdx = smallChunkIndex();

		String mpd = DashManifestBuilder.build(video, null, vIdx, null,
				120L, PROXY_BASE, "vtok", "atok");

		assertNotNull(mpd);
		assertTrue("expected <SegmentTimeline>:\n" + mpd,
				mpd.contains("<SegmentTimeline>"));
		assertTrue("expected d=\"5000000\":\n" + mpd,
				mpd.contains("d=\"5000000\""));
		assertTrue("expected r=\"2\" for 3 equal-duration segments:\n" + mpd,
				mpd.contains("r=\"2\""));
	}

	@Test
	public void proxyBaseUrlIsParseable() {
		assertNotNull(PROXY_BASE);
		assertTrue(PROXY_BASE.toString().startsWith("http://192.168.1.5:8080"));
	}
}
