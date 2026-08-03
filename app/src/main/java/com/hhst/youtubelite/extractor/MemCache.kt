package com.hhst.youtubelite.extractor

import android.util.LruCache

/** In-memory LRU [Cache] for player-facing Innertube results. */
class MemCache(
    maxEntries: Int = DEFAULT_MAX,
) : Cache {

    private val meta = lru<Metadata>(maxEntries)
    private val streams = lru<Stream>(maxEntries)
    private val segments = lru<Segment>(maxEntries)

    override fun getMetadata(videoId: String): Metadata? = synchronized(meta) { meta.get(videoId) }

    override fun putMetadata(videoId: String, metadata: Metadata) {
        synchronized(meta) { meta.put(videoId, metadata) }
    }

    override fun getStream(videoId: String): Stream? = synchronized(streams) { streams.get(videoId) }

    override fun putStream(videoId: String, stream: Stream) {
        synchronized(streams) { streams.put(videoId, stream) }
    }

    override fun invalidateStream(videoId: String) {
        synchronized(streams) { streams.remove(videoId) }
    }

    override fun getSegment(videoId: String): Segment? =
        synchronized(segments) { segments.get(videoId) }

    override fun putSegment(videoId: String, segment: Segment) {
        synchronized(segments) { segments.put(videoId, segment) }
    }

    fun size(): Int = synchronized(meta) {
        maxOf(meta.size(), streams.size(), segments.size())
    }

    private fun <T> lru(max: Int) = object : LruCache<String, T>(max) {
        override fun sizeOf(key: String, value: T): Int = 1
    }

    companion object {
        const val DEFAULT_MAX = 16
    }
}
