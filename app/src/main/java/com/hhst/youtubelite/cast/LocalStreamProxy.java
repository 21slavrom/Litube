package com.hhst.youtubelite.cast;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;

import com.hhst.youtubelite.AppConstants;
import com.hhst.youtubelite.core.Retry;
import com.hhst.youtubelite.core.RetryPolicy;
import com.hhst.youtubelite.player.engine.datasource.YoutubeHttpDataSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import fi.iki.elonen.NanoHTTPD;

/**
 * Local HTTP proxy that serves a synthesized DASH manifest and proxies byte-range
 * requests to YouTube via {@link YoutubeHttpDataSource}. The receiver (dash.js or
 * the Chromecast default media receiver) fetches
 * {@code http://<lan-ip>:<port>/manifest.mpd}, parses the {@code SegmentBase} to
 * learn the initialization (moov) and index (sidx) byte ranges, then issues HTTP
 * Range GETs against {@code http://<lan-ip>:<port>/stream/<token>}, which this
 * proxy translates into positioned reads on the YouTube URL (with po-token).
 */
@UnstableApi
public final class LocalStreamProxy extends NanoHTTPD {
	private static final String TAG = "YTLCastProxy";

	/**
	 * Hard cap on how many bytes we stream back in one HTTP response, regardless
	 * of what the receiver asked for via {@code Range} or {@code ?seg=}. The
	 * default receiver on some Cast endpoints (notably Windows Media Renderer /
	 * "Cast to Device", UA {@code NSPlayer/WMFSDK}) ignores the manifest's
	 * {@code SegmentList} and issues {@code Range: bytes=0-} for the whole
	 * resource — up to 764MB for a 1080p4h video. Returning that in a single
	 * response forces the receiver to buffer minutes worth of data before
	 * playback starts, and most receivers just stall (no picture).
	 * <p>
	 * Chunking caps each response to {@link #DEFAULT_CHUNK_MAX_BYTES} so clients
	 * are forced to issue follow-up {@code Range} requests. Combined with the
	 * {@code Content-Range} header on every response this degrades gracefully
	 * for:
	 * <ul>
	 *   <li>dash.js / Chromecast default media receiver: each segment is already
	 *       small, so this is a no-op in practice.</li>
	 *   <li>Windows Media Renderer / other legacy receivers: get natural
	 *       {@code 206 Partial Content} chunks they understand.</li>
	 * </ul>
	 * This is not user-configurable: any larger value re-introduces the stall.
	 */
	private static final int DEFAULT_CHUNK_MAX_BYTES = 256 * 1024;
	/**
	 * Some legacy receivers (Windows Media Renderer / "Cast to Device", UA
	 * {@code NSPlayer/WMFSDK}) ignore {@code SegmentList} and request the whole
	 * stream with {@code Range: bytes=0-}. A 256KB cap forces them to issue
	 * hundreds of sequential requests, making first-screen and seek unusable.
	 * Allowing 1MB chunks lets them buffer fast enough to start playback while
	 * still avoiding a multi-hundred-megabyte single response.
	 */
	private static final int LEGACY_RECEIVER_CHUNK_MAX_BYTES = 1024 * 1024;

	/**
	 * User-Agent substrings seen on cast receivers / DASH players that are not
	 * browsers. These UAs must not trigger {@link #recordBrowserSeen()} when they
	 * request {@code /manifest.mpd}, otherwise connecting to a Chromecast would
	 * falsely light up the "Streaming via link" banner.
	 */
	private static final String[] RECEIVER_UAS = {
			"NSPlayer", "WMFSDK", "Chromecast", "CrKey", "CastSDK"};

	/**
	 * Refreshes the YouTube stream URLs when the cached ones are rejected
	 * (HTTP 403, the most common cause being an expired/invalidated
	 * {@code pot=} po-token). Implementations should re-extract the video and
	 * re-configure the proxy with the fresh URLs via {@link #configure}, then
	 * return the new URL for the given token. Returns {@code null} if no fresh
	 * URL could be obtained (the proxy then surfaces the original 403).
	 * <p>
	 * Implementations are blocking and must be invoked off the main thread
	 * (the proxy calls this from {@link NanoHTTPD#serve(IHTTPSession)} which
	 * already runs on a NanoHTTPD worker thread).
	 */
	public interface UrlRefresher {
		@Nullable
		String refreshUrl(@NonNull String token);
	}

	@Nullable
	private volatile UrlRefresher urlRefresher;

	/**
	 * Elapsed-realtime millis of the last request from a browser / receiver
	 * consuming the share link (a {@code /player} or {@code /manifest.mpd} GET).
	 * Used by {@link #isBrowserConnected()} to distinguish "proxy is running"
	 * from "a browser is actually playing" — the Cast dialog and player banner
	 * should only show the connected state once a browser has fetched the page.
	 * <p>
	 * {@code 0} means no browser has connected yet.
	 */
	private volatile long lastBrowserSeenMs = 0L;
	/**
	 * Window after which a browser that has stopped polling the manifest is
	 * considered disconnected. The player page polls {@code /manifest.mpd}
	 * every 15s, so 30s tolerates one missed poll without flapping.
	 */
	private static final long BROWSER_DISCONNECT_TIMEOUT_MS = 30_000L;

	/** Metadata needed to serve byte-range segments for a single stream. */
	private static final class StreamInfo {
		final String youtubeUrl;
		final long initStart;
		final long initEnd;
		final long indexStart;
		final long indexEnd;
		final long contentLength;

		StreamInfo(@NonNull String youtubeUrl, long initStart, long initEnd,
		           long indexStart, long indexEnd, long contentLength) {
			this.youtubeUrl = youtubeUrl;
			this.initStart = initStart;
			this.initEnd = initEnd;
			this.indexStart = indexStart;
			this.indexEnd = indexEnd;
			this.contentLength = contentLength;
		}
	}

	@NonNull
	private final YoutubeHttpDataSource.Factory dataSourceFactory;
	@Nullable
	private volatile String manifestXml;
	@Nullable
	private volatile String videoTitle;
	/**
	 * Current YouTube videoId served through the proxy. Injected into the
	 * manifest as a `<!-- vid:... -->` comment so a long-lived share-link
	 * page can detect local video switches and re-init dash.js. {@code null}
	 * when no manifest is configured.
	 */
	@Nullable
	private volatile String currentVideoId;
	@NonNull
	private final android.content.Context context;
	@NonNull
	private final Map<String, StreamInfo> tokenToInfo = new ConcurrentHashMap<>();
	/**
	 * Cached LAN IP detected once on first use. The proxy is short-lived (one cast
	 * session), so re-detecting on every {@code getProxyUrl()} call is wasteful and
	 * risks returning a different interface if the network state changes mid-cast
	 * (WiFi handoff, mobile data flapping) — which would make the manifest BaseURL
	 * and the manifestUrl passed to the receiver disagree.
	 */
	@Nullable
	private volatile String cachedLanIp;

	public LocalStreamProxy(@NonNull android.content.Context context, int port) {
		super(port);
		this.context = context.getApplicationContext();
		this.dataSourceFactory = new YoutubeHttpDataSource.Factory(AppConstants.USER_AGENT)
					.setConnectTimeoutMs(30_000)
					// A 30s read timeout lets a stalled upstream connection hang the
					// proxy for half a minute before retrying. 10s is enough for slow
					// CDN response starts while still failing fast on genuine stalls.
					.setReadTimeoutMs(10_000)
					.setRangeParameterEnabled(true)
					.setRnParameterEnabled(true);
	}

	/**
	 * Sets the callback used to refresh expired stream URLs (403 recovery).
	 * Must be set before the first segment request to be effective.
	 */
	public void setUrlRefresher(@Nullable UrlRefresher refresher) {
		this.urlRefresher = refresher;
	}

	/**
	 * Returns whether a browser / receiver is currently consuming the share
	 * link. True once {@code /player} or {@code /manifest.mpd} has been
	 * requested and within the last {@link #BROWSER_DISCONNECT_TIMEOUT_MS} of
	 * such a request. Distinct from the proxy merely running — opening the
	 * Cast dialog starts the proxy, but the "connected" UI should only appear
	 * once a browser actually loads the page.
	 */
	public boolean isBrowserConnected() {
		long last = lastBrowserSeenMs;
		if (last == 0L) return false;
		long now = android.os.SystemClock.elapsedRealtime();
		return (now - last) < BROWSER_DISCONNECT_TIMEOUT_MS;
	}

	/**
	 * Records that a browser-facing endpoint ({@code /player} or
	 * {@code /manifest.mpd}) was just served. Called from the serve path so
	 * {@link #isBrowserConnected()} reflects live activity.
	 */
	private void recordBrowserSeen() {
		lastBrowserSeenMs = android.os.SystemClock.elapsedRealtime();
	}

	/**
	 * Returns the current YouTube URL registered for the given token, or
	 * {@code null} if the token is unknown. Used by the URL refresher to
	 * detect which stream a 403 belongs to.
	 */
	@Nullable
	public String getUrlForToken(@NonNull String token) {
		StreamInfo info = tokenToInfo.get(token);
		return info != null ? info.youtubeUrl : null;
	}

	/**
	 * Sets the manifest to serve and registers the token-to-URL mappings for segment proxying.
	 */
	public void configure(@Nullable String manifestXml,
	                      @NonNull String videoTitle,
	                      @Nullable String videoId,
	                      @Nullable String videoToken, @Nullable String videoUrl,
	                      @Nullable String audioToken, @Nullable String audioUrl,
	                      long videoInitStart, long videoInitEnd,
	                      long videoIndexStart, long videoIndexEnd, long videoContentLength,
	                      long audioInitStart, long audioInitEnd,
	                      long audioIndexStart, long audioIndexEnd, long audioContentLength) {
		this.manifestXml = manifestXml;
		this.videoTitle = videoTitle;
		this.currentVideoId = videoId;
		tokenToInfo.clear();
		refreshedUrls.clear();
		if (videoToken != null && videoUrl != null) {
			tokenToInfo.put(videoToken, new StreamInfo(videoUrl, videoInitStart, videoInitEnd,
					videoIndexStart, videoIndexEnd, videoContentLength));
		}
		if (audioToken != null && audioUrl != null) {
			tokenToInfo.put(audioToken, new StreamInfo(audioUrl, audioInitStart, audioInitEnd,
					audioIndexStart, audioIndexEnd, audioContentLength));
		}
	}

	/**
	 * Fetches the sidx box for the given token's stream from YouTube and parses it
	 * into a {@link ChunkIndex} of per-segment byte ranges. Called by the cast
	 * controller before building the manifest so a {@code SegmentList} can be
	 * emitted, letting the receiver fetch individual segments instead of the
	 * entire stream. Returns {@code null} on any failure (caller falls back to
	 * {@code SegmentBase}).
	 */
	@Nullable
	@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
	public androidx.media3.extractor.ChunkIndex fetchChunkIndex(@NonNull String token) {
		StreamInfo info = tokenToInfo.get(token);
		if (info == null || info.indexStart < 0 || info.indexEnd <= info.indexStart) {
			Log.w(TAG, "fetchChunkIndex: no index range for token=" + token);
			return null;
		}
		androidx.media3.extractor.ChunkIndex idx = fetchAndParseSidx(token, info, info.indexStart,
				info.indexEnd - info.indexStart + 1);
		if (idx != null) return idx;

		// Experiment: the itag's indexStart may not point exactly at the sidx
		// box (some YouTube fmp4 layouts put extra boxes — styp, emsg, etc. —
		// between moov and sidx, and the extractor's indexRange may be off).
		// Widen the fetch to span from byte 0 through indexEnd so the SidxParser
		// scanner has the full preamble to walk through. This is more bytes on
		// the wire (~indexEnd, typically <100KB) but far cheaper than letting
		// the cast device download the whole stream.
		Log.d(TAG, "fetchChunkIndex: retrying with widened range for token=" + token);
		return fetchAndParseSidx(token, info, 0L, info.indexEnd + 1);
	}

	/**
	 * Opens a {@link YoutubeHttpDataSource} against the given DataSpec, retrying
	 * per {@link RetryPolicy#UPSTREAM_OPEN} on transient failures. Each retry
	 * uses a fresh data source (and therefore a fresh {@link HttpURLConnection})
	 * so a reset TLS connection is not reused. Returns the opened data source
	 * on success; throws the last {@link Exception} on exhaustion or when the
	 * policy's predicate rejects the error.
	 */
	@NonNull
	private YoutubeHttpDataSource openWithRetry(@NonNull DataSpec dataSpec,
	                                            @NonNull String token) throws Exception {
		final RetryPolicy policy = RetryPolicy.UPSTREAM_OPEN;
		return Retry.withBlocking(policy, attempt -> {
			YoutubeHttpDataSource dataSource = dataSourceFactory.createDataSource();
			try {
				dataSource.open(dataSpec);
				return dataSource;
			} catch (Exception e) {
				// Tear down the failed connection before Retry re-attempts. close()
				// failures during cleanup are not actionable and must not mask the
				// original error.
				closeQuietly(dataSource);
				Log.d(TAG, "openWithRetry: attempt " + attempt + " failed for token=" + token
						+ ": " + e.getClass().getSimpleName());
				throw e;
			}
		});
	}

	/**
	 * Closes a data source without propagating exceptions. Used during cleanup
	 * after a failure where re-throwing would mask the original error.
	 */
	private static void closeQuietly(@Nullable YoutubeHttpDataSource dataSource) {
		if (dataSource == null) return;
		try {
			dataSource.close();
		} catch (Exception e) {
			Log.d(TAG, "closeQuietly: ignoring close failure: " + e.getClass().getSimpleName());
		}
	}

	@Nullable
	@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
	private androidx.media3.extractor.ChunkIndex fetchAndParseSidx(@NonNull String token,
	                                                               @NonNull StreamInfo info,
	                                                               long startOffset,
	                                                               long length) {
		androidx.media3.extractor.ChunkIndex idx = fetchAndParseSidxOnce(token, info, startOffset, length);
		if (idx != null) return idx;
		// 403 recovery: refresh the stream URL once and retry. Sidx pre-fetch
		// often runs shortly after extraction, but the po-token in the URL may
		// have been invalidated if local playback paused between extraction and
		// cast/share-link start.
		if (refreshedUrls.add(token)) {
			UrlRefresher refresher = urlRefresher;
			String freshUrl = refresher != null ? refresher.refreshUrl(token) : null;
			if (freshUrl != null) {
				StreamInfo fresh = new StreamInfo(freshUrl,
						info.initStart, info.initEnd,
						info.indexStart, info.indexEnd, info.contentLength);
				tokenToInfo.put(token, fresh);
				Log.d(TAG, "fetchAndParseSidx: retried with refreshed URL for token=" + token);
				return fetchAndParseSidxOnce(token, fresh, startOffset, length);
			}
		}
		return null;
	}

	@Nullable
	@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
	private androidx.media3.extractor.ChunkIndex fetchAndParseSidxOnce(@NonNull String token,
	                                                               @NonNull StreamInfo info,
	                                                               long startOffset,
	                                                               long length) {
		try {
			Uri youtubeUri = Uri.parse(info.youtubeUrl);
			DataSpec dataSpec = new DataSpec.Builder()
					.setUri(youtubeUri)
					.setPosition(startOffset)
					.setLength(length)
					.build();
			YoutubeHttpDataSource dataSource = openWithRetry(dataSpec, token);
			byte[] sidxBytes = new byte[(int) length];
			int read = 0;
			while (read < sidxBytes.length) {
				int n = dataSource.read(sidxBytes, read, sidxBytes.length - read);
				if (n == C.RESULT_END_OF_INPUT) break;
				read += n;
			}
			dataSource.close();
			if (read < sidxBytes.length) {
				Log.w(TAG, "fetchAndParseSidx: short read " + read + "/" + length + " for token=" + token
						+ " start=" + startOffset);
				return null;
			}
			androidx.media3.extractor.ChunkIndex idx = SidxParser.parse(sidxBytes, startOffset);
			if (idx != null) {
				Log.d(TAG, "fetchAndParseSidx: parsed " + idx.length + " segments for token=" + token
						+ " start=" + startOffset
						+ " firstOffset=" + idx.offsets[0]
						+ " firstSize=" + idx.sizes[0]
						+ " firstDurationMs=" + (idx.durationsUs[0] / 1000));
			} else {
				StringBuilder hex = new StringBuilder();
				for (int i = 0; i < Math.min(sidxBytes.length, 32); i++) {
					hex.append(String.format("%02x ", sidxBytes[i]));
				}
				Log.w(TAG, "fetchAndParseSidx: SidxParser returned null for token=" + token
						+ " start=" + startOffset + " length=" + length
						+ " firstBytes=" + hex);
			}
			return idx;
		} catch (Exception e) {
			Log.w(TAG, "fetchAndParseSidx: failed for token=" + token + " start=" + startOffset, e);
			return null;
		}
	}

	@NonNull
	public String getProxyUrl(@NonNull String path) {
		return "http://" + getLanIpAddress() + ":" + getListeningPort() + path;
	}
	@Override
	@NonNull
	public NanoHTTPD.Response serve(@NonNull NanoHTTPD.IHTTPSession session) {
		String uri = session.getUri();
		String rangeHeader = getHeader(session, "range");
		String segParam = getQueryParameter(session, "seg");
		String userAgent = getHeader(session, "user-agent");
		Log.d(TAG, "serve: " + session.getMethod() + " " + uri
				+ (segParam != null ? "?seg=" + segParam : "")
				+ (rangeHeader != null ? " Range=" + rangeHeader : "")
				+ " ua=" + (userAgent != null
						? userAgent.substring(0, Math.min(userAgent.length(), 40)) : "null"));
		if ("/player".equals(uri) || "/player/".equals(uri)) {
			return servePlayerPage();
		}
		if ("/manifest.mpd".equals(uri)) {
			return serveManifest(session);
		}
		if (uri != null && uri.startsWith("/stream/")) {
			String token = uri.substring("/stream/".length());
			// Defensive: some NanoHTTPD versions include the query string in
			// getUri(); strip it so the token is just the path segment.
			int q = token.indexOf('?');
			if (q >= 0) token = token.substring(0, q);
			if (token.isEmpty() || token.contains("/")) {
				return newResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Bad stream token");
			}
			return serveStream(session, token);
		}
		return newResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Not found");
	}

	private boolean isReceiverUserAgent(@Nullable String userAgent) {
		if (userAgent == null) return false;
		String lower = userAgent.toLowerCase(java.util.Locale.ROOT);
		for (String ua : RECEIVER_UAS) {
			if (lower.contains(ua.toLowerCase(java.util.Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Windows Media Renderer / "Cast to Device" uses {@code NSPlayer/WMFSDK} and
	 * ignores {@code SegmentList}, falling back to sequential {@code Range}
	 * requests. It needs a larger chunk size than dash.js/Chromecast so playback
	 * starts and seeks do not stall on hundreds of tiny 256KB requests.
	 */
	private static boolean isLegacyWindowsMediaReceiver(@Nullable String userAgent) {
		if (userAgent == null) return false;
		String lower = userAgent.toLowerCase(java.util.Locale.ROOT);
		return lower.contains("nsplayer") || lower.contains("wmfsdk");
	}

	private static int chunkMaxBytesForUserAgent(@Nullable String userAgent) {
		return isLegacyWindowsMediaReceiver(userAgent)
				? LEGACY_RECEIVER_CHUNK_MAX_BYTES
				: DEFAULT_CHUNK_MAX_BYTES;
	}

	@NonNull
	private NanoHTTPD.Response serveManifest(@NonNull NanoHTTPD.IHTTPSession session) {
		String userAgent = getHeader(session, "user-agent");
		// Only browsers / dash.js polling the manifest should light up the
		// "Streaming via link" banner. Cast receivers (Chromecast, WMR, ...)
		// fetch the same manifest but are not browsers.
		if (!isReceiverUserAgent(userAgent)) {
			recordBrowserSeen();
		}
		String xml = manifestXml;
		if (xml == null) {
			Log.w(TAG, "serveManifest: no manifest configured");
			return newResponse(NanoHTTPD.Response.Status.NOT_FOUND, "No manifest");
		}
		// The share link page polls /manifest.mpd every few seconds to detect a
		// local video switch; the stable manifest URL would otherwise return
		// the same bytes everywhere. Inject a comment carrying the current
		// videoId so the page knows when to re-init dash.js.
		String out = xml;
		if (currentVideoId != null && !currentVideoId.isEmpty()) {
			String marker = "<!-- vid:" + currentVideoId + " -->";
			if (!xml.contains(marker)) {
				int declEnd = xml.indexOf("?>");
				if (declEnd > -1) {
					int ins = declEnd + 2;
					out = xml.substring(0, ins) + marker + xml.substring(ins);
				} else {
					out = marker + xml;
				}
			}
		}
		Log.d(TAG, "serveManifest: returning " + out.length() + " bytes; snippet="
				+ out.substring(0, Math.min(out.length(), 500)));
		byte[] bytes = out.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		InputStream stream = new ByteArrayInputStream(bytes);
		NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
				NanoHTTPD.Response.Status.OK, "application/dash+xml", stream, bytes.length);
		response.addHeader("Access-Control-Allow-Origin", "*");
		return response;
	}

	@NonNull
	private NanoHTTPD.Response servePlayerPage() {
		// A browser opening the player page is the first sign of connection.
		recordBrowserSeen();
		try (InputStream asset = context.getAssets().open("cast/player.html")) {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] tmp = new byte[8192];
			int n;
			while ((n = asset.read(tmp)) != -1) {
				buffer.write(tmp, 0, n);
			}
			String html = new String(buffer.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
			String title = escapeHtml(videoTitle != null ? videoTitle : "Player");
			String manifestUrl = getProxyUrl("/manifest.mpd");
			html = html.replace("__VIDEO_TITLE__", title)
					.replace("__MANIFEST_URL__", manifestUrl);
			byte[] bytes = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			InputStream stream = new ByteArrayInputStream(bytes);
			NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
					NanoHTTPD.Response.Status.OK, "text/html", stream, bytes.length);
			response.addHeader("Access-Control-Allow-Origin", "*");
			return response;
		} catch (Exception e) {
			return newResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "Failed to serve player page");
		}
	}

	/**
	 * Serves a byte range of the YouTube fmp4 stream for a given token. The
	 * receiver (dash.js / Chromecast default media receiver) drives the byte
	 * ranges: it fetches the {@code Initialization} range and the {@code indexRange}
	 * (sidx) declared in the MPD, parses sidx to learn each segment's byte
	 * boundaries, then issues HTTP Range GETs against this endpoint. We honor the
	 * Range header and translate it into a positioned read on the YouTube URL.
	 * <p>
	 * If no Range header is present, serve from byte 0 (some receivers probe the
	 * head of the resource first).
	 */
	@NonNull
	private NanoHTTPD.Response serveStream(@NonNull NanoHTTPD.IHTTPSession session,
	                                       @NonNull String token) {
		StreamInfo info = tokenToInfo.get(token);
		if (info == null) {
			Log.w(TAG, "serveStream: unknown token=" + token);
			return newResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Unknown token");
		}

		String userAgent = getHeader(session, "user-agent");

		long rangeStart = 0;
		long rangeEnd = info.contentLength > 0 ? info.contentLength - 1 : C.LENGTH_UNSET;
		boolean hasRange = false;
		boolean fromSeg = false;

		// Per-segment URL from the manifest's SegmentList: ?seg=START-END.
		// This takes precedence over the Range header because the Chromecast
		// default receiver ignores SegmentURL's mediaRange attribute and would
		// otherwise request Range: bytes=0- (the entire stream).
		String segParam = getQueryParameter(session, "seg");
		if (segParam != null && segParam.contains("-")) {
			try {
				String[] parts = segParam.split("-", 2);
				rangeStart = Long.parseLong(parts[0]);
				rangeEnd = Long.parseLong(parts[1]);
				hasRange = true;
				fromSeg = true;
			} catch (NumberFormatException e) {
				Log.w(TAG, "serveStream: bad seg param=" + segParam + " for token=" + token);
				segParam = null;
			}
		}

		if (!hasRange) {
			String rangeHeader = getHeader(session, "range");
			if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
				hasRange = true;
				long[] parsed = parseRange(rangeHeader);
				rangeStart = parsed[0];
				if (parsed[1] != C.LENGTH_UNSET) {
					rangeEnd = parsed[1];
				} else if (info.contentLength > 0) {
					rangeEnd = info.contentLength - 1;
				}
			}
		}
		if (rangeEnd != C.LENGTH_UNSET && rangeEnd < rangeStart) {
			Log.w(TAG, "serveStream: bad range " + rangeStart + "-" + rangeEnd + " for token=" + token);
			return newResponse(NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE, "Bad range");
		}

		// How many bytes the client *asked* for (may be the entire resource).
		long requestLength = rangeEnd != C.LENGTH_UNSET ? (rangeEnd - rangeStart + 1) : C.LENGTH_UNSET;

		long chunkStart = rangeStart;
		long chunkEnd = rangeStart;
		long servedLength = requestLength;
		boolean clamped = false;
		int chunkMaxBytes = chunkMaxBytesForUserAgent(userAgent);
		if (fromSeg) {
			// SegmentList gives us an exact segment byte range. The URL itself
			// identifies a complete segment, so we must serve it in full without
			// truncating. The proxy still enforces the range via DataSpec length.
			chunkEnd = rangeEnd;
			servedLength = requestLength;
		} else if (requestLength != C.LENGTH_UNSET) {
			// Clamp generic Range requests to a single chunk so a misbehaving
			// receiver that issues Range: bytes=0- cannot pull the whole file.
			long maxEnd = rangeStart + (long) chunkMaxBytes - 1;
			if (rangeEnd > maxEnd) {
				chunkEnd = maxEnd;
				clamped = true;
			} else {
				chunkEnd = rangeEnd;
			}
			chunkStart = rangeStart;
			servedLength = chunkEnd - chunkStart + 1;
		} else {
			// Open length (no Range, no ?seg=) — we don't know total, so clamp
			// to one chunk past rangeStart and let the client keep requesting.
			chunkEnd = rangeStart + (long) chunkMaxBytes - 1;
			chunkStart = rangeStart;
			servedLength = chunkMaxBytes;
			clamped = true;
		}
		long totalSize = info.contentLength > 0 ? info.contentLength : C.LENGTH_UNSET;

		Log.d(TAG, "serveStream: token=" + token
				+ " range=" + rangeStart + "-" + (rangeEnd != C.LENGTH_UNSET ? rangeEnd : "*")
				+ " chunk=" + chunkStart + "-" + chunkEnd
				+ " servedLength=" + servedLength
				+ (clamped ? " (clamped)" : "")
				+ " chunkMax=" + chunkMaxBytes
				+ " total=" + (totalSize != C.LENGTH_UNSET ? totalSize : "*")
				+ " contentLength=" + info.contentLength
				+ " ua=" + (userAgent != null ? userAgent.substring(0, Math.min(userAgent.length(), 40)) : "null"));

		try {
			return serveStreamUpstream(token, info, chunkStart, chunkEnd, servedLength,
					hasRange, segParam, totalSize, clamped, userAgent);
		} catch (HttpDataSource.InvalidResponseCodeException e) {
			if (e.responseCode == 403 && refreshedUrls.add(token)) {
				// 403 typically means the stream URL's po-token (pot=) has been
				// invalidated by YouTube. Ask the refresher for a fresh URL,
				// re-register it for this token, and retry the request once.
				// Guarded by refreshedUrls so a second 403 on the same token
				// (genuinely broken) surfaces immediately instead of looping.
				Log.w(TAG, "stream 403 for token=" + token + " — refreshing URL", e);
				UrlRefresher refresher = urlRefresher;
				String freshUrl = refresher != null ? refresher.refreshUrl(token) : null;
				if (freshUrl != null) {
					StreamInfo fresh = new StreamInfo(freshUrl,
							info.initStart, info.initEnd,
							info.indexStart, info.indexEnd, info.contentLength);
					tokenToInfo.put(token, fresh);
					try {
						return serveStreamUpstream(token, fresh, chunkStart, chunkEnd,
								servedLength, hasRange, segParam, totalSize, clamped, userAgent);
					} catch (Exception retry) {
						Log.w(TAG, "stream retry failed for token=" + token, retry);
					}
				}
			}
			Log.w(TAG, "stream " + e.responseCode + " for token=" + token, e);
			return newResponse(NanoHTTPD.Response.Status.FORBIDDEN, "Upstream " + e.responseCode);
		} catch (Exception e) {
			Log.w(TAG, "stream fetch failed for token=" + token, e);
			return newResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "Proxy error");
		}
	}

	/**
	 * Tokens that have already had their URL refreshed once due to a 403. Used
	 * to prevent refresh loops: a second 403 on the same token (after a
	 * successful refresh) is treated as a genuine failure.
	 */
	private final java.util.Set<String> refreshedUrls =
			java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

	/**
	 * Opens the upstream YouTube stream and builds the response. Throws on
	 * failure so the caller can apply 403-URL-refresh recovery.
	 *
	 * @param chunkStart  first byte to serve (inclusive)
	 * @param chunkEnd    last byte to serve (inclusive); clamped to chunk size
	 * @param servedLength {@code chunkEnd - chunkStart + 1}
	 * @param totalSize   upstream resource total size, or {@link C#LENGTH_UNSET}
	 * @param clamped     true if the original request was larger than one chunk
	 * @param userAgent   client UA, for diagnostics only
	 */
	@NonNull
	private NanoHTTPD.Response serveStreamUpstream(@NonNull String token,
	                                               @NonNull StreamInfo info,
	                                               long chunkStart, long chunkEnd,
	                                               long servedLength,
	                                               boolean hasRange,
	                                               @Nullable String segParam,
	                                               long totalSize,
	                                               boolean clamped,
	                                               @Nullable String userAgent) throws Exception {
		long t0 = System.currentTimeMillis();
		Uri youtubeUri = Uri.parse(info.youtubeUrl);
		DataSpec.Builder specBuilder = new DataSpec.Builder()
				.setUri(youtubeUri)
				.setPosition(chunkStart);
		if (servedLength != C.LENGTH_UNSET) {
			// Bound the upstream read to exactly one chunk so we don't buffer
			// the whole resource in memory / keep the YouTube connection open
			// past what we intend to serve.
			specBuilder.setLength(servedLength);
		}
		DataSpec dataSpec = specBuilder.build();

		YoutubeHttpDataSource dataSource = openWithRetry(dataSpec, token);
		long tOpen = System.currentTimeMillis();
		Log.d(TAG, "serveStream: upstream open took " + (tOpen - t0) + "ms for token=" + token);

		CountingDataSourceInputStream inputStream = new CountingDataSourceInputStream(dataSource, token, servedLength);
		// Probe the first read so an immediate upstream failure (403 mid-session,
		// network reset, …) surfaces as a 5xx instead of being silently turned
		// into a truncated 200/PARTIAL_CONTENT that the receiver decodes as broken.
		inputStream.prefill();
		long tPrefill = System.currentTimeMillis();
		IOException error = inputStream.readError();
		if (error != null) {
			Log.w(TAG, "stream read failed at open for token=" + token
					+ " after " + (tPrefill - tOpen) + "ms", error);
			return newResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "Upstream read error");
		}
		Log.d(TAG, "serveStream: upstream OK, serving " + servedLength
				+ " bytes for token=" + token + " (prefill " + (tPrefill - tOpen) + "ms)"
				+ (clamped ? " [chunked]" : "")
				+ (userAgent != null ? " ua=" + userAgent.substring(0, Math.min(userAgent.length(), 40)) : ""));
		String streamMimeType = "a".equals(token) ? "audio/mp4" : "video/mp4";
		NanoHTTPD.Response response;
		if (segParam != null) {
			// ?seg=START-END: a standalone segment resource (its own URL in the
			// manifest's SegmentList). Return 200 OK — the URL identifies a
			// complete segment, not a byte range of a larger resource. Returning
			// 206 + Content-Range here confuses the cast receiver because the
			// response is not a sub-range of the full stream.
			response = NanoHTTPD.newFixedLengthResponse(
					NanoHTTPD.Response.Status.OK, streamMimeType,
					inputStream, servedLength);
		} else if (hasRange) {
			response = NanoHTTPD.newFixedLengthResponse(
					NanoHTTPD.Response.Status.PARTIAL_CONTENT, streamMimeType,
					inputStream, servedLength);
			// Content-Range is only meaningful for a byte-range response.
			if (chunkEnd != C.LENGTH_UNSET) {
				response.addHeader("Content-Range",
						"bytes " + chunkStart + "-" + chunkEnd + "/"
								+ (totalSize != C.LENGTH_UNSET ? totalSize : "*"));
			}
		} else {
			// No Range header: serve the first chunk as 200 OK. The client must
			// keep requesting subsequent chunks via Range; the Content-Range
			// header tells it the total size so it knows when to stop.
			response = NanoHTTPD.newFixedLengthResponse(
					NanoHTTPD.Response.Status.OK, streamMimeType,
					inputStream, servedLength);
			if (chunkEnd != C.LENGTH_UNSET) {
				response.addHeader("Content-Range",
						"bytes " + chunkStart + "-" + chunkEnd + "/"
								+ (totalSize != C.LENGTH_UNSET ? totalSize : "*"));
			}
		}
		response.addHeader("Accept-Ranges", "bytes");
		response.addHeader("Access-Control-Allow-Origin", "*");
		Log.d(TAG, "serveStream: responding " + response.getStatus().getDescription()
				+ " Content-Length=" + servedLength
				+ (chunkEnd != C.LENGTH_UNSET
						? " Content-Range=" + chunkStart + "-" + chunkEnd + "/"
								+ (totalSize != C.LENGTH_UNSET ? totalSize : "*")
						: "")
				+ " for token=" + token);
		return response;
	}

	@Nullable
	private static String getHeader(@NonNull NanoHTTPD.IHTTPSession session, @NonNull String name) {
		Map<String, String> headers = session.getHeaders();
		for (Map.Entry<String, String> entry : headers.entrySet()) {
			if (name.equalsIgnoreCase(entry.getKey())) {
				return entry.getValue();
			}
		}
		return null;
	}

	@Nullable
	private static String getQueryParameter(@NonNull NanoHTTPD.IHTTPSession session, @NonNull String name) {
		for (Map.Entry<String, List<String>> entry : session.getParameters().entrySet()) {
			if (name.equals(entry.getKey())) {
				List<String> values = entry.getValue();
				return values != null && !values.isEmpty() ? values.get(0) : null;
			}
		}
		return null;
	}

	/**
	 * Parses an HTTP {@code Range: bytes=<start>-<end>} header.
	 * @return {@code [start, end]} where {@code end} may be {@link C#LENGTH_UNSET}.
	 */
	@NonNull
	private static long[] parseRange(@NonNull String rangeHeader) {
		long start = 0;
		long end = C.LENGTH_UNSET;
		String spec = rangeHeader.substring("bytes=".length());
		int dash = spec.indexOf('-');
		if (dash >= 0) {
			try {
				start = dash > 0 ? Long.parseLong(spec.substring(0, dash)) : 0L;
				String endPart = spec.substring(dash + 1);
				end = !endPart.isEmpty() ? Long.parseLong(endPart) : C.LENGTH_UNSET;
			} catch (NumberFormatException e) {
				// Malformed Range header — fall through to "0-unbounded" semantics
				// so the receiver still gets a usable response.
				Log.d(TAG, "parseRange: malformed Range header: " + rangeHeader);
			}
		}
		return new long[]{start, end};
	}

	@NonNull
	private static String escapeHtml(@NonNull String text) {
		StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '<' -> out.append("&lt;");
				case '>' -> out.append("&gt;");
				case '&' -> out.append("&amp;");
				case '"' -> out.append("&quot;");
				case '\'' -> out.append("&#39;");
				default -> out.append(c);
			}
		}
		return out.toString();
	}

	@NonNull
	private NanoHTTPD.Response newResponse(@NonNull NanoHTTPD.Response.Status status, @NonNull String message) {
		NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(status, "text/plain", message);
		response.addHeader("Access-Control-Allow-Origin", "*");
		return response;
	}

	/**
	 * Returns the LAN IP, detected once and cached for the proxy's lifetime.
	 * See {@link #cachedLanIp} for why we don't re-detect on every call.
	 */
	@NonNull
	private String getLanIpAddress() {
		String cached = cachedLanIp;
		if (cached != null) return cached;
		String detected = detectLanIpAddress();
		cachedLanIp = detected;
		return detected;
	}

	/**
	 * Detects the LAN IPv4 address the Chromecast receiver can reach.
	 * <p>
	 * Strategy (in priority order):
	 * <ol>
	 *   <li>Ask {@link ConnectivityManager} for the interface name of the active
	 *       default network (usually WiFi). If we can match it to a
	 *       {@link NetworkInterface} <em>and it is not a virtual tunnel</em>, use
	 *       its IPv4 address. When a VPN is active the system default network is
	 *       the VPN tunnel ({@code tun0}), which the Chromecast cannot route to,
	 *       so we must skip it and fall through to enumeration.</li>
	 *   <li>Enumerate interfaces, skipping known virtual/tunnel prefixes
	 *       ({@code tun}, {@code ppp}, {@code rmnet}, {@code dummy}) and
	 *       carrier-grade VPN ranges (198.18/15). Prefers names starting with
	 *       {@code wlan} or {@code eth}.</li>
	 *   <li>Final fallback: {@code 127.0.0.1} (cast will fail, but at least the
	 *       error is visible in logs rather than silently routing to a VPN).</li>
	 * </ol>
	 */
	@NonNull
	private String detectLanIpAddress() {
		StringBuilder ifaceLog = new StringBuilder();
		String activeIfaceName = getActiveNetworkInterfaceName();
		ifaceLog.append("\n  activeIface=").append(activeIfaceName != null ? activeIfaceName : "null");

		// Strategy 1: match the active network's interface, but only if it is not a
		// virtual tunnel. A VPN-active phone reports tun0 as the active interface;
		// the Chromecast cannot reach it, so we must fall through to enumeration
		// and pick the real wlan0 address instead.
		if (activeIfaceName != null && !isVirtualTunnelName(activeIfaceName)) {
			String ip = ipv4OfInterface(activeIfaceName, ifaceLog);
			if (ip != null && !isVirtualTunnelAddress(activeIfaceName, ip)) {
				Log.d(TAG, "detectLanIpAddress: selected " + ip
						+ " from active iface=" + activeIfaceName
						+ ifaceLog);
				return ip;
			}
		}

		// Strategy 2: enumerate, preferring wlan/eth, skipping virtual tunnels.
		String bestIp = null;
		String bestIface = null;
		try {
			Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
			while (interfaces != null && interfaces.hasMoreElements()) {
				NetworkInterface network = interfaces.nextElement();
				boolean up = network.isUp();
				boolean loopback = network.isLoopback();
				String name = network.getDisplayName();
				ifaceLog.append(String.format("\n  %-12s up=%-5s loopback=%-5s",
						name, up, loopback));
				if (loopback || !up) continue;
				Enumeration<InetAddress> addresses = network.getInetAddresses();
				while (addresses.hasMoreElements()) {
					InetAddress address = addresses.nextElement();
					ifaceLog.append(" addr=").append(address.getHostAddress());
					if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
					String ip = address.getHostAddress();
					if (isVirtualTunnelAddress(name, ip)) continue;
					// Prefer wlan/eth interfaces; remember others as fallback.
					if (isLikelyHardwareInterface(name)) {
						Log.d(TAG, "detectLanIpAddress: selected " + ip
								+ " from iface=" + name
								+ ifaceLog);
						return ip;
					}
					if (bestIp == null) {
						bestIp = ip;
						bestIface = name;
					}
				}
			}
		} catch (Exception e) {
			Log.w(TAG, "detectLanIpAddress: enumeration failed", e);
		}

		if (bestIp != null) {
			Log.d(TAG, "detectLanIpAddress: selected " + bestIp
					+ " from non-hardware iface=" + bestIface
					+ ifaceLog);
			return bestIp;
		}
		Log.w(TAG, "detectLanIpAddress: no non-loopback IPv4 found, falling back to 127.0.0.1"
				+ ifaceLog);
		return "127.0.0.1";
	}

	/**
	 * Returns the interface name of the system's active default network (typically
	 * WiFi), or {@code null} if unavailable. Uses {@link ConnectivityManager} which
	 * only requires {@code ACCESS_NETWORK_STATE} (already declared).
	 */
	@Nullable
	private String getActiveNetworkInterfaceName() {
		try {
			ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
			if (cm == null) return null;
			Network network = cm.getActiveNetwork();
			if (network == null) return null;
			LinkProperties lp = cm.getLinkProperties(network);
			return lp != null ? lp.getInterfaceName() : null;
		} catch (Exception e) {
			Log.w(TAG, "getActiveNetworkInterfaceName failed", e);
			return null;
		}
	}

	/**
	 * Returns the IPv4 address bound to the named interface, or {@code null}.
	 */
	@Nullable
	private static String ipv4OfInterface(@NonNull String ifaceName, @NonNull StringBuilder ifaceLog) {
		try {
			NetworkInterface network = NetworkInterface.getByName(ifaceName);
			if (network == null || !network.isUp() || network.isLoopback()) return null;
			Enumeration<InetAddress> addresses = network.getInetAddresses();
			while (addresses.hasMoreElements()) {
				InetAddress address = addresses.nextElement();
				if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
					return address.getHostAddress();
				}
			}
		} catch (Exception e) {
			Log.w(TAG, "ipv4OfInterface failed for " + ifaceName, e);
		}
		return null;
	}

	/**
	 * Returns true if the interface name looks like a virtual tunnel that the
	 * Chromecast cannot route to (VPN tun0, ppp0, etc.).
	 */
	private static boolean isVirtualTunnelName(@Nullable String ifaceName) {
		if (ifaceName == null) return false;
		String lower = ifaceName.toLowerCase(Locale.ROOT);
		return lower.startsWith("tun") || lower.startsWith("ppp")
				|| lower.startsWith("rmnet") || lower.startsWith("dummy");
	}

	/**
	 * Returns true if the interface name or IP indicates a virtual tunnel that the
	 * Chromecast cannot route to (VPN tun0, ppp0, etc.) or a carrier-grade VPN
	 * address range (198.18.0.0/15, used by Clash/V2Ray/Surge).
	 */
	private static boolean isVirtualTunnelAddress(@Nullable String ifaceName, @NonNull String ip) {
		if (isVirtualTunnelName(ifaceName)) return true;
		// 198.18.0.0/15 is reserved for benchmarking (RFC 2544) but commonly used
		// by Android VPN apps as a virtual tunnel address.
		try {
			String[] parts = ip.split("\\.");
			if (parts.length == 4) {
				int first = Integer.parseInt(parts[0]);
				int second = Integer.parseInt(parts[1]);
				int ip16 = (first << 8) | second;
				return ip16 >= 0xC612 && ip16 <= 0xC613; // 198.18.0.0 - 198.19.255.255
			}
		} catch (NumberFormatException e) {
			// Non-numeric IPv4 octet — not in the benchmark range.
			Log.d(TAG, "isVirtualTunnelAddress: non-numeric IPv4 octet in " + ip);
		}
		return false;
	}

	/**
	 * Returns true if the interface name looks like a real hardware interface
	 * (WiFi or Ethernet) rather than a virtual one.
	 */
	private static boolean isLikelyHardwareInterface(@Nullable String name) {
		if (name == null) return false;
		String lower = name.toLowerCase(Locale.ROOT);
		return lower.startsWith("wlan") || lower.startsWith("eth")
				|| lower.startsWith("wifi") || lower.startsWith("ap");
	}

	/**
	 * InputStream that reads from a {@link YoutubeHttpDataSource} and closes it at EOF
	 * or once the configured number of bytes have been served (chunk cap). Read
	 * errors are recorded rather than silently treated as EOF, so the proxy can
	 * report a 5xx instead of truncating the response (which causes MEDIA_ERR_DECODE
	 * on the receiver).
	 */
	private static final class CountingDataSourceInputStream extends InputStream {
		@NonNull
		private final YoutubeHttpDataSource dataSource;
		@NonNull
		private final String token;
		private final byte[] buffer = new byte[8192];
		private int bufferPos = 0;
		private int bufferLen = 0;
		private boolean eof = false;
		@Nullable
		private volatile IOException readError;
		private long totalBytesServed = 0;
		/**
		 * Bytes we are allowed to serve from upstream; once the served count
		 * reaches this we force-close the upstream connection even if the source
		 * has more data. This is the server-side chunk cap: without it a receiver
		 * that asks for {@code Range: bytes=0-} would keep the YouTube connection
		 * open until the whole resource is consumed. {@code 0} means unlimited
		 * (only EOF closes) — used when total size is unknown.
		 */
		private final long serveLimit;
		private long bytesRemaining = 0;
		private final long startMs = System.currentTimeMillis();

		CountingDataSourceInputStream(@NonNull YoutubeHttpDataSource dataSource,
		                              @NonNull String token,
		                              long serveLimit) {
			this.dataSource = dataSource;
			this.token = token;
			this.serveLimit = serveLimit > 0 ? serveLimit : 0;
			this.bytesRemaining = this.serveLimit;
		}

		/** Returns any error encountered while reading, or {@code null}. */
		@Nullable
		IOException readError() {
			return readError;
		}

		/**
		 * Performs one eager read so the caller can detect an upstream failure before
		 * committing to a fixed-length response. After this, the buffered bytes are
		 * available to the next {@code read()} call.
		 */
		void prefill() {
			if (bufferPos >= bufferLen && !eof) {
				fill();
			}
		}

		@Override
		public int read() {
			if (bufferPos >= bufferLen) {
				if (eof) return -1;
				fill();
				if (bufferPos >= bufferLen) return -1;
			}
			bufferPos++;
			totalBytesServed++;
			return buffer[bufferPos - 1] & 0xFF;
		}

		@Override
		public int read(@NonNull byte[] b, int off, int len) {
			if (bufferPos >= bufferLen) {
				if (eof) return -1;
				fill();
				if (bufferPos >= bufferLen) return -1;
			}
			int toCopy = Math.min(len, bufferLen - bufferPos);
			System.arraycopy(buffer, bufferPos, b, off, toCopy);
			bufferPos += toCopy;
			totalBytesServed += toCopy;
			return toCopy;
		}

		private void fill() {
			if (serveLimit > 0 && bytesRemaining <= 0) {
				// Server-side chunk limit reached — signal EOF so NanoHTTPD
				// finishes the response without consuming any more of the
				// upstream YouTube connection.
				eof = true;
				bufferLen = 0;
				bufferPos = 0;
				closeQuietly(dataSource);
				return;
			}
			try {
				int want = buffer.length;
				if (serveLimit > 0) {
					want = (int) Math.min(want, bytesRemaining);
					if (want <= 0) {
						eof = true;
						bufferLen = 0;
						bufferPos = 0;
						closeQuietly(dataSource);
						return;
					}
				}
				int read = dataSource.read(buffer, 0, want);
				if (read == C.RESULT_END_OF_INPUT) {
					eof = true;
					bufferLen = 0;
					bufferPos = 0;
				} else {
					bufferPos = 0;
					bufferLen = read;
					if (serveLimit > 0) {
						bytesRemaining -= read;
					}
				}
			} catch (java.io.IOException e) {
				// Record the error instead of silently ending the stream; the caller
				// checks readError() and reports a 5xx so the receiver surfaces a
				// clear failure rather than a truncated/decoded-broken response.
				readError = e;
				eof = true;
				bufferLen = 0;
				bufferPos = 0;
			}
		}

		@Override
		public void close() {
			long elapsed = System.currentTimeMillis() - startMs;
			Log.d(TAG, "serveStream: token=" + token
					+ " served=" + totalBytesServed + "B"
					+ (serveLimit > 0 ? " limit=" + serveLimit + "B" : "")
					+ " elapsed=" + elapsed + "ms"
					+ (readError != null ? " ERROR=" + readError.getClass().getSimpleName()
							+ ": " + readError.getMessage() : ""));
			closeQuietly(dataSource);
		}
	}
}
