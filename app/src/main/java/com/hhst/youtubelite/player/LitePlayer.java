package com.hhst.youtubelite.player;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.DefaultTimeBar;

import com.hhst.youtubelite.PlaybackService;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.cast.CastPlaybackController;
import com.hhst.youtubelite.extractor.Delivery;
import com.hhst.youtubelite.extractor.ExtractionSession;
import com.hhst.youtubelite.extractor.OEmbedTitleFetcher;
import com.hhst.youtubelite.extractor.PlaybackMode;
import com.hhst.youtubelite.extractor.PlaybackDetails;
import com.hhst.youtubelite.extractor.PlaybackPlan;
import com.hhst.youtubelite.extractor.PlaybackPlanner;
import com.hhst.youtubelite.extractor.StreamCandidate;
import com.hhst.youtubelite.extractor.SegmentPoll;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.extractor.exception.ExtractionException;
import com.hhst.youtubelite.extractor.exception.LoginRequiredExtractionException;
import com.hhst.youtubelite.player.common.PlayerLoopMode;
import com.hhst.youtubelite.player.common.PlayerPreferences;
import com.hhst.youtubelite.player.controller.Controller;
import com.hhst.youtubelite.player.engine.Engine;
import com.hhst.youtubelite.player.queue.QueueInvalidationListener;
import com.hhst.youtubelite.player.queue.QueueNav;
import com.hhst.youtubelite.player.queue.QueueRepository;
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager;
import com.hhst.youtubelite.player.sponsor.SponsorOverlayView;
import com.hhst.youtubelite.ui.ErrorDialog;
import com.hhst.youtubelite.util.DeviceUtils;
import com.tencent.mmkv.MMKV;

import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException;
import org.schabi.newpipe.extractor.stream.StreamSegment;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import dagger.hilt.android.scopes.ActivityScoped;
import lombok.Getter;

/**
 * Playback facade that tracks the current media session and UI state.
 */
@UnstableApi
@ActivityScoped
public class LitePlayer {
	private static final String TAG = "LitePlayer";
	private static final String CAST_TAG = "YTLCast";
	private static final String KEY_LAST_AUDIO_LANG = "last_audio_lang";

	@NonNull
	private final Activity activity;
	@NonNull
	private final YoutubeExtractor extractor;
	@NonNull
	private final OEmbedTitleFetcher oembedFetcher;
	@NonNull
	private final LitePlayerView playerView;
	@NonNull
	private final Controller controller;
	@NonNull
	private final Engine engine;
	@NonNull
	private final CastPlaybackController castController;
	@NonNull
	private final SponsorBlockManager sponsor;
	@NonNull
	private final QueueRepository queueRepo;
	@NonNull
	private final PlayerPreferences prefs;
	@NonNull
	private final PlayerStateStore stateStore;
	@NonNull
	private final Executor executor;
	private final MMKV kv = MMKV.defaultMMKV();
	@Nullable
	private PlaybackService playbackService;
	@NonNull
	private final QueueInvalidationListener queueListener = this::refreshQueueNav;
	@Nullable
	private CompletableFuture<Void> task;
	@Nullable
	private CompletableFuture<Void> sponsorTask;
	@Nullable
	private String queuedId;
	/**
	 * VideoId currently being served by the long-lived share link. Null when
	 * no link session is open. Used to reconfigure (rather than rebuild)
	 * the proxy when the user switches videos while the link is alive.
	 */
	@Nullable
	private String lastLinkVideoId;
	@Nullable
	private volatile String activeId;
	@Nullable
	private volatile String currentUrl;
	@Nullable
	private volatile PlaybackDetails lastDetails;
	@Nullable
	private ExtractionSession extractSession;
	@Nullable
	private Runnable onRestore;
	@Nullable
	private Runnable onClose;
	@Getter
	private boolean inMiniPlayer;
	private boolean wasInPip;

	@Nullable
	private androidx.mediarouter.media.MediaRouter mediaRouter;
	@Nullable
	private androidx.mediarouter.media.MediaRouteSelector routeSelector;

	@Inject
	public LitePlayer(@NonNull Activity activity,
	                  @NonNull YoutubeExtractor extractor,
	                  @NonNull OEmbedTitleFetcher oembedFetcher,
	                  @NonNull LitePlayerView playerView,
	                  @NonNull Controller controller,
	                  @NonNull Engine engine,
	                  @NonNull CastPlaybackController castController,
	                  @NonNull SponsorBlockManager sponsor,
	                  @NonNull QueueRepository queueRepo,
	                  @NonNull PlayerPreferences prefs,
	                  @NonNull PlayerStateStore stateStore,
	                  @NonNull Executor executor) {
		this.activity = activity;
		this.extractor = extractor;
		this.oembedFetcher = oembedFetcher;
		this.playerView = playerView;
		this.controller = controller;
		this.engine = engine;
		this.castController = castController;
		this.sponsor = sponsor;
		this.queueRepo = queueRepo;
		this.prefs = prefs;
		this.stateStore = stateStore;
		this.executor = executor;
		playerView.setup();
		queueRepo.addListener(queueListener);
		setupEngineListeners();
		castController.setOnSessionStartedListener(this::onCastSessionStarted);
		castController.setOnSessionEndedListener(this::resumeFromCast);
		controller.setOnCastRequested(this::openCastDialog);
		controller.setOnCastStopRequested(this::stopCastingDirect);
		// Provide URL refresh for the cast/share-link proxy: when YouTube
		// rejects a segment fetch with 403 (po-token expired), the proxy asks
		// us for a fresh URL. We re-extract and return the matching stream URL.
		castController.setUrlRefresher(this::refreshProxyStreamUrl);
	}

	/**
	 * Re-extracts the current video and returns the fresh stream URL for the
	 * given proxy token ("v" video / "a" audio), for 403 recovery in the cast
	 * proxy. Blocks the caller (runs on a NanoHTTPD thread). Returns {@code null}
	 * if no video is loaded or re-extraction fails.
	 */
	@Nullable
	private String refreshProxyStreamUrl(@NonNull String token) {
		long t0 = System.currentTimeMillis();
		String url = currentUrl;
		String videoId = YoutubeExtractor.getVideoId(url);
		if (url == null || videoId == null) {
			Log.w(CAST_TAG, "refreshProxyStreamUrl: no current url/videoId for token=" + token);
			return null;
		}
		Log.d(CAST_TAG, "refreshProxyStreamUrl: token=" + token + " videoId=" + videoId);
		try {
			// Invalidate the cached PlaybackDetails so the next getInfo() does
			// a full re-extraction with fresh signatures and a new po-token.
			extractor.invalidate(videoId);
			PlaybackDetails details = extractor.getInfo(url, null).join();
			long tExtract = System.currentTimeMillis();
			Log.d(CAST_TAG, "refreshProxyStreamUrl: re-extract took " + (tExtract - t0) + "ms");
			PlaybackPlan plan = PlaybackPlanner.plan(details.deliveries(),
					prefs.getPreferredQuality(),
					kv.decodeString(KEY_LAST_AUDIO_LANG, "und"));
			// Update lastDetails so a subsequent startProxy/startCasting uses
			// the fresh URLs too.
			this.lastDetails = new PlaybackDetails(
					details.video(), details.catalog(), details.deliveries(), plan,
					details.segments(), details.subtitles());
			// Re-apply the cast video-candidate picking so the refreshed URL
			// matches what the proxy is actually serving.
			if ("v".equals(token)) {
				StreamCandidate video = pickProxyVideoCandidate(plan);
				String vUrl = video != null && video.getVideoStream() != null
						? video.getVideoStream().getContent() : null;
				Log.d(CAST_TAG, "refreshProxyStreamUrl: refreshed video url present=" + (vUrl != null));
				return vUrl;
			} else if ("a".equals(token)) {
				StreamCandidate audio = plan.getAudioCandidate();
				String aUrl = audio != null && audio.getAudioStream() != null
						? audio.getAudioStream().getContent() : null;
				Log.d(CAST_TAG, "refreshProxyStreamUrl: refreshed audio url present=" + (aUrl != null));
				return aUrl;
			}
		} catch (Exception e) {
			Log.w(CAST_TAG, "refreshProxyStreamUrl: failed for token=" + token + " after "
					+ (System.currentTimeMillis() - t0) + "ms", e);
		}
		return null;
	}

	/**
	 * Picks the cast video candidate (H.264 over VP9/AV1) for the refreshed
	 * plan, mirroring {@code CastPlaybackController.pickCastVideoCandidate}.
	 */
	@Nullable
	private static StreamCandidate pickProxyVideoCandidate(@NonNull PlaybackPlan plan) {
		StreamCandidate original = plan.getVideoCandidate();
		if (original == null) original = plan.getMuxedCandidate();
		if (original == null) return null;
		Delivery delivery = plan.getDelivery();
		if (delivery == null) return original;
		String origCodec = codecOf(original);
		boolean origIsAvc = origCodec != null && (origCodec.startsWith("avc1") || origCodec.startsWith("avc"));
		if (origIsAvc) return original;
		for (StreamCandidate c : delivery.getVideo()) {
			if (c == null || c.getVideoStream() == null) continue;
			String codec = codecOf(c);
			if (codec != null && (codec.startsWith("avc1") || codec.startsWith("avc"))) return c;
		}
		return original;
	}

	@Nullable
	private static String codecOf(@Nullable StreamCandidate candidate) {
		org.schabi.newpipe.extractor.stream.Stream stream = candidate != null ? candidate.getVideoStream() : null;
		if (stream == null) stream = candidate != null ? candidate.getAudioStream() : null;
		if (stream == null || stream.getItagItem() == null) return null;
		return stream.getItagItem().getCodec();
	}

	private static boolean containsException(@Nullable Throwable throwable,
	                                         @NonNull Class<? extends Throwable> throwableClass) {
		if (throwable == null) return false;

		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		List<Throwable> pending = new ArrayList<>();
		pending.add(throwable);

		for (int i = 0; i < pending.size(); i++) {
			Throwable th = pending.get(i);
			if (th == null || !visited.add(th)) continue;
			if (throwableClass.isInstance(th)) return true;

			Throwable cause = th.getCause();
			if (cause != null) pending.add(cause);
			Collections.addAll(pending, th.getSuppressed());
		}
		return false;
	}

	private void setupEngineListeners() {
		engine.addListener(new Player.Listener() {
			@Override
			public void onIsPlayingChanged(boolean isPlaying) {
				updateServiceProgress(isPlaying);
			}

			@Override
			public void onTracksChanged(@NonNull Tracks tracks) {
				saveSelectedTrackLanguage(tracks);
			}

			@Override
			public void onPlaybackStateChanged(int state) {
				if (state == Player.STATE_READY) {
					updateServiceProgress(engine.isPlaying());
				}
			}

			@Override
			public void onPlayerError(@NonNull PlaybackException error) {
				if (engine.recoverFromPlaybackError(error)) {
					return;
				}
				if (engine.shouldReExtract(error) && reExtract()) {
					return;
				}
				ErrorDialog.show(activity, error.getMessage(), error);
			}
		});
	}

	private void saveSelectedTrackLanguage(Tracks tracks) {
		try {
			for (Tracks.Group group : tracks.getGroups()) {
				if (group.getType() == androidx.media3.common.C.TRACK_TYPE_AUDIO && group.isSelected()) {
					for (int i = 0; i < group.length; i++) {
						if (group.isTrackSelected(i)) {
							String lang = group.getTrackFormat(i).language;
							kv.encode(KEY_LAST_AUDIO_LANG, lang != null ? lang : "und");
							return;
						}
					}
				}
			}
		} catch (Exception e) {
			Log.w(TAG, "saveSelectedTrackLanguage: failed to persist selected audio language", e);
		}
	}

	private void updateServiceProgress(boolean isPlaying) {
		if (playbackService != null) {
			playbackService.updateProgress(engine.position(), engine.getPlaybackRate(), isPlaying);
		}
	}

	public void attachPlaybackService(@Nullable PlaybackService service) {
		this.playbackService = service;
		if (service != null) {
			service.initialize(engine);
			refreshQueueNav();
		}
	}

	public void refreshQueueNav() {
		QueueNav availability = engine.getQueueNavigationAvailability();
		activity.runOnUiThread(() -> controller.refreshQueueNavigationAvailability(availability));
		if (playbackService != null) {
			playbackService.updateQueueNavigationAvailability(availability);
		}
	}

	public void play(String url) {
		play(url, false);
	}

	private void play(String url, boolean isRetry) {
		String videoId = YoutubeExtractor.getVideoId(url);
		if (videoId == null) return;
		if (!isRetry && Objects.equals(this.queuedId, videoId)) {
			return;
		}
		this.queuedId = videoId;
		this.currentUrl = url;

		preparePlaybackSurface();

		cancelExtraction();
		if (task != null) task.cancel(true);
		if (sponsorTask != null) sponsorTask.cancel(true);
		ExtractionSession session = new ExtractionSession();
		extractSession = session;

		launchSponsor(videoId, session);
		launchExtraction(url, videoId, session, isRetry);
		attachSponsorMarkerRefresh(videoId);
		pollChapterSegments(videoId);
	}

	private void preparePlaybackSurface() {
		activity.runOnUiThread(() -> {
			engine.clear();
			playerView.setTitle(null);
			SponsorOverlayView layer = playerView.findViewById(R.id.sponsor_overlay);
			layer.setData(null, 0, TimeUnit.MILLISECONDS);
			DefaultTimeBar bar = playerView.findViewById(R.id.exo_progress);
			bar.setAdGroupTimesMs(null, null, 0);
			playerView.show();
			controller.syncRotation(
							DeviceUtils.isRotateOn(activity),
							activity.getResources().getConfiguration().orientation);
		});
	}

	// Decouple from extraction critical path to avoid blocking getInfo.
	private void launchSponsor(String videoId, ExtractionSession session) {
		sponsorTask = CompletableFuture.runAsync(() -> {
			if (session.isCancelled()) return;
			sponsor.load(videoId);
		}, executor);
	}

	private void launchExtraction(String url, String videoId, ExtractionSession session, boolean isRetry) {
		task = extractor.getInfo(url, session)
				.thenApply(details -> buildPlaybackDetails(details, videoId))
				.thenAccept(er -> activity.runOnUiThread(() -> onExtractionReady(er, videoId, session)))
				.exceptionally(e -> onExtractionFailed(e, url, videoId, session, isRetry));
	}

	private PlaybackDetails buildPlaybackDetails(PlaybackDetails details, String videoId) {
		String lang = kv.decodeString(KEY_LAST_AUDIO_LANG, "und");
		String preferredQuality = prefs.getPreferredQuality();
		PlaybackPlan plan = PlaybackPlanner.plan(details.deliveries(), preferredQuality, lang);
		if (prefs.shouldUseAdaptiveMuxedFallback(videoId) && plan.getMode() == PlaybackMode.ADAPTIVE) {
			PlaybackPlan fallback = PlaybackPlanner.muxedFallbackPlan(details.deliveries(), preferredQuality);
			if (fallback != null) {
				plan = fallback;
			}
		}
		return new PlaybackDetails(
						details.video(),
						details.catalog(),
						details.deliveries(),
						plan,
						details.segments(),
						details.subtitles());
	}

	private void onExtractionReady(PlaybackDetails er, String videoId, ExtractionSession session) {
		if (this.extractSession == session) this.extractSession = null;
		if (!Objects.equals(this.queuedId, videoId)) return;
		playerView.setTitle(er.video().getTitle());
		playerView.updateSkipMarkers(er.video().getDuration(), TimeUnit.SECONDS);
		this.lastDetails = er;

		try {
			if (castController.hasActiveSession()) {
				if (handleCastPlayback(er, videoId)) return;
				// Cast failed: already handled in handleCastPlayback (stops the half-started
				// cast state and surfaces a hint). Do NOT fall through to local playback —
				// the cast session is still connected and rendering locally would play
				// audio/video out of sync with the (now-empty) receiver.
				return;
			}
			// No Cast session but the share link may still be alive (the
			// user opened it on a browser earlier). Re-point the proxy
			// at the new manifest so the remote receiver picks up the
			// switched video on its next manifest poll. If the proxy
			// is not running, this is a no-op — the next dialog open
			// will start it fresh.
			if (castController.isLinkProxyRunning()) {
				castController.startProxy(er);
			}
			engine.play(er);
		} catch (IllegalArgumentException e) {
			ErrorDialog.show(activity, e.getMessage(), e);
			return;
		}
		activateVideo(er, videoId);
	}

	private boolean handleCastPlayback(PlaybackDetails er, String videoId) {
		long pos = engine.position();
		if (castController.startCasting(er, engine, pos)) {
			engine.setCastDelegate(castController.getCastPlayer());
			controller.setCasting(true, castController.getCastingDeviceName());
			if (playbackService != null) {
				PlaybackService.start(activity);
				playbackService.showNotification(er.video().getTitle(), er.video().getAuthor(), er.video().getThumbnailUrl(), er.video().getDuration() * 1000);
			}
			this.activeId = videoId;
			stateStore.setVideoId(videoId);
			refreshQueueNav();
			return true;
		}
		Log.w(CAST_TAG, "play: startCasting failed while switching video");
		castController.stopCasting();
		controller.showHint(activity.getString(R.string.cast_failed),
				com.hhst.youtubelite.player.common.PlayerUiConstants.HINT_HIDE_DELAY_MS);
		return false;
	}

	private void activateVideo(PlaybackDetails er, String videoId) {
		this.activeId = videoId;
		stateStore.setVideoId(videoId);

		if (playbackService != null) {
			PlaybackService.start(activity);
			playbackService.showNotification(er.video().getTitle(), er.video().getAuthor(), er.video().getThumbnailUrl(), er.video().getDuration() * 1000);
		}
		refreshQueueNav();

		if (prefs.getExtensionManager().isEnabled(com.hhst.youtubelite.extension.PreferenceKeys.USE_ORIGINAL_TITLE)) {
			fetchOriginalTitle(videoId, er.video().getAuthor(), er.video().getThumbnailUrl(), er.video().getDuration());
		}
	}

	private Void onExtractionFailed(Throwable e, String url, String videoId, ExtractionSession session, boolean isRetry) {
		if (this.extractSession == session) this.extractSession = null;
		if (!isRetry && Objects.equals(this.queuedId, videoId)) {
			// Clear caches and the last-success-client hint, then retry once with
			// the default client order.
			prefs.setLastSuccessClient(null);
			extractor.invalidate(videoId);
			play(url, true);
			return null;
		}
		Throwable cause = unwrapExtractionCause(e);
		if (cause instanceof Exception && !(cause instanceof ExtractionException)) {
			cause = classifyException((Exception) cause);
		}
		if (cause instanceof ExtractionException) {
			Throwable error = cause;
			activity.runOnUiThread(() -> {
				if (!Objects.equals(this.queuedId, videoId)) return;
				ErrorDialog.show(activity, error.getMessage(), error);
			});
		}
		return null;
	}

	/**
	 * Walks the exception chain to the first non-{@link CompletionException}
	 * cause. {@link CompletableFuture#supplyAsync} wraps execution exceptions
	 * in {@code CompletionException}, and nested stages can add additional
	 * layers. A while-loop (rather than a fixed two-layer unwrap) stays correct
	 * if the future chain depth changes.
	 */
	private static Throwable unwrapExtractionCause(Throwable e) {
		Throwable cur = e;
		while (cur instanceof CompletionException && cur.getCause() != null) {
			cur = cur.getCause();
		}
		return cur;
	}

	// Once SponsorBlock segments arrive, refresh the timeline markers.
	private void attachSponsorMarkerRefresh(String videoId) {
		sponsorTask.whenComplete((ignored, ex) -> activity.runOnUiThread(() -> {
			if (Objects.equals(this.queuedId, videoId) && lastDetails != null) {
				playerView.updateSkipMarkers(lastDetails.video().getDuration(), TimeUnit.SECONDS);
			}
		}));
	}

	// The /next response (chapter segments) arrives asynchronously after extraction.
	// Poll the cache briefly and update the Engine's segments when they become available,
	// so the chapter picker shows up without requiring a re-play. Delegates the polling
	// loop to SegmentPoll so the 500ms wait between attempts is a non-blocking coroutine
	// delay instead of a blocking Thread.sleep on the executor pool.
	private void pollChapterSegments(String videoId) {
		SegmentPoll.pollUntilNonEmpty(
				() -> {
					if (!Objects.equals(this.queuedId, videoId)) {
						return Collections.<StreamSegment>emptyList();
					}
					PlaybackDetails cached = extractor.getCachedPlaybackDetails(videoId);
					List<StreamSegment> segments = cached != null ? cached.segments() : null;
					return segments != null ? segments : Collections.emptyList();
				},
				500L,
				10_000L,
				segs -> activity.runOnUiThread(() -> {
					if (Objects.equals(this.queuedId, videoId)) {
						engine.updateSegments(segs);
					}
				}));
	}

	@NonNull
	private ExtractionException classifyException(@NonNull Exception exception) {
		return classifyExtractionException(exception);
	}

	/**
	 * Re-extracts a fresh playback source (new signatures / po-tokens) and replays the current
	 * video from its last position. Triggered when local recovery against the stale
	 * {@code DeliveryCatalog} snapshot is exhausted. Returns {@code false} if there is no URL to
	 * re-extract or a re-extraction was already attempted for this video.
	 */
	private boolean reExtract() {
		String url = currentUrl;
		String videoId = YoutubeExtractor.getVideoId(url);
		if (url == null || videoId == null) {
			return false;
		}
		engine.markReExtractionStarted();
		ExtractionSession session = new ExtractionSession();
		extractSession = session;
		CompletableFuture.runAsync(() -> extractor.invalidate(videoId), executor)
						.thenCompose(ignored -> extractor.getInfo(url, session))
						.thenApply(details -> {
							String lang = kv.decodeString(KEY_LAST_AUDIO_LANG, "und");
							String preferredQuality = prefs.getPreferredQuality();
							PlaybackPlan plan = PlaybackPlanner.plan(details.deliveries(), preferredQuality, lang);
							return new PlaybackDetails(
											details.video(),
											details.catalog(),
											details.deliveries(),
											plan,
											details.segments(),
											details.subtitles());
						}).thenAccept(er -> {
							activity.runOnUiThread(() -> {
								if (this.extractSession == session) this.extractSession = null;
								if (!Objects.equals(this.queuedId, videoId)) return;
								try {
									engine.play(er);
								} catch (IllegalArgumentException e) {
									ErrorDialog.show(activity, e.getMessage(), e);
								}
							});
						}).exceptionally(e -> {
							prefs.setLastSuccessClient(null);
							if (this.extractSession == session) this.extractSession = null;
							Throwable cause = e instanceof CompletionException ? e.getCause() : e;
							String message = cause != null && cause.getMessage() != null
											? cause.getMessage() : "Playback recovery failed";
							activity.runOnUiThread(() -> {
								if (!Objects.equals(this.queuedId, videoId)) return;
								ErrorDialog.show(activity, message, cause instanceof Exception ? (Exception) cause : null);
							});
							return null;
						});
		return true;
	}

	/**
	 * Asynchronously fetches the original (untranslated) title via oEmbed and refreshes the
	 * player UI + notification if the video is still active. Failures are silent — the
	 * NewPipe-localized title already set during playback remains in place.
	 */
	private void fetchOriginalTitle(@NonNull String videoId,
	                                @NonNull String author,
	                                @Nullable String thumbnailUrl,
	                                long durationSeconds) {
		CompletableFuture.runAsync(() -> {
			String original = oembedFetcher.fetchOriginalTitle(videoId);
			if (original == null) return;
			activity.runOnUiThread(() -> {
				if (!Objects.equals(this.queuedId, videoId)) return;
				playerView.setTitle(original);
				if (playbackService != null) {
					playbackService.showNotification(original, author, thumbnailUrl, durationSeconds * 1000);
				}
			});
		}, executor);
	}

	/**
	 * Initializes the Cast framework. Call from Activity onCreate. Returns false if Cast is
	 * unavailable (no Google Play Services).
	 */
	public boolean initializeCast() {
		return castController.initialize();
	}

	/**
	 * Ends the Cast session directly (without opening the device-picker dialog).
	 * The session-ended listener ({@link #resumeFromCast}) will then stop casting
	 * and resume local playback.
	 */
	public void stopCastingDirect() {
		castController.endSession();
	}

	/**
	 * Pushes the currently playing video onto the receiver when a Cast session
	 * starts mid-playback. Invoked on the main thread by the Cast controller.
	 */
	private void onCastSessionStarted() {
		PlaybackDetails details = lastDetails;
		if (details == null || activeId == null) {
			// A session connected with no video loaded. Let the controller reflect
			// "connected" so the user knows to start a video; it will be pushed on
			// the next play().
			controller.showHint(activity.getString(R.string.cast_connected_start_video),
					com.hhst.youtubelite.player.common.PlayerUiConstants.HINT_HIDE_DELAY_MS);
			return;
		}
		long pos = engine.position();
		if (castController.startCasting(details, engine, pos)) {
			engine.setCastDelegate(castController.getCastPlayer());
			controller.setCasting(true, castController.getCastingDeviceName());
			if (playbackService != null) {
				PlaybackService.start(activity);
				playbackService.showNotification(details.video().getTitle(), details.video().getAuthor(), details.video().getThumbnailUrl(), details.video().getDuration() * 1000);
			}
		} else {
			// Cast failed: stop the half-started cast state and surface a
			// hint. Do NOT fall through to local playback — the cast
			// session is still connected and rendering locally would play
			// audio/video out of sync with the (now-empty) receiver.
			Log.w(CAST_TAG, "onCastSessionStarted: startCasting failed, cleaning up");
			castController.stopCasting();
			controller.showHint(activity.getString(R.string.cast_failed),
					com.hhst.youtubelite.player.common.PlayerUiConstants.HINT_HIDE_DELAY_MS);
		}
	}

	/**
	 * Releases Cast resources. Call from Activity onDestroy.
	 */
	public void releaseCast() {
		// Per-dialog route callbacks are removed on dialog dismiss; nothing to
		// tear down here beyond the Cast framework itself.
		castController.release();
	}

	/**
	 * Returns whether casting is currently active.
	 */
	public boolean isCasting() {
		return castController.isCasting();
	}

	/**
	 * Starts the local proxy and returns the player page URL that can be opened in
	 * any browser on the local network. The proxy persists across video switches —
	 * only the first call actually spins up the HTTP server; subsequent calls
	 * (when switching videos) just hot-swap the manifest. Returns null if no
	 * video is loaded or the proxy failed to start.
	 */
	@Nullable
	public String startProxyForSharing() {
		PlaybackDetails details = lastDetails;
		if (details == null) return null;
		String videoId = activeId;
		// If the share link is already serving this exact video, nothing to do.
		// Use isLinkProxyRunning() (not isLinkCasting(), which now requires a
		// browser to be connected) so a video switch before the browser opens
		// still hot-swaps the manifest instead of rebuilding the proxy.
		if (castController.isLinkProxyRunning()
				&& java.util.Objects.equals(videoId, lastLinkVideoId)) {
			String existing = castController.getPlayerUrl();
			if (existing != null) return existing;
		}
		String url = castController.startProxy(details);
		if (url != null) {
			lastLinkVideoId = videoId;
		}
		return url;
	}

	/**
	 * Opens the Cast dialog. Renders a single scrolling list split into three
	 * logical sections (each wrapped in a section header entry so the dialog
	 * stays a single scrollable area):
	 * <ol>
	 *   <li>"Connected devices" — optional; only shown while a Chromecast session
	 *       is active OR the long-lived share link is open over the proxy. The
	 *       active route renders with a Disconnect affordance.</li>
	 *   <li>"Available devices" — discovered Cast routes; tapping selects one
	 *       (which starts a session).</li>
	 *   <li>"Open on any device" — the tappable, stable share link.</li>
	 * </ol>
	 * Discovery keeps running for as long as the dialog is open; the share
	 * proxy stays up after the dialog closes so a link the user just copied
	 * does not 404.
	 */
	public void openCastDialog() {
		String playerUrl = startProxyForSharing();
		if (playerUrl == null) {
			controller.showHint(activity.getString(R.string.cast_no_stream),
					com.hhst.youtubelite.player.common.PlayerUiConstants.HINT_HIDE_DELAY_MS);
			return;
		}
		ensureMediaRouter();
		final androidx.mediarouter.media.MediaRouter router = mediaRouter;
		final androidx.mediarouter.media.MediaRouteSelector selector = routeSelector;
		if (router == null || selector == null) return;

		View content = android.view.LayoutInflater.from(activity)
				.inflate(R.layout.dialog_cast, new android.widget.FrameLayout(activity), false);
		final androidx.recyclerview.widget.RecyclerView devicesView =
				content.findViewById(R.id.cast_devices);
		devicesView.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(activity));
		devicesView.setNestedScrollingEnabled(false);
		final View searchingFooter = content.findViewById(R.id.cast_searching_footer);
		final View emptyCard = content.findViewById(R.id.cast_empty_card);
		final android.widget.TextView emptyTitle = content.findViewById(R.id.cast_empty_title);
		final android.widget.TextView emptyHint = content.findViewById(R.id.cast_empty_hint);
		final android.widget.TextView learnFooter = content.findViewById(R.id.cast_learn_footer);
		learnFooter.setOnClickListener(v -> {
			Intent intent = new Intent(Intent.ACTION_VIEW,
					android.net.Uri.parse("https://support.google.com/chromecast/chromecast"));
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			try {
				activity.startActivity(intent);
			} catch (Exception e) {
				Log.d(TAG, "learnFooter click: no browser app available to handle intent", e);
			}
		});

		android.widget.TextView urlView = content.findViewById(R.id.cast_url);
		android.text.SpannableString urlSpan = new android.text.SpannableString(playerUrl);
		urlSpan.setSpan(new android.text.style.UnderlineSpan(), 0, playerUrl.length(), 0);
		urlView.setText(urlSpan);
		urlView.setOnClickListener(v -> shareUrl(playerUrl));

		final CastDeviceAdapter adapter = new CastDeviceAdapter(router,
				route -> router.selectRoute(route),
				route -> router.unselect(androidx.mediarouter.media.MediaRouter.UNSELECT_REASON_DISCONNECTED),
				() -> closeShareLink());
		devicesView.setAdapter(adapter);

		com.google.android.material.dialog.MaterialAlertDialogBuilder builder =
				new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
						.setView(content);
		final androidx.appcompat.app.AlertDialog dialog = builder.create();
		content.findViewById(R.id.cast_close).setOnClickListener(v -> dialog.dismiss());

		// Show the "Learn about casting" hint only after searching for a while with no results.
		final android.os.Handler delayHandler = new android.os.Handler(android.os.Looper.getMainLooper());
		final Runnable showLearnFooter = () -> learnFooter.setVisibility(View.VISIBLE);

		// Refresh the device list now and on every route/state change while open.
		final Runnable refresh = () -> {
			List<androidx.mediarouter.media.MediaRouter.RouteInfo> routes = collectCastRoutes(router);
			androidx.mediarouter.media.MediaRouter.RouteInfo active = activeCastRoute(router);
			// linkProxyRunning: the share-link HTTP proxy is up (URL copyable).
			// linkConnected: a browser is actually consuming it (page/manifest
			// fetched within the last 30s). The dialog row and player banner
			// only show the "connected" highlight once a browser has connected.
			boolean linkProxyRunning = castController.isLinkProxyRunning();
			boolean linkConnected = castController.isBrowserConnected();
			adapter.submit(new CastDialogState(active,
					active != null ? active.getConnectionState() : -1,
					linkProxyRunning, linkConnected, routes));
			// Drive the player-side link banner from the live browser-connection
			// state while the dialog is open: show once a browser connects, hide
			// when it disconnects (unless the user already dismissed it).
			if (linkConnected) {
				controller.showLinkBanner();
			} else if (linkProxyRunning) {
				controller.hideLinkBanner();
			}
			delayHandler.removeCallbacks(showLearnFooter);
			if (!castController.isCastInitialized()) {
				// Cast framework unavailable (no Play Services). No point scanning.
				searchingFooter.setVisibility(View.GONE);
				learnFooter.setVisibility(View.GONE);
				emptyTitle.setText(R.string.cast_unavailable);
				emptyHint.setText(R.string.cast_unavailable_hint);
				emptyCard.setVisibility(routes.isEmpty() && !linkProxyRunning ? View.VISIBLE : View.GONE);
			} else if (routes.isEmpty() && active == null && !linkProxyRunning) {
				// No devices yet: keep the spinner at the bottom of the list and
				// reveal the learn-more hint after a short delay.
				emptyCard.setVisibility(View.GONE);
				searchingFooter.setVisibility(View.VISIBLE);
				delayHandler.postDelayed(showLearnFooter, 8000);
			} else {
				emptyCard.setVisibility(View.GONE);
				learnFooter.setVisibility(View.GONE);
				searchingFooter.setVisibility(View.VISIBLE);
			}
		};
		refresh.run();
		androidx.mediarouter.media.MediaRouter.Callback cb = new androidx.mediarouter.media.MediaRouter.Callback() {
			@Override public void onRouteAdded(@NonNull androidx.mediarouter.media.MediaRouter r,
			                                  @NonNull androidx.mediarouter.media.MediaRouter.RouteInfo route) { activity.runOnUiThread(refresh); }
			@Override public void onRouteRemoved(@NonNull androidx.mediarouter.media.MediaRouter r,
			                                    @NonNull androidx.mediarouter.media.MediaRouter.RouteInfo route) { activity.runOnUiThread(refresh); }
			@Override public void onRouteSelected(@NonNull androidx.mediarouter.media.MediaRouter r,
			                                     @NonNull androidx.mediarouter.media.MediaRouter.RouteInfo route) { activity.runOnUiThread(refresh); }
			@Override public void onRouteUnselected(@NonNull androidx.mediarouter.media.MediaRouter r,
			                                        @NonNull androidx.mediarouter.media.MediaRouter.RouteInfo route) { activity.runOnUiThread(refresh); }
		};
		router.addCallback(selector, cb,
				androidx.mediarouter.media.MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY
						| androidx.mediarouter.media.MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN);
		// Also listen to CastContext state changes: the Cast route provider only
		// populates MediaRouter routes after its first scan completes (which can
		// take several seconds), and onCastStateChanged fires exactly then.
		final com.hhst.youtubelite.cast.CastPlaybackController.OnCastStateChangedListener stateListener =
				state -> activity.runOnUiThread(refresh);
		castController.setOnCastStateChangedListener(stateListener);
		// Defensive polling: the first CastContext discovery scan can take several
		// seconds and the CastStateListener callback occasionally arrives late or
		// is dropped on some OEM ROMs. Re-poll every 1s while the dialog is open
		// so devices show up promptly; stopped on dismiss. Every 5th poll we
		// re-arm the MediaRouter callback so an active scan keeps running even if
		// some OEM ROM quietly drops it — this is the main line of defence
		// against "device turned on mid-scan and never shows up". Uses a Handler
		// rather than a coroutine because all work is on the UI thread (refresh
		// and MediaRouter callback management) with no blocking I/O.
		final android.os.Handler pollHandler = new android.os.Handler(android.os.Looper.getMainLooper());
		final int[] pollCount = {0};
		// Guard against the poll re-adding a MediaRouter callback after the
		// dialog was dismissed: dismiss removes the callback and clears the
		// pending poll, but a poll already mid-tick could call addCallback
		// after dismiss ran, leaking an active-scan callback forever.
		final boolean[] dismissed = {false};
		final Runnable poll = new Runnable() {
			@Override public void run() {
				if (dismissed[0] || !dialog.isShowing()) return;
				refresh.run();
				pollCount[0]++;
				if (pollCount[0] % 5 == 0 && !dismissed[0] && dialog.isShowing()) {
					router.removeCallback(cb);
					router.addCallback(selector, cb,
							androidx.mediarouter.media.MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY
									| androidx.mediarouter.media.MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN);
				}
				pollHandler.postDelayed(this, 500);
			}
		};
		dialog.setOnDismissListener(d -> {
			dismissed[0] = true;
			router.removeCallback(cb);
			pollHandler.removeCallbacks(poll);
			delayHandler.removeCallbacks(showLearnFooter);
			// Stop listening for discovery state changes once the dialog closes.
			castController.setOnCastStateChangedListener(null);
			// Keep the share-link proxy alive after the dialog closes: the user
			// may open the link in a browser moments later, and tearing it down
			// here would make the URL they just copied go 404. The proxy is torn
			// down when a new video is cast (startCasting/startProxy stop any
			// previous proxy), when casting ends (stopCasting → stopProxy), and
			// when the activity is destroyed (releaseCast → release → stopProxy),
			// or explicitly when the user taps the link row's close button.
			controller.hideControlsAutomatically();
		});
		dialog.show();
		pollHandler.postDelayed(poll, 500);
	}

	@Nullable
	private static androidx.mediarouter.media.MediaRouter.RouteInfo activeCastRoute(
			@NonNull androidx.mediarouter.media.MediaRouter router) {
		String castCategory = com.google.android.gms.cast.CastMediaControlIntent.categoryForCast(
				com.google.android.gms.cast.CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID);
		androidx.mediarouter.media.MediaRouter.RouteInfo selected = router.getSelectedRoute();
		if (selected != null
				&& selected.getPlaybackType() == androidx.mediarouter.media.MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE
				&& selected.supportsControlCategory(castCategory)) {
			return selected;
		}
		return null;
	}

	@NonNull
	private static List<androidx.mediarouter.media.MediaRouter.RouteInfo> collectCastRoutes(
			@NonNull androidx.mediarouter.media.MediaRouter router) {
		String castCategory = com.google.android.gms.cast.CastMediaControlIntent.categoryForCast(
				com.google.android.gms.cast.CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID);
		List<androidx.mediarouter.media.MediaRouter.RouteInfo> routes = new ArrayList<>();
		for (androidx.mediarouter.media.MediaRouter.RouteInfo route : router.getRoutes()) {
			if (!route.isSelected()
					&& route.getPlaybackType() == androidx.mediarouter.media.MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE
					&& route.supportsControlCategory(castCategory)
					&& route.isEnabled()) {
				routes.add(route);
			}
		}
		return routes;
	}

	/**
	 * Snapshot the dialog renders in one submit. Kept as an immutable value so
	 * the adapter can diff strictly on content (not on caller's identity).
	 */
	static final class CastDialogState {
		@Nullable
		final androidx.mediarouter.media.MediaRouter.RouteInfo active;
		@Nullable
		final String activeId;
		final int activeState; // {@code -1} when no active route
		/** Share-link proxy is running (URL copyable), browser may or may not have connected. */
		final boolean linkProxyRunning;
		/** A browser is actually consuming the share link (page/manifest fetched recently). */
		final boolean linkConnected;
		@NonNull
		final List<androidx.mediarouter.media.MediaRouter.RouteInfo> available;

		CastDialogState(@Nullable androidx.mediarouter.media.MediaRouter.RouteInfo active,
		                int activeState,
		                boolean linkProxyRunning,
		                boolean linkConnected,
		                @NonNull List<androidx.mediarouter.media.MediaRouter.RouteInfo> available) {
			this.active = active;
			this.activeId = active != null ? active.getId() : null;
			this.activeState = activeState;
			this.linkProxyRunning = linkProxyRunning;
			this.linkConnected = linkConnected;
			this.available = available;
		}

		boolean hasConnected() {
			// The "Connected devices" section only appears when something is
			// actually connected: a Chromecast session is up, OR a browser is
			// consuming the share link. A proxy that is merely running (URL
			// copied but no browser opened yet) does NOT count — the link row
			// would otherwise show a misleading "Opening link…" entry before
			// any browser has connected.
			return active != null || linkConnected;
		}
	}

	/**
	 * RecyclerView adapter that renders one scrolling list with three logical
	 * sections (each wrapped in its own section header entry so the dialog
	 * stays a single scrollable area):
	 * <ol>
	 *   <li>"Connected devices" — optional; shown while a Chromecast session is
	 *       active OR the long-lived share link is open over the proxy.</li>
	 *   <li>"Available devices" — discovered Cast routes, each row tappable to
	 *       select (which starts a session).</li>
	 * </ol>
	 * A single list keeps the footer's "searching" spinner reachable regardless
	 * of device count, avoids nested-scroll quirks, and lets the section
	 * headers fade in/out smoothly inside the same scroll.
	 */
	private final class CastDeviceAdapter
			extends androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder> {
		private static final int TYPE_HEADER = 0;
		private static final int TYPE_LINK = 1;
		private static final int TYPE_ACTIVE = 2;
		private static final int TYPE_AVAILABLE = 3;

		@NonNull
		private final androidx.mediarouter.media.MediaRouter router;
		@NonNull
		private final java.util.function.Consumer<androidx.mediarouter.media.MediaRouter.RouteInfo> onSelect;
		@NonNull
		private final java.util.function.Consumer<androidx.mediarouter.media.MediaRouter.RouteInfo> onDisconnect;
		@NonNull
		private final Runnable onCloseLink;

		@Nullable
		private CastDialogState state;

		/**
		 * Tick counter used for stale-route housekeeping. Each successful submit
		 * bumps the tick; routes missing in two consecutive polls are pruned.
		 * (MediaRouter does not always fire onRouteRemoved when a Cast endpoint
		 * loses power, so we clean up ourselves.)
		 */
		private final java.util.Map<String, Integer> routeLastSeenTick = new java.util.HashMap<>();
		private int pollTick = 0;

		/**
		 * Guards against the 1s poll repeatedly stopping and restarting the
		 * connecting-state pulse animation on the active row, which would make
		 * the circular spinner appear frozen.
		 * <p>
		 * Must use {@link java.util.HashMap} (not {@link java.util.IdentityHashMap})
		 * because the key is a freshly-built {@code String} on every bind —
		 * {@code IdentityHashMap} compares by reference and would never match,
		 * so the pulse animation would be cleared and restarted on every poll,
		 * making it appear frozen mid-cycle.
		 */
		private final java.util.Map<String, Integer> boundRowState = new java.util.HashMap<>();

		CastDeviceAdapter(@NonNull androidx.mediarouter.media.MediaRouter router,
		                  @NonNull java.util.function.Consumer<androidx.mediarouter.media.MediaRouter.RouteInfo> onSelect,
		                  @NonNull java.util.function.Consumer<androidx.mediarouter.media.MediaRouter.RouteInfo> onDisconnect,
		                  @NonNull Runnable onCloseLink) {
			this.router = router;
			this.onSelect = onSelect;
			this.onDisconnect = onDisconnect;
			this.onCloseLink = onCloseLink;
		}

		void submit(@NonNull CastDialogState newState) {
			// Diff: if the structured content is unchanged, skip notify* to keep
			// pulse animations running on connecting devices. Only linkConnected
			// (not linkProxyRunning) affects which rows render, so the proxy
			// starting/stopping without a browser connecting does not rebind.
			CastDialogState cur = this.state;
			boolean sameLinks = (cur != null && cur.linkConnected == newState.linkConnected);
			boolean sameActiveId = java.util.Objects.equals(cur != null ? cur.activeId : null, newState.activeId);
			boolean sameActiveState = cur != null && cur.activeState == newState.activeState;
			boolean sameAvail = cur != null && sameRouteIds(cur.available, newState.available);
			this.state = newState;

			// Housekeeping for stale routes (device powered off). Bump tick on
			// routes present this poll; prune routes unseen for 1 consecutive poll
			// so turned-off devices disappear faster.
			pollTick++;
			for (androidx.mediarouter.media.MediaRouter.RouteInfo r : newState.available) {
				routeLastSeenTick.put(r.getId(), pollTick);
			}
			routeLastSeenTick.entrySet().removeIf(e -> (pollTick - e.getValue()) > 1);

			if (sameLinks && sameActiveId && sameActiveState && sameAvail) {
				return;
			}
			notifyDataSetChanged();
		}

		private static boolean sameRouteIds(
				@NonNull List<androidx.mediarouter.media.MediaRouter.RouteInfo> a,
				@NonNull List<androidx.mediarouter.media.MediaRouter.RouteInfo> b) {
			if (a.size() != b.size()) return false;
			for (int i = 0; i < a.size(); i++) {
				if (!java.util.Objects.equals(a.get(i).getId(), b.get(i).getId())) return false;
			}
			return true;
		}

		@Override
		public int getItemViewType(int position) {
			CastDialogState s = this.state;
			if (s == null) return TYPE_HEADER;
			int pos = 0;
			if (s.hasConnected()) {
				if (position == pos) return TYPE_HEADER;
				pos++;
				// The link row only appears once a browser has actually
				// connected; a proxy that is merely running (URL copied but
				// no browser yet) does not get a row here.
				if (s.linkConnected && position == pos) return TYPE_LINK;
				if (s.linkConnected) pos++;
				if (s.active != null && position == pos) return TYPE_ACTIVE;
				if (s.active != null) pos++;
			}
			if (position == pos) return TYPE_HEADER;
			pos++;
			return TYPE_AVAILABLE;
		}

		@androidx.annotation.NonNull
		@Override
		public androidx.recyclerview.widget.RecyclerView.ViewHolder onCreateViewHolder(
				@NonNull android.view.ViewGroup parent, int viewType) {
			android.view.LayoutInflater inflater = android.view.LayoutInflater.from(parent.getContext());
			switch (viewType) {
				case TYPE_LINK: {
					android.view.View v = inflater.inflate(R.layout.item_cast_link, parent, false);
					return new LinkVH(v);
				}
				case TYPE_HEADER: {
					android.view.View v = inflater.inflate(R.layout.item_cast_header, parent, false);
					return new HeaderVH(v);
				}
				case TYPE_ACTIVE:
				case TYPE_AVAILABLE:
				default: {
					android.view.View v = inflater.inflate(R.layout.item_cast_device, parent, false);
					return new DeviceVH(v);
				}
			}
		}

		@Override
		public void onBindViewHolder(@NonNull androidx.recyclerview.widget.RecyclerView.ViewHolder vh, int position) {
			CastDialogState s = this.state;
			if (s == null) return;
			int pos = 0;
			if (s.hasConnected()) {
				if (position == pos) {
					((HeaderVH) vh).bind(R.string.cast_section_connected);
				}
				pos++;
				if (s.linkConnected) {
					if (position == pos) {
						((LinkVH) vh).bind();
						return;
					}
					pos++;
				}
				if (s.active != null) {
					if (position == pos) {
						((DeviceVH) vh).bind(s.active, true,
								v -> onSelect.accept(s.active),
								v -> onDisconnect.accept(s.active));
						return;
					}
					pos++;
				}
			}
			// The "available" header.
			if (position == pos) {
				((HeaderVH) vh).bind(R.string.cast_section_devices);
				return;
			}
			pos++;
			int idx = position - pos;
			if (idx >= 0 && idx < s.available.size()) {
				androidx.mediarouter.media.MediaRouter.RouteInfo route = s.available.get(idx);
				((DeviceVH) vh).bind(route, false,
						v -> onSelect.accept(route),
						v -> onDisconnect.accept(route));
			}
		}

		@Override
		public int getItemCount() {
			CastDialogState s = this.state;
			if (s == null) return 0;
			int count = 0;
			if (s.hasConnected()) {
				count++; // connected-section header
				if (s.linkConnected) count++;
				if (s.active != null) count++;
			}
			count++; // available-section header
			count += (s.available != null ? s.available.size() : 0);
			return count;
		}

		final class HeaderVH extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
			@NonNull
			private final android.widget.TextView text;

			HeaderVH(@NonNull android.view.View v) {
				super(v);
				text = v.findViewById(R.id.cast_section_header_text);
			}

			void bind(int titleRes) {
				text.setText(titleRes);
				itemView.setOnClickListener(null);
			}
		}

		final class LinkVH extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
			@NonNull
			private final ImageView icon;
			@NonNull
			private final android.widget.TextView name;
			@NonNull
			private final android.widget.ImageButton close;

			LinkVH(@NonNull android.view.View v) {
				super(v);
				icon = v.findViewById(R.id.cast_link_icon);
				name = v.findViewById(R.id.cast_link_name);
				close = v.findViewById(R.id.cast_link_close);
			}

			void bind() {
				// This row is only ever shown once a browser has actually
				// fetched the player page / manifest, so it always renders in
				// the connected ("opened in browser") state with primary highlight.
				name.setText(R.string.cast_link_connected);
				close.setVisibility(View.VISIBLE);
				close.setOnClickListener(v -> {
					Runnable r = onCloseLink;
					if (r != null) r.run();
				});
				itemView.setOnClickListener(v -> {
					Runnable r = onCloseLink;
					if (r != null) r.run();
				});
				icon.setImageResource(R.drawable.ic_cast_link);
				icon.setColorFilter(activity.getColor(R.color.primary));
				name.setTextColor(activity.getColor(R.color.primary));
			}
		}

		final class DeviceVH extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
			@NonNull
			private final ImageView icon;
			@NonNull
			private final android.widget.TextView name;
			@NonNull
			private final android.widget.ImageButton disconnect;

			DeviceVH(@NonNull android.view.View v) {
				super(v);
				icon = v.findViewById(R.id.cast_device_icon);
				name = v.findViewById(R.id.cast_device_name);
				disconnect = v.findViewById(R.id.cast_device_disconnect);
			}

			void bind(@NonNull androidx.mediarouter.media.MediaRouter.RouteInfo route,
			          boolean isActive,
			          @NonNull android.view.View.OnClickListener onSelect,
			          @NonNull android.view.View.OnClickListener onDisconnect) {
				name.setText(route.getName());
				// Compute a stable per-row "state key" and compare to the last
				// bound state. When connecting, the 1s poll rebinds this row on
				// every tick; we only touch the icon (image / color filter /
				// pulse animation) if the state actually changed — otherwise
				// re-setting the image resource on every tick disturbs the
				// running pulse animation and makes it look frozen mid-cycle.
				String rowKey = (isActive ? "A:" : "R:") + route.getId();
				int newState = isActive ? 1000 + route.getConnectionState() : route.getConnectionState();
				Integer prevState = boundRowState.put(rowKey, newState);
				boolean stateChanged = prevState == null || prevState != newState;
				if (isActive) {
					disconnect.setVisibility(View.VISIBLE);
					disconnect.setOnClickListener(onDisconnect);
					itemView.setOnClickListener(null);
					name.setTextColor(activity.getColor(R.color.primary));
					if (stateChanged) {
						icon.clearAnimation();
						if (route.getConnectionState()
								== androidx.mediarouter.media.MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTED) {
							icon.setImageResource(R.drawable.ic_cast_connected);
							icon.setColorFilter(activity.getColor(R.color.primary));
						} else {
							icon.setImageResource(R.drawable.ic_cast);
							icon.setColorFilter(activity.getColor(R.color.primary));
							icon.startAnimation(android.view.animation.AnimationUtils
									.loadAnimation(activity, R.anim.cast_pulse));
						}
					}
				} else {
					disconnect.setVisibility(View.GONE);
					disconnect.setOnClickListener(null);
					itemView.setOnClickListener(onSelect);
					name.setTextColor(activity.getColor(R.color.on_surface));
					if (stateChanged) {
						icon.clearAnimation();
						icon.setImageResource(R.drawable.ic_cast);
						icon.setColorFilter(activity.getColor(R.color.on_surface_variant));
					}
				}
			}
		}
	}

	/**
	 * Returns whether a long-lived share-link session is currently alive on the
	 * {@link com.hhst.youtubelite.cast.LocalStreamProxy}. The link session is
	 * started the first time the user opens the Cast dialog and stays up until
	 * the activity is destroyed — or until {@link #closeShareLink()} is called
	 * (e.g. from the dialog's link row close button). Video switches
	 * transparently re-point the proxy at the new manifest without tearing
	 * down the server, so the URL stays the same.
	 */
	public boolean isLinkCasting() {
		return castController.isLinkCasting();
	}

	/**
	 * Tears down the long-lived share link. Called when the user taps the close
	 * button on the dialog's link-cast row. After this the share URL stops
	 * working and a new proxy is spawned on the next dialog open.
	 */
	public void closeShareLink() {
		castController.stopProxy();
		lastLinkVideoId = null;
		controller.hideLinkBanner();
	}

	private void ensureMediaRouter() {
		if (mediaRouter != null) return;
		String castCategory = com.google.android.gms.cast.CastMediaControlIntent.categoryForCast(
				com.google.android.gms.cast.CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID);
		routeSelector = new androidx.mediarouter.media.MediaRouteSelector.Builder()
				.addControlCategory(castCategory)
				.build();
		mediaRouter = androidx.mediarouter.media.MediaRouter.getInstance(activity);
	}

	private void shareUrl(@NonNull String url) {
		Intent shareIntent = new Intent(Intent.ACTION_SEND);
		shareIntent.setType("text/plain");
		shareIntent.putExtra(Intent.EXTRA_TEXT, url);
		Intent chooser = Intent.createChooser(shareIntent, activity.getString(R.string.cast));
		try {
			activity.startActivity(chooser);
		} catch (Exception e) {
			DeviceUtils.copyToClipboard(activity, activity.getString(R.string.cast), url);
			controller.showHint(activity.getString(R.string.debug_info_copied),
					com.hhst.youtubelite.player.common.PlayerUiConstants.HINT_HIDE_DELAY_MS);
		}
	}

	/**
	 * Resumes local playback after a cast session ends. Clears the cast delegate,
	 * stops the receiver, then either resumes the still-loaded local media or
	 * reloads it from {@link #lastDetails} (the local player is cleared when a
	 * different video was selected while casting), seeking to the last cast
	 * position. The local PlayerView was never rebound, so no rebind is needed.
	 */
	public void resumeFromCast() {
		engine.setCastDelegate(null);
		long position = castController.stopCasting();
		controller.setCasting(false, null);
		PlaybackDetails details = lastDetails;
		if (details == null || activeId == null) {
			return;
		}
		// Reload the media source if the item loaded into the local ExoPlayer no
		// longer matches what was being cast. While casting the local player was
		// only paused (never re-played), so a video switch during cast leaves the
		// ExoPlayer holding the OLD item while activeId points at the new one.
		// Also reload if the local player was cleared (STATE_IDLE).
		boolean videoChanged = !java.util.Objects.equals(engine.getVideoId(), activeId);
		boolean idle = engine.getPlaybackState() == Player.STATE_IDLE;
		if (videoChanged || idle) {
			try {
				engine.play(details);
			} catch (IllegalArgumentException e) {
				ErrorDialog.show(activity, e.getMessage(), e);
				return;
			}
		}
		engine.seekTo(position);
		engine.play();
	}

	@NonNull
	static ExtractionException classifyExtractionException(@NonNull Exception exception) {
		if (containsException(exception, SignInConfirmNotBotException.class)) {
			return new LoginRequiredExtractionException(exception);
		}
		if (containsException(exception, org.schabi.newpipe.extractor.exceptions.ReCaptchaException.class)) {
			return new LoginRequiredExtractionException(exception);
		}
		if (containsException(exception, org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException.class)) {
			return new ExtractionException("This video is age restricted and could not be extracted.", exception);
		}
		if (containsException(exception, org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException.class)
						|| containsException(exception, org.schabi.newpipe.extractor.exceptions.UnsupportedContentInCountryException.class)) {
			return new ExtractionException("This video is not available in your region.", exception);
		}
		if (containsException(exception, org.schabi.newpipe.extractor.exceptions.PrivateContentException.class)) {
			return new ExtractionException("This video is private or requires permission.", exception);
		}
		if (containsException(exception, org.schabi.newpipe.extractor.exceptions.PaidContentException.class)
						|| containsException(exception, org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException.class)
						|| containsException(exception, org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException.class)) {
			return new ExtractionException("This video type is not supported.", exception);
		}
		if (containsException(exception, SocketTimeoutException.class)
						|| containsException(exception, ConnectException.class)
						|| containsException(exception, NoRouteToHostException.class)
						|| containsException(exception, UnknownHostException.class)) {
			return new ExtractionException("Network error while extracting video info.", exception);
		}
		if (containsException(exception, IOException.class)) {
			return new ExtractionException("I/O error while extracting video info.", exception);
		}
		org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException unavailable =
						firstException(exception, org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException.class);
		if (unavailable != null) {
			return new ExtractionException(messageOrDefault(unavailable, "This video is not available."), exception);
		}
		org.schabi.newpipe.extractor.exceptions.ExtractionException extraction =
						firstException(exception, org.schabi.newpipe.extractor.exceptions.ExtractionException.class);
		if (extraction != null) {
			return new ExtractionException("Extract failed: " + messageOrDefault(extraction, extraction.getClass().getSimpleName()), exception);
		}
		return new ExtractionException("Extract failed", exception);
	}

	@Nullable
	private static <T extends Throwable> T firstException(@Nullable Throwable throwable,
	                                                     @NonNull Class<T> throwableClass) {
		if (throwable == null) return null;

		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		List<Throwable> pending = new ArrayList<>();
		pending.add(throwable);

		for (int i = 0; i < pending.size(); i++) {
			Throwable th = pending.get(i);
			if (th == null || !visited.add(th)) continue;
			if (throwableClass.isInstance(th)) return throwableClass.cast(th);

			Throwable cause = th.getCause();
			if (cause != null) pending.add(cause);
			Collections.addAll(pending, th.getSuppressed());
		}
		return null;
	}

	@NonNull
	private static String messageOrDefault(@NonNull Throwable throwable,
	                                       @NonNull String fallback) {
		String message = throwable.getMessage();
		return message == null || message.isBlank() ? fallback : message;
	}

	public void hide() {
		this.queuedId = null;
		this.activeId = null;
		this.currentUrl = null;
		stateStore.setVideoId(null);
		cancelExtraction();
		if (task != null) task.cancel(true);
		if (sponsorTask != null) sponsorTask.cancel(true);
		activity.runOnUiThread(() -> {
			controller.clearRotation();
			playerView.disableAutoPiP();
			exitInAppMiniPlayer();
			setMiniPlayerCallbacks(null, null);
			playerView.hide();
			engine.clear();
			if (playbackService != null) {
				playbackService.hideNotification();
			}
		});
	}

	public boolean isPlaying() {
		return engine.isPlaying();
	}

	public void pause() {
		engine.pause();
	}

	public void suspendBackgroundPlayback() {
		engine.pause();
		if (playbackService != null) {
			playbackService.hideNotification();
		}
	}

	public boolean seekLoadedVideo(@Nullable String url, long positionMs) {
		if (positionMs < 0L || url == null) return false;
		String videoId = YoutubeExtractor.getVideoId(url);
		if (videoId == null || !Objects.equals(activeId, videoId)) return false;
		activity.runOnUiThread(() -> engine.seekTo(positionMs));
		return true;
	}

	public long getResumePosition(@Nullable String videoId) {
		return prefs.getResumePosition(videoId);
	}

	public boolean isFullscreen() {
		return controller.isFullscreen();
	}

	public void enterFullscreen() {
		controller.enterFullscreen();
	}

	public void exitFullscreen() {
		controller.exitFullscreen();
	}

	public void exitFullscreenImmediately() {
		controller.exitFullscreenImmediately();
	}

	public void syncRotation(boolean autoRotate, int orientation) {
		controller.syncRotation(autoRotate, orientation);
	}

	public void enterPictureInPicture() {
		playerView.enterPiP();
	}

	public boolean shouldAutoEnterPictureInPicture() {
		return playerView.getVisibility() == View.VISIBLE;
	}

	public boolean canSuspendWatch() {
		return playerView.getVisibility() == View.VISIBLE;
	}

	public void enterInAppMiniPlayer() {
		inMiniPlayer = true;
		stateStore.setInMiniPlayer(true);
		playerView.enterInAppMiniPlayer();
		controller.enterMiniPlayer();
	}

	public void exitInAppMiniPlayer() {
		inMiniPlayer = false;
		stateStore.setInMiniPlayer(false);
		playerView.exitInAppMiniPlayer();
		controller.exitMiniPlayer();
	}

	public void restoreInAppMiniPlayerUiIfNeeded() {
		if (!inMiniPlayer) return;
		playerView.show();
		playerView.enterInAppMiniPlayer();
		controller.enterMiniPlayer();
	}

	public void suspendInAppMiniPlayerUiIfNeeded() {
		if (!inMiniPlayer) return;
		playerView.hide();
	}

	public void setMiniPlayerCallbacks(@Nullable Runnable onRestore, @Nullable Runnable onClose) {
		this.onRestore = onRestore;
		this.onClose = onClose;
		playerView.setMiniPlayerCallbacks(
						onRestore,
						onClose == null
										? null
										: () -> {
							hide();
							onClose.run();
						});
	}

	public void onPictureInPictureModeChanged(boolean isInPiP) {
		controller.onPictureInPictureModeChanged(isInPiP);
		if (!isInPiP) playerView.disableAutoPiP();
		if (wasInPip && !isInPiP && inMiniPlayer && onRestore != null) {
			onRestore.run();
		}
		wasInPip = isInPiP;
	}

	public void setHeight(int height) {
		playerView.post(() -> playerView.setHeight(height));
	}

	@Nullable
	public String getVideoId() {
		return activeId;
	}

	@NonNull
	public PlayerLoopMode getLoopMode() {
		return controller.getLoopMode();
	}

	public void setLoopMode(@NonNull PlayerLoopMode mode) {
		controller.setLoopMode(mode);
	}

	@Nullable
	public String getSubtitleLanguage() {
		return engine.getSubtitleLanguage();
	}

	public void setSubtitleLanguage(@Nullable String language) {
		engine.setSubtitleLanguage(language);
	}

	public void release() {
		cancelExtraction();
		if (task != null) task.cancel(true);
		if (sponsorTask != null) sponsorTask.cancel(true);
		activeId = null;
		queueRepo.removeListener(queueListener);
		stateStore.clear();
		wasInPip = false;
		onRestore = null;
		onClose = null;
		controller.release();
		activity.runOnUiThread(() -> {
			playerView.disableAutoPiP();
			playerView.setMiniPlayerCallbacks(null, null);
		});
		inMiniPlayer = false;
		engine.release();
	}

	private void cancelExtraction() {
		if (extractSession == null) return;
		extractSession.cancel();
		extractSession = null;
	}

}
