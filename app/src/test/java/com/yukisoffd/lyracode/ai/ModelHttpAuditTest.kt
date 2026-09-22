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
