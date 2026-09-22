package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.AuditLogStore
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

internal data class ModelAuditTag(val profileId: String, val model: String)
internal interface HttpAuditSink {
    fun start(title: String, detail: String): Long
    fun write(id: Long, section: String, text: String, append: Boolean = false)
}

internal class StoreHttpAuditSink(private val store: AuditLogStore) : HttpAuditSink {
    override fun start(title: String, detail: String) = store.add("model_http", title, detail)
    override fun write(id: Long, section: String, text: String, append: Boolean) = store.section(id, section, text, append)
}

private class HttpAuditExchange(var id: Long, var exchanges: Int = 0)

/** Application interception captures decoded JSON/SSE as it is consumed, without delaying streaming.
 * Network interception observes the final headers added by OkHttp, after model customization. */
internal class ModelHttpAudit(private val sink: HttpAuditSink, private val tempDirectory: File) {
    private fun write(id: Long, section: String, value: String, append: Boolean = false) {
        runCatching { sink.write(id, section, value, append) }
    }

    private fun start(request: Request): Long = runCatching {
        val tag = request.tag(ModelAuditTag::class.java)
        sink.start("${tag?.model.orEmpty()} · ${request.method} ${request.url.host}",
            "profile=${tag?.profileId.orEmpty()}\nmodel=${tag?.model.orEmpty()}\n${request.method} ${request.url}")
    }.getOrDefault(-1L)

    private fun request(id: Long, request: Request, observed: Boolean) {
        write(id, "request.metadata", JSONObject().put("method", request.method).put("url", request.url.toString())
            .put("observed_at_network", observed).toString())
        write(id, "request.headers", headers(request.headers))
        runCatching {
            val body = request.body ?: return@runCatching
            if (body.isDuplex() || body.isOneShot()) {
                write(id, "request.body", "[non-repeatable body; not read by logger]")
            } else {
                val buffer = Buffer()
                body.writeTo(buffer)
                write(id, "request.body", buffer.readString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8))
            }
        }.onFailure { write(id, "capture.error", it.toString()) }
    }

    val network = Interceptor { chain ->
        val request = chain.request()
        val exchange = request.tag(HttpAuditExchange::class.java) ?: return@Interceptor chain.proceed(request)
        if (exchange.exchanges++ > 0) exchange.id = start(request)
        val id = exchange.id
        request(id, request, true)
        val response = chain.proceed(request)
        write(id, "response.metadata", JSONObject().put("code", response.code).put("message", response.message)
            .put("protocol", response.protocol.toString()).toString())
        write(id, "response.headers", headers(response.headers))
        write(id, "response.state", "Headers received; body pending (redirect/retry bodies are not consumed by the model).")
        response
    }

    val application = Interceptor { chain ->
        val original = chain.request()
        if (original.tag(ModelAuditTag::class.java) == null) return@Interceptor chain.proceed(original)
        val exchange = HttpAuditExchange(start(original))
        val started = System.nanoTime()
        request(exchange.id, original, false)
        write(exchange.id, "response.state", "Connecting")
        try {
            val response = chain.proceed(original.newBuilder().tag(HttpAuditExchange::class.java, exchange).build())
            val id = exchange.id
            val body = response.body
            if (body == null) {
                write(id, "response.state", "No response body")
                response
            } else {
                write(id, "response.state", "Reading response stream")
                capture(response, body, id, started)
            }
        } catch (error: IOException) {
            write(exchange.id, "response.state", "Failed: $error")
            throw error
        }
    }

    private fun capture(response: Response, body: ResponseBody, id: Long, started: Long): Response {
        val file = runCatching { tempDirectory.mkdirs(); File.createTempFile("http-audit-", ".tmp", tempDirectory) }.getOrNull()
            ?: return response.also { write(id, "capture.error", "Unable to create response capture file") }
        val output = runCatching { file.outputStream().buffered() }.getOrElse {
            file.delete()
            write(id, "capture.error", it.toString())
            return response
        }
        var finished = false
        var failed = false
        fun finish(state: String) {
            if (finished) return
            finished = true
            runCatching {
                output.close()
                file.bufferedReader(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8).use { reader ->
                    val chars = CharArray(16_384)
                    var pending = ""
                    while (true) {
                        val count = reader.read(chars)
                        if (count < 0) break
                        var text = pending + String(chars, 0, count)
                        pending = if (text.lastOrNull()?.isHighSurrogate() == true) text.takeLast(1) else ""
                        if (pending.isNotEmpty()) text = text.dropLast(1)
                        write(id, "response.body", text, true)
                    }
                    if (pending.isNotEmpty()) write(id, "response.body", pending, true)
                }
            }.onFailure { write(id, "capture.error", it.toString()) }
            file.delete()
            write(id, "response.state", state)
            write(id, "duration_ms", ((System.nanoTime() - started) / 1_000_000).toString())
        }
        val source = object : ForwardingSource(body.source()) {
            override fun read(sinkBuffer: Buffer, byteCount: Long): Long {
                val offset = sinkBuffer.size
                return try {
                    val read = super.read(sinkBuffer, byteCount)
                    if (read > 0 && !finished) {
                        runCatching {
                            val copy = Buffer()
                            sinkBuffer.copyTo(copy, offset, read)
                            output.write(copy.readByteArray())
                        }.onFailure { failed = true; finish("Capture failed: $it") }
                    } else if (read == -1L) finish("Complete (EOF)")
                    read
                } catch (error: IOException) {
                    failed = true
                    finish("Partial response: $error")
                    throw error
                }
            }
            override fun close() {
                try { super.close() } finally {
                    finish(if (failed) "Partial response" else "Closed by consumer; captured bytes read (SSE may stop at its completion event).")
                }
            }
        }.buffer()
        return response.newBuilder().body(object : ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source(): BufferedSource = source
        }).build()
    }

    private fun headers(headers: Headers): String = JSONArray().apply {
        for (index in 0 until headers.size) put(JSONObject().put("name", headers.name(index)).put("value", headers.value(index)))
    }.toString()
}
