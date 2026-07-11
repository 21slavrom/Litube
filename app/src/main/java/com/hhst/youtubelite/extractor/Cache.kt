package com.hhst.youtubelite.extractor

import com.hhst.youtubelite.core.JsonCache
import java.util.concurrent.TimeUnit

/**
 * Persistent cache for [Metadata], [Stream], and [Segment].
 *
 * Stream URLs expire sooner (~2 min) than metadata and chapters (~6 h).
 */
interface Cache {
    fun getMetadata(videoId: String): Metadata?
    fun putMetadata(videoId: String, metadata: Metadata)

    fun getStream(videoId: String): Stream?
    fun putStream(videoId: String, stream: Stream)
    fun invalidateStream(videoId: String)

    fun getSegment(videoId: String): Segment?
    fun putSegment(videoId: String, segment: Segment)
}

/** [Cache] backed by [JsonCache] (MMKV in production). */
class DiskCache(
    private val store: JsonCache,
) : Cache {

    override fun getMetadata(videoId: String): Metadata? =
        store.get(METADATA_KEY + videoId, Metadata::class.java)

    override fun putMetadata(videoId: String, metadata: Metadata) {
        store.put(METADATA_KEY + videoId, metadata, METADATA_TTL_MS)
    }

    override fun getStream(videoId: String): Stream? =
        store.get(STREAM_KEY + videoId, Stream::class.java)

    override fun putStream(videoId: String, stream: Stream) {
        store.put(STREAM_KEY + videoId, stream, STREAM_TTL_MS)
    }

    override fun invalidateStream(videoId: String) {
        store.invalidate(STREAM_KEY + videoId)
    }

    override fun getSegment(videoId: String): Segment? =
        store.get(SEGMENT_KEY + videoId, Segment::class.java)

    override fun putSegment(videoId: String, segment: Segment) {
        store.put(SEGMENT_KEY + videoId, segment, SEGMENT_TTL_MS)
    }

    private companion object {
        const val METADATA_KEY = "extractor:metadata:"
        const val STREAM_KEY = "extractor:stream:"
        const val SEGMENT_KEY = "extractor:segment:"

        val METADATA_TTL_MS = TimeUnit.HOURS.toMillis(6)
        val STREAM_TTL_MS = TimeUnit.MINUTES.toMillis(2)
        val SEGMENT_TTL_MS = TimeUnit.HOURS.toMillis(6)
    }
}
