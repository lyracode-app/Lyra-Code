package com.yukisoffd.lyracode.ai

import com.sun.net.httpserver.HttpServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

class OpenCodeInterceptorTest {
    @get:Rule val installation = TemporaryFolder()

    private fun interceptor(directory: File) = OpenCodeInterceptor(
        "LyraCode/test", { OpenCodeInterceptor.loadInstallationId(directory) }, "lyra-fallback",
    )

    private fun capture(request: Request, directory: File = installation.root): Request {
        var seen: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor(directory))
            .addInterceptor { chain ->
                seen = chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("ok")
                    .code(200).body("{}".toResponseBody()).build()
            }.build()
        client.newCall(request).execute().close()
        return requireNotNull(seen)
    }

    @Test fun conversationRequestsCarryAStableSessionAndClientUserAgent() {
        val first = capture(Request.Builder().url("https://opencode.ai/inference/go/v1/chat/completions").openCodeSession(42).build())
        val second = capture(Request.Builder().url("https://opencode.ai/inference/go/v1/chat/completions").openCodeSession(42).build())
        assertNotEquals("lyra-fallback", first.header(OpenCodeInterceptor.SESSION_HEADER))
        assertNotNull(first.header(OpenCodeInterceptor.SESSION_HEADER))
        assertEquals(first.header(OpenCodeInterceptor.SESSION_HEADER), second.header(OpenCodeInterceptor.SESSION_HEADER))
        assertEquals("LyraCode/test", first.header("User-Agent"))
    }

    @Test fun requestsWithoutAConversationUseTheFallbackSession() {
        val seen = capture(Request.Builder().url("https://opencode.ai/inference/go/v1/models").build())
        assertEquals("lyra-fallback", seen.header(OpenCodeInterceptor.SESSION_HEADER))
    }

    @Test fun sameLocalIdOnDifferentInstallationsAndDifferentLocalIdsHaveDistinctSessions() {
        fun session(directory: File, id: Long) = capture(
            Request.Builder().url("https://opencode.ai/").openCodeSession(id).build(), directory,
        ).header(OpenCodeInterceptor.SESSION_HEADER)
        val first = session(installation.root, 1)
        assertNotEquals(first, session(installation.newFolder(), 1))
        assertNotEquals(first, session(installation.root, 2))
    }

    @Test fun redirectChainKeepsTheSessionOnlyOnTheOriginalOrigin() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = HttpServer.create(InetSocketAddress(loopback, 0), 0)
        val port = server.address.port
        server.createContext("/") { exchange ->
            exchange.requestHeaders.getFirst(OpenCodeInterceptor.SESSION_HEADER)?.let {
                exchange.responseHeaders.add("Seen-Session", it)
            }
            val next = when (exchange.requestURI.path) {
                "/start" -> "/same-origin"
                "/same-origin" -> "http://other.test:$port/external"
                "/external" -> "http://sub.opencode.ai:$port/done"
                else -> null
            }
            if (next != null) exchange.responseHeaders.add("Location", next)
            exchange.sendResponseHeaders(if (next == null) 200 else 302, -1)
            exchange.close()
        }
        server.start()
        try {
            val client = OkHttpClient.Builder()
                .dns(object : Dns {
                    override fun lookup(hostname: String) = listOf(loopback)
                })
                .proxy(Proxy.NO_PROXY)
                .addNetworkInterceptor(interceptor(installation.root))
                .build()
            val request = Request.Builder().url("http://opencode.ai:$port/start")
                .header(OpenCodeInterceptor.SESSION_HEADER, "custom-session")
                .build()
            for (initial in listOf(request, request.newBuilder().openCodeSession(42).build())) {
                client.newCall(initial).execute().use { response ->
                    assertEquals(200, response.code)
                    val sessions = generateSequence(response) { it.priorResponse }.toList()
                        .asReversed().map { it.header("Seen-Session") }
                    val expected = capture(initial).header(OpenCodeInterceptor.SESSION_HEADER)
                    assertEquals(listOf(expected, expected, null, null), sessions)
                }
            }
        } finally {
            server.stop(0)
        }
    }

    @Test fun otherHostsAreUntouched() {
        val seen = capture(Request.Builder().url("https://api.openai.com/v1/chat/completions").openCodeSession(42).build())
        assertNull(seen.header(OpenCodeInterceptor.SESSION_HEADER))
        assertNull(seen.header("User-Agent"))
    }
}
