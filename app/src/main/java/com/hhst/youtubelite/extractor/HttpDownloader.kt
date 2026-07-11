package com.hhst.youtubelite.extractor

import com.hhst.youtubelite.core.Constants
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit

/** OkHttp [Downloader] for NewPipe extraction traffic. */
class HttpDownloader(
    client: OkHttpClient,
) : Downloader() {

    private val http = client.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

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

        http.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 429) {
                throw ReCaptchaException("HTTP 429", url)
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
}
