package com.hhst.youtubelite.browser

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import com.google.gson.Gson
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extractor.Extractor
import com.hhst.youtubelite.extractor.Promise
import com.hhst.youtubelite.extractor.VideoId
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch

/** WebView JS bridge (`Bridge` / `lite`). */
class Bridge(
    private val onOpenTab: (url: String) -> Unit,
    private val onOpenExtension: () -> Unit,
    private val extensionManager: ExtensionManager,
    private val extractor: Extractor? = null,
    private val gson: Gson = Gson(),
) {
    private val main = Handler(Looper.getMainLooper())
    private val lastVideo = AtomicReference<String?>(null)
    private val pendingVideo = AtomicReference<String?>(null)

    @JavascriptInterface
    fun openTab(url: String?) {
        if (url.isNullOrBlank()) return
        if (!UrlPolicy.isAllowedUrl(url)) return
        if (PageKind.of(url) == "unknown") return
        main.post { onOpenTab(url) }
    }

    @JavascriptInterface
    fun extension() {
        main.post(onOpenExtension)
    }

    @JavascriptInterface
    fun getPreferences(): String = gson.toJson(extensionManager.allPreferences())

    /**
     * Video id switch from watch-id.js.
     * Starts Innertube extract to warm the cache for the player.
     */
    @JavascriptInterface
    fun onVideoChanged(id: String?) {
        val videoId = VideoId.parse(id) ?: return
        val prev = lastVideo.getAndSet(videoId)
        if (prev == videoId) return
        val ex = extractor ?: return
        // Stay off the WebView binder path for heavy startup work.
        Promise.DEFAULT_SCOPE.launch {
            try {
                ex.extract(videoId)
            } catch (e: Exception) {
                Log.w(TAG, "prefetch fail $videoId", e)
            }
        }
    }

    /**
     * Reports the video id of a /player POST the page is about to send, so the
     * request interceptor can serve the shared response. Null clears the match.
     */
    @JavascriptInterface
    fun onPlayerRequest(id: String?) {
        pendingVideo.set(VideoId.parse(id))
    }

    /** Video id of the pending page /player request, if known. */
    val pendingVideoId: String?
        get() = pendingVideo.get()

    companion object {
        const val NAME = "Bridge"
        private const val TAG = "Bridge"
    }
}
