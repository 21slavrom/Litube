package com.hhst.youtubelite.cast;

import android.app.Activity;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.cast.CastPlayer;
import androidx.media3.extractor.ChunkIndex;

import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.hhst.youtubelite.extractor.PlaybackDetails;
import com.hhst.youtubelite.extractor.Delivery;
import com.hhst.youtubelite.extractor.PlaybackPlan;
import com.hhst.youtubelite.extractor.StreamCandidate;
import com.hhst.youtubelite.player.common.PlayerPreferences;
import com.hhst.youtubelite.player.engine.Engine;

import javax.inject.Inject;

import dagger.hilt.android.scopes.ActivityScoped;
import okhttp3.HttpUrl;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Coordinates Chromecast playback by synthesizing a DASH manifest from the adaptive video/audio
 * candidates, serving it through a local proxy, and loading it on a {@link CastPlayer}. While
 * casting, the local {@link Engine} (ExoPlayer) is paused but its {@link LitePlayerView}
 * <em>stays attached</em> to the ExoPlayer; playback control and state are mirrored to the
 * CastPlayer via the Engine's cast delegate so the phone acts as a remote control without
 * rendering its own (empty) video surface.
 */
@UnstableApi
@ActivityScoped
public class CastPlaybackController {
	private static final String TAG = "YTLCast";
	private static final int PROXY_PORT = 0; // Let the OS pick an available port.
	/**
	 * How long to wait for both video and audio sidx boxes before falling back
	 * to a SegmentBase manifest. YouTube CDN opens can take 1-2s each, and we
	 * retry on transient failures, so 3s is too short and leaves Chromecast on
	 * the full-stream path. 15s covers two sequential slow opens plus retries
	 * while still getting SegmentList loaded before the user notices a stall.
	 */
	private static final int SIDX_FETCH_TIMEOUT_MS = 15_000;
	/**
	 * After loading SegmentBase because sidx was slow, keep trying in the
	 * background for this long. Once sidx arrives we reload the receiver with
	 * a SegmentList manifest so seek/playback is segment-addressable.
	 */
	private static final int SIDX_FETCH_RELOAD_TIMEOUT_MS = 15_000;

	/**
	 * Notified when a Cast session starts, so the caller can push the currently
	 * playing video onto the receiver. Invoked on the main thread.
	 */
	public interface OnSessionStartedListener {
		void onCastSessionStarted();
	}

	/**
	 * Notified when a Cast session ends, so the caller can resume local playback
	 * from the last cast position. Invoked on the main thread.
	 */
	public interface OnSessionEndedListener {
		void onCastSessionEnded();
	}

	/**
	 * Notified when the Cast discovery state changes (devices appear or
	 * disappear on the network). Invoked on the main thread. Used by the cast
	 * dialog to refresh its device list while open — {@code MediaRouter} route
	 * callbacks alone are unreliable because the Cast route provider only
	 * populates routes once {@link CastContext} has finished its first scan,
	 * which can take several seconds after the framework is initialized.
	 */
	public interface OnCastStateChangedListener {
		void onCastStateChanged(int castState);
	}

	@NonNull
	private final Activity activity;
	@NonNull
	private final PlayerPreferences prefs;
	/**
	 * Executor for sidx fetches. {@link LocalStreamProxy#fetchChunkIndex} does
	 * network I/O, so it must not run on the main thread. Video and audio sidx
	 * are independent, and the coordinator task blocks waiting for both futures,
	 * so a 2-thread pool would accidentally serialize the fetches. 4 threads
	 * allow the coordinator + 2 concurrent sidx fetches + headroom for link-cast
	 * background upgrades without creating an unbounded number of threads.
	 */
	@NonNull
	private final ExecutorService ioExecutor = Executors.newFixedThreadPool(4, r -> {
		Thread t = new Thread(r, "YTL-CastSidx");
		t.setDaemon(true);
		return t;
	});
	@Nullable
	private CastPlayer castPlayer;
	@Nullable
	private volatile LocalStreamProxy proxy;
	@Nullable
	private SessionManagerListener<CastSession> sessionListener;
	@Nullable
	private CastSession currentSession;
	@Nullable
	private OnSessionStartedListener onSessionStartedListener;
	@Nullable
	private OnSessionEndedListener onSessionEndedListener;
	@Nullable
	private OnCastStateChangedListener onCastStateChangedListener;
	@Nullable
	private com.google.android.gms.cast.framework.CastStateListener castStateListener;
	@Nullable
	private String castingDeviceName;
	/**
	 * Refreshes the YouTube stream URL for a proxy token when the cached one
	 * is rejected (403). Set by {@link LitePlayer} so the proxy can recover
	 * from an expired po-token without surfacing the error to dash.js.
	 */
	@Nullable
	private LocalStreamProxy.UrlRefresher urlRefresher;
	private boolean casting = false;

	@Inject
	public CastPlaybackController(@NonNull Activity activity,
	                             @NonNull PlayerPreferences prefs) {
		this.activity = activity;
		this.prefs = prefs;
	}

	/**
	 * Registers a listener invoked when a Cast session starts, so the caller can
	 * push the current video onto the receiver. Must be set before
	 * {@link #initialize()}.
	 */
	public void setOnSessionStartedListener(@Nullable OnSessionStartedListener listener) {
		this.onSessionStartedListener = listener;
	}

	/**
	 * Registers a listener invoked when a Cast session ends, so the caller can
	 * resume local playback. Must be set before {@link #initialize()}.
	 */
	public void setOnSessionEndedListener(@Nullable OnSessionEndedListener listener) {
		this.onSessionEndedListener = listener;
	}

	/**
	 * Sets the callback used to refresh expired stream URLs (403 po-token
	 * recovery). The proxy calls it when YouTube rejects a segment/sidx fetch
	 * with HTTP 403; the refresher re-extracts the video and returns the new
	 * URL for the given token ("v" or "a").
	 */
	public void setUrlRefresher(@Nullable LocalStreamProxy.UrlRefresher refresher) {
		this.urlRefresher = refresher;
	}

	/**
	 * Registers a listener invoked when the Cast discovery state changes
	 * (devices appear/disappear on the network). Used by the cast dialog to
	 * refresh its device list while open.
	 */
	public void setOnCastStateChangedListener(@Nullable OnCastStateChangedListener listener) {
		this.onCastStateChangedListener = listener;
	}

	/**
	 * Initializes the Cast framework and registers a session listener. Must be called from the
	 * Activity onCreate. Returns false if Google Play Services / Cast is unavailable.
	 */
	public boolean initialize() {
		try {
			CastContext castContext = CastContext.getSharedInstance(activity);
			CastPlayer player = new CastPlayer(castContext);
			player.addListener(new Player.Listener() {
				@Override
				public void onPlaybackStateChanged(int state) {
					String label = switch (state) {
						case Player.STATE_IDLE -> "IDLE";
						case Player.STATE_BUFFERING -> "BUFFERING";
						case Player.STATE_READY -> "READY";
						case Player.STATE_ENDED -> "ENDED";
						default -> "UNKNOWN(" + state + ")";
					};
					Log.d(TAG, "cast player state -> " + label
							+ " position=" + player.getCurrentPosition()
							+ " duration=" + player.getDuration());
					if (state == Player.STATE_ENDED && casting) {
						Log.d(TAG, "cast playback ended");
					}
				}

				@Override
				public void onPlayerErrorChanged(@Nullable androidx.media3.common.PlaybackException error) {
					if (error != null) {
						Log.w(TAG, "cast player error: " + error.getErrorCodeName()
								+ " cause=" + (error.getCause() != null
										? error.getCause().getClass().getSimpleName()
												+ ": " + error.getCause().getMessage()
										: "null"), error);
					}
				}
			});
			this.castPlayer = player;
			SessionManager sessionManager = castContext.getSessionManager();
			this.sessionListener = new SessionManagerListener<CastSession>() {
				@Override
				public void onSessionStarted(@NonNull CastSession session, @NonNull String sessionId) {
					currentSession = session;
					if (onSessionStartedListener != null) {
						onSessionStartedListener.onCastSessionStarted();
					}
				}

				@Override
				public void onSessionEnded(@NonNull CastSession session, int error) {
					currentSession = null;
					// Always run the teardown path. If a listener is registered it
					// will stopCasting() (capturing the last position) and resume
					// local playback; otherwise stopCasting() directly.
					if (onSessionEndedListener != null) {
						onSessionEndedListener.onCastSessionEnded();
					} else if (casting) {
						stopCasting();
					}
				}

				@Override
				public void onSessionResumed(@NonNull CastSession session, boolean wasSuspended) {
					currentSession = session;
					// Refresh the device name in case the session resumed on a
					// different device.
					if (casting) {
						castingDeviceName = session.getCastDevice() != null
								? session.getCastDevice().getFriendlyName() : null;
					}
				}

				@Override
				public void onSessionStarting(@NonNull CastSession session) {
				}

				@Override
				public void onSessionStartFailed(@NonNull CastSession session, int error) {
					Log.w(TAG, "onSessionStartFailed: error=" + error);
				}

				@Override
				public void onSessionEnding(@NonNull CastSession session) {
				}

				@Override
				public void onSessionResuming(@NonNull CastSession session, @NonNull String sessionId) {
				}

				@Override
				public void onSessionResumeFailed(@NonNull CastSession session, int error) {
				}

				@Override
				public void onSessionSuspended(@NonNull CastSession session, int reason) {
				}
			};
			sessionManager.addSessionManagerListener(sessionListener, CastSession.class);
			// Register a CastStateListener so the dialog (and any other observer)
			// is notified when the Cast framework finishes its first discovery scan
			// and when devices appear/disappear. Without this the dialog only hears
			// about route changes via MediaRouter, which lags behind CastContext.
			this.castStateListener = state -> {
				OnCastStateChangedListener l = onCastStateChangedListener;
				if (l != null) {
					l.onCastStateChanged(state);
				}
			};
			castContext.addCastStateListener(castStateListener);
			return true;
		} catch (Exception e) {
			Log.w(TAG, "Cast unavailable", e);
			return false;
		}
	}

	/**
	 * Returns whether a Cast route is currently available (device on the network).
	 */
	public boolean isCastAvailable() {
		return getCastState() != com.google.android.gms.cast.framework.CastState.NO_DEVICES_AVAILABLE;
	}

	/**
	 * Returns the current Cast discovery/connection state. One of
	 * {@code CastState.NO_DEVICES_AVAILABLE}, {@code NOT_CONNECTED},
	 * {@code CONNECTING}, {@code CONNECTED}. Returns
	 * {@code NO_DEVICES_AVAILABLE} if the Cast framework is unavailable.
	 */
	public int getCastState() {
		try {
			CastContext castContext = CastContext.getSharedInstance(activity);
			return castContext.getCastState();
		} catch (Exception e) {
			// Cast framework unavailable (Play Services missing/disabled). Fall
			// back to NO_DEVICES_AVAILABLE so the UI hides cast affordances.
			Log.w(TAG, "getCastState: Cast framework unavailable", e);
			return com.google.android.gms.cast.framework.CastState.NO_DEVICES_AVAILABLE;
		}
	}

	/**
	 * Returns whether casting is currently active.
	 */
	public boolean isCasting() {
		return casting;
	}

	/**
	 * Returns whether the Cast framework initialized successfully (Google Play
	 * Services is available and {@link #initialize()} ran). When true, discovery
	 * is active and {@link #getCastState()} will eventually transition out of
	 * {@code NO_DEVICES_AVAILABLE} once the first scan completes. Distinct from
	 * {@link #isCastAvailable()}, which is only true once at least one device
	 * has actually been found.
	 */
	public boolean isCastInitialized() {
		return castPlayer != null;
	}

	/**
	 * Returns the friendly name of the device currently being cast to, or
	 * {@code null} if not casting or unavailable. Used for the "Casting to …"
	 * indicator on the phone-side player.
	 */
	@Nullable
	public String getCastingDeviceName() {
		return casting ? castingDeviceName : null;
	}

	/**
	 * Returns the {@link CastPlayer} used to drive the receiver, or {@code null} if
	 * Cast is not initialized. The caller attaches this as the local engine's
	 * playback delegate so phone-side controls (play/pause/seek) are forwarded to
	 * the TV while its state changes are mirrored back to the phone UI.
	 */
	@Nullable
	public CastPlayer getCastPlayer() {
		return castPlayer;
	}

	/**
	 * Returns whether a Cast session is currently connected.
	 */
	public boolean hasActiveSession() {
		return currentSession != null;
	}

	/**
	 * Returns the proxy URL for the browser player page, or {@code null} if no proxy is running.
	 */
	@Nullable
	public String getPlayerUrl() {
		LocalStreamProxy p = proxy;
		return p != null ? p.getProxyUrl("/player") : null;
	}

	/**
	 * Loads the manifest on the Cast receiver. Must run on the main thread
	 * because {@link com.google.android.gms.cast.framework.media.RemoteMediaClient}
	 * is not thread-safe.
	 */
	private void loadOnReceiver(@NonNull CastSession session,
	                            @NonNull CastPlayer player,
	                            @NonNull String manifestUrl,
	                            @NonNull String videoTitle,
	                            @Nullable String videoAuthor,
	                            long startPositionMs) {
		MediaItem mediaItem = MediaItem.fromUri(manifestUrl)
				.buildUpon()
				.setMimeType(MimeTypes.APPLICATION_MPD)
				.build();
		com.google.android.gms.cast.framework.media.RemoteMediaClient client =
				session.getRemoteMediaClient();
		if (client == null) {
			Log.w(TAG, "loadOnReceiver: RemoteMediaClient null, falling back to CastPlayer");
			player.setMediaItem(mediaItem);
			player.prepare();
			if (startPositionMs > 0) {
				player.seekTo(startPositionMs);
			}
			player.play();
			return;
		}
		com.google.android.gms.cast.MediaMetadata metadata = new com.google.android.gms.cast.MediaMetadata(
				com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MOVIE);
		metadata.putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, videoTitle);
		if (videoAuthor != null) {
			metadata.putString(com.google.android.gms.cast.MediaMetadata.KEY_SUBTITLE, videoAuthor);
		}
		com.google.android.gms.cast.MediaInfo mediaInfo =
				new com.google.android.gms.cast.MediaInfo.Builder(manifestUrl)
						.setContentType(MimeTypes.APPLICATION_MPD)
						.setStreamType(com.google.android.gms.cast.MediaInfo.STREAM_TYPE_BUFFERED)
						.setMetadata(metadata)
						.build();
		Log.d(TAG, "loadOnReceiver: loading manifestUrl=" + manifestUrl
				+ " position=" + startPositionMs);
		client.load(mediaInfo, true, startPositionMs)
				.setResultCallback(result -> {
					if (result.getStatus().isSuccess()) {
						Log.d(TAG, "RemoteMediaClient.load: SUCCESS");
					} else {
						Log.w(TAG, "RemoteMediaClient.load: FAILED statusCode="
								+ result.getStatus().getStatusCode()
								+ " message=" + result.getStatus().getStatusMessage());
					}
				});
	}

	/**
	 * Returns whether a share-link session is currently alive on the local
	 * proxy <em>and</em> a browser / receiver is actively consuming it. The
	 * proxy is started as soon as the Cast dialog opens (so the share URL is
	 * copyable), but this only returns true once a browser has actually
	 * requested {@code /player} or {@code /manifest.mpd} within the last
	 * disconnect window. The Cast dialog and player banner use this to show
	 * the "connected" state only when a browser is really playing.
	 */
	public boolean isLinkCasting() {
		LocalStreamProxy p = proxy;
		return p != null && p.isBrowserConnected();
	}

	/**
	 * Returns whether the share-link proxy is running, regardless of whether a
	 * browser has connected yet. Used to decide whether to hot-swap the
	 * manifest (vs. start a fresh proxy) on video switches — the proxy may be
	 * up before any browser has opened the link.
	 */
	public boolean isLinkProxyRunning() {
		return proxy != null;
	}

	/**
	 * Returns whether a browser / receiver is currently consuming the share
	 * link. Delegates to {@link LocalStreamProxy#isBrowserConnected()}; returns
	 * false when the proxy is not running.
	 */
	public boolean isBrowserConnected() {
		LocalStreamProxy p = proxy;
		return p != null && p.isBrowserConnected();
	}

	/**
	 * Starts the local proxy and builds a DASH manifest for the given playback details,
	 * without requiring a Chromecast session. Returns the manifest URL that can be opened
	 * in any DASH-capable browser or media player (VLC, MPV) on the local network.
	 * <p>
	 * The proxy stays alive until {@link #stopCasting()} or {@link #release()} is called.
	 *
	 * @param details the extracted playback details
	 * @return the manifest URL, or {@code null} on failure
	 */
	@Nullable
	public String startProxy(@NonNull PlaybackDetails details) {
		PlaybackPlan plan = details.plan();
		// Pick an fmp4/H.264 video candidate, just like startCasting() does.
		// VP9/WebM streams (itag 248/271/313…) have no sidx box, so sidx parsing
		// fails and dash.js cannot locate video segments → "no picture but
		// progress moves". H.264/AVC is fmp4 (sidx works) and universally
		// decodable by browsers and Chromecast. AAC audio avoids opus-in-webm
		// which causes MEDIA_ERR_DECODE when the manifest claims audio/mp4.
		StreamCandidate videoCandidate = pickCastVideoCandidate(plan);
		StreamCandidate audioCandidate = pickCastAudioCandidate(plan);
		// MUXED plan: a single combined stream. Treat it as the video representation
		// so DashManifestBuilder produces a single-AdaptationSet MPD.
		if (videoCandidate == null) {
			videoCandidate = plan.getMuxedCandidate();
		}
		if (videoCandidate == null && audioCandidate == null) {
			return null;
		}
		// Link-cast session: if the proxy is already running, just hot-swap
		// the manifest so the URL stays stable. The share link is expected to
		// outlive a single video switch — only a real cast session should tear
		// the proxy down.
		if (proxy != null) {
			hotConfigureProxy(plan, videoCandidate, audioCandidate, details);
			return proxy != null ? proxy.getProxyUrl("/player") : null;
		}
		// Stop any previous proxy. (preserved — new link-cast hot-path above
		// handles the proxy-already-running case before reaching here.)
		if (proxy != null) {
			try {
				proxy.stop();
			} catch (Exception e) {
				Log.w(TAG, "startProxy: previous proxy stop failed", e);
			}
			proxy = null;
		}
		try {
			proxy = new LocalStreamProxy(activity, PROXY_PORT);
			proxy.setUrlRefresher(urlRefresher);
			proxy.start();

			long durationSeconds = details.video().getDuration() != null ? details.video().getDuration() : 0L;
			String videoToken = "v";
			String audioToken = "a";
			String videoId = details.video().getId();
			String videoUrl = videoCandidate != null && videoCandidate.getVideoStream() != null
					? videoCandidate.getVideoStream().getContent() : null;
			String audioUrl = audioCandidate != null && audioCandidate.getAudioStream() != null
					? audioCandidate.getAudioStream().getContent() : null;

			HttpUrl proxyBase = HttpUrl.parse(proxy.getProxyUrl(""));
			if (proxyBase == null) {
				proxy.stop();
				proxy = null;
				return null;
			}

			// Build a single SegmentBase manifest (~1KB regardless of duration).
			// The receiver (dash.js / Chromecast default receiver) fetches the
			// init and indexRange (sidx) itself and parses sidx to locate
			// segments — no need to enumerate every segment here (a SegmentList
			// would balloon to ~1MB for long videos and stall dash.js). The
			// proxy's 256KB response chunk cap prevents full-stream download.
			final CastManifestContext ctx = new CastManifestContext(
					proxy, videoCandidate, audioCandidate, durationSeconds, proxyBase,
					videoToken, audioToken, videoId, details.video().getTitle(), videoUrl, audioUrl);
			if (!configureProxyManifest(ctx)) {
				proxy.stop();
				proxy = null;
				return null;
			}
			Log.d(TAG, "startProxy: manifest built"
					+ " video=" + (videoUrl != null) + " audio=" + (audioUrl != null));

			// Upgrade to SegmentList in the background: browsers and some cast
			// receivers handle SegmentBase, but Chromecast's default receiver needs
			// explicit segment URLs. The proxy stays responsive while sidx is fetched.
			final LocalStreamProxy fp = proxy;
			CompletableFuture.runAsync(() -> {
				if (proxy != fp) return;
				CompletableFuture<ChunkIndex> vFuture = videoUrl != null
						? CompletableFuture.supplyAsync(() -> fp.fetchChunkIndex(ctx.videoToken()), ioExecutor)
						: CompletableFuture.completedFuture(null);
				CompletableFuture<ChunkIndex> aFuture = audioUrl != null
						? CompletableFuture.supplyAsync(() -> fp.fetchChunkIndex(ctx.audioToken()), ioExecutor)
						: CompletableFuture.completedFuture(null);
				try {
					CompletableFuture.allOf(vFuture, aFuture).get(SIDX_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
				} catch (Exception e) {
					Log.d(TAG, "startProxy: sidx upgrade failed or timed out, staying on SegmentBase", e);
					return;
				}
				if (proxy != fp) return;
				ChunkIndex vci = vFuture.getNow(null);
				ChunkIndex aci = aFuture.getNow(null);
				if (upgradeProxyToSegmentList(ctx, vci, aci)) {
					Log.d(TAG, "startProxy: upgraded to SegmentList"
							+ " videoSegs=" + (vci != null ? vci.length : 0)
							+ " audioSegs=" + (aci != null ? aci.length : 0));
				}
			}, ioExecutor);

			return proxy.getProxyUrl("/player");
		} catch (Exception e) {
			Log.w(TAG, "Failed to start proxy", e);
			if (proxy != null) {
				try { proxy.stop(); } catch (Exception stopErr) {
					Log.w(TAG, "startProxy: cleanup stop failed", stopErr);
				}
				proxy = null;
			}
			return null;
		}
	}

	/**
	 * Starts casting the given playback details. Pauses the local engine, starts the proxy,
	 * builds the DASH manifest, and loads it on the CastPlayer.
	 *
	 * @param details the extracted playback details (must contain an adaptive plan)
	 * @param engine the local engine to pause
	 * @param startPositionMs the position to resume from
	 * @return true if casting started, false if no castable stream was available
	 */
	public boolean startCasting(@NonNull PlaybackDetails details,
	                            @NonNull Engine engine,
	                            long startPositionMs) {
		CastPlayer player = castPlayer;
		if (player == null || currentSession == null) {
			return false;
		}
		PlaybackPlan plan = details.plan();
		// Pick an fmp4-container video candidate for casting. VP9/WebM streams
		// have no sidx box, so the cast receiver downloads the entire stream.
		// Also switch audio to AAC so the manifest/container mime types match.
		StreamCandidate videoCandidate = pickCastVideoCandidate(plan);
		StreamCandidate audioCandidate = pickCastAudioCandidate(plan);
		if (videoCandidate == null && audioCandidate == null) {
			Log.w(TAG, "startCasting: no video/audio candidate available (mode=" + plan.getMode() + ")");
			return false;
		}
		Log.d(TAG, "startCasting: video=" + describe(videoCandidate)
				+ " audio=" + describe(audioCandidate)
				+ " startPositionMs=" + startPositionMs);

		// Pause local playback; keep the local PlayerView attached to the ExoPlayer
		// (it has the video surface). Playback control/state is mirrored to the
		// CastPlayer via the Engine's cast delegate, so the phone acts as a remote.
		engine.pause();
		castingDeviceName = currentSession.getCastDevice() != null
				? currentSession.getCastDevice().getFriendlyName() : null;
		casting = true;

		HttpUrl proxyBase = startProxyForCast();
		if (proxyBase == null) {
			stopCasting();
			return false;
		}

		try {
			long durationSeconds = details.video().getDuration() != null ? details.video().getDuration() : 0L;
			String videoToken = "v";
			String audioToken = "a";
			String videoId = details.video().getId();
			String videoTitle = details.video().getTitle();
			String videoAuthor = details.video().getAuthor();
			String videoUrl = videoCandidate != null && videoCandidate.getVideoStream() != null
							? videoCandidate.getVideoStream().getContent() : null;
			String audioUrl = audioCandidate != null && audioCandidate.getAudioStream() != null
							? audioCandidate.getAudioStream().getContent() : null;

			final CastManifestContext ctx = new CastManifestContext(
					proxy, videoCandidate, audioCandidate, durationSeconds, proxyBase,
					videoToken, audioToken, videoId, videoTitle, videoUrl, audioUrl);
			if (!configureProxyManifest(ctx)) {
				Log.w(TAG, "startCasting: DashManifestBuilder.build returned null"
						+ " (video=" + describe(videoCandidate)
						+ " audio=" + describe(audioCandidate) + ")");
				stopCasting();
				return false;
			}

			final CastSession session = currentSession;
			final CastPlayer playerRef = player;
			final long pos = startPositionMs;
			final String segmentBaseUrl = proxy.getProxyUrl("/manifest.mpd");
			Log.d(TAG, "startCasting: initial manifestUrl=" + segmentBaseUrl);

			scheduleSidxUpgradeAndReceiverLoad(ctx, session, playerRef, pos, videoAuthor, segmentBaseUrl);
			return true;
		} catch (Exception e) {
			Log.w(TAG, "Failed to start casting", e);
			stopCasting();
			return false;
		}
	}

	/**
	 * Stops any previous proxy and starts a fresh one for a new cast session.
	 * Returns the proxy base URL for manifest building, or {@code null} if the
	 * proxy failed to start. The caller is responsible for calling
	 * {@link #stopCasting()} on failure so partial state is rolled back.
	 */
	@Nullable
	private HttpUrl startProxyForCast() {
		if (proxy != null) {
			try {
				proxy.stop();
			} catch (Exception e) {
				Log.w(TAG, "startProxyForCast: previous proxy stop failed", e);
			}
			proxy = null;
		}
		try {
			proxy = new LocalStreamProxy(activity, PROXY_PORT);
			proxy.setUrlRefresher(urlRefresher);
			proxy.start();
			Log.d(TAG, "startCasting: proxy started on port " + proxy.getListeningPort()
					+ " url=" + proxy.getProxyUrl(""));
			return HttpUrl.parse(proxy.getProxyUrl(""));
		} catch (Exception e) {
			Log.w(TAG, "startProxyForCast: failed to start proxy", e);
			return null;
		}
	}

	/**
	 * Orchestrates the sidx fetch and receiver load protocol on a background
	 * thread. The Chromecast default receiver cannot parse sidx itself, so we
	 * must upgrade the manifest to SegmentList for segment-addressable seek.
	 * Sidx fetches can take 1-2s each on cold CDN opens, so the protocol avoids
	 * blocking the receiver on sidx:
	 * <ol>
	 *   <li>Wait up to {@link #SIDX_FETCH_TIMEOUT_MS} for both sidx boxes.</li>
	 *   <li>If ready: build SegmentList, load on receiver, done.</li>
	 *   <li>If timed out: load SegmentBase immediately (receiver starts
	 *       playing), then keep waiting up to
	 *       {@link #SIDX_FETCH_RELOAD_TIMEOUT_MS}. If sidx arrives in this
	 *       window, rebuild as SegmentList and reload the receiver at its
	 *       current position.</li>
	 *   <li>If reload also times out: stay on SegmentBase (seek will not be
	 *       segment-addressable, but playback continues).</li>
	 * </ol>
	 * If the SegmentList build fails on the first load, falls back to loading
	 * the SegmentBase manifest so the receiver is never left without content.
	 */
	private void scheduleSidxUpgradeAndReceiverLoad(@NonNull CastManifestContext ctx,
	                                                @NonNull CastSession session,
	                                                @NonNull CastPlayer playerRef,
	                                                long pos,
	                                                @Nullable String videoAuthor,
	                                                @NonNull String segmentBaseUrl) {
		final LocalStreamProxy fp = ctx.proxy();
		CompletableFuture.runAsync(() -> {
			CompletableFuture<ChunkIndex> vFuture = ctx.videoUrl() != null
					? CompletableFuture.supplyAsync(() -> fp.fetchChunkIndex(ctx.videoToken()), ioExecutor)
					: CompletableFuture.completedFuture(null);
			CompletableFuture<ChunkIndex> aFuture = ctx.audioUrl() != null
					? CompletableFuture.supplyAsync(() -> fp.fetchChunkIndex(ctx.audioToken()), ioExecutor)
					: CompletableFuture.completedFuture(null);

			boolean sidxReady = false;
			try {
				CompletableFuture.allOf(vFuture, aFuture).get(SIDX_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
				sidxReady = true;
			} catch (TimeoutException te) {
				Log.d(TAG, "startCasting: sidx fetch timed out, loading SegmentBase first");
			} catch (Exception e) {
				Log.w(TAG, "startCasting: sidx fetch failed", e);
			}

			ChunkIndex vci = vFuture.getNow(null);
			ChunkIndex aci = aFuture.getNow(null);
			final boolean firstLoadSegmentList = sidxReady && (vci != null || aci != null);
			final ChunkIndex vciFirst = vci;
			final ChunkIndex aciFirst = aci;

			activity.runOnUiThread(() -> {
				if (proxy != fp) return;
				if (firstLoadSegmentList
						&& upgradeProxyToSegmentList(ctx, vciFirst, aciFirst)) {
					loadOnReceiver(session, playerRef, fp.getProxyUrl("/manifest.mpd"),
							ctx.videoTitle(), videoAuthor, pos);
					Log.d(TAG, "startCasting: loaded SegmentList manifest on receiver"
							+ " videoSegs=" + (vciFirst != null ? vciFirst.length : 0)
							+ " audioSegs=" + (aciFirst != null ? aciFirst.length : 0));
				} else {
					// Fall back to SegmentBase if SegmentList build failed — the
					// receiver must still get a manifest to start playback.
					loadOnReceiver(session, playerRef, segmentBaseUrl, ctx.videoTitle(), videoAuthor, pos);
					Log.d(TAG, "startCasting: loaded SegmentBase manifest on receiver");
				}
			});

			if (sidxReady) return;

			// Sidx was slow: keep the futures running and reload with SegmentList
			// once they finish. This avoids leaving Chromecast on the full-stream
			// SegmentBase path, which causes seek stalls and clamped chunking.
			try {
				CompletableFuture.allOf(vFuture, aFuture).get(SIDX_FETCH_RELOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
				vci = vFuture.getNow(null);
				aci = aFuture.getNow(null);
			} catch (TimeoutException te) {
				Log.d(TAG, "startCasting: sidx reload timed out, staying on SegmentBase");
				return;
			} catch (Exception e) {
				Log.w(TAG, "startCasting: sidx reload failed", e);
				return;
			}

			if (proxy != fp) {
				Log.d(TAG, "startCasting: proxy changed, aborting SegmentList reload");
				return;
			}
			final ChunkIndex vciReload = vci;
			final ChunkIndex aciReload = aci;
			if (!upgradeProxyToSegmentList(ctx, vciReload, aciReload)) {
				Log.d(TAG, "startCasting: SegmentList rebuild failed, staying on SegmentBase");
				return;
			}
			final int videoSegs = vciReload != null ? vciReload.length : 0;
			final int audioSegs = aciReload != null ? aciReload.length : 0;
			activity.runOnUiThread(() -> {
				if (proxy != fp) return;
				try {
					long reloadPos = playerRef.getCurrentPosition();
					loadOnReceiver(session, playerRef, fp.getProxyUrl("/manifest.mpd"),
							ctx.videoTitle(), videoAuthor, reloadPos);
					Log.d(TAG, "startCasting: reloaded SegmentList manifest on receiver"
							+ " videoSegs=" + videoSegs + " audioSegs=" + audioSegs);
				} catch (Exception e) {
					Log.w(TAG, "startCasting: failed to reload SegmentList on receiver", e);
				}
			});
		}, ioExecutor);
	}

	/**
	 * Stops casting, tears down the proxy, and signals the caller to resume local playback.
	 * Captures the last cast position before clearing receiver media. The local PlayerView
	 * was never rebound, so the caller only needs to clear the cast delegate and resume.
	 *
	 * @return the last known cast position, or 0 if unavailable.
	 */
	public long stopCasting() {
		// Mark inactive first so re-entrant calls (e.g. onSessionEnded -> listener ->
		// stopCasting) are safe and don't double-tear-down.
		casting = false;
		castingDeviceName = null;
		long position = 0;
		CastPlayer player = castPlayer;
		if (player != null) {
			try {
				position = player.getCurrentPosition();
			} catch (Exception e) {
				Log.w(TAG, "stopCasting: getCurrentPosition failed", e);
			}
			try {
				player.clearMediaItems();
				player.pause();
			} catch (Exception e) {
				Log.w(TAG, "stopCasting: clearMediaItems/pause failed", e);
			}
		}
		stopProxy();
		return position;
	}

	/**
	 * Ends the current Cast session directly (without opening the device-picker
	 * dialog). This triggers {@code onSessionEnded} → the caller's
	 * {@link OnSessionEndedListener} (which calls {@link #stopCasting()} and
	 * resumes local playback). Used by the "Stop casting" action in the
	 * more-options sheet.
	 */
	public void endSession() {
		try {
			CastContext castContext = CastContext.getSharedInstance(activity);
			castContext.getSessionManager().endCurrentSession(true);
		} catch (Exception e) {
			Log.w(TAG, "endSession: failed", e);
		}
	}

	/**
	 * Stops the local proxy if it is running, releasing the HTTP server. Called when
	 * the Cast dialog is dismissed without an active session (so the share-link
	 * proxy does not outlive the dialog) and from {@link #stopCasting()}/{@link #release()}.
	 */
	public void stopProxy() {
		LocalStreamProxy p = proxy;
		if (p != null) {
			try {
				p.stop();
			} catch (Exception e) {
				Log.w(TAG, "stopProxy: failed", e);
			}
			proxy = null;
		}
	}

	/**
	 * Hot-swaps the manifest of an already-running share-link proxy for a new
	 * video without tearing down the HTTP server. Used by the link-cast session
	 * path so the URL remains stable across video switches. The receiver picks
	 * up the new manifest on its next GET of /manifest.mpd; we kick off an
	 * asynchronous sidx fetch to upgrade to a SegmentList (which the proxy
	 * handles internally).
	 */
	private void hotConfigureProxy(@NonNull PlaybackPlan plan,
	                                @NonNull StreamCandidate videoCandidate,
	                                @Nullable StreamCandidate audioCandidate,
	                                @NonNull PlaybackDetails details) {
		LocalStreamProxy p = proxy;
		if (p == null) return;
		try {
			long durationSeconds = details.video().getDuration() != null
					? details.video().getDuration() : 0L;
			String videoToken = "v";
			String audioToken = "a";
			String videoId = details.video().getId();
			String videoTitle = details.video().getTitle();
			String videoUrl = videoCandidate.getVideoStream() != null
					? videoCandidate.getVideoStream().getContent() : null;
			String audioUrl = audioCandidate != null && audioCandidate.getAudioStream() != null
					? audioCandidate.getAudioStream().getContent() : null;

			HttpUrl proxyBase = HttpUrl.parse(p.getProxyUrl(""));
			if (proxyBase == null) return;

			// Build a SegmentBase manifest fast so the proxy is immediately
			// consistent; upgrade to SegmentList async below (same pattern as
			// startProxy()).
			final CastManifestContext ctx = new CastManifestContext(
					p, videoCandidate, audioCandidate, durationSeconds, proxyBase,
					videoToken, audioToken, videoId, videoTitle, videoUrl, audioUrl);
			if (!configureProxyManifest(ctx)) {
				Log.w(TAG, "hotConfigureProxy: DashManifestBuilder returned null");
				return;
			}
			// Async sidx upgrade. Same fire-and-forget style as startProxy(),
			// but guard every mutation against the proxy being torn down while
			// the sidx fetch was in flight (user closed the link / switched to
			// a real cast). Without this, configure() would run on a stopped
			// proxy and NanoHTTPD may throw or silently re-arm a dead server.
			final LocalStreamProxy fp = p;
			CompletableFuture.runAsync(() -> {
				if (proxy != fp) {
					Log.d(TAG, "hotConfigureProxy: proxy changed during sidx fetch, aborting rebuild");
					return;
				}
				androidx.media3.extractor.ChunkIndex vci = videoUrl != null
						? fp.fetchChunkIndex(ctx.videoToken()) : null;
				androidx.media3.extractor.ChunkIndex aci = audioUrl != null
						? fp.fetchChunkIndex(ctx.audioToken()) : null;
				if (proxy != fp) return;
				if (upgradeProxyToSegmentList(ctx, vci, aci)) {
					Log.d(TAG, "hotConfigureProxy: upgraded to SegmentList");
				}
			}, ioExecutor);
		} catch (Exception e) {
			Log.w(TAG, "hotConfigureProxy: failed", e);
		}
	}

	/**
	 * Releases all cast resources. Call from Activity onDestroy.
	 */
	public void release() {
		if (casting) {
			stopCasting();
		} else {
			// A share-link proxy may still be running even without a cast session.
			stopProxy();
		}
		CastPlayer player = castPlayer;
		if (player != null) {
			player.release();
			castPlayer = null;
		}
		try {
			CastContext castContext = CastContext.getSharedInstance(activity);
			if (sessionListener != null) {
				castContext.getSessionManager().removeSessionManagerListener(sessionListener, CastSession.class);
			}
			if (castStateListener != null) {
				castContext.removeCastStateListener(castStateListener);
				castStateListener = null;
			}
		} catch (Exception e) {
			// CastContext teardown failures are not actionable from here; the
			// framework cleans up its own state on process death. Logged so a
			// repeated teardown failure is observable in logcat.
			Log.w(TAG, "release: CastContext teardown failed", e);
		}
		// Shut down the sidx fetch pool so daemon threads do not outlive the
		// controller. Pending fetches are cancelled; in-flight ones finish.
		ioExecutor.shutdown();
	}

	private static long initStart(@Nullable StreamCandidate candidate) {
		org.schabi.newpipe.extractor.stream.Stream stream = streamOf(candidate);
		if (stream == null || stream.getItagItem() == null) return 0;
		return stream.getItagItem().getInitStart();
	}

	private static long initEnd(@Nullable StreamCandidate candidate) {
		org.schabi.newpipe.extractor.stream.Stream stream = streamOf(candidate);
		if (stream == null || stream.getItagItem() == null) return 0;
		return stream.getItagItem().getInitEnd();
	}

	private static long contentLength(@Nullable StreamCandidate candidate) {
		org.schabi.newpipe.extractor.stream.Stream stream = streamOf(candidate);
		if (stream == null || stream.getItagItem() == null) return 0;
		return stream.getItagItem().getContentLength();
	}

	private static long indexStart(@Nullable StreamCandidate candidate) {
		org.schabi.newpipe.extractor.stream.Stream stream = streamOf(candidate);
		if (stream == null || stream.getItagItem() == null) return -1;
		return stream.getItagItem().getIndexStart();
	}

	private static long indexEnd(@Nullable StreamCandidate candidate) {
		org.schabi.newpipe.extractor.stream.Stream stream = streamOf(candidate);
		if (stream == null || stream.getItagItem() == null) return -1;
		return stream.getItagItem().getIndexEnd();
	}

	/**
	 * Bundles the manifest/proxy parameters shared by the cast, link-cast, and
	 * hot-swap paths. Passing this record instead of 11-13 individual
	 * parameters eliminates the argument-order risk that previously caused
	 * {@code videoId} and {@code videoTitle} to be swapped.
	 */
	private record CastManifestContext(
			@NonNull LocalStreamProxy proxy,
			@Nullable StreamCandidate vc,
			@Nullable StreamCandidate ac,
			long durationSeconds,
			@NonNull HttpUrl base,
			@NonNull String videoToken,
			@NonNull String audioToken,
			@NonNull String videoId,
			@NonNull String videoTitle,
			@Nullable String videoUrl,
			@Nullable String audioUrl) {
	}

	/**
	 * Builds an initial SegmentBase manifest and applies it to the proxy. Shared
	 * by the cast, link-cast, and hot-swap paths so the manifest XML layout and
	 * {@link LocalStreamProxy#configure} argument order live in exactly one
	 * place. Returns {@code false} (without touching the proxy) when the
	 * manifest builder rejects the candidates.
	 */
	private static boolean configureProxyManifest(@NonNull CastManifestContext ctx) {
		String manifest = DashManifestBuilder.build(
				ctx.vc(), ctx.ac(), null, null, ctx.durationSeconds(),
				ctx.base(), ctx.videoToken(), ctx.audioToken());
		if (manifest == null) return false;
		ctx.proxy().configure(manifest, ctx.videoTitle(), ctx.videoId(),
				ctx.videoToken(), ctx.videoUrl(), ctx.audioToken(), ctx.audioUrl(),
				initStart(ctx.vc()), initEnd(ctx.vc()), indexStart(ctx.vc()), indexEnd(ctx.vc()), contentLength(ctx.vc()),
				initStart(ctx.ac()), initEnd(ctx.ac()), indexStart(ctx.ac()), indexEnd(ctx.ac()), contentLength(ctx.ac()));
		return true;
	}

	/**
	 * Builds a SegmentList manifest from already-fetched sidx indexes and
	 * hot-swaps it into the running proxy. Returns {@code false} when both
	 * indexes are null or the builder rejects the inputs, leaving the proxy on
	 * its previous SegmentBase manifest. Centralizing this call also prevents
	 * the argument-order bug that previously swapped {@code videoId} and
	 * {@code videoTitle} in the hot-swap path.
	 */
	private static boolean upgradeProxyToSegmentList(@NonNull CastManifestContext ctx,
	                                                 @Nullable ChunkIndex vci,
	                                                 @Nullable ChunkIndex aci) {
		if (vci == null && aci == null) return false;
		String sl = DashManifestBuilder.build(ctx.vc(), ctx.ac(), vci, aci,
				ctx.durationSeconds(), ctx.base(), ctx.videoToken(), ctx.audioToken());
		if (sl == null) return false;
		ctx.proxy().configure(sl, ctx.videoTitle(), ctx.videoId(),
				ctx.videoToken(), ctx.videoUrl(), ctx.audioToken(), ctx.audioUrl(),
				initStart(ctx.vc()), initEnd(ctx.vc()), indexStart(ctx.vc()), indexEnd(ctx.vc()), contentLength(ctx.vc()),
				initStart(ctx.ac()), initEnd(ctx.ac()), indexStart(ctx.ac()), indexEnd(ctx.ac()), contentLength(ctx.ac()));
		return true;
	}

	/**
	 * Picks the best video candidate for casting.
	 * <p>
	 * VP9 itags (e.g. 248, 271, 313) use a WebM container with no sidx box, so
	 * the cast receiver downloads the entire stream. AV1 itags (e.g. 399-401)
	 * are fmp4 but many Chromecast devices cannot decode AV1, causing an
	 * infinite init-segment retry loop. H.264/AVC (avc1) is the safest choice:
	 * fmp4 container (sidx works) and universally supported by cast devices.
	 */
	@Nullable
	private static StreamCandidate pickCastVideoCandidate(@NonNull PlaybackPlan plan) {
		StreamCandidate original = plan.getVideoCandidate();
		if (original == null) {
			original = plan.getMuxedCandidate();
		}
		if (original == null) return null;

		Delivery delivery = plan.getDelivery();
		if (delivery == null) return original;

		String origCodec = codecOf(original);
		boolean origIsAvc = origCodec != null && (origCodec.startsWith("avc1") || origCodec.startsWith("avc"));
		if (origIsAvc) return original; // already the best option

		// Original is VP9/WebM or AV1 — look for an H.264 alternative.
		StreamCandidate avc = null;
		for (StreamCandidate c : delivery.getVideo()) {
			if (c == null || c.getVideoStream() == null) continue;
			String codec = codecOf(c);
			if (codec == null) continue;
			if (codec.startsWith("avc1") || codec.startsWith("avc")) {
				avc = c;
				break;
			}
		}
		if (avc != null) {
			Log.d(TAG, "pickCastVideoCandidate: switched from "
					+ describe(original) + " to H.264 " + describe(avc));
			return avc;
		}
		Log.w(TAG, "pickCastVideoCandidate: no H.264 alternative for "
				+ describe(original) + " — cast may fail to play");
		return original;
	}

	/**
	 * Picks the best audio candidate for casting.
	 * <p>
	 * Opus itags (249-251) are in a WebM container; if the manifest claims
	 * audio/mp4 while serving audio/webm, browsers and Chromecast throw
	 * MEDIA_ERR_DECODE and reset playback. AAC (mp4a) in an M4A/fmp4 container
	 * is universally supported, so prefer it for casting.
	 */
	@Nullable
	private static StreamCandidate pickCastAudioCandidate(@NonNull PlaybackPlan plan) {
		StreamCandidate original = plan.getAudioCandidate();
		if (original == null) return null;

		Delivery delivery = plan.getDelivery();
		if (delivery == null) return original;

		String origCodec = codecOf(original);
		boolean origIsAac = origCodec != null && (origCodec.startsWith("mp4a") || origCodec.startsWith("aac"));
		if (origIsAac) return original; // already the best option

		StreamCandidate aac = null;
		for (StreamCandidate c : delivery.getAudio()) {
			if (c == null || c.getAudioStream() == null) continue;
			String codec = codecOf(c);
			if (codec == null) continue;
			if (codec.startsWith("mp4a") || codec.startsWith("aac")) {
				aac = c;
				break;
			}
		}
		if (aac != null) {
			Log.d(TAG, "pickCastAudioCandidate: switched from "
					+ describe(original) + " to AAC " + describe(aac));
			return aac;
		}
		Log.w(TAG, "pickCastAudioCandidate: no AAC alternative for "
				+ describe(original) + " — cast may use webm/opus audio");
		return original;
	}

	@Nullable
	private static String codecOf(@Nullable StreamCandidate candidate) {
		org.schabi.newpipe.extractor.stream.Stream stream = streamOf(candidate);
		if (stream == null || stream.getItagItem() == null) return null;
		return stream.getItagItem().getCodec();
	}

	@Nullable
	private static org.schabi.newpipe.extractor.stream.Stream streamOf(@Nullable StreamCandidate candidate) {
		if (candidate == null) return null;
		if (candidate.getVideoStream() != null) return candidate.getVideoStream();
		return candidate.getAudioStream();
	}

	/**
	 * Returns a concise, log-safe description of a stream candidate: kind, itag,
	 * content length, and init/index ranges. YouTube URLs are reduced to host+path
	 * so po-tokens and signatures are not leaked into logcat.
	 */
	@NonNull
	private static String describe(@Nullable StreamCandidate candidate) {
		if (candidate == null) return "null";
		org.schabi.newpipe.extractor.stream.Stream stream = streamOf(candidate);
		if (stream == null || stream.getItagItem() == null) {
			return "kind=" + candidate.getKind();
		}
		org.schabi.newpipe.extractor.services.youtube.ItagItem itag = stream.getItagItem();
		return "itag=" + itag.id
				+ " contentLength=" + itag.getContentLength()
				+ " init=" + itag.getInitStart() + "-" + itag.getInitEnd()
				+ " index=" + itag.getIndexStart() + "-" + itag.getIndexEnd()
				+ " urlHost=" + safeUrl(stream.getContent());
	}

	/**
	 * Returns the host+path of a URL for logging, or {@code "null"} if the URL is
	 * blank. Avoids logging query parameters that may carry po-tokens/signatures.
	 */
	@NonNull
	private static String safeUrl(@Nullable String url) {
		if (url == null || url.isBlank()) return "null";
		try {
			HttpUrl parsed = HttpUrl.parse(url);
			return parsed != null ? parsed.host() + parsed.encodedPath() : "unparseable";
		} catch (Exception e) {
			Log.w(TAG, "safeUrl: failed to parse " + url, e);
			return "unparseable";
		}
	}
}
