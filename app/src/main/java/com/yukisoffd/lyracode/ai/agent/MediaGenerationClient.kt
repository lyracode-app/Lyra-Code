package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.ai.customizedFor
import android.content.Context
import android.net.Uri
import com.yukisoffd.lyracode.data.ApiProfile
import com.yukisoffd.lyracode.data.MediaGenerationKind
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import java.util.Locale
import java.util.UUID

internal const val MEDIA_MESSAGE_ROLE = "media"

internal data class MediaGenerationPrompt(
    val kind: MediaGenerationKind,
    val prompt: String,
    val negativePrompt: String = "",
    val aspectRatio: String = "",
    val durationSeconds: Int? = null,
    val lyrics: String = "",
    val instrumental: Boolean? = null,
    val voice: String = "",
    val references: List<String> = emptyList(),
)

internal data class GeneratedMediaAsset(
    val source: String,
    val mimeType: String,
)

internal data class MediaGenerationResult(
    val assets: List<GeneratedMediaAsset>,
)

internal class MediaGenerationClient(
    context: Context,
    private val client: OkHttpClient,
) {
    private val appContext = context.applicationContext

    fun generate(
        profile: ApiProfile,
        model: String,
        request: MediaGenerationPrompt,
    ): MediaGenerationResult {

        require(model.isNotBlank()) { "The configured ${request.kind.value} model is empty." }
        val references = request.references.distinct().map(::normalizeReferenceSource)
        val prompt = mediaPromptText(request)
        val payload = when (profile.apiFormat) {
            ApiProfile.API_FORMAT_ANTHROPIC -> anthropicPayload(model, prompt, references)
            ApiProfile.API_FORMAT_GEMINI -> geminiPayload(model, prompt, references, request.kind)
            else -> openAiPayload(profile, model, prompt, references)
        }
        val requestBuilder = Request.Builder()
            .url(
                when (profile.apiFormat) {
                    ApiProfile.API_FORMAT_GEMINI -> profile.geminiGenerateContentEndpoint(model)
                    ApiProfile.API_FORMAT_OPENAI -> if (profile.useResponsesApi) profile.responsesEndpoint else profile.chatEndpoint
                    else -> profile.chatEndpoint
                },
            )
            .addHeader("Content-Type", "application/json")
        when (profile.apiFormat) {
            ApiProfile.API_FORMAT_ANTHROPIC -> requestBuilder
                .apply { if (profile.apiKey.isNotBlank()) addHeader("x-api-key", profile.apiKey) }
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
            ApiProfile.API_FORMAT_GEMINI -> requestBuilder.apply { if (profile.apiKey.isNotBlank()) addHeader("x-goog-api-key", profile.apiKey) }
            else -> requestBuilder.apply { if (profile.apiKey.isNotBlank()) addHeader("Authorization", "Bearer ${profile.apiKey}") }
        }
        val httpRequest = requestBuilder
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        var directAsset: GeneratedMediaAsset? = null
        val responseBody = client.newCall(httpRequest.customizedFor(profile, model, com.yukisoffd.lyracode.data.AppSettings(appContext).purePromptMode)).execute().use { response ->
            val body = response.body ?: error("The media model returned an empty response.")
            val responseMime = body.contentType()?.toString()?.substringBefore(';').orEmpty()
            val directMediaResponse = request.kind.acceptsMimeType(responseMime)
            val responseLimit = if (directMediaResponse) MAX_GENERATED_MEDIA_BYTES else MAX_MEDIA_RESPONSE_BYTES
            val declaredLength = body.contentLength()
            require(declaredLength < 0 || declaredLength <= responseLimit) {
                "The media model response is too large (${declaredLength} bytes)."
            }
            if (!response.isSuccessful) {
                val text = if (directMediaResponse) {
                    "[binary media response omitted]"
                } else {
                    readBoundedBytes(
                        body.byteStream(),
                        MAX_MEDIA_RESPONSE_BYTES,
                        "The media model error response exceeds the 96 MB limit.",
                    ).toString(Charsets.UTF_8)
                }
                error("Media model HTTP ${response.code}: ${sanitizeMediaGenerationError(text)}")
            }
            if (directMediaResponse) {
                val bytes = readBoundedBytes(
                    body.byteStream(),
                    MAX_GENERATED_MEDIA_BYTES,
                    "Generated media exceeds the 512 MB limit.",
                )
                directAsset = writeGeneratedAsset(bytes, responseMime, request.kind)
                ""
            } else {
                readBoundedBytes(
                    body.byteStream(),
                    MAX_MEDIA_RESPONSE_BYTES,
                    "The media model response exceeds the 96 MB limit.",
                ).toString(Charsets.UTF_8)
            }
        }
        directAsset?.let { return MediaGenerationResult(listOf(it)) }
        val extracted = extractMediaAssets(responseBody, request.kind)
        if (extracted.isEmpty()) {
            error(
                "The ${request.kind.value} model completed without returning a usable media file. " +
                    sanitizeMediaGenerationError(responseBody),
            )
        }
        return MediaGenerationResult(extracted.take(MAX_MEDIA_ASSETS).map { materializeAsset(it, request.kind) })
    }

    private fun openAiPayload(
        profile: ApiProfile,
        model: String,
        prompt: String,
        references: List<MediaReference>,
    ): JSONObject {
        val userContent = openAiUserContent(prompt, references)
        return if (profile.useResponsesApi) {
            JSONObject()
                .put("model", model)
                .put(
                    "input",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "message")
                            .put("role", "user")
                            .put("content", responsesUserContent(prompt, references)),
                    ),
                )
                .put("stream", false)
                .put("store", false)
        } else {
            JSONObject()
                .put("model", model)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", userContent)))
                .put("stream", false)
        }
    }

    private fun anthropicPayload(model: String, prompt: String, references: List<MediaReference>): JSONObject {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        references.filter { it.mimeType.startsWith("image/") && it.source.startsWith("data:") }.forEach { reference ->
            content.put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", reference.mimeType)
                            .put("data", reference.source.substringAfter("base64,")),
                    ),
            )
        }
        return JSONObject()
            .put("model", model)
            .put("max_tokens", 4096)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("stream", false)
    }

    private fun geminiPayload(
        model: String,
        prompt: String,
        references: List<MediaReference>,
        kind: MediaGenerationKind,
    ): JSONObject {
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        references.forEach { reference ->
            if (reference.source.startsWith("data:")) {
                parts.put(
                    JSONObject().put(
                        "inlineData",
                        JSONObject()
                            .put("mimeType", reference.mimeType)
                            .put("data", reference.source.substringAfter("base64,")),
                    ),
                )
            } else {
                parts.put(
                    JSONObject().put(
                        "fileData",
                        JSONObject().put("mimeType", reference.mimeType).put("fileUri", reference.source),
                    ),
                )
            }
        }
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .apply {
                if (kind == MediaGenerationKind.IMAGE) {
                    put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")))
                }
            }
    }

    private fun openAiUserContent(prompt: String, references: List<MediaReference>): Any {
        if (references.isEmpty()) return prompt
        return JSONArray().also { parts ->
            parts.put(JSONObject().put("type", "text").put("text", prompt))
            references.forEach { parts.put(openAiReferencePart(it)) }
        }
    }

    private fun responsesUserContent(prompt: String, references: List<MediaReference>): JSONArray = JSONArray().also { parts ->
        parts.put(JSONObject().put("type", "input_text").put("text", prompt))
        references.forEach { reference ->
            val type = when {
                reference.mimeType.startsWith("image/") -> "input_image"
                reference.mimeType.startsWith("video/") -> "input_video"
                else -> "input_audio"
            }
            parts.put(
                JSONObject()
                    .put("type", type)
                    .put(
                        when (type) {
                            "input_image" -> "image_url"
                            "input_video" -> "video_url"
                            else -> "audio_url"
                        },
                        reference.source,
                    ),
            )
        }
    }

    private fun openAiReferencePart(reference: MediaReference): JSONObject = when {
        reference.mimeType.startsWith("image/") -> JSONObject()
            .put("type", "image_url")
            .put("image_url", JSONObject().put("url", reference.source))
        reference.mimeType.startsWith("video/") -> JSONObject()
            .put("type", "video_url")
            .put("video_url", JSONObject().put("url", reference.source))
        else -> JSONObject()
            .put("type", "input_audio")
            .put(
                "input_audio",
                JSONObject()
                    .put("data", reference.source.substringAfter("base64,", reference.source))
                    .put("format", audioFormat(reference.mimeType)),
            )
    }

    private fun mediaPromptText(request: MediaGenerationPrompt): String = buildString {
        append(request.prompt.trim())
        request.negativePrompt.trim().takeIf { it.isNotBlank() }?.let { append("\n\nNegative prompt: ").append(it) }
        request.aspectRatio.trim().takeIf { it.isNotBlank() }?.let { append("\nAspect ratio: ").append(it) }
        request.durationSeconds?.takeIf { it > 0 }?.let { append("\nDuration: ").append(it).append(" seconds") }
        request.lyrics.trim().takeIf { it.isNotBlank() }?.let { append("\n\nLyrics:\n").append(it) }
        request.instrumental?.let { append("\nInstrumental: ").append(it) }
        request.voice.trim().takeIf { it.isNotBlank() }?.let { append("\nVoice: ").append(it) }
    }.trim()

    private fun normalizeReferenceSource(source: String): MediaReference {
        val clean = source.trim()
        if (clean.startsWith("data:", ignoreCase = true)) {
            return MediaReference(clean, dataUrlMimeType(clean) ?: "application/octet-stream")
        }
        if (clean.startsWith("http://", true) || clean.startsWith("https://", true)) {
            return MediaReference(clean, mimeTypeFromName(clean) ?: "application/octet-stream")
        }
        val uri = Uri.parse(clean)
        val bytes = when {
            clean.startsWith("content://", true) || clean.startsWith("file://", true) ->
                appContext.contentResolver.openInputStream(uri)?.use {
                    readBoundedBytes(it, MAX_REFERENCE_BYTES, "Reference media exceeds the 48 MB limit.")
                }
            else -> File(clean).takeIf { it.isFile }?.inputStream()?.use {
                readBoundedBytes(it, MAX_REFERENCE_BYTES, "Reference media exceeds the 48 MB limit.")
            }
        } ?: error("Reference media is unavailable.")
        val mime = appContext.contentResolver.getType(uri)
            ?: mimeTypeFromName(clean)
            ?: sniffMimeType(bytes)
            ?: "application/octet-stream"
        return MediaReference("data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}", mime)
    }

    private fun materializeAsset(asset: RawMediaAsset, kind: MediaGenerationKind): GeneratedMediaAsset {
        if (asset.source.startsWith("data:", ignoreCase = true) || looksLikeBase64(asset.source)) {
            val mime = dataUrlMimeType(asset.source) ?: asset.mimeType.ifBlank { defaultMimeType(kind) }
            require(kind.acceptsMimeType(mime)) { "The ${kind.value} model returned incompatible media type $mime." }
            val encoded = asset.source.substringAfter("base64,", asset.source).replace("\n", "").replace("\r", "")
            val bytes = runCatching { Base64.getDecoder().decode(encoded) }
                .getOrElse { error("The ${kind.value} model returned invalid base64 media data.") }
            return writeGeneratedAsset(bytes, mime, kind)
        }
        if (asset.source.startsWith("http://", true) || asset.source.startsWith("https://", true)) {
            return downloadGeneratedAsset(asset.source, asset.mimeType, kind)
        }
        error("The ${kind.value} model returned an unsupported media source.")
    }

    private fun downloadGeneratedAsset(url: String, hintedMime: String, kind: MediaGenerationKind): GeneratedMediaAsset {
        val request = Request.Builder().url(url).get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Generated media download failed with HTTP ${response.code}.")
            val body = response.body ?: error("Generated media download returned no file.")
            val mime = sequenceOf(
                body.contentType()?.toString()?.substringBefore(';'),
                hintedMime,
                mimeTypeFromName(url),
            ).filterNotNull().firstOrNull(kind::acceptsMimeType) ?: defaultMimeType(kind)
            val dir = generatedMediaDirectory()
            val file = File(dir, "${System.currentTimeMillis()}_${UUID.randomUUID()}.${extensionForMime(mime)}")
            var total = 0L
            body.byteStream().use { input ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_GENERATED_MEDIA_BYTES) { "Generated media exceeds the 512 MB limit." }
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (total <= 0L) {
                file.delete()
                error("Generated media download returned an empty file.")
            }
            GeneratedMediaAsset(file.absolutePath, mime)
        }
    }

    private fun writeGeneratedAsset(bytes: ByteArray, mime: String, kind: MediaGenerationKind): GeneratedMediaAsset {
        require(bytes.isNotEmpty()) { "The ${kind.value} model returned an empty media file." }
        require(bytes.size.toLong() <= MAX_GENERATED_MEDIA_BYTES) { "Generated media exceeds the 512 MB limit." }
        val file = File(
            generatedMediaDirectory(),
            "${System.currentTimeMillis()}_${UUID.randomUUID()}.${extensionForMime(mime)}",
        )
        file.outputStream().use { it.write(bytes) }
        return GeneratedMediaAsset(file.absolutePath, mime)
    }

    private fun generatedMediaDirectory(): File = File(appContext.filesDir, "generated_media").apply { mkdirs() }

    private data class MediaReference(val source: String, val mimeType: String)

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val MAX_MEDIA_ASSETS = 8
        const val MAX_MEDIA_RESPONSE_BYTES = 96L * 1024 * 1024
        const val MAX_REFERENCE_BYTES = 48L * 1024 * 1024
        const val MAX_GENERATED_MEDIA_BYTES = 512L * 1024 * 1024
    }
}

private data class RawMediaAsset(val source: String, val mimeType: String = "")

private fun extractMediaAssets(body: String, kind: MediaGenerationKind): List<RawMediaAsset> {
    val output = mutableListOf<RawMediaAsset>()
    val seen = mutableSetOf<String>()
    fun add(source: String, mimeType: String = "") {
        val clean = source.trim().trim('"', '\'', ' ', '\n', '\r')
        if (clean.isBlank() || !seen.add(clean)) return
        output += RawMediaAsset(clean, mimeType)
    }
    fun collectText(text: String, mimeType: String = "", acceptPlainUrl: Boolean = false) {
        extractRenderedMediaSources(text).forEach { add(it, mimeType) }
        DATA_URL_PATTERN.findAll(text).forEach { add(it.value, it.groupValues[1]) }
        MEDIA_URL_PATTERN.findAll(text).forEach { add(it.value, mimeType) }
        val trimmed = text.trim()
        if (acceptPlainUrl && (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true))) add(trimmed, mimeType)
    }
    lateinit var walk: (Any?, String, JSONObject?) -> Unit
    walk = { value, key, parent ->
        when (value) {
            is JSONObject -> {
                val inline = value.optJSONObject("inlineData") ?: value.optJSONObject("inline_data")
                if (inline != null) {
                    val mime = inline.optString("mimeType").ifBlank { inline.optString("mime_type") }.ifBlank { defaultMimeType(kind) }
                    inline.optString("data").takeIf { it.isNotBlank() }?.let { add("data:$mime;base64,$it", mime) }
                }
                val mime = value.optString("mimeType")
                    .ifBlank { value.optString("mime_type") }
                    .ifBlank { value.optString("media_type") }
                value.keys().forEach { childKey -> walk(value.opt(childKey), childKey, value) }
            }
            is JSONArray -> for (index in 0 until value.length()) walk(value.opt(index), key, parent)
            is String -> {
                val normalizedKey = key.lowercase(Locale.US)
                val mime = parent?.optString("mimeType")
                    ?.ifBlank { parent.optString("mime_type") }
                    ?.ifBlank { parent.optString("media_type") }
                    .orEmpty()
                when (normalizedKey) {
                    "b64_json", "base64", "result" -> if (looksLikeBase64(value)) {
                        val resolvedMime = mime.ifBlank { defaultMimeType(kind) }
                        add("data:$resolvedMime;base64,${value.trim()}", resolvedMime)
                    } else {
                        collectText(value, mime)
                    }
                    "url", "uri", "fileuri", "file_uri", "image_url", "video_url", "audio_url" ->
                        collectText(value, mime, acceptPlainUrl = true)
                    "content", "text", "output_text", "data" -> collectText(value, mime)
                }
            }
        }
    }
    val parsed = runCatching { JSONObject(body) }.getOrNull()
        ?: runCatching { JSONArray(body) }.getOrNull()
    if (parsed != null) walk(parsed, "", null) else collectText(body, acceptPlainUrl = true)
    return output
}

internal fun extractRenderedMediaSources(content: String): List<String> {
    val markdown = Regex("""!\[[^]]*]\(([^)\s]+)\)""")
        .findAll(content)
        .map { it.groupValues[1] }
        .toList()
    if (markdown.isNotEmpty()) return markdown
    val trimmed = content.trim()
    return if (
        trimmed.startsWith("data:", true) ||
        trimmed.startsWith("http://", true) ||
        trimmed.startsWith("https://", true) ||
        trimmed.startsWith("file://", true) ||
        trimmed.startsWith("/")
    ) listOf(trimmed) else emptyList()
}

internal fun generatedMediaMarkdown(kind: MediaGenerationKind, assets: List<GeneratedMediaAsset>): String =
    assets.mapIndexed { index, asset ->
        val label = when (kind) {
            MediaGenerationKind.IMAGE -> "Generated image"
            MediaGenerationKind.VIDEO -> "Generated video"
            MediaGenerationKind.MUSIC -> "Generated music"
            MediaGenerationKind.AUDIO -> "Generated audio"
        }
        "![$label ${index + 1}](${asset.source})"
    }.joinToString("\n\n")

private fun MediaGenerationKind.acceptsMimeType(mimeType: String): Boolean = when (this) {
    MediaGenerationKind.IMAGE -> mimeType.startsWith("image/", true)
    MediaGenerationKind.VIDEO -> mimeType.startsWith("video/", true)
    MediaGenerationKind.MUSIC, MediaGenerationKind.AUDIO -> mimeType.startsWith("audio/", true)
}

private fun defaultMimeType(kind: MediaGenerationKind): String = when (kind) {
    MediaGenerationKind.IMAGE -> "image/png"
    MediaGenerationKind.VIDEO -> "video/mp4"
    MediaGenerationKind.MUSIC, MediaGenerationKind.AUDIO -> "audio/mpeg"
}

private fun dataUrlMimeType(value: String): String? =
    Regex("""^data:([^;,]+);base64,""", RegexOption.IGNORE_CASE).find(value.trim())?.groupValues?.get(1)

private fun looksLikeBase64(value: String): Boolean {
    val clean = value.trim().replace("\n", "").replace("\r", "")
    return clean.length >= 128 && clean.length % 4 == 0 && BASE64_PATTERN.matches(clean)
}

internal fun sanitizeMediaGenerationError(value: String): String = value
    .replace(DATA_URL_PATTERN, "[media data omitted]")
    .replace(MEDIA_URL_PATTERN, "[media URL omitted]")
    .replace(Regex("""[A-Za-z0-9+/_=-]{128,}"""), "[binary data omitted]")
    .replace(Regex("""\s+"""), " ")
    .trim()
    .take(800)
    .ifBlank { "No readable error text was returned." }

private fun mimeTypeFromName(value: String): String? {
    val clean = value.substringBefore('?').substringBefore('#').lowercase(Locale.US)
    return when {
        clean.endsWith(".png") -> "image/png"
        clean.endsWith(".jpg") || clean.endsWith(".jpeg") -> "image/jpeg"
        clean.endsWith(".gif") -> "image/gif"
        clean.endsWith(".webp") -> "image/webp"
        clean.endsWith(".mp4") || clean.endsWith(".m4v") || clean.endsWith(".mov") -> "video/mp4"
        clean.endsWith(".webm") -> "video/webm"
        clean.endsWith(".wav") -> "audio/wav"
        clean.endsWith(".aac") || clean.endsWith(".m4a") -> "audio/aac"
        clean.endsWith(".ogg") -> "audio/ogg"
        clean.endsWith(".flac") -> "audio/flac"
        clean.endsWith(".mp3") -> "audio/mpeg"
        else -> null
    }
}

private fun extensionForMime(mimeType: String): String = when (mimeType.lowercase(Locale.US)) {
    "image/jpeg", "image/jpg" -> "jpg"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    "video/webm" -> "webm"
    "video/quicktime" -> "mov"
    "audio/wav", "audio/x-wav" -> "wav"
    "audio/aac" -> "aac"
    "audio/ogg" -> "ogg"
    "audio/flac" -> "flac"
    "audio/mp4" -> "m4a"
    "audio/mpeg" -> "mp3"
    "video/mp4" -> "mp4"
    else -> if (mimeType.startsWith("image/")) "png" else if (mimeType.startsWith("video/")) "mp4" else "mp3"
}

private fun sniffMimeType(bytes: ByteArray): String? = when {
    bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() -> "image/png"
    bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
    bytes.size >= 12 && bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp" -> "video/mp4"
    bytes.size >= 3 && bytes.copyOfRange(0, 3).toString(Charsets.US_ASCII) == "ID3" -> "audio/mpeg"
    bytes.size >= 4 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" -> "audio/wav"
    else -> null
}

private fun audioFormat(mimeType: String): String = when {
    mimeType.contains("wav", true) -> "wav"
    else -> "mp3"
}

private fun readBoundedBytes(input: java.io.InputStream, maxBytes: Long, tooLargeMessage: String): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= maxBytes) { tooLargeMessage }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private val DATA_URL_PATTERN = Regex("""data:((?:image|video|audio)/[^;,\s]+);base64,[A-Za-z0-9+/_=-]+""", RegexOption.IGNORE_CASE)
private val MEDIA_URL_PATTERN = Regex("""https?://[^\s)\]>'\"]+\.(?:png|jpe?g|gif|webp|bmp|mp4|webm|mov|m4v|mp3|wav|m4a|aac|ogg|flac)(?:\?[^\s)\]>'\"]*)?""", RegexOption.IGNORE_CASE)
private val BASE64_PATTERN = Regex("""^[A-Za-z0-9+/_=-]+$""")
