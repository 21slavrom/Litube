package com.hhst.youtubelite.browser;

import android.util.Log;
import android.webkit.CookieManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import okhttp3.Response;

/**
 * Coordinator that serializes cookie access between WebView and extractor.
 */
final class CookieAccessCoordinator {

	private static final String TAG = "CookieAccessCoord";

	@NonNull
	private final Backend backend;
	@NonNull
	private final Scheduler scheduler;
	@NonNull
	private final LongSupplier nowMillisSupplier;
	private final long readCacheTtlMillis;
	private final long flushDelayMillis;
	@NonNull
	private final Map<String, CacheEntry> cookieCache = new ConcurrentHashMap<>();

	CookieAccessCoordinator(@NonNull Backend backend, @NonNull Scheduler scheduler, @NonNull LongSupplier nowMillisSupplier, long readCacheTtlMillis, long flushDelayMillis) {
		this.backend = Objects.requireNonNull(backend);
		this.scheduler = Objects.requireNonNull(scheduler);
		this.nowMillisSupplier = Objects.requireNonNull(nowMillisSupplier);
		this.readCacheTtlMillis = Math.max(0L, readCacheTtlMillis);
		this.flushDelayMillis = Math.max(0L, flushDelayMillis);
	}

	@NonNull
	static CookieAccessCoordinator create(@NonNull CookieManager cookieManager) {
		return new CookieAccessCoordinator(new CookieManagerBackend(cookieManager), new ExecutorScheduler(Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "web-cookie-flush");
			thread.setDaemon(true);
			return thread;
		})), System::currentTimeMillis, 250L, 150L);
	}

	@Nullable
	String getCookie(@NonNull String url) {
		long nowMillis = nowMillisSupplier.getAsLong();
		String cacheKey = normalizeCacheKey(url);
		CacheEntry cachedEntry = cookieCache.get(cacheKey);
		if (cachedEntry != null && (nowMillis - cachedEntry.createdAtMillis) <= readCacheTtlMillis) {
			return cachedEntry.value;
		}

		String cookie = backend.getCookie(url);
		cookieCache.put(cacheKey, new CacheEntry(cookie, nowMillis));
		return cookie;
	}

	void syncCookies(@NonNull Response response) {
		List<Response> responseChain = new ArrayList<>();
		Response resp = response;
		while (resp != null) {
			responseChain.add(resp);
			resp = resp.priorResponse();
		}

		boolean cookiesUpdated = false;
		for (int i = responseChain.size() - 1; i >= 0; i--) {
			Response chainResponse = responseChain.get(i);
			for (String cookie : chainResponse.headers().values("Set-Cookie")) {
				backend.setCookie(chainResponse.request().url().toString(), cookie);
				cookiesUpdated = true;
			}
		}
		if (!cookiesUpdated) return;
		cookieCache.clear();
		scheduler.schedule(flushDelayMillis, backend::flush);
	}

	/**
	 * Releases the scheduled executor backing cookie flush operations. Call
	 * when the owning WebView is destroyed to prevent thread accumulation
	 * across browser session recreations.
	 */
	void release() {
		scheduler.shutdown();
	}

	@NonNull
	private String normalizeCacheKey(@NonNull String url) {
		try {
			URI uri = URI.create(url);
			String scheme = uri.getScheme();
			String authority = uri.getRawAuthority();
			if (scheme == null || authority == null) return url;
			String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
			return scheme + "://" + authority + path;
		} catch (RuntimeException e) {
			Log.d(TAG, "normalizeCacheKey: malformed URL, returning raw url", e);
			return url;
		}
	}

	interface Backend {
		@Nullable
		String getCookie(@NonNull String url);

		void setCookie(@NonNull String url, @NonNull String cookie);

		void flush();
	}

	private interface Scheduler {
		void schedule(long delayMillis, @NonNull Runnable task);

		/**
		 * Shuts down the backing executor, cancelling pending scheduled tasks.
		 * Called from {@link CookieAccessCoordinator#release()} when the owning
		 * WebView is destroyed.
		 */
		void shutdown();
	}

	private record CacheEntry(@Nullable String value, long createdAtMillis) {
	}

	private record CookieManagerBackend(@NonNull CookieManager cookieManager) implements Backend {
		private CookieManagerBackend {
			Objects.requireNonNull(cookieManager);
		}

		@Override
		@Nullable
		public String getCookie(@NonNull String url) {
			return cookieManager.getCookie(url);
		}

		@Override
		public void setCookie(@NonNull String url, @NonNull String cookie) {
			cookieManager.setCookie(url, cookie);
		}

		@Override
		public void flush() {
			cookieManager.flush();
		}
	}

	private record ExecutorScheduler(
					@NonNull ScheduledExecutorService executor) implements Scheduler {
		private ExecutorScheduler {
			Objects.requireNonNull(executor);
		}

		@Override
		public void schedule(long delayMillis, @NonNull Runnable task) {
			executor.schedule(task, Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
		}

		@Override
		public void shutdown() {
			executor.shutdownNow();
		}
	}
}
