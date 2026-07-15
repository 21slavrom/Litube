package com.hhst.youtubelite.extractor

import kotlinx.coroutines.CoroutineScope
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider as NpPoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * YouTube extraction entry point.
 *
 * [extract] returns immediately; work runs on [scope]. Prefer [DiskCache] in production.
 */
class Extractor(
    downloader: HttpDownloader,
    private val cache: Cache,
    poToken: NpPoTokenProvider? = null,
    private val clientOrder: ClientOrderStore? = null,
    private val scope: CoroutineScope = Promise.DEFAULT_SCOPE,
) {
    private val inFlight = ConcurrentHashMap<String, Extraction>()

    init {
        NewPipe.init(downloader)
        if (poToken != null) {
            YoutubeStreamExtractor.setPoTokenProvider(poToken)
        }
        YoutubeStreamExtractor.setFetchIosClient(false)
        clientOrder?.load()
    }

    /**
     * Starts extraction for [urlOrId]. Reuses an in-flight [Extraction] for the same id.
     *
     * @throws IllegalArgumentException if [urlOrId] is not a valid id or URL
     */
    fun extract(urlOrId: String): Extraction {
        val id = VideoId.parse(urlOrId)
            ?: throw IllegalArgumentException("Invalid YouTube url or id: $urlOrId")
        return inFlight.computeIfAbsent(id) {
            val player = PlayerPage.shared(id, scope, clientOrder)
            val extraction = Extraction(
                videoId = id,
                metadata = Promise(Metadata(), scope) {
                    it.resolveMetadata(id, cache, player)
                },
                stream = Promise(Stream(), scope) {
                    it.resolveStream(id, cache, player)
                },
                segment = Promise(Segment(), scope) { it.resolveSegment(id, cache) },
            )

            val remaining = AtomicInteger(3)
            val release = {
                if (remaining.decrementAndGet() == 0) {
                    inFlight.remove(id, extraction)
                }
            }
            extraction.metadata.whenDone(release)
            extraction.stream.whenDone(release)
            extraction.segment.whenDone(release)

            extraction
        }
    }
}
