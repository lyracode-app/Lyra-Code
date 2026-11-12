package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.ApiProfile
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.GzipSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class GzipRequestInterceptorTest {
    private val profile = ApiProfile("p", "Provider", "main", "https://example.com/v1", selectedModel = "model",
        savedModels = listOf("model"), gzipRequestBodies = true)
    private val json = "application/json; charset=utf-8".toMediaType()
    private val large = """{"messages":"${"hello ".repeat(2000)}"}"""

    private class Seen(val encoding: String?, val length: Long, val body: String, val key: String?)

    private fun client(interceptor: GzipRequestInterceptor, seen: MutableList<Seen>, outer: Interceptor? = null,
                       reply: (Seen) -> Pair<Int, String> = { 200 to "ok" }) =
        OkHttpClient.Builder().apply { outer?.let { addInterceptor(it) } }.addInterceptor(interceptor).addInterceptor { chain ->
            val request = chain.request()
            val raw = Buffer().also { request.body!!.writeTo(it) }
            val encoding = request.header("Content-Encoding")
            val text = if (encoding == "gzip") GzipSource(raw).buffer().readUtf8() else raw.readUtf8()
            val entry = Seen(encoding, request.body!!.contentLength(), text, request.header("Authorization")?.removePrefix("Bearer ")).also(seen::add)
            val (code, body) = reply(entry)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("test")
                .code(code).body(body.toResponseBody()).build()
        }.build()

    private fun post(body: String, profile: ApiProfile? = this.profile, url: String = profile?.chatEndpoint ?: "https://example.com/v1/chat/completions",
                     contentType: MediaType = json, build: Request.Builder.() -> Unit = {}): Request {
        val request = Request.Builder().url(url).post(body.toRequestBody(contentType)).apply(build).build()
        return if (profile == null) request else request.withProviderKeys(profile)
    }

    @Test fun largeJsonBodiesAreGzippedWithAKnownLengthAndRoundTrip() {
        val seen = mutableListOf<Seen>()
        client(GzipRequestInterceptor(), seen).newCall(post(large)).execute().use { assertEquals(200, it.code) }
        assertEquals("gzip", seen.single().encoding)
        assertEquals(large, seen.single().body)
        assertTrue(seen.single().length in 1 until large.length)
    }

    @Test fun bodiesBelowTheThresholdStayPlain() {
        val seen = mutableListOf<Seen>()
        val small = "x".repeat((GzipRequestInterceptor.MIN_BYTES - 1).toInt())
        client(GzipRequestInterceptor(), seen).newCall(post(small)).execute().close()
        assertNull(seen.single().encoding)
        assertEquals(small, seen.single().body)
    }

    @Test fun ineligibleRequestsAreSentUnchanged() {
        val chatGpt = profile.copy(chatGptAccountId = "account")
        val cases = listOf(
            post(large) { header("Content-Encoding", "br") },
            post(large, contentType = "text/plain".toMediaType()),
            post(large, profile = null),
            post(large, profile = profile.copy(gzipRequestBodies = false)),
            post(large, profile = chatGpt, url = "https://example.com/v1/responses"),
            post(large).newBuilder().post(object : RequestBody() {
                override fun contentType() = json
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) { sink.writeUtf8(large) }
            }).build(),
        )
        for (request in cases) {
            val seen = mutableListOf<Seen>()
            client(GzipRequestInterceptor(), seen).newCall(request).execute().close()
            assertEquals(request.header("Content-Encoding"), seen.single().encoding)
            assertEquals(large, seen.single().body)
        }
    }

    @Test fun aRefusedEncodingIsResentPlainAndTheHostStaysPlain() {
        val refusals = listOf(
            415 to "Unsupported Media Type",
            400 to """{"error":{"message":"corrupt gzip body: incorrect header check"}}""",
            400 to "We could not parse the JSON body of your request.",
            400 to "invalid character '\u001f' looking for beginning of value",
            400 to """{"detail":"There was an error parsing the body"}""",
            422 to """{"detail":[{"type":"json_invalid","msg":"JSON decode error"}]}""",
            500 to "[json.exception.parse_error.101] parse error at line 1, column 1",
            403 to "Content-Encoding not allowed",
        )
        for ((status, error) in refusals) {
            val seen = mutableListOf<Seen>()
            val client = client(GzipRequestInterceptor(), seen) { if (it.encoding == "gzip") status to error else 200 to "ok" }
            client.newCall(post(large)).execute().use { assertEquals(200, it.code); assertEquals("ok", it.body!!.string()) }
            assertEquals(listOf("gzip", null), seen.map { it.encoding })
            assertEquals(listOf(large, large), seen.map { it.body })

            client.newCall(post(large)).execute().close()
            assertNull(seen[2].encoding)
            client.newCall(post(large, url = "https://other.example.com/v1/chat/completions")).execute().close()
            assertEquals("gzip", seen[3].encoding)
        }
    }

    @Test fun otherErrorsAreReturnedWithoutARetry() {
        for ((status, error) in listOf(400 to """{"error":{"message":"model not found"}}""", 400 to "Invalid schema for response_format json_schema",
            400 to "malformed tool call arguments", 403 to "forbidden", 401 to "bad key", 422 to "messages: field required", 500 to "internal error")) {
            val seen = mutableListOf<Seen>()
            client(GzipRequestInterceptor(), seen) { status to error }.newCall(post(large)).execute().use {
                assertEquals(status, it.code)
                assertEquals(error, it.body!!.string())
            }
            assertEquals(listOf("gzip"), seen.map { it.encoding })
        }
    }

    @Test fun failedPlainFallbackPreservesServerErrorAndAddsGzipDiagnostic() {
        val seen = mutableListOf<Seen>()
        val error = """{"error":{"message":"unsupported request encoding"}}"""
        val client = client(GzipRequestInterceptor(), seen) { 415 to error }
        client.newCall(post(large)).execute().use { response ->
            assertEquals(415, response.code)
            assertEquals(error, response.body!!.string())
            assertEquals("HTTP 415\nGzip may be unsupported; disable it in service settings.",
                response.withGzipFailureHint("HTTP 415") { "Gzip may be unsupported; disable it in service settings." })
        }
        assertEquals(listOf("gzip", null), seen.map { it.encoding })
        assertEquals(listOf(large, large), seen.map { it.body })
    }

    @Test fun successfulFallbackAndUncompressedErrorsDoNotBlameGzip() {
        for (gzip in listOf(false, true)) {
            val seen = mutableListOf<Seen>()
            val client = client(GzipRequestInterceptor(), seen) { if (gzip && it.encoding == null) 200 to "ok" else 415 to "unsupported" }
            client.newCall(post(large, profile = profile.copy(gzipRequestBodies = gzip))).execute().use { response ->
                assertEquals(if (gzip) 200 else 415, response.code)
                assertEquals("original", response.withGzipFailureHint("original") { error("No gzip diagnostic should be shown") })
            }
        }
    }

    @Test fun onlyAnthropicProfilesCompressByDefaultAndOtherFormatsCanOptIn() {
        for (format in listOf(ApiProfile.API_FORMAT_OPENAI, ApiProfile.API_FORMAT_ANTHROPIC, ApiProfile.API_FORMAT_GEMINI)) {
            val defaultProfile = ApiProfile("p", "Provider", "key", "https://example.com/v1", apiFormat = format,
                selectedModel = "model", savedModels = listOf("model"))
            val seen = mutableListOf<Seen>()
            client(GzipRequestInterceptor(), seen).newCall(post(large, profile = defaultProfile)).execute().close()
            assertEquals(if (format == ApiProfile.API_FORMAT_ANTHROPIC) "gzip" else null, seen.single().encoding)
        }
    }

    @Test fun aRefusalIsResentPlainBeforeProviderKeysRotate() {
        val keyed = profile.copy(secondaryApiKeys = listOf("backup"))
        for ((error, expected) in listOf("Content-Encoding not allowed" to listOf("main" to "gzip", "main" to null),
            "forbidden" to listOf("main" to "gzip", "backup" to "gzip"))) {
            val seen = mutableListOf<Seen>()
            client(GzipRequestInterceptor(), seen, outer = ProviderKeyInterceptor()) { (if (seen.size == 1) 403 else 200) to error }
                .newCall(post(large, profile = keyed) { header("Authorization", "Bearer main") }).execute().use { assertEquals(200, it.code) }
            assertEquals(expected, seen.map { it.key to it.encoding })
        }
    }
}
