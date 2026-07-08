package com.hhst.youtubelite.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hhst.youtubelite.core.JsonCache;

import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Cache for extractor playback and video details. Delegates storage and TTL
 * semantics to the shared {@link JsonCache} abstraction (backed by
 * {@code MmkvJsonCache}) so the JSON-Slot-MMKV encoding pattern has a single
 * implementation and a single place to fix the silent-fallback trap that
 * previously swallowed decode errors.
 */
@Singleton
public final class InfoCache {
	private static final String STREAM_KEY = "extractor:stream:";
	private static final String INFO_KEY = "extractor:info:";
	private static final long STREAM_TTL_MS = TimeUnit.MINUTES.toMillis(2);
	private static final long INFO_TTL_MS = TimeUnit.HOURS.toMillis(6);

	@NonNull
	private final JsonCache cache;

	@Inject
	public InfoCache(@NonNull JsonCache cache) {
		this.cache = cache;
	}

	@Nullable
	public PlaybackDetails getPlaybackDetails(@NonNull String videoId) {
		return cache.get(STREAM_KEY + videoId, PlaybackDetails.class);
	}

	public void putPlaybackDetails(@NonNull String videoId,
	                               @NonNull PlaybackDetails details) {
		cache.put(STREAM_KEY + videoId, details, STREAM_TTL_MS);
	}

	/**
	 * Removes the cached playback details for a video so that the next extraction fetches a
	 * fresh source (new signatures / po-tokens). Used by playback recovery when URLs have
	 * expired and replanning against the stale snapshot is exhausted.
	 */
	public void invalidatePlaybackDetails(@NonNull String videoId) {
		cache.invalidate(STREAM_KEY + videoId);
	}

	@Nullable
	public VideoDetails getVideoDetails(@NonNull String videoId) {
		return cache.get(INFO_KEY + videoId, VideoDetails.class);
	}

	public void putVideoDetails(@NonNull String videoId,
	                            @NonNull VideoDetails details) {
		cache.put(INFO_KEY + videoId, details, INFO_TTL_MS);
	}
}
