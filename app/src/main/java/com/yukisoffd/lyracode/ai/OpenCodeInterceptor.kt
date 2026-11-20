package com.yukisoffd.lyracode.ai

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.Locale
import java.util.UUID

/** Conversation a model request belongs to; turned into OpenCode's session header for opencode.ai only. */
internal data class OpenCodeSessionTag(val conversationId: Long)

internal fun Request.Builder.openCodeSession(conversationId: Long): Request.Builder =
    tag(OpenCodeSessionTag::class.java, OpenCodeSessionTag(conversationId))

/**
 * OpenCode Go (and Zen) route and cache by a stable `x-opencode-session` per conversation and expect a
 * client-specific User-Agent. Requests to other hosts pass through untouched.
 */
internal class OpenCodeInterceptor(
    private val userAgent: String,
    private val fallbackSession: String = "lyra-${UUID.randomUUID()}",
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!isOpenCodeHost(original.url)) return chain.proceed(original)
        val session = original.tag(OpenCodeSessionTag::class.java)?.let { sessionId(it.conversationId) }
            ?: original.header(SESSION_HEADER)?.takeIf(String::isNotBlank)
            ?: fallbackSession
        return chain.proceed(
            original.newBuilder()
                .header("User-Agent", userAgent)
                .header(SESSION_HEADER, session)
                .build(),
        )
    }

    internal companion object {
        const val SESSION_HEADER = "x-opencode-session"

        fun sessionId(conversationId: Long): String = "lyra-$conversationId"

        fun isOpenCodeHost(url: HttpUrl): Boolean {
            val host = url.host.lowercase(Locale.ROOT)
            return host == "opencode.ai" || host.endsWith(".opencode.ai")
        }
    }
}
