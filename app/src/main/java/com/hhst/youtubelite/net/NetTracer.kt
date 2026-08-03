package com.hhst.youtubelite.net

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.hhst.youtubelite.extractor.Promise
import java.util.ArrayDeque
import kotlinx.coroutines.launch

/** Collects WebView network timing from injected JS. */
class NetTracer(
    private val gson: Gson = Gson(),
    private val slowMs: Long = DEFAULT_SLOW_MS,
    private val cap: Int = DEFAULT_CAP,
) {
    private val lock = Any()
    private val ring = ArrayDeque<Hit>(cap)
    private val script = PageScript(ASSET, TAG)

    val bridge: Bridge = Bridge()

    fun install(context: Context, webView: WebView) {
        webView.addJavascriptInterface(bridge, JS_NAME)
        script.install(context, webView)
    }

    /** Fallback when document-start is unavailable; safe to call repeatedly. */
    fun inject(context: Context, webView: WebView) {
        script.inject(context, webView)
    }

    fun hits(): List<Hit> = synchronized(lock) { ring.toList() }

    fun slow(minMs: Long = slowMs): List<Hit> =
        hits().filter { it.ms >= minMs }

    fun dump(tag: String = TAG) {
        val all = hits()
        val bad = all.filter { it.ms >= slowMs }.sortedByDescending { it.ms }.take(40)
        // Log off the WebView/UI path to avoid jank on page finished.
        Promise.DEFAULT_SCOPE.launch {
            Log.i(tag, "dump total=${all.size} slow=${bad.size} thr=${slowMs}ms")
            bad.forEach { h -> Log.w(tag, format(h)) }
        }
    }

    fun clear() = synchronized(lock) { ring.clear() }

    private fun push(hit: Hit) {
        if (noise(hit.url, hit.src)) return
        synchronized(lock) {
            if (ring.size >= cap) ring.removeFirst()
            ring.addLast(hit)
        }
        val line = format(hit)
        if (hit.ms >= slowMs) Log.w(TAG, line) else Log.d(TAG, line)
    }

    private fun format(h: Hit): String {
        val tag = if (h.ms >= slowMs) "SLOW" else "ok"
        val err = h.err?.let { " err=$it" } ?: ""
        return "$tag ${h.method} ${h.ms}ms #${h.status} ${h.src} ${h.url}$err"
    }

    private fun noise(url: String, src: String): Boolean {
        val u = url.lowercase()
        if (u.isEmpty()) return true
        if (URL_NOISE.any { u.contains(it) }) return true
        if (RESOURCE_NOISE.any { src.startsWith(it) }) return true
        if (src.startsWith("res:") && IMAGE_REGEX.containsMatchIn(u)) return true
        return false
    }

    inner class Bridge {
        @JavascriptInterface
        fun onHit(json: String?) {
            if (json.isNullOrBlank()) return
            val hit = runCatching { gson.fromJson(json, Hit::class.java) }.getOrNull() ?: return
            push(hit)
        }

        @JavascriptInterface
        fun dbg(msg: String?) {
            if (msg.isNullOrBlank()) return
            Log.d(CACHE_TAG, msg)
        }
    }

    data class Hit(
        @SerializedName("id") val id: Long = 0,
        @SerializedName("ts") val ts: Long = 0,
        @SerializedName("src") val src: String = "",
        @SerializedName("method") val method: String = "GET",
        @SerializedName("url") val url: String = "",
        @SerializedName("status") val status: Int = 0,
        @SerializedName("ms") val ms: Long = 0,
        @SerializedName("ok") val ok: Boolean = true,
        @SerializedName("err") val err: String? = null,
    )

    companion object {
        const val TAG = "NetTracer"
        const val CACHE_TAG = "InnertubeCache"
        const val JS_NAME = "NetTrace"
        const val ASSET = "script/net-tracer.js"
        const val DEFAULT_SLOW_MS = 500L
        const val DEFAULT_CAP = 256

        private val URL_NOISE = listOf(
            "doubleclick", "googleads", "/pagead/", "pcs/activeview",
            "pagead/interaction", "pagead/adview", "play.google.com/log",
            "/s/search/audio/", "generate_204", "/api/stats/",
            "youtubei/v1/log_event", "googlevideo.com", "c.youtube.com",
            "videoplayback", "accounts.google.com", "servicelogin",
            "/js/th/", "/s/_/ytmweb/_/js/",
        )
        private val RESOURCE_NOISE = listOf("res:beacon", "res:img", "res:iframe", "res:script")
        private val IMAGE_REGEX = Regex("""\.(jpg|jpeg|png|webp|gif|svg)(\?|$)""", RegexOption.IGNORE_CASE)
    }
}
