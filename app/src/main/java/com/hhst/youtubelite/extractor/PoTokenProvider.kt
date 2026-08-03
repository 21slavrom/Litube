package com.hhst.youtubelite.extractor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider as NpPoTokenProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * WEB [NpPoTokenProvider] via a hidden WebView (`assets/potoken/potoken.js`).
 *
 * [getWebClientPoToken] mints synchronously on miss (blocks the caller).
 * Do not call from the main thread. Per-video results are cached in memory;
 * the video-independent parts (visitor data, integrity, streaming token) are
 * shared across videos until their TTLs lapse. Call [warmUp] off the main
 * thread at app start to move that cost out of the first video switch.
 */
class PoTokenProvider(
    context: Context,
    private val http: OkHttpClient,
) : NpPoTokenProvider {

    private val app = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private val mintLock = Any()
    private val ready = CountDownLatch(1)
    private val requestSeq = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val cache = ConcurrentHashMap<String, PoTokenResult>()

    @Volatile private var webView: WebView? = null
    @Volatile private var integrity: TtlValue<Unit>? = null
    @Volatile private var visitor: TtlValue<String>? = null
    @Volatile private var streaming: TtlValue<String>? = null

    /** Value with a capture time, readable while younger than a TTL. */
    private class TtlValue<T>(val value: T, val at: Long = System.currentTimeMillis()) {
        fun get(ttlMs: Long): T? = if (System.currentTimeMillis() - at < ttlMs) value else null
    }

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        cache[videoId]?.let { return it }
        // NewPipe calls this on the extraction thread.
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        return synchronized(mintLock) {
            cache[videoId]?.let { return it }
            runCatching { mint(videoId) }
                .onFailure { Log.w(TAG, "mint failed for $videoId", it) }
                .getOrNull()
        }
    }

    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = null

    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = null

    override fun getIosClientPoToken(videoId: String): PoTokenResult? = null

    /** Warms the video-independent pipeline (integrity, visitor, streaming). */
    fun warmUp() {
        if (Looper.myLooper() == Looper.getMainLooper()) return
        runCatching {
            synchronized(mintLock) {
                if (!ensureReady() || !ensureIntegrity()) return@runCatching
                ensureVisitor()?.let { ensureStreaming(it) }
            }
        }.onFailure { Log.w(TAG, "warm-up failed", it) }
    }

    /** Caller must hold [mintLock]. Only the player token is video-specific. */
    private fun mint(videoId: String): PoTokenResult? {
        if (!ensureReady()) return null
        if (!ensureIntegrity()) return null
        val visitorData = ensureVisitor() ?: return null
        val player = mintToken(videoId) ?: return null
        val streamingToken = ensureStreaming(visitorData) ?: player
        return PoTokenResult(visitorData, player, streamingToken).also { cache[videoId] = it }
    }

    private fun ensureReady(): Boolean {
        initialize()
        val up = try {
            ready.await(INIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!up) return false
        val hasPoToken = eval(
            "(function(){try{return window.__poToken?'ok':'no'}catch(e){return 'err'}})()",
            INIT_MS,
        )
        return hasPoToken == "ok"
    }

    private fun ensureIntegrity(): Boolean {
        if (integrity?.get(INTEGRITY_TTL_MS) != null) return true
        if (!initIntegrity()) return false
        integrity = TtlValue(Unit)
        return true
    }

    private fun ensureVisitor(): String? {
        visitor?.get(VISITOR_TTL_MS)?.let { return it }
        val fetched = runCatching {
            val info = InnertubeClientRequestInfo.ofWebClient()
            info.clientInfo.clientVersion = YoutubeParsingHelper.getClientVersion()
            YoutubeParsingHelper.getVisitorDataFromInnertube(
                info,
                Localization.DEFAULT,
                ContentCountry.DEFAULT,
                YoutubeParsingHelper.getYouTubeHeaders(),
                YoutubeParsingHelper.YOUTUBEI_V1_URL,
                null,
                false,
            )
        }.getOrNull() ?: return null
        visitor = TtlValue(fetched)
        return fetched
    }

    private fun ensureStreaming(visitorData: String): String? {
        streaming?.get(STREAM_TTL_MS)?.let { return it }
        val minted = mintToken(visitorData) ?: return null
        streaming = TtlValue(minted)
        return minted
    }

    /** Creates the host WebView early. Safe on any thread. */
    fun initialize() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            createWebView()
        } else {
            mainHandler.post { createWebView() }
        }
    }

    private fun initIntegrity(): Boolean {
        val createBody = JsonArray().apply { add(REQUEST_KEY) }
        val createResp = botguardPost(
            "https://www.youtube.com/api/jnn/v1/Create",
            createBody.toString(),
        ) ?: return false

        val botguard = bridgeCall(INIT_MS) { id ->
            "(function(){try{window.__poToken.runInit(${gson.toJson(createResp)},${gson.toJson(id)});" +
                "return 'ok'}catch(e){return 'err:'+e}})()"
        } ?: return false

        val genBody = JsonArray().apply {
            add(REQUEST_KEY)
            add(botguard)
        }
        val genResp = botguardPost(
            "https://www.youtube.com/api/jnn/v1/GenerateIT",
            genBody.toString(),
        ) ?: return false

        val token = runCatching {
            JsonParser.parseString(genResp).asJsonArray[0].asString
        }.getOrNull() ?: return false

        val set = eval(
            "(function(){try{return window.__poToken.setIntegrityToken(${gson.toJson(token)})?'ok':'no'}" +
                "catch(e){return 'err'}})()",
            INIT_MS,
        )
        return set == "ok"
    }

    private fun mintToken(identifier: String): String? = bridgeCall(MINT_MS) { id ->
        "(function(){try{window.__poToken.mint(${gson.toJson(identifier)},${gson.toJson(id)});" +
            "return 'ok'}catch(e){return 'err'}})()"
    }

    /**
     * Eval [script] (which must return "ok" when queued) then wait for Bridge
     * onSuccess/onError for the generated request id.
     */
    private fun bridgeCall(timeoutMs: Long, script: (requestId: String) -> String): String? {
        val requestId = requestSeq.incrementAndGet().toString()
        val deferred = CompletableDeferred<String>()
        pending.put(requestId, deferred)?.cancel()
        if (eval(script(requestId), timeoutMs) != "ok") {
            pending.remove(requestId)?.cancel()
            return null
        }
        return runBlocking {
            withTimeoutOrNull(timeoutMs.milliseconds) { deferred.await() }
        }
    }

    private fun botguardPost(url: String, jsonBody: String): String? {
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON_PROTOBUF))
            .header("User-Agent", DESKTOP_UA)
            .header("Content-Type", "application/json+protobuf")
            .header("x-goog-api-key", API_KEY)
            .header("x-user-agent", "grpc-web-javascript/0.1")
            .build()
        return runCatching {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        }.getOrNull()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        if (webView != null) return
        val view = WebView(app)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = false
        view.settings.blockNetworkLoads = true
        view.settings.userAgentString = DESKTOP_UA
        view.addJavascriptInterface(Bridge(), JS_BRIDGE)
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView?, url: String?) {
                ready.countDown()
            }
        }
        webView = view
        val script = app.assets.open("potoken/potoken.js").use { it.readBytes().decodeToString() }
        view.loadDataWithBaseURL(
            "https://www.youtube.com",
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><script>$script</script></head><body></body></html>",
            "text/html",
            "utf-8",
            null,
        )
    }

    private fun eval(script: String, timeoutMs: Long): String? {
        val deferred = CompletableDeferred<String?>()
        val started = AtomicBoolean(false)
        val start = Runnable {
            if (!started.compareAndSet(false, true)) return@Runnable
            val view = webView
            if (view == null) {
                deferred.complete(null)
            } else {
                view.evaluateJavascript(script) { raw ->
                    if (raw == null || raw == "null") {
                        deferred.complete(null)
                        return@evaluateJavascript
                    }
                    deferred.complete(
                        runCatching {
                            val el = JsonParser.parseString(raw)
                            when {
                                el.isJsonNull -> null
                                el.isJsonPrimitive && el.asJsonPrimitive.isString -> el.asString
                                el.isJsonPrimitive -> el.asJsonPrimitive.toString()
                                else -> el.toString()
                            }
                        }.getOrDefault(raw),
                    )
                }
            }
        }
        // BotGuard JS shares the UI thread with the page. Run it during main-thread
        // idle to avoid contending with interaction; the delayed post guarantees it
        // still runs when the looper never goes idle.
        Looper.getMainLooper().queue.addIdleHandler { start.run(); false }
        mainHandler.postDelayed(start, IDLE_FALLBACK_MS)
        return runBlocking {
            withTimeoutOrNull(timeoutMs.milliseconds) { deferred.await() }
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onSuccess(requestId: String, value: String) {
            pending.remove(requestId)?.complete(value)
        }

        @JavascriptInterface
        fun onError(requestId: String, error: String) {
            pending.remove(requestId)?.completeExceptionally(IllegalStateException(error))
        }
    }

    private companion object {
        const val TAG = "PoToken"
        const val JS_BRIDGE = "PoTokenBridge"
        const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        const val API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        const val INIT_MS = 4_000L
        const val MINT_MS = 3_000L
        const val IDLE_FALLBACK_MS = 1_000L

        // Conservative TTLs; shorten if googlevideo starts rejecting stale tokens.
        val INTEGRITY_TTL_MS = TimeUnit.HOURS.toMillis(6)
        val VISITOR_TTL_MS = TimeUnit.HOURS.toMillis(6)
        val STREAM_TTL_MS = TimeUnit.MINUTES.toMillis(30)

        val JSON_PROTOBUF = "application/json+protobuf".toMediaType()
    }
}
