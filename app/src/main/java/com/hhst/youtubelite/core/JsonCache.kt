package com.hhst.youtubelite.core

/**
 * TTL JSON key-value store.
 *
 * Miss, expiry, and decode failure return null so callers can refetch.
 */
interface JsonCache {
    fun <T> get(key: String, type: Class<T>): T?
    fun put(key: String, value: Any, ttlMs: Long)
    fun invalidate(key: String)
}
