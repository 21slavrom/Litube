package com.hhst.youtubelite.extractor

/**
 * Reads mem first, then disk. Writes fill both so player hits stay hot in RAM.
 */
class LayeredCache(
    private val mem: Cache,
    private val disk: Cache,
) : Cache {

    override fun getMetadata(videoId: String): Metadata? {
        mem.getMetadata(videoId)?.let { return it }
        return disk.getMetadata(videoId)?.also { mem.putMetadata(videoId, it) }
    }

    override fun putMetadata(videoId: String, metadata: Metadata) {
        mem.putMetadata(videoId, metadata)
        disk.putMetadata(videoId, metadata)
    }

    override fun getStream(videoId: String): Stream? {
        mem.getStream(videoId)?.let { return it }
        return disk.getStream(videoId)?.also { mem.putStream(videoId, it) }
    }

    override fun putStream(videoId: String, stream: Stream) {
        mem.putStream(videoId, stream)
        disk.putStream(videoId, stream)
    }

    override fun invalidateStream(videoId: String) {
        mem.invalidateStream(videoId)
        disk.invalidateStream(videoId)
    }

    override fun getSegment(videoId: String): Segment? {
        mem.getSegment(videoId)?.let { return it }
        return disk.getSegment(videoId)?.also { mem.putSegment(videoId, it) }
    }

    override fun putSegment(videoId: String, segment: Segment) {
        mem.putSegment(videoId, segment)
        disk.putSegment(videoId, segment)
    }
}
