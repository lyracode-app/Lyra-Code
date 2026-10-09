package com.yukisoffd.lyracode.mcp

import java.io.InputStream
import java.util.Locale

internal data class McpHttpRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String)

/** Content-Length counts UTF-8 bytes, not Java characters. Also accept chunked HTTP clients. */
internal fun readMcpHttpRequest(input: InputStream): McpHttpRequest {
    fun line(): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            require(byte >= 0) { "Incomplete HTTP request" }
            if (byte == 10) return bytes.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r")
            require(bytes.size() < 8192) { "HTTP header too large" }
            bytes.write(byte)
        }
    }
    fun bytes(length: Int): ByteArray {
        require(length in 0..4 * 1024 * 1024) { "HTTP request body too large" }
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(buffer, offset, length - offset)
            require(count > 0) { "Incomplete HTTP body" }
            offset += count
        }
        return buffer
    }
    val parts = line().split(' ')
    require(parts.size == 3) { "Invalid HTTP request line" }
    val headers = linkedMapOf<String, String>()
    while (true) {
        val header = line()
        if (header.isEmpty()) break
        require(headers.size < 100) { "Too many HTTP headers" }
        val separator = header.indexOf(':')
        require(separator > 0) { "Invalid HTTP header" }
        val name = header.substring(0, separator).lowercase(Locale.US)
        require(!headers.containsKey(name)) { "Duplicate HTTP header: $name" }
        headers[name] = header.substring(separator + 1).trim()
    }
    val body = if (headers["transfer-encoding"]?.equals("chunked", true) == true) {
        require(!headers.containsKey("content-length")) { "Ambiguous HTTP body length" }
        val output = java.io.ByteArrayOutputStream()
        while (true) {
            val size = line().substringBefore(';').trim().toInt(16)
            require(size >= 0 && output.size().toLong() + size <= 4 * 1024 * 1024) { "HTTP request body too large" }
            if (size == 0) {
                var trailers = 0
                while (line().isNotEmpty()) { require(++trailers <= 100) { "Too many HTTP trailers" } }
                break
            }
            output.write(bytes(size))
            require(line().isEmpty()) { "Invalid HTTP chunk delimiter" }
        }
        output.toByteArray()
    } else {
        require(!headers.containsKey("transfer-encoding")) { "Unsupported HTTP transfer encoding" }
        bytes(headers["content-length"]?.toInt() ?: 0)
    }
    return McpHttpRequest(parts[0].uppercase(Locale.US), parts[1].substringBefore('?'), headers, String(body, Charsets.UTF_8))
}
