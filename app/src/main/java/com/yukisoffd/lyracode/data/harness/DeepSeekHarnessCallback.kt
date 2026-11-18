package com.yukisoffd.lyracode.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal class DeepSeekHarnessCallback : Closeable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 500 }
    private val authority = "127.0.0.1:${server.localPort}"
    val redirectUri = "http://$authority${DeepSeekHarnessProtocol.CALLBACK_PATH}"

    suspend fun await(state: String, deadlineNanos: Long): BrowserReply {
        while (System.nanoTime() < deadlineNanos) {
            currentCoroutineContext().ensureActive()
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            val code = runCatching { readCode(socket, state, deadlineNanos) }.getOrNull()
            if (code != null) return BrowserReply(socket, code)
            socket.use { runCatching { reply(it, "400 Bad Request") } }
        }
        throw HarnessSignInTimedOut()
    }

    private fun readCode(socket: Socket, state: String, deadlineNanos: Long): String? {
        socket.soTimeout = 500
        val headerDeadline = minOf(deadlineNanos, System.nanoTime() + TimeUnit.SECONDS.toNanos(2))
        val input = socket.getInputStream().buffered()
        val header = StringBuilder()
        while (header.length < 16_384 && System.nanoTime() < headerDeadline) {
            val byte = input.read()
            if (byte < 0) return null
            header.append(byte.toChar())
            if (header.endsWith("\r\n\r\n")) break
        }
        if (!header.endsWith("\r\n\r\n")) return null
        val lines = header.split("\r\n")
        val request = lines.first().split(' ')
        val hosts = lines.filter { it.startsWith("Host:", ignoreCase = true) }
        if (request.size != 3 || request[0] != "GET" || !request[1].startsWith('/') ||
            hosts.singleOrNull()?.substringAfter(':')?.trim() != authority
        ) return null
        val url = ("http://$authority" + request[1]).toHttpUrlOrNull() ?: return null
        if (url.encodedPath != DeepSeekHarnessProtocol.CALLBACK_PATH || url.fragment != null) return null
        val received = url.queryParameterValues("state").singleOrNull() ?: return null
        if (!MessageDigest.isEqual(state.toByteArray(), received.toByteArray())) return null
        return url.queryParameterValues("code").singleOrNull()?.takeIf(String::isNotBlank)
    }

    override fun close() = server.close()

    internal class BrowserReply(private val socket: Socket, val code: String) : Closeable {
        fun complete(url: HttpUrl) {
            runCatching { reply(socket, "303 See Other", url) }
        }

        override fun close() {
            runCatching { if (!socket.isOutputShutdown) reply(socket, "204 No Content") }
            runCatching { socket.close() }
        }

        override fun toString() = "DeepSeek Harness authorization callback"
    }

    private companion object {
        fun reply(socket: Socket, status: String, location: HttpUrl? = null) {
            val headers = buildString {
                append("HTTP/1.1 $status\r\n")
                if (location != null) append("Location: $location\r\n")
                append("Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\nContent-Length: 0\r\n\r\n")
            }
            socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); flush() }
            socket.shutdownOutput()
        }
    }
}
