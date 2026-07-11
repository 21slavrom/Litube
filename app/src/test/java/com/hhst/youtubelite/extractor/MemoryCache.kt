package com.hhst.youtubelite.extractor

/**
 * In-memory [Cache] for unit / network tests only.
 */
class MemoryCache : Cache {
    private val metadata = mutableMapOf<String, Metadata>()
    private val streams = mutableMapOf<String, Stream>()
    private val segments = mutableMapOf<String, Segment>()

    override fun getMetadata(videoId: String): Metadata? = metadata[videoId]
    override fun putMetadata(videoId: String, metadata: Metadata) {
        this.metadata[videoId] = metadata
    }

    override fun getStream(videoId: String): Stream? = streams[videoId]
    override fun putStream(videoId: String, stream: Stream) {
        streams[videoId] = stream
    }

    override fun invalidateStream(videoId: String) {
        streams.remove(videoId)
    }

    override fun getSegment(videoId: String): Segment? = segments[videoId]
    override fun putSegment(videoId: String, segment: Segment) {
        segments[videoId] = segment
    }
}
