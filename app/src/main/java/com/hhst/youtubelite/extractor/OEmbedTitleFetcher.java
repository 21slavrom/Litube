package com.hhst.youtubelite.extractor;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.hhst.youtubelite.AppConstants;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches the original (untranslated) video title via YouTube's locale-agnostic oEmbed endpoint.
 * <p>
 * The oEmbed endpoint returns the canonical title in the video's own language, regardless of the
 * requesting locale. This mirrors the approach used by the "YouTube Anti Translate" extension.
 */
@Singleton
public class OEmbedTitleFetcher {
	private static final String TAG = "OEmbedTitleFetcher";
	private static final String OEMBED_URL_TEMPLATE =
					"https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=%s";

	private final OkHttpClient client;
	private final Gson gson;

	@Inject
	public OEmbedTitleFetcher(@NonNull OkHttpClient client, @NonNull Gson gson) {
		this.client = client.newBuilder()
						.connectTimeout(10L, TimeUnit.SECONDS)
						.readTimeout(15L, TimeUnit.SECONDS)
						.build();
		this.gson = gson;
	}

	/**
	 * Returns the original video title, or {@code null} if it could not be retrieved
	 * (network error, restricted video returning 401, malformed response, etc.).
	 */
	@Nullable
	public String fetchOriginalTitle(@NonNull String videoId) {
		return fetchFromUrl(String.format(OEMBED_URL_TEMPLATE, videoId));
	}

	/**
	 * Fetches and parses the oEmbed response at the given URL. Exposed so tests can redirect
	 * the call to a mock server.
	 */
	@Nullable
	String fetchFromUrl(@NonNull String url) {
		Request request = new Request.Builder()
						.url(url)
						.header("User-Agent", AppConstants.USER_AGENT)
						.get()
						.build();
		try (Response response = client.newCall(request).execute()) {
			if (!response.isSuccessful()) {
				return null;
			}
			ResponseBody body = response.body();
			if (body == null) {
				return null;
			}
			JsonObject json = gson.fromJson(body.string(), JsonObject.class);
			if (json == null || !json.has("title") || json.get("title").isJsonNull()) {
				return null;
			}
			String title = json.get("title").getAsString();
			return title == null || title.isBlank() ? null : title;
		} catch (IOException | RuntimeException e) {
			Log.w(TAG, "oEmbed title fetch failed", e);
			return null;
		}
	}
}
