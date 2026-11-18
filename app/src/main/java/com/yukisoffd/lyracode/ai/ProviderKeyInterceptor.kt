package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.ApiProfile
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

internal fun Request.withProviderKeys(profile: ApiProfile): Request = newBuilder()
    .tag(ApiProfile::class.java, profile).build()

/** Retry only rejected credentials/quota/rate limits, before consuming any successful stream. */
internal class ProviderKeyInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val profile = original.tag(ApiProfile::class.java) ?: return chain.proceed(original)
        if (profile.usesAccountCredential) return chain.proceed(original)
        if (original.body?.isOneShot() == true || original.body?.isDuplex() == true) return chain.proceed(original)
        val keys = profile.allApiKeys
        val templates = profile.modelRequestOverrides.values.flatMap { it.headers.entries }
            .filter { it.value.contains("{{apiKey}}") }
        val current = keys.firstOrNull { key ->
            AUTH_HEADERS.any { header -> original.header(header) == key || original.header(header) == "Bearer $key" } ||
                templates.any { (name, template) -> original.header(name) == template.replace("{{apiKey}}", key) }
        } ?: return chain.proceed(original) // Respect manually overridden or removed authentication headers.
        var response = chain.proceed(original)
        for (key in keys.filter { it != current }) {
            if (response.code !in KEY_FAILURE_STATUSES || chain.call().isCanceled()) break
            response.close()
            val builder = original.newBuilder()
            for (header in AUTH_HEADERS) {
                when (original.header(header)) {
                    current -> builder.header(header, key)
                    "Bearer $current" -> builder.header(header, "Bearer $key")
                }
            }
            // Also honor custom headers that explicitly use the standard {{apiKey}} template.
            templates.forEach { (name, template) ->
                    if (original.header(name) == template.replace("{{apiKey}}", current)) {
                        builder.header(name, template.replace("{{apiKey}}", key))
                    }
                }
            response = chain.proceed(builder.build())
        }
        return response
    }

    private companion object {
        val AUTH_HEADERS = listOf("Authorization", "x-api-key", "x-goog-api-key")
        val KEY_FAILURE_STATUSES = setOf(401, 402, 403, 429)
    }
}
