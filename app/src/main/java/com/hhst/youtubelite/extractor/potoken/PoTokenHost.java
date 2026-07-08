package com.hhst.youtubelite.extractor.potoken;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import com.hhst.youtubelite.util.StreamIOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * Host that manages PoToken warm-up and handoff.
 */
@Singleton
public final class PoTokenHost {
	private static final String TAG = "PoTokenHost";
	private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
					+ "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
	private static final String HOST_HTML_PREFIX =
					"<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><script>";
	private static final String HOST_HTML_SUFFIX =
					"</script></head><body></body></html>";

	@NonNull
	private final Context appContext;
	@NonNull
	private final PoTokenBridge poTokenBridge;
	@NonNull
	private final Handler handler = new Handler(Looper.getMainLooper());
	@NonNull
	private final Object lock = new Object();

	@Nullable
	private WebView webView;
	private boolean ready;
	private boolean loading;
	private long generation;
	@Nullable
	private String hostHtml;

	@Inject
	public PoTokenHost(@ApplicationContext @NonNull Context appContext,
	                   @NonNull PoTokenBridge poTokenBridge) {
		this.appContext = appContext;
		this.poTokenBridge = poTokenBridge;
	}

	public void prewarm() {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			ensureLoadedOnMainThread();
			return;
		}
		handler.post(this::ensureLoadedOnMainThread);
	}

	public boolean isReady() {
		synchronized (lock) {
			return ready && webView != null;
		}
	}

	public boolean awaitReady(long timeoutMs) {
		prewarm();
		if (Looper.myLooper() == Looper.getMainLooper()) {
			return isReady();
		}
		long deadline = SystemClock.uptimeMillis() + timeoutMs;
		synchronized (lock) {
			while (!ready || webView == null) {
				long remainingMs = deadline - SystemClock.uptimeMillis();
				if (remainingMs <= 0L) {
					return false;
				}
				try {
					lock.wait(remainingMs);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return false;
				}
			}
			return true;
		}
	}

	public long getGeneration() {
		synchronized (lock) {
			return generation;
		}
	}

	/**
	 * Destroys the host WebView and resets state. Must be called on the main
	 * thread. After release, the next {@link #prewarm()} re-creates the WebView.
	 * Intended for app shutdown or instrumentation tests; in normal use the
	 * host is {@code @Singleton}-scoped and kept for the process lifetime.
	 */
	public void release() {
		if (Looper.myLooper() != Looper.getMainLooper()) {
			handler.post(this::release);
			return;
		}
		final WebView view;
		synchronized (lock) {
			view = webView;
			webView = null;
			ready = false;
			loading = false;
			hostHtml = null;
		}
		if (view != null) {
			view.removeJavascriptInterface(PoTokenBridge.JS_INTERFACE);
			view.destroy();
		}
	}

	public boolean isCurrentGeneration(long expectedGeneration) {
		synchronized (lock) {
			return ready && webView != null && generation == expectedGeneration;
		}
	}

	@Nullable
	public String evaluateJavascript(long expectedGeneration,
	                                 @NonNull String script,
	                                 final long timeoutMs) {
		CompletableFuture<String> future = new CompletableFuture<>();
		handler.post(() -> {
			final WebView view;
			synchronized (lock) {
				if (webView == null || !ready || generation != expectedGeneration) {
					future.complete(null);
					return;
				}
				view = webView;
			}
			view.evaluateJavascript(script, future::complete);
		});

		try {
			String rawValue = future.get(timeoutMs, TimeUnit.MILLISECONDS);
			return isCurrentGeneration(expectedGeneration) ? rawValue : null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (ExecutionException | TimeoutException e) {
			Log.w(TAG, "evaluateForResult: JS evaluation failed: "
					+ e.getClass().getSimpleName(), e);
			future.cancel(true);
			return null;
		}
	}

	private void ensureLoadedOnMainThread() {
		final WebView hostWebView;
		synchronized (lock) {
			if (webView == null) {
				webView = new WebView(appContext);
				configureWebView(webView);
			}
			if (ready || loading) {
				return;
			}
			hostWebView = webView;
			ready = false;
			loading = true;
			generation += 1L;
		}
		hostWebView.loadDataWithBaseURL("https://www.youtube.com", getHostHtml(), "text/html", "utf-8", null);
	}

	@SuppressLint("SetJavaScriptEnabled")
	private void configureWebView(@NonNull WebView webView) {
		WebSettings settings = webView.getSettings();
		// JavaScript is required to run the po-token host page that exchanges
		// context with the native bridge; without it the bridge is inert.
		settings.setJavaScriptEnabled(true);
		settings.setDomStorageEnabled(false);
		settings.setDatabaseEnabled(false);
		settings.setUserAgentString(USER_AGENT);
		settings.setBlockNetworkLoads(true);
		if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
			WebSettingsCompat.setSafeBrowsingEnabled(settings, false);
		}
		webView.addJavascriptInterface(poTokenBridge, PoTokenBridge.JS_INTERFACE);
		webView.setWebViewClient(new WebViewClient() {
			@Override
			public void onPageFinished(@NonNull WebView view, @NonNull String url) {
				super.onPageFinished(view, url);
				synchronized (lock) {
					if (view != webView) {
						return;
					}
					loading = false;
					ready = true;
					lock.notifyAll();
				}
			}

			@Override
			public void onReceivedHttpError(@NonNull WebView view,
			                                @NonNull WebResourceRequest request,
			                                @NonNull WebResourceResponse errorResponse) {
				super.onReceivedHttpError(view, request, errorResponse);
				if (request.isForMainFrame()) {
					onLoadFailed(view);
				}
			}

			@Override
			public void onReceivedError(@NonNull WebView view,
			                            @NonNull WebResourceRequest request,
			                            @NonNull android.webkit.WebResourceError error) {
				super.onReceivedError(view, request, error);
				if (request.isForMainFrame()) {
					onLoadFailed(view);
				}
			}
		});
	}

	private void onLoadFailed(@NonNull WebView view) {
		synchronized (lock) {
			if (view != webView) {
				return;
			}
			loading = false;
			ready = false;
			lock.notifyAll();
		}
	}

	@NonNull
	private String getHostHtml() {
		synchronized (lock) {
			if (hostHtml != null) {
				return hostHtml;
			}
			hostHtml = HOST_HTML_PREFIX + loadPoTokenScript() + HOST_HTML_SUFFIX;
			return hostHtml;
		}
	}

	@NonNull
	private String loadPoTokenScript() {
		try (InputStream inputStream = appContext.getAssets().open("potoken/potoken.js")) {
			return new String(StreamIOUtils.readInputStreamToBytes(inputStream), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("Unable to load potoken script asset", e);
		}
	}
}
