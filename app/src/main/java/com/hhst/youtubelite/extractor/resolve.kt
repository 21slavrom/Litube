package com.hhst.youtubelite.extractor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared `/player` payload for metadata and stream.
 *
 * Fetched at most once, only when a promise needs the network.
 */
internal class PlayerPage private constructor(
    val info: StreamInfo,
    val extractor: StreamExtractor,
) {
    companion object {
        fun shared(
            videoId: String,
            scope: CoroutineScope,
            clientOrder: ClientOrderStore? = null,
        ): () -> PlayerPage {
            val deferred = CompletableDeferred<PlayerPage>()
            val started = AtomicBoolean(false)
            return {
                if (started.compareAndSet(false, true)) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            deferred.complete(fetch(videoId, clientOrder))
                        } catch (t: Throwable) {
                            deferred.completeExceptionally(t)
                        }
                    }
                }
                runBlocking { deferred.await() }
            }
        }

        private fun fetch(videoId: String, clientOrder: ClientOrderStore?): PlayerPage {
            val extractor = ServiceList.YouTube.getStreamExtractor(VideoId.watchUrl(videoId))
            val info = StreamInfo.getInfo(extractor)
            clientOrder?.save()
            return PlayerPage(info, extractor)
        }
    }
}

/** Cache hit or `/player`, then persist [Metadata]. */
internal fun Promise<Metadata>.resolveMetadata(
    videoId: String,
    cache: Cache,
    player: () -> PlayerPage,
) {
    cache.getMetadata(videoId)?.let { cached ->
        set { copyFrom(cached) }
        return
    }
    set { fillFrom(player()) }
    cache.putMetadata(videoId, value)
}

/** Cache hit or `/player`, then persist [Stream]. */
internal fun Promise<Stream>.resolveStream(
    videoId: String,
    cache: Cache,
    player: () -> PlayerPage,
) {
    cache.getStream(videoId)?.let { cached ->
        set { copyFrom(cached) }
        return
    }
    set { fillFrom(player()) }
    cache.putStream(videoId, value)
}

/** Cache hit or `/next`, then persist [Segment]. */
internal fun Promise<Segment>.resolveSegment(
    videoId: String,
    cache: Cache,
) {
    cache.getSegment(videoId)?.let { cached ->
        set { copyFrom(cached) }
        return
    }
    val extractor = ServiceList.YouTube.getStreamExtractor(VideoId.watchUrl(videoId))
    val chapters = StreamInfo.getSegments(extractor).map {
        Chapter(title = it.title.orEmpty(), startSeconds = it.startTimeSeconds)
    }
    set { this.chapters = chapters }
    cache.putSegment(videoId, value)
}

private fun Metadata.fillFrom(page: PlayerPage) {
    val info = page.info
    val extractor = page.extractor
    id = info.id
    title = info.name.orEmpty()
    author = runCatching { extractor.uploaderName }.getOrNull()
    description = runCatching { extractor.description.content }.getOrNull()
    duration = runCatching { extractor.length }.getOrDefault(0L).coerceAtLeast(0L)
    thumbnailUrl = bestImageUrl(runCatching { extractor.thumbnails }.getOrDefault(emptyList()))
        ?: "https://img.youtube.com/vi/${info.id}/hqdefault.jpg"
    likeCount = runCatching { extractor.likeCount }.getOrDefault(-1L)
    dislikeCount = runCatching { extractor.dislikeCount }.getOrDefault(-1L)
    uploadedAt = runCatching {
        extractor.uploadDate?.instant?.toEpochMilli()
    }.getOrNull()
    uploaderUrl = runCatching { extractor.uploaderUrl }.getOrNull()
    uploaderAvatarUrl = bestImageUrl(
        runCatching { extractor.uploaderAvatars }.getOrDefault(emptyList()),
    )
    viewCount = runCatching { extractor.viewCount }.getOrDefault(-1L)
}

private fun Stream.fillFrom(page: PlayerPage) {
    val info = page.info
    formats = buildList {
        info.videoOnlyStreams.forEach { add(it.toFormat(videoOnly = true)) }
        info.audioStreams.forEach { add(it.toFormat()) }
        info.videoStreams.forEach { add(it.toFormat()) }
    }
        .filter { it.url.isNotBlank() }
        .sortedWith(
            compareByDescending<Format> { it.height }
                .thenByDescending { it.bitrate },
        )
    subtitles = runCatching { page.extractor.subtitlesDefault }
        .getOrDefault(emptyList())
        .mapNotNull { track ->
            val url = track.content.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Subtitle(
                url = url,
                languageCode = track.languageTag.orEmpty(),
                autoGenerated = track.isAutoGenerated,
            )
        }
    dashUrl = info.dashMpdUrl?.takeIf { it.isNotBlank() }
    hlsUrl = info.hlsUrl?.takeIf { it.isNotBlank() }
}

private fun VideoStream.toFormat(videoOnly: Boolean = false): Format =
    Format(
        url = content,
        height = height,
        width = width,
        bitrate = bitrate,
        fps = fps,
        qualityLabel = getResolution(),
        codec = codec,
        format = format?.name,
        itag = itag.takeIf { it > 0 },
        videoOnly = videoOnly,
    )

private fun AudioStream.toFormat(): Format =
    Format(
        url = content,
        bitrate = if (averageBitrate > 0) averageBitrate else bitrate,
        codec = codec,
        format = format?.name,
        itag = itag.takeIf { it > 0 },
        audioOnly = true,
    )

private fun bestImageUrl(images: List<Image>): String? {
    if (images.isEmpty()) return null
    val rank = mapOf(
        Image.ResolutionLevel.HIGH to 3,
        Image.ResolutionLevel.MEDIUM to 2,
        Image.ResolutionLevel.LOW to 1,
        Image.ResolutionLevel.UNKNOWN to 0,
    )
    return images.maxByOrNull { rank[it.estimatedResolutionLevel] ?: 0 }?.url
}
