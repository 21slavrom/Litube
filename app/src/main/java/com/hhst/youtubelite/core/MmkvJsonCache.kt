package com.hhst.youtubelite.core

import android.util.Log
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MMKV-backed [JsonCache] implementation. Values are JSON-serialized via Gson
 * and wrapped in a [Slot] that carries the expiry timestamp. Reads past expiry
 * return {@code null} and emit a debug log so silent fallbacks are visible.
 *
 * Extracted from the original {@code InfoCache#read}/{@code write} pattern
 * (Slot record + Gson + MMKV), so all cache call sites can share the same
 * storage and decoding semantics.
 */
@Singleton
class MmkvJsonCache @Inject constructor(
    private val kv: MMKV,
    private val gson: Gson
) : JsonCache {

    private data class Slot(val until: Long, val json: String)

    override fun <T> get(key: String, type: Class<T>): T? {
        val raw = kv.decodeString(key, null)
        if (raw.isNullOrEmpty()) return null
        return try {
            val slot = gson.fromJson(raw, Slot::class.java) ?: return null
            if (slot.until <= System.currentTimeMillis()) {
                Log.d(TAG, "get: expired key=$key type=${type.simpleName}")
                return null
            }
            gson.fromJson(slot.json, type)
        } catch (e: RuntimeException) {
            // Fallback trap was silent historically; surface as a warning so
            // corrupted cache entries are observable in logcat without forcing
            // every caller to wrap reads in try/catch.
            Log.w(TAG, "get: decode failed for key=$key type=${type.simpleName}", e)
            null
        }
    }

    override fun put(key: String, value: Any, ttlMillis: Long) {
        val slot = Slot(System.currentTimeMillis() + ttlMillis, gson.toJson(value))
        kv.encode(key, gson.toJson(slot))
    }

    override fun invalidate(key: String) {
        kv.removeValueForKey(key)
    }

    companion object {
        private const val TAG = "MmkvJsonCache"
    }
}
