package com.hhst.youtubelite.core

import android.util.Log
import com.google.gson.Gson
import com.tencent.mmkv.MMKV

/** [JsonCache] backed by MMKV with Gson and wall-clock TTL. */
class MmkvJsonCache(
    private val kv: MMKV,
    private val gson: Gson = Gson(),
) : JsonCache {

    private data class Entry(val until: Long, val json: String)

    override fun <T> get(key: String, type: Class<T>): T? {
        val raw = kv.decodeString(key, null)
        if (raw.isNullOrEmpty()) return null
        return try {
            val entry = gson.fromJson(raw, Entry::class.java) ?: return null
            if (entry.until <= System.currentTimeMillis()) {
                Log.d(TAG, "expired key=$key")
                return null
            }
            gson.fromJson(entry.json, type)
        } catch (e: RuntimeException) {
            Log.w(TAG, "decode failed key=$key type=${type.simpleName}", e)
            null
        }
    }

    override fun put(key: String, value: Any, ttlMs: Long) {
        val entry = Entry(System.currentTimeMillis() + ttlMs, gson.toJson(value))
        kv.encode(key, gson.toJson(entry))
    }

    override fun invalidate(key: String) {
        kv.removeValueForKey(key)
    }

    private companion object {
        const val TAG = "MmkvJsonCache"
    }
}
