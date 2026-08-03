package com.hhst.youtubelite.extractor

import android.os.SystemClock
import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Raw WEB-client /player responses keyed by video id, served to the WebView to
 * avoid duplicate requests. The TTL is short because stream URLs expire quickly.
 * In-flight fetches are tracked so callers can wait for a pending response.
 */
class PlayerCache {
    private val entries = LruCache<String, Entry>(MAX)
    private val inFlight = ConcurrentHashMap<String, CountDownLatch>()

    private class Entry(val bytes: ByteArray, val createdAt: Long)

    fun get(videoId: String): ByteArray? {
        val entry = entries[videoId] ?: return null
        if (SystemClock.elapsedRealtime() - entry.createdAt > TTL_MS) {
            entries.remove(videoId)
            return null
        }
        return entry.bytes
    }

    fun put(videoId: String, bytes: ByteArray) {
        entries.put(videoId, Entry(bytes, SystemClock.elapsedRealtime()))
    }

    /** Runs [block] while the fetch of [videoId] is marked in-flight. */
    fun <T> tracking(videoId: String, block: () -> T): T {
        begin(videoId)
        try {
            return block()
        } finally {
            end(videoId)
        }
    }

    /** Blocks until the fetch for [videoId] completes or [timeoutMs] elapses. */
    fun await(videoId: String, timeoutMs: Long): Boolean {
        val latch = inFlight[videoId] ?: return false
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun begin(videoId: String) {
        inFlight.putIfAbsent(videoId, CountDownLatch(1))
    }

    private fun end(videoId: String) {
        inFlight.remove(videoId)?.countDown()
    }

    private companion object {
        val TTL_MS = TimeUnit.MINUTES.toMillis(2)
        const val MAX = 16
    }
}
