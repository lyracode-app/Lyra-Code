package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.ApiProfile
import com.yukisoffd.lyracode.R
import com.yukisoffd.lyracode.uiText
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.GzipSink
import okio.buffer
import java.util.concurrent.ConcurrentHashMap

/** Compress large JSON bodies when the provider configuration enables it.
 * A server that refuses the encoding gets the plain body again, and that host stays plain for this client's lifetime. */
internal class GzipRequestInterceptor : Interceptor {
    private val plainHosts = ConcurrentHashMap.newKeySet<String>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val gzipped = gzippedOrNull(original) ?: return chain.proceed(original)
        val response = chain.proceed(gzipped)
        if (!refusesEncoding(response) || chain.call().isCanceled()) return response
        response.close()
        plainHosts += hostKey(original)
        val plain = chain.proceed(original)
        if (plain.isSuccessful) return plain
        // Preserve the server's error body and status; attach context for user-facing diagnostics.
        return plain.newBuilder().request(plain.request.newBuilder()
            .tag(GzipRequestRefused::class.java, GzipRequestRefused).build()).build()
    }

    private fun gzippedOrNull(request: Request): Request? {
        val profile = request.tag(ApiProfile::class.java) ?: return null
        if (!profile.gzipRequestBodies || profile.isChatGpt || hostKey(request) in plainHosts) return null
        val body = request.body ?: return null
        if (body.isOneShot() || body.isDuplex() || request.header("Content-Encoding") != null) return null
        if (body.contentType()?.subtype?.contains("json", ignoreCase = true) != true) return null
        if (body.contentLength() in 0 until MIN_BYTES) return null
        val plain = Buffer().also(body::writeTo)
        if (plain.size < MIN_BYTES) return null
        val compressed = Buffer().also { sink -> GzipSink(sink).buffer().use { it.writeAll(plain) } }
        return request.newBuilder()
            .header("Content-Encoding", "gzip")
            .method(request.method, compressed.readByteString().toRequestBody(body.contentType()))
            .build()
    }

    /** 415 is an explicit refusal; other codes count only when the error reads like an undecodable body. */
    private fun refusesEncoding(response: Response): Boolean = when (response.code) {
        415 -> true
        400, 403, 422, 500 -> runCatching { response.peekBody(PEEK_BYTES).string().lowercase() }.getOrDefault("")
            .let { text -> REFUSAL_HINTS.any(text::contains) }
        else -> false
    }

    private fun hostKey(request: Request) = "${request.url.host}:${request.url.port}"

    internal companion object {
        const val MIN_BYTES = 4L * 1024
        private const val PEEK_BYTES = 16L * 1024
        // How JSON parsers describe gzip bytes. A false match costs one plain resend; a miss fails every large request.
        private val REFUSAL_HINTS = listOf(
            "content-encoding", "gzip", "unsupported media type",
            "invalid json", "malformed json", "json parse", "json decode", "parse error", "error parsing",
            "could not parse", "failed to parse", "unexpected token", "unexpected character", "invalid character",
        )
    }
}

private object GzipRequestRefused

internal fun Response.withGzipFailureHint(message: String, hint: () -> String = { uiText(R.string.gzip_request_failed_hint) }): String =
    if (request.tag(GzipRequestRefused::class.java) != null) "$message\n${hint()}" else message
