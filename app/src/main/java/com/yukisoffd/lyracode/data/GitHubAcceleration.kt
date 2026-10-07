package com.yukisoffd.lyracode.data

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class GitHubAccelerator(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val prefix: String,
    val enabled: Boolean = true,
)

data class GitHubAccelerationConfig(
    val enabled: Boolean = true,
    val links: List<GitHubAccelerator> = listOf(
        GitHubAccelerator(id = "gh-proxy", name = "GH-Proxy", prefix = "https://gh-proxy.org/"),
    ),
)

class GitHubAccelerationSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("lyra_github_acceleration", Context.MODE_PRIVATE)

    fun load(): GitHubAccelerationConfig = decodeGitHubAccelerationConfig(prefs.getString("config", null))

    fun save(config: GitHubAccelerationConfig) {
        prefs.edit().putString("config", encodeGitHubAccelerationConfig(config)).apply()
    }
}

internal fun encodeGitHubAccelerationConfig(config: GitHubAccelerationConfig): String = JSONObject()
    .put("enabled", config.enabled)
    .put("links", JSONArray().apply {
        config.links.forEach { link ->
            put(JSONObject().put("id", link.id).put("name", link.name)
                .put("prefix", normalizeGitHubAcceleratorPrefix(link.prefix)).put("enabled", link.enabled))
        }
    }).toString()

internal fun decodeGitHubAccelerationConfig(raw: String?): GitHubAccelerationConfig {
    if (raw == null) return GitHubAccelerationConfig()
    return runCatching {
        val json = JSONObject(raw)
        val entries = json.getJSONArray("links")
        GitHubAccelerationConfig(
            enabled = json.optBoolean("enabled", true),
            links = (0 until entries.length()).mapNotNull { index ->
                val item = entries.optJSONObject(index) ?: return@mapNotNull null
                val prefix = normalizeGitHubAcceleratorPrefix(item.optString("prefix")) ?: return@mapNotNull null
                GitHubAccelerator(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    name = item.optString("name").trim().ifBlank { prefix.toHttpUrlOrNull()!!.host },
                    prefix = prefix,
                    enabled = item.optBoolean("enabled", true),
                )
            }.distinctBy { it.id },
        )
    }.getOrElse { GitHubAccelerationConfig(enabled = false, links = emptyList()) }
}

/** Accelerators receive the original public URL after an HTTPS prefix. */
internal fun normalizeGitHubAcceleratorPrefix(value: String): String? {
    val url = value.trim().toHttpUrlOrNull() ?: return null
    if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
        url.query != null || url.fragment != null || isGitHubDownloadUrl(url.toString())) return null
    return url.toString().trimEnd('/') + "/"
}

internal fun isGitHubDownloadUrl(value: String): Boolean {
    val url = value.toHttpUrlOrNull() ?: return false
    return url.username.isEmpty() && url.password.isEmpty() && url.host in setOf(
        "github.com", "raw.githubusercontent.com", "objects.githubusercontent.com",
        "release-assets.githubusercontent.com", "codeload.github.com",
    )
}

internal data class GitHubDownloadTarget(val url: String, val acceleratorName: String? = null)

internal fun githubDownloadTargets(url: String, config: GitHubAccelerationConfig): List<GitHubDownloadTarget> = buildList {
    add(GitHubDownloadTarget(url))
    if (config.enabled && isGitHubDownloadUrl(url)) {
        config.links.filter { it.enabled }.forEach { link ->
            normalizeGitHubAcceleratorPrefix(link.prefix)?.let { prefix ->
                add(GitHubDownloadTarget(prefix + url, link.name))
            }
        }
    }
}.distinctBy { it.url }

data class GitHubConnectionResult(
    val successful: Boolean,
    val elapsedMs: Long,
    val httpStatus: Int? = null,
    val error: String = "",
)

internal val githubProbeClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
    .callTimeout(12, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
    .build()

/** Test the download path, not just a proxy homepage; never download the whole APK. */
internal fun probeGitHubConnection(url: String, client: OkHttpClient = githubProbeClient): GitHubConnectionResult {
    val start = System.nanoTime()
    var status: Int? = null
    val result = runCatching {
        val request = Request.Builder().url(url)
            .header("Range", "bytes=0-4095")
            .header("Accept-Encoding", "identity")
            .header("User-Agent", "LyraCode/GitHubConnectivityTest")
            .get().build()
        client.newCall(request).execute().use { response ->
            status = response.code
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty response")
            val bytes = ByteArray(4096)
            var count = 0
            body.byteStream().use { input ->
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    count += read
                }
            }
            if (count == 0) throw IOException("Empty response")
            val originalPath = request.url.encodedPath.lowercase()
            if ((originalPath.endsWith(".apk") || originalPath.endsWith(".zip")) && !hasZipHeader(bytes, count)) {
                throw IOException("Response is not an APK/ZIP file")
            }
        }
    }
    return GitHubConnectionResult(
        successful = result.isSuccess,
        elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
        httpStatus = status,
        error = result.exceptionOrNull()?.message.orEmpty(),
    )
}

internal const val GITHUB_CONNECTIVITY_TEST_URL = "https://github.com/lyracode-app/Lyra-Code/archive/refs/heads/main.zip"
