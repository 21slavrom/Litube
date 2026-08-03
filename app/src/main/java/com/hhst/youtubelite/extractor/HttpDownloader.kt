package com.hhst.youtubelite.extractor

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.hhst.youtubelite.core.Constants
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** OkHttp [Downloader] for NewPipe extraction traffic. */
class HttpDownloader(
    client: OkHttpClient,
    private val playerCache: PlayerCache? = null,
) : Downloader() {

    private val http = client.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val method = request.httpMethod() ?: "GET"
        val url = request.url()

        val builder = okhttp3.Request.Builder()
            .url(url)
            .method(method, request.dataToSend()?.toRequestBody())
            .header("User-Agent", Constants.USER_AGENT)

        request.headers()?.forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { value -> builder.addHeader(name, value) }
        }

        val cache = playerCache
        val playerId = webPlayerId(url, method, request.dataToSend())
        return if (cache != null && playerId != null) {
            cache.tracking(playerId) { fetch(builder, url, cache, playerId) }
        } else {
            fetch(builder, url, cache, playerId)
        }
    }

    private fun fetch(
        builder: okhttp3.Request.Builder,
        url: String,
        cache: PlayerCache?,
        playerId: String?,
    ): Response {
        http.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 429) {
                throw ReCaptchaException("HTTP 429", url)
            }
            if (cache != null && playerId != null && response.isSuccessful) {
                cacheIfPlayable(cache, playerId, body)
            }
            val headers = LinkedHashMap<String, List<String>>()
            response.headers.forEach { (name, value) ->
                headers.merge(name, listOf(value)) { left, right -> left + right }
            }
            return Response(
                response.code,
                response.message,
                headers,
                body,
                response.request.url.toString(),
            )
        }
    }

    /** Video id of a WEB-client /player POST; null for anything not reusable by the page. */
    private fun webPlayerId(url: String, method: String, bodyBytes: ByteArray?): String? {
        if (!method.equals("POST", ignoreCase = true)) return null
        if (!url.contains(PLAYER_PATH, ignoreCase = true)) return null
        return try {
            val json = gson.fromJson(
                String(bodyBytes ?: return null, StandardCharsets.UTF_8),
                JsonObject::class.java,
            )
            val clientName = json.getAsJsonObject("context")
                ?.getAsJsonObject("client")
                ?.get("clientName")?.asString
            if (clientName != WEB_CLIENT) return null
            VideoId.parse(json.get("videoId")?.asString)
        } catch (_: Exception) {
            null
        }
    }

    /** Caches only playable responses; error payloads must not be served to the page. */
    private fun cacheIfPlayable(cache: PlayerCache, videoId: String, body: String) {
        val playable = runCatching {
            gson.fromJson(body, JsonObject::class.java)
                .getAsJsonObject("playabilityStatus")
                ?.get("status")?.asString == "OK"
        }.getOrDefault(false)
        if (playable) {
            cache.put(videoId, body.toByteArray(StandardCharsets.UTF_8))
            Log.d(TAG, "player response cached $videoId")
        }
    }

    private companion object {
        const val TAG = "HttpDownloader"
        const val PLAYER_PATH = "/youtubei/v1/player"
        const val WEB_CLIENT = "WEB"
    }
}
