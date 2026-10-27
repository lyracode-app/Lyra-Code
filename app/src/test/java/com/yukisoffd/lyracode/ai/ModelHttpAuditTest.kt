package com.yukisoffd.lyracode.ai

import com.sun.net.httpserver.HttpServer
import com.yukisoffd.lyracode.data.ApiProfile
import com.yukisoffd.lyracode.data.ModelRequestCustomization
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import okio.buffer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class ModelHttpAuditTest {
    private class MemorySink : HttpAuditSink {
        val records = linkedMapOf<Long, MutableMap<String, String>>()
        override fun start(title: String, detail: String): Long = (records.size + 1L).also { records[it] = linkedMapOf("detail" to detail) }
        override fun write(id: Long, section: String, text: String, append: Boolean) {
            val record = records.getValue(id)
            record[section] = (if (append) record[section].orEmpty() else "") + text
        }
    }

    @Test fun capturesActualCustomizedRequestHeadersAndUntruncatedGzipResponse() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val sink = MemorySink()
        val dir = Files.createTempDirectory("audit-test").toFile()
        val audit = ModelHttpAudit(sink, dir)
        val client = OkHttpClient.Builder().addInterceptor(audit.application).addNetworkInterceptor(audit.network).build()
        val responseText = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject()
            .put("content", "输出😀".repeat(150_000)).put("reasoning_content", "思考过程").put("tool_calls", JSONArray())))).toString()
        var receivedBody = ""
        var receivedHeaders = emptyMap<String, List<String>>()
        server.createContext("/chat") { exchange ->
            receivedBody = exchange.requestBody.bufferedReader().readText()
            receivedHeaders = exchange.requestHeaders.toMap()
            val bytes = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(responseText.toByteArray()) } }.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.responseHeaders.add("Content-Encoding", "gzip")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val profile = ApiProfile("test", "test", "fixture-key", "http://127.0.0.1:${server.address.port}", selectedModel = "test", savedModels = listOf("test"),
                modelRequestOverrides = mapOf("test" to ModelRequestCustomization(headers = mapOf("X-Custom" to "injected"), removedHeaders = setOf("X-Remove"), body = """{"extra":{"enabled":true}}""")))
            val payload = JSONObject().put("model", "test").put("messages", JSONArray().put(JSONObject().put("role", "user")
                .put("content", "data:image/png;base64," + "A".repeat(600_000)))).put("tools", JSONArray().put(JSONObject().put("type", "function")))
            applyReasoningDepth(payload, profile, "high")
            val request = Request.Builder().url("${profile.baseUrl}/chat").header("Authorization", "Bearer fixture-key").header("X-Remove", "old")
                .post(payload.toString().toRequestBody("application/json".toMediaType())).build().customizedFor(profile, "test")
            client.newCall(request).execute().use { assertEquals(responseText, it.body!!.string()) }
            val log = sink.records.values.single()
            assertEquals(receivedBody, log["request.body"])
            assertEquals("high", JSONObject(log.getValue("request.body")).getString("reasoning_effort"))
            assertTrue(JSONObject(log.getValue("request.metadata")).getBoolean("observed_at_network"))
            val headers = JSONArray(log.getValue("request.headers"))
            val logged = (0 until headers.length()).associate { headers.getJSONObject(it).getString("name").lowercase() to headers.getJSONObject(it).getString("value") }
            assertEquals("injected", logged["x-custom"])
            assertEquals("Bearer fixture-key", logged["authorization"])
            assertFalse(logged.containsKey("x-remove"))
            for ((name, values) in receivedHeaders) assertEquals(values.joinToString(", "), logged[name.lowercase()])
            assertEquals(responseText, log["response.body"])
            assertEquals("Complete (EOF)", log["response.state"])
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        } finally { server.stop(0); client.connectionPool.evictAll(); dir.deleteRecursively() }
    }

    @Test fun gzippedRequestsAreLoggedAsPlainJsonAndRefusalsAreResentPlain() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val received = mutableListOf<Pair<String?, String>>()
        server.createContext("/chat") { exchange ->
            val encoding = exchange.requestHeaders.getFirst("Content-Encoding")
            val bytes = exchange.requestBody.readBytes()
            val text = if (encoding == "gzip") GZIPInputStream(bytes.inputStream()).readBytes().decodeToString() else bytes.decodeToString()
            received += encoding to text
            val refuse = encoding == "gzip" && exchange.requestURI.path.endsWith("/refuse")
            val reply = (if (refuse) "Unsupported Media Type" else "{}").toByteArray()
            exchange.sendResponseHeaders(if (refuse) 415 else 200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
        val sink = MemorySink()
        val dir = Files.createTempDirectory("audit-gzip").toFile()
        val audit = ModelHttpAudit(sink, dir)
        val client = OkHttpClient.Builder().addInterceptor(audit.application).addInterceptor(GzipRequestInterceptor())
            .addNetworkInterceptor(audit.network).build()
        try {
            val profile = ApiProfile("test", "test", "fixture-key", "http://127.0.0.1:${server.address.port}", selectedModel = "test", savedModels = listOf("test"), gzipRequestBodies = true)
            val payload = JSONObject().put("model", "test").put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "你好 ".repeat(5_000))))
            fun post(path: String) = Request.Builder().url("${profile.baseUrl}$path").header("Authorization", "Bearer fixture-key")
                .post(payload.toString().toRequestBody("application/json".toMediaType())).build().customizedFor(profile, "test")

            client.newCall(post("/chat")).execute().use { assertEquals(200, it.code) }
            val sent = received.single().second
            assertEquals("gzip", received.single().first)
            assertEquals(sent, sink.records.values.single()["request.body"])
            assertTrue(sink.records.values.single().getValue("request.headers").contains("gzip"))

            client.newCall(post("/chat/refuse")).execute().use { assertEquals(200, it.code) }
            assertEquals(listOf("gzip", null), received.drop(1).map { it.first })
            assertEquals(listOf(sent, sent), received.drop(1).map { it.second })
            assertEquals(listOf(sent, sent), sink.records.values.drop(1).map { it["request.body"] })
        } finally { server.stop(0); client.connectionPool.evictAll(); dir.deleteRecursively() }
    }

    @Test fun redirectsAndAutomatic503RetriesRecordEveryRequestBodyWithAndWithoutGzip() {
        for (firstCode in listOf(307, 503)) {
            for (gzip in listOf(true, false)) {
                val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                val received = mutableListOf<Triple<String, String?, String>>()
                server.createContext("/") { exchange ->
                    val encoding = exchange.requestHeaders.getFirst("Content-Encoding")
                    val bytes = exchange.requestBody.readBytes()
                    val text = if (encoding == "gzip") GZIPInputStream(bytes.inputStream()).use { it.readBytes().decodeToString() } else bytes.decodeToString()
                    received += Triple(exchange.requestMethod, encoding, text)
                    val code = if (received.size == 1) firstCode else 200
                    if (code == 307) exchange.responseHeaders.add("Location", "/target")
                    if (code == 503) exchange.responseHeaders.add("Retry-After", "0")
                    val reply = "{}".toByteArray()
                    exchange.sendResponseHeaders(code, reply.size.toLong())
                    exchange.responseBody.use { it.write(reply) }
                }
                server.start()
                val sink = MemorySink()
                val dir = Files.createTempDirectory("audit-followup").toFile()
                val audit = ModelHttpAudit(sink, dir)
                val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).addInterceptor(audit.application)
                    .addInterceptor(GzipRequestInterceptor()).addNetworkInterceptor(audit.network).build()
                try {
                    val profile = ApiProfile("test", "test", "fixture-key", "http://127.0.0.1:${server.address.port}",
                        selectedModel = "test", savedModels = listOf("test"), gzipRequestBodies = gzip)
                    val payload = JSONObject().put("model", "test").put("messages", JSONArray().put(JSONObject()
                        .put("role", "user").put("content", "大请求😀".repeat(80_000))))
                    val request = Request.Builder().url("${profile.baseUrl}/start")
                        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build().customizedFor(profile, "test")
                    client.newCall(request).execute().use { response ->
                        assertEquals(200, response.code)
                        assertEquals("{}", response.body!!.string())
                    }
                    assertEquals(listOf("POST", "POST"), received.map { it.first })
                    assertEquals(listOf(if (gzip) "gzip" else null, if (gzip) "gzip" else null), received.map { it.second })
                    assertEquals(received[0].third, received[1].third)
                    val records = sink.records.values.toList()
                    assertEquals(2, records.size)
                    assertEquals(received.map { it.third }, records.map { it["request.body"] })
                    assertEquals(listOf(firstCode, 200), records.map { JSONObject(it.getValue("response.metadata")).getInt("code") })
                    assertEquals(if (firstCode == 307) "/target" else "/start",
                        java.net.URI(JSONObject(records[1].getValue("request.metadata")).getString("url")).path)
                    assertTrue(records.all { JSONObject(it.getValue("request.metadata")).getBoolean("observed_at_network") })
                    assertEquals("{}", records.last()["response.body"])
                    assertTrue(records.none { it.containsKey("capture.error") })
                } finally { server.stop(0); client.connectionPool.evictAll(); dir.deleteRecursively() }
            }
        }
    }

    @Test fun redirectToGetDoesNotInheritPostBodyInAudit() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.readBytes()
            if (exchange.requestURI.path == "/start") exchange.responseHeaders.add("Location", "/target")
            exchange.sendResponseHeaders(if (exchange.requestURI.path == "/start") 303 else 200, -1)
            exchange.close()
        }
        server.start()
        val sink = MemorySink()
        val dir = Files.createTempDirectory("audit-get-redirect").toFile()
        val audit = ModelHttpAudit(sink, dir)
        val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).addInterceptor(audit.application)
            .addInterceptor(GzipRequestInterceptor()).addNetworkInterceptor(audit.network).build()
        try {
            val request = Request.Builder().url("http://127.0.0.1:${server.address.port}/start")
                .post("x".repeat(10_000).toRequestBody("application/json".toMediaType()))
                .tag(ModelAuditTag::class.java, ModelAuditTag("p", "m"))
                .tag(ApiProfile::class.java, ApiProfile("p", "test", "", "http://127.0.0.1", selectedModel = "m", savedModels = listOf("m"), gzipRequestBodies = true)).build()
            client.newCall(request).execute().close()
            assertEquals(2, sink.records.size)
            val redirected = sink.records.values.last()
            assertEquals("GET", JSONObject(redirected.getValue("request.metadata")).getString("method"))
            assertFalse(redirected.containsKey("request.body"))
        } finally { server.stop(0); client.connectionPool.evictAll(); dir.deleteRecursively() }
    }

    @Test fun sseIsDeliveredBeforeStreamFinishesAndRecordedExactly() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val gate = CountDownLatch(1)
        val first = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"思考\"}}]}\n\n"
        val last = "data: {\"choices\":[{\"delta\":{\"content\":\"完成\"}}]}\n\ndata: [DONE]\n\n"
        server.createContext("/stream") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                out.write(first.toByteArray()); out.flush()
                gate.await(5, TimeUnit.SECONDS)
                out.write(last.toByteArray())
            }
        }
        server.start()
        val dir = Files.createTempDirectory("audit-sse").toFile()
        val sink = MemorySink()
        val audit = ModelHttpAudit(sink, dir)
        val client = OkHttpClient.Builder().readTimeout(3, TimeUnit.SECONDS).addInterceptor(audit.application).addNetworkInterceptor(audit.network).build()
        try {
            val request = Request.Builder().url("http://127.0.0.1:${server.address.port}/stream").tag(ModelAuditTag::class.java, ModelAuditTag("p", "m")).build()
            client.newCall(request).execute().use { response ->
                val source = response.body!!.source()
                assertEquals(first.substringBefore('\n'), source.readUtf8Line())
                gate.countDown()
                source.readUtf8()
            }
            assertEquals(first + last, sink.records.values.single()["response.body"])
        } finally { gate.countDown(); server.stop(0); client.connectionPool.evictAll(); dir.deleteRecursively() }
    }

    @Test fun loggingFailureCannotBreakHttpResponse() {
        val sink = object : HttpAuditSink {
            override fun start(title: String, detail: String): Long = error("disk full")
            override fun write(id: Long, section: String, text: String, append: Boolean) = error("disk full")
        }
        val dir = Files.createTempDirectory("audit-failure").toFile()
        val audit = ModelHttpAudit(sink, dir)
        val client = OkHttpClient.Builder().addInterceptor(audit.application).addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(okhttp3.ResponseBody.create("text/plain".toMediaType(), "still works")).build()
        }.build()
        try {
            val request = Request.Builder().url("http://example.invalid").tag(ModelAuditTag::class.java, ModelAuditTag("p", "m")).build()
            client.newCall(request).execute().use { assertEquals("still works", it.body!!.string()) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun failedResponseReadPreservesPartialContentAndOriginalError() {
        val sink = MemorySink()
        val dir = Files.createTempDirectory("audit-partial").toFile()
        val audit = ModelHttpAudit(sink, dir)
        val partial = "data: {\"delta\":\"partial😀\"}\n\n"
        val source = object : okio.Source {
            var sent = false
            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                if (sent) throw java.io.IOException("fixture disconnect")
                sent = true
                val bytes = partial.toByteArray()
                sink.write(bytes)
                return bytes.size.toLong()
            }
            override fun timeout() = okio.Timeout.NONE
            override fun close() = Unit
        }
        // A forwarding buffered source allows testing a real read failure after some bytes arrived.
        val body = object : okhttp3.ResponseBody() {
            private val wrapped = source.buffer()
            override fun contentType() = "text/event-stream".toMediaType()
            override fun contentLength() = -1L
            override fun source() = wrapped
        }
        val client = OkHttpClient.Builder().addInterceptor(audit.application).addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        }.build()
        try {
            val request = Request.Builder().url("http://example.invalid").tag(ModelAuditTag::class.java, ModelAuditTag("p", "m")).build()
            val failure = runCatching { client.newCall(request).execute().use { it.body!!.string() } }.exceptionOrNull()
            assertEquals("fixture disconnect", failure?.message)
            assertEquals(partial, sink.records.values.single()["response.body"])
            assertTrue(sink.records.values.single().getValue("response.state").startsWith("Partial response:"))
        } finally { dir.deleteRecursively() }
    }
}
