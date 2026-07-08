package com.hhst.youtubelite.cast;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.ChunkIndex;

import com.hhst.youtubelite.extractor.StreamCandidate;

import org.schabi.newpipe.extractor.services.youtube.ItagItem;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import okhttp3.HttpUrl;

/**
 * Builds a DASH MPD for Chromecast / browser playback. Each Representation
 * points at the local proxy stream endpoint
 * {@code http://<lan-ip>:<port>/stream/<token>}.
 * <p>
 * When a sidx-derived {@link ChunkIndex} is provided and the segment count is
 * within {@link #MAX_SEGMENT_LIST_ENTRIES}, the builder emits a
 * {@code SegmentList} with explicit per-segment URLs
 * ({@code .../stream/<token>?seg=START-END}). This lets receivers that do not
 * parse sidx themselves — notably the Chromecast default receiver / Windows
 * Media Renderer (UA {@code NSPlayer/WMFSDK}) — fetch individual segments
 * instead of requesting the whole stream.
 * <p>
 * If no {@code ChunkIndex} is available or the segment count exceeds the
 * threshold, the builder falls back to {@code BaseURL + SegmentBase +
 * indexRange} and lets the receiver parse sidx itself. The fallback keeps the
 * manifest small for very long videos where a SegmentList would be unwieldy.
 */
@UnstableApi
public final class DashManifestBuilder {

	private static final String TAG = "YTLCast";

	/**
	 * Maximum number of segment entries we are willing to emit in a
	 * {@code SegmentList}. Longer videos fall back to {@code SegmentBase +
	 * indexRange} to keep the manifest small. At ~5s per segment, 3000 entries
	 * covers roughly 4 hours.
	 */
	private static final int MAX_SEGMENT_LIST_ENTRIES = 3000;

	private DashManifestBuilder() {
	}

	/**
	 * Builds a castable DASH MPD.
	 *
	 * @param videoCandidate   the video-only stream candidate (may be {@code null})
	 * @param audioCandidate   the audio-only stream candidate (may be {@code null})
	 * @param videoChunkIndex  sidx-derived segment index for video (may be {@code null})
	 * @param audioChunkIndex  sidx-derived segment index for audio (may be {@code null})
	 * @param durationSeconds  the video duration in seconds
	 * @param proxyBase        the proxy base URL, e.g. {@code http://192.168.1.5:8080}
	 * @param videoToken       the proxy path token for the video stream
	 * @param audioToken       the proxy path token for the audio stream
	 * @return the MPD XML string, or {@code null} if neither stream could be manifested
	 */
	@Nullable
	public static String build(@Nullable StreamCandidate videoCandidate,
	                           @Nullable StreamCandidate audioCandidate,
	                           @Nullable ChunkIndex videoChunkIndex,
	                           @Nullable ChunkIndex audioChunkIndex,
	                           long durationSeconds,
	                           @NonNull HttpUrl proxyBase,
	                           @NonNull String videoToken,
	                           @NonNull String audioToken) {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			DocumentBuilder builder = factory.newDocumentBuilder();

			Document doc = builder.newDocument();
			Element mpd = doc.createElement("MPD");
			mpd.setAttribute("xmlns", "urn:mpeg:dash:schema:mpd:2011");
			mpd.setAttribute("profiles", "urn:mpeg:dash:profile:isoff-live:2011");
			mpd.setAttribute("type", "static");
			mpd.setAttribute("minBufferTime", "PT1.500S");
			mpd.setAttribute("mediaPresentationDuration", formatDuration(durationSeconds));
			doc.appendChild(mpd);

			Element period = doc.createElement("Period");
			mpd.appendChild(period);

			boolean added = false;
			Element videoAdaptation = buildAdaptationSet(doc, videoCandidate, videoChunkIndex,
					durationSeconds, proxyBase, videoToken, "video");
			if (videoAdaptation != null) {
				period.appendChild(videoAdaptation);
				added = true;
			} else {
				Log.w(TAG, "build: video adaptation set skipped (candidate="
						+ describeSkip(videoCandidate) + ")");
			}
			Element audioAdaptation = buildAdaptationSet(doc, audioCandidate, audioChunkIndex,
					durationSeconds, proxyBase, audioToken, "audio");
			if (audioAdaptation != null) {
				period.appendChild(audioAdaptation);
				added = true;
			} else {
				Log.w(TAG, "build: audio adaptation set skipped (candidate="
						+ describeSkip(audioCandidate) + ")");
			}
			if (!added) {
				Log.w(TAG, "build: no adaptation set produced, returning null");
				return null;
			}

			Transformer transformer = TransformerFactory.newInstance().newTransformer();
			transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
			transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
			java.io.StringWriter writer = new java.io.StringWriter();
			transformer.transform(new DOMSource(doc), new StreamResult(writer));
			String result = writer.toString();
			int videoSegs = videoChunkIndex != null ? videoChunkIndex.length : 0;
			int audioSegs = audioChunkIndex != null ? audioChunkIndex.length : 0;
			Log.d(TAG, "build: produced " + result.length() + " byte manifest"
					+ " (video=" + (videoAdaptation != null) + " videoSegs=" + videoSegs
					+ " audio=" + (audioAdaptation != null) + " audioSegs=" + audioSegs
					+ " duration=" + durationSeconds + "s)");
			return result;
		} catch (Exception e) {
			Log.w(TAG, "build: failed", e);
			return null;
		}
	}

	@Nullable
	private static Element buildAdaptationSet(@NonNull Document doc,
	                                          @Nullable StreamCandidate candidate,
	                                          @Nullable ChunkIndex chunkIndex,
	                                          long durationSeconds,
	                                          @NonNull HttpUrl proxyBase,
	                                          @NonNull String token,
	                                          @NonNull String contentType) {
		Stream stream = extractStream(candidate);
		if (stream == null || stream.getItagItem() == null) {
			return null;
		}
		String originalUrl = stream.getContent();
		if (originalUrl == null || originalUrl.isBlank()) {
			return null;
		}
		ItagItem itag = stream.getItagItem();
		long contentLength = itag.getContentLength();
		long durationMs = itag.getApproxDurationMs() > 0
				? itag.getApproxDurationMs()
				: TimeUnit.SECONDS.toMillis(durationSeconds);
		if (contentLength <= 0 || durationMs <= 0) {
			Log.w(TAG, "buildAdaptationSet: skipping " + contentType
					+ " itag=" + itag.id
					+ " contentLength=" + contentLength
					+ " durationMs=" + durationMs);
			return null;
		}

		int bandwidth = itag.getBitrate() > 0 ? itag.getBitrate() : 1;
		String codecs = itag.getCodec();
		// Use the stream's actual container mime type instead of assuming mp4.
		// Casting may fall back to webm/opus audio, and a mismatched mimeType
		// causes browsers/Chromecast to reject the source with MEDIA_ERR_DECODE.
		String mimeType = itag.getMediaFormat() != null
				? itag.getMediaFormat().getMimeType()
				: ("video".equals(contentType) ? "video/mp4" : "audio/mp4");
		Log.d(TAG, "buildAdaptationSet: " + contentType
					+ " itag=" + itag.id
					+ " bandwidth=" + bandwidth
					+ " codecs=" + codecs
					+ " contentLength=" + contentLength
					+ " init=" + itag.getInitStart() + "-" + itag.getInitEnd()
					+ " index=" + itag.getIndexStart() + "-" + itag.getIndexEnd()
					+ " segs=" + (chunkIndex != null ? chunkIndex.length : "null(sidx fallback)")
					+ (contentType.equals("video") ? " " + itag.getWidth() + "x" + itag.getHeight() : ""));

		Element adaptationSet = doc.createElement("AdaptationSet");
		// AdaptationSet@id must be unique within a Period; use 0 for video and
		// 1 for audio so dash.js can address them unambiguously.
		adaptationSet.setAttribute("id", "video".equals(contentType) ? "0" : "1");
		adaptationSet.setAttribute("mimeType", mimeType);
		adaptationSet.setAttribute("subsegmentAlignment", "true");
		adaptationSet.setAttribute("contentType", contentType);

		Element role = doc.createElement("Role");
		role.setAttribute("schemeIdUri", "urn:mpeg:dash:role:2011");
		role.setAttribute("value", "main");
		adaptationSet.appendChild(role);

		Element representation = doc.createElement("Representation");
		representation.setAttribute("id", String.valueOf(itag.id));
		representation.setAttribute("codecs", codecs != null ? codecs : "");
		representation.setAttribute("startWithSAP", "1");
		representation.setAttribute("maxPlayoutRate", "1");
		representation.setAttribute("bandwidth", String.valueOf(bandwidth));

		if ("video".equals(contentType)) {
			representation.setAttribute("width", String.valueOf(itag.getWidth()));
			representation.setAttribute("height", String.valueOf(itag.getHeight()));
			int fps = itag.getFps();
			if (fps > 0) {
				representation.setAttribute("frameRate", String.valueOf(fps));
			}
		} else {
			Element audioConfig = doc.createElement("AudioChannelConfiguration");
			audioConfig.setAttribute("schemeIdUri",
					"urn:mpeg:dash:23003:3:audio_channel_configuration:2011");
			audioConfig.setAttribute("value", "2");
			representation.appendChild(audioConfig);
		}

		long initStart = itag.getInitStart();
		long initEnd = itag.getInitEnd();
		String streamUrl = proxyBase.newBuilder()
				.addPathSegment("stream")
				.addPathSegment(token)
				.build()
				.toString();

		if (chunkIndex != null && chunkIndex.length <= MAX_SEGMENT_LIST_ENTRIES) {
			// Emit SegmentList with explicit per-segment URLs. Receivers that do
			// not parse sidx themselves (Chromecast default receiver, WMR) can
			// still play because each SegmentURL is a standalone resource.
			Element segmentList = doc.createElement("SegmentList");
			segmentList.setAttribute("timescale", "1000000");

			Element initialization = doc.createElement("Initialization");
			initialization.setAttribute("sourceURL", streamUrl);
			initialization.setAttribute("range", initStart + "-" + initEnd);
			segmentList.appendChild(initialization);

			Element segmentTimeline = doc.createElement("SegmentTimeline");
			long prevDuration = -1;
			int repeatCount = 0;
			for (int i = 0; i < chunkIndex.length; i++) {
				long duration = chunkIndex.durationsUs[i];
				if (duration == prevDuration) {
					repeatCount++;
				} else {
					if (prevDuration != -1) {
						addSegmentTimelineElement(doc, segmentTimeline, prevDuration, repeatCount);
					}
					prevDuration = duration;
					repeatCount = 0;
				}
			}
			if (prevDuration != -1) {
				addSegmentTimelineElement(doc, segmentTimeline, prevDuration, repeatCount);
			}
			segmentList.appendChild(segmentTimeline);

			for (int i = 0; i < chunkIndex.length; i++) {
				long start = chunkIndex.offsets[i];
				long end = start + chunkIndex.sizes[i] - 1;
				Element segmentUrl = doc.createElement("SegmentURL");
				segmentUrl.setAttribute("media", streamUrl + "?seg=" + start + "-" + end);
				segmentList.appendChild(segmentUrl);
			}
			representation.appendChild(segmentList);
		} else {
			// Fallback: let the receiver parse sidx itself. Keep the manifest
			// tiny by referencing the stream via BaseURL + SegmentBase.
			Element baseUrl = doc.createElement("BaseURL");
			baseUrl.setTextContent(streamUrl);
			representation.appendChild(baseUrl);

			long indexStart = itag.getIndexStart();
			long indexEnd = itag.getIndexEnd();
			Element segmentBase = doc.createElement("SegmentBase");
			segmentBase.setAttribute("indexRange", indexStart + "-" + indexEnd);
			Element initialization = doc.createElement("Initialization");
			initialization.setAttribute("range", initStart + "-" + initEnd);
			segmentBase.appendChild(initialization);
			representation.appendChild(segmentBase);
		}

		adaptationSet.appendChild(representation);
		return adaptationSet;
	}

	@Nullable
	private static Stream extractStream(@Nullable StreamCandidate candidate) {
		if (candidate == null) return null;
		VideoStream video = candidate.getVideoStream();
		if (video != null) return video;
		AudioStream audio = candidate.getAudioStream();
		return audio;
	}

	/**
	 * Returns a concise reason why a candidate was skipped at the manifest level,
	 * for logging. Distinguishes "null", "no stream", "no itag", and "ok" so the
	 * log clearly shows where the build path bailed out.
	 */
	@NonNull
	private static String describeSkip(@Nullable StreamCandidate candidate) {
		if (candidate == null) return "null";
		Stream stream = extractStream(candidate);
		if (stream == null) return "kind=" + candidate.getKind() + " noStream";
		if (stream.getItagItem() == null) return "kind=" + candidate.getKind() + " noItag";
		ItagItem itag = stream.getItagItem();
		return "itag=" + itag.id
				+ " contentLength=" + itag.getContentLength()
				+ " approxDurationMs=" + itag.getApproxDurationMs();
	}

	private static void addSegmentTimelineElement(@NonNull Document doc,
	                                              @NonNull Element parent,
	                                              long duration,
	                                              int repeatCount) {
		Element s = doc.createElement("S");
		s.setAttribute("d", String.valueOf(duration));
		if (repeatCount > 0) {
			s.setAttribute("r", String.valueOf(repeatCount));
		}
		parent.appendChild(s);
	}

	@NonNull
	private static String formatDuration(long seconds) {
		long hours = seconds / 3600;
		long minutes = (seconds % 3600) / 60;
		long secs = seconds % 60;
		return String.format(Locale.ROOT, "PT%dH%dM%dS", hours, minutes, secs);
	}
}
