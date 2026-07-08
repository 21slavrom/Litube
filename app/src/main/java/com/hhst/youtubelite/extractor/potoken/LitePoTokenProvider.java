package com.hhst.youtubelite.extractor.potoken;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider;
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult;

import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Provider that feeds PoToken data into extraction.
 * <p>
 * Design: PoToken is a <em>reactive remedy</em>, not a prerequisite. All provider methods return
 * immediately — either with a cached token or {@code null}. No method blocks on WebView readiness.
 * When extraction needs a PoToken that isn't cached yet, call {@link #requestPoTokenAsync} to mint
 * it in the background and retry later (typically after a 403).
 */
@Singleton
public final class LitePoTokenProvider implements PoTokenProvider {
	private final PoTokenCoordinator coordinator;
	private final Executor executor;

	@Inject
	public LitePoTokenProvider(PoTokenCoordinator coordinator,
	                           Executor executor) {
		this.coordinator = coordinator;
		this.executor = executor;
	}

	/**
	 * Returns a cached WEB PoToken, or {@code null} if none available yet.
	 * Non-blocking — never waits on WebView warm-up.
	 */
	@Override
	@Nullable
	public PoTokenResult getWebClientPoToken(String videoId) {
		return coordinator.getCachedWebPoToken(videoId);
	}

	/** No embedded WEB PoToken support. */
	@Override
	@Nullable
	public PoTokenResult getWebEmbedClientPoToken(String videoId) {
		return null;
	}

	/** Returns a cached ANDROID PoToken, or {@code null} (non-blocking, MMKV cache only). */
	@Override
	@Nullable
	public PoTokenResult getAndroidClientPoToken(String videoId) {
		return coordinator.getAndroidClientPoToken(videoId);
	}

	/** Returns a cached IOS PoToken, or {@code null} (non-blocking, MMKV cache only). */
	@Override
	@Nullable
	public PoTokenResult getIosClientPoToken(String videoId) {
		return coordinator.getIosClientPoToken(videoId);
	}

	/**
	 * Async WEB PoToken mint for 403 recovery: mints in background, then calls back.
	 */
	public void requestWebPoTokenAsync(@NonNull String videoId,
	                                   @NonNull PoTokenCallback callback) {
		executor.execute(() -> {
			PoTokenResult result = coordinator.mintWebPoTokenBlocking(videoId);
			callback.onResult(result);
		});
	}

	/**
	 * Callback for async PoToken minting.
	 */
	public interface PoTokenCallback {
		void onResult(@Nullable PoTokenResult result);
	}
}
