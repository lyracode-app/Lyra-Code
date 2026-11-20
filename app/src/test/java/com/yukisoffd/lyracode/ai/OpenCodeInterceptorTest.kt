package com.yukisoffd.lyracode.ai

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenCodeInterceptorTest {
    private fun capture(request: Request, fallback: String = "lyra-fallback"): Request {
        var seen: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(OpenCodeInterceptor("LyraCode/test", fallback))
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
        assertEquals("lyra-42", first.header(OpenCodeInterceptor.SESSION_HEADER))
        assertEquals(first.header(OpenCodeInterceptor.SESSION_HEADER), second.header(OpenCodeInterceptor.SESSION_HEADER))
        assertEquals("LyraCode/test", first.header("User-Agent"))
    }

    @Test fun requestsWithoutAConversationUseTheFallbackSession() {
        val seen = capture(Request.Builder().url("https://opencode.ai/inference/go/v1/models").build())
        assertEquals("lyra-fallback", seen.header(OpenCodeInterceptor.SESSION_HEADER))
    }

    @Test fun otherHostsAreUntouched() {
        val seen = capture(Request.Builder().url("https://api.openai.com/v1/chat/completions").openCodeSession(42).build())
        assertNull(seen.header(OpenCodeInterceptor.SESSION_HEADER))
        assertNull(seen.header("User-Agent"))
    }
}
