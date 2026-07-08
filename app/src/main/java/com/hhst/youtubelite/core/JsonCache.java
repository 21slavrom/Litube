package com.hhst.youtubelite.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * TTL-based key-value cache abstraction. Values are JSON-serialized via Gson
 * and wrapped in a slot carrying the expiry timestamp. Replaces the duplicated
 * "serialize + wrap-in-slot + decode-with-Gson" pattern that previously lived
 * inline in {@code InfoCache} and {@code WebViewCachePolicy}.
 *
 * @param <T> value type; resolved at the call site via the {@code type} argument
 */
public interface JsonCache {

    /**
     * Returns the cached value for {@code key} if present and unexpired, or
     * {@code null} otherwise. Decode failures are logged by the implementation
     * and surface as {@code null} (never thrown) so callers can fall back to a
     * fresh fetch without a try/catch.
     */
    @Nullable
    <T> T get(@NonNull String key, @NonNull Class<T> type);

    /**
     * Stores {@code value} under {@code key} with the given TTL. Existing
     * entries for the same key are overwritten.
     */
    void put(@NonNull String key, @NonNull Object value, long ttlMillis);

    /**
     * Removes the entry for {@code key}. No-op if absent.
     */
    void invalidate(@NonNull String key);
}
