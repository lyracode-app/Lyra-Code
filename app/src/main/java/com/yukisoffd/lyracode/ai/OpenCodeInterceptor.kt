package com.yukisoffd.lyracode.ai

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/** Conversation a model request belongs to; turned into OpenCode's session header for opencode.ai only. */
internal data class OpenCodeSessionTag(val conversationId: Long)

internal fun Request.Builder.openCodeSession(conversationId: Long): Request.Builder =
    tag(OpenCodeSessionTag::class.java, OpenCodeSessionTag(conversationId))

/**
 * OpenCode Go (and Zen) route and cache by a stable `x-opencode-session` per conversation and expect a
 * client-specific User-Agent. Run at the network layer so every redirect is checked before sending.
 */
internal class OpenCodeInterceptor(
    private val userAgent: String,
    loadInstallationId: () -> UUID,
    private val fallbackSession: String = "lyra-${UUID.randomUUID()}",
) : Interceptor {
    private val installationId by lazy(loadInstallationId)

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val initialUrl = chain.call().request().url
        val url = original.url
        if (url.host != initialUrl.host || url.scheme != initialUrl.scheme || url.port != initialUrl.port) {
            return chain.proceed(original.newBuilder().removeHeader(SESSION_HEADER).build())
        }
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

    private fun sessionId(conversationId: Long): String {
        val key = "$installationId/$conversationId".toByteArray(Charsets.UTF_8)
        return "lyra-${UUID.nameUUIDFromBytes(key)}"
    }

    internal companion object {
        const val SESSION_HEADER = "x-opencode-session"

        // noBackupFilesDir keeps the namespace local even across device-to-device restores.
        @Synchronized
        fun loadInstallationId(noBackupFilesDir: File): UUID {
            val file = File(noBackupFilesDir, "opencode-installation-id")
            return RandomAccessFile(file, "rw").use { data ->
                data.channel.lock().use {
                    if (data.length() == 16L) UUID(data.readLong(), data.readLong())
                    else UUID.randomUUID().also { id ->
                        data.writeLong(id.mostSignificantBits)
                        data.writeLong(id.leastSignificantBits)
                        data.setLength(16L)
                        data.fd.sync()
                    }
                }
            }
        }

        fun isOpenCodeHost(url: HttpUrl): Boolean {
            val host = url.host
            return host == "opencode.ai" || host.endsWith(".opencode.ai")
        }
    }
}
