package com.yukisoffd.lyracode.ai

import android.content.Context
import android.util.Log
import com.yukisoffd.lyracode.data.ApiProfile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal data class DeepSeekInlineImage(
    val mimeType: String,
    val bytes: ByteArray,
) {
    val digest: String = sha256(bytes)
    val filename: String = "lyra-$digest.${extensionFor(mimeType)}"
}

internal data class DeepSeekCachedFile(
    val fileId: String,
    val expiresAtMillis: Long,
)

internal fun supportsDeepSeekFilesApi(profile: ApiProfile, model: String): Boolean {
    return isDeepSeekApiProfile(profile) &&
        profile.apiFormat != ApiProfile.API_FORMAT_GEMINI &&
        model.trim().lowercase(Locale.US).contains("vision")
}

internal fun deepSeekFilesEndpoint(profile: ApiProfile): String {
    val raw = profile.baseUrl.trim().trimEnd('/')
    val uri = runCatching { URI(raw) }.getOrNull()
    if (uri?.scheme != null && uri.host != null && uri.host.equals("api.deepseek.com", ignoreCase = true)) {
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "${uri.scheme}://${uri.host}$port/files"
    }
    val base = raw
        .removeSuffix("/anthropic/v1")
        .removeSuffix("/anthropic")
        .removeSuffix("/v1")
    return "$base/files"
}

internal fun parseDeepSeekInlineImage(dataUrl: String): DeepSeekInlineImage? {
    val match = DATA_URL_REGEX.matchEntire(dataUrl.trim()) ?: return null
    val mimeType = match.groupValues[1].lowercase(Locale.US)
    if (mimeType !in SUPPORTED_IMAGE_MIME_TYPES) return null
    val bytes = runCatching { Base64.getMimeDecoder().decode(match.groupValues[2]) }.getOrNull() ?: return null
    if (bytes.isEmpty() || bytes.size > MAX_DEEPSEEK_FILE_BYTES) return null
    return DeepSeekInlineImage(mimeType, bytes)
}

internal fun replaceOpenAiInlineImagesWithDeepSeekFiles(
    messages: JSONArray,
    resolveFileId: (DeepSeekInlineImage) -> String?,
): Boolean {
    var replaced = false
    for (messageIndex in 0 until messages.length()) {
        val message = messages.optJSONObject(messageIndex) ?: continue
        if (message.optString("role") != "user") continue
        val content = message.optJSONArray("content") ?: continue
        for (partIndex in 0 until content.length()) {
            val part = content.optJSONObject(partIndex) ?: continue
            if (part.optString("type") != "image_url") continue
            val dataUrl = part.optJSONObject("image_url")?.optString("url").orEmpty()
            val image = parseDeepSeekInlineImage(dataUrl) ?: continue
            val fileId = resolveFileId(image)?.takeIf { it.startsWith("file-api-") } ?: continue
            content.put(
                partIndex,
                JSONObject()
                    .put("type", "file")
                    .put("file_id", fileId),
            )
            replaced = true
        }
    }
    return replaced
}

internal fun replaceAnthropicInlineImagesWithDeepSeekFiles(
    messages: JSONArray,
    resolveFileId: (DeepSeekInlineImage) -> String?,
): Boolean {
    var replaced = false
    for (messageIndex in 0 until messages.length()) {
        val message = messages.optJSONObject(messageIndex) ?: continue
        if (message.optString("role") != "user") continue
        val content = message.optJSONArray("content") ?: continue
        for (partIndex in 0 until content.length()) {
            val part = content.optJSONObject(partIndex) ?: continue
            if (part.optString("type") != "image") continue
            val source = part.optJSONObject("source") ?: continue
            if (source.optString("type") != "base64") continue
            val dataUrl = "data:${source.optString("media_type")};base64,${source.optString("data")}"
            val image = parseDeepSeekInlineImage(dataUrl) ?: continue
            val fileId = resolveFileId(image)?.takeIf { it.startsWith("file-api-") } ?: continue
            content.put(
                partIndex,
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject()
                            .put("type", "file")
                            .put("file_id", fileId),
                    ),
            )
            replaced = true
        }
    }
    return replaced
}

internal fun reusableDeepSeekFileId(
    nowMillis: Long,
    cached: DeepSeekCachedFile?,
    upload: () -> DeepSeekCachedFile,
): DeepSeekCachedFile {
    return cached?.takeIf {
        it.fileId.startsWith("file-api-") && it.expiresAtMillis - FILE_EXPIRY_SAFETY_MILLIS > nowMillis
    } ?: upload()
}

internal fun deepSeekFileUploadRequest(
    profile: ApiProfile,
    endpoint: String,
    image: DeepSeekInlineImage,
): Request {
    val body = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("purpose", "user_data")
        .addFormDataPart("expires_after[anchor]", "created_at")
        .addFormDataPart("expires_after[seconds]", FILE_EXPIRY_SECONDS.toString())
        .addFormDataPart("file", image.filename, image.bytes.toRequestBody(image.mimeType.toMediaType()))
        .build()
    return Request.Builder()
        .url(endpoint)
        .apply { if (profile.apiKey.isNotBlank()) addHeader("Authorization", "Bearer ${profile.apiKey}") }
        .post(body)
        .build()
}

internal class DeepSeekFilesApi(
    context: Context,
    private val client: OkHttpClient,
) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val keyLocks = ConcurrentHashMap<String, Any>()

    fun replaceOpenAiInlineImages(messages: JSONArray, profile: ApiProfile): Boolean {
        return replaceOpenAiInlineImagesWithDeepSeekFiles(messages) { image -> resolveFileIdOrNull(profile, image) }
    }

    fun replaceAnthropicInlineImages(messages: JSONArray, profile: ApiProfile): Boolean {
        return replaceAnthropicInlineImagesWithDeepSeekFiles(messages) { image -> resolveFileIdOrNull(profile, image) }
    }

    private fun resolveFileIdOrNull(profile: ApiProfile, image: DeepSeekInlineImage): String? = runCatching {
        resolveFileId(profile, image)
    }.onFailure { error ->
        Log.w(LOG_TAG, "Files API upload failed; falling back to the inline image", error)
    }.getOrNull()

    private fun resolveFileId(profile: ApiProfile, image: DeepSeekInlineImage): String {
        val endpoint = deepSeekFilesEndpoint(profile)
        val cacheKey = sha256("$endpoint\n${sha256(profile.apiKey.toByteArray())}\n${image.digest}".toByteArray())
        val lock = keyLocks.getOrPut(cacheKey) { Any() }
        return synchronized(lock) {
            val now = System.currentTimeMillis()
            val cached = readCache(cacheKey)
            val reusable = reusableDeepSeekFileId(now, cached) {
                upload(profile, endpoint, image, now).also { writeCache(cacheKey, it) }
            }
            reusable.fileId
        }
    }

    private fun upload(
        profile: ApiProfile,
        endpoint: String,
        image: DeepSeekInlineImage,
        nowMillis: Long,
    ): DeepSeekCachedFile {
        val request = deepSeekFileUploadRequest(profile, endpoint, image)
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("DeepSeek Files API 上传失败 ${response.code}: ${responseBody.take(500)}")
            }
            val root = runCatching { JSONObject(responseBody) }.getOrElse { error ->
                throw IOException("DeepSeek Files API 返回了无效 JSON", error)
            }
            val fileId = root.optString("id")
            if (!fileId.startsWith("file-api-")) throw IOException("DeepSeek Files API 未返回有效 file_id")
            val serverExpiryMillis = root.optLong("expires_at", 0L).takeIf { it > 0L }?.times(1000L)
            return DeepSeekCachedFile(
                fileId = fileId,
                expiresAtMillis = serverExpiryMillis ?: nowMillis + FILE_EXPIRY_SECONDS * 1000L,
            )
        }
    }

    private fun readCache(key: String): DeepSeekCachedFile? {
        val raw = preferences.getString(key, null) ?: return null
        return runCatching {
            val root = JSONObject(raw)
            DeepSeekCachedFile(root.getString("file_id"), root.getLong("expires_at_ms"))
        }.getOrNull()
    }

    private fun writeCache(key: String, cached: DeepSeekCachedFile) {
        preferences.edit()
            .putString(
                key,
                JSONObject()
                    .put("file_id", cached.fileId)
                    .put("expires_at_ms", cached.expiresAtMillis)
                    .toString(),
            )
            .apply()
    }
}

private fun extensionFor(mimeType: String): String = when (mimeType) {
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    else -> "img"
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

private val DATA_URL_REGEX = Regex("""^data:([^;,]+);base64,(.+)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val SUPPORTED_IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")
private const val MAX_DEEPSEEK_FILE_BYTES = 64 * 1024 * 1024
private const val FILE_EXPIRY_SECONDS = 30L * 24L * 60L * 60L
private const val FILE_EXPIRY_SAFETY_MILLIS = 60L * 60L * 1000L
private const val PREFERENCES_NAME = "deepseek_files_api_cache"
private const val LOG_TAG = "DeepSeekFilesApi"
