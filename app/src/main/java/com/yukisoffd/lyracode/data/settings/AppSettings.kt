package com.yukisoffd.lyracode.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.yukisoffd.lyracode.R
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream


class AppSettings(context: Context) {
    private val appContext = context.applicationContext
    private val plainPrefs = appContext.getSharedPreferences("lyra_settings", Context.MODE_PRIVATE)
    private val securePrefs = createSecurePrefs()
    private val skillImportClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** App-private OpenSSH known_hosts file used by both exec and interactive SSH. */
    fun sshKnownHostsFile(): File {
        val directory = File(appContext.filesDir, "ssh").also { it.mkdirs() }
        return File(directory, "known_hosts").also { file ->
            if (!file.exists()) file.createNewFile()
        }
    }

    init {
        cleanupRetiredConversationModeData()
    }

    private fun cleanupRetiredConversationModeData() {
        File(appContext.filesDir, "roleplay").deleteRecursively()
        val editor = plainPrefs.edit()
            .remove("immersive_roleplay_enabled")
            .remove("selected_roleplay_id")
        plainPrefs.all.keys
            .filter { it.startsWith("roleplay_affection_") || it.startsWith("${KEY_CHAT_INPUT_DRAFT_PREFIX}roleplay:") }
            .forEach { editor.remove(it) }
        if (plainPrefs.getString(KEY_SELECTED_SYSTEM_PROMPT_ID, NATIVE_SYSTEM_PROMPT_ID) == RETIRED_ROLEPLAY_PROMPT_ID) {
            editor.putString(KEY_SELECTED_SYSTEM_PROMPT_ID, NATIVE_SYSTEM_PROMPT_ID)
        }
        plainPrefs.getString(KEY_CUSTOM_SYSTEM_PROMPTS, null)
            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
            ?.takeIf { it.has(RETIRED_ROLEPLAY_PROMPT_ID) }
            ?.let { prompts ->
                prompts.remove(RETIRED_ROLEPLAY_PROMPT_ID)
                editor.putString(KEY_CUSTOM_SYSTEM_PROMPTS, prompts.toString())
            }
        plainPrefs.getString(KEY_SYSTEM_PROMPT_CONFIGS, null)
            ?.let { raw -> runCatching { JSONArray(raw) }.getOrNull() }
            ?.let { configs ->
                val sanitized = JSONArray()
                var removed = false
                for (index in 0 until configs.length()) {
                    val item = configs.optJSONObject(index) ?: continue
                    if (item.optString("id") == RETIRED_ROLEPLAY_PROMPT_ID) {
                        removed = true
                    } else {
                        sanitized.put(item)
                    }
                }
                if (removed) editor.putString(KEY_SYSTEM_PROMPT_CONFIGS, sanitized.toString())
            }
        editor.apply()
    }

    var workspaceUri: String?
        get() = plainPrefs.getString(KEY_WORKSPACE_URI, null)
        set(value) = plainPrefs.edit().putString(KEY_WORKSPACE_URI, value).apply()

    var apiKey: String
        get() = securePrefs.getString(KEY_API_KEY, "").orEmpty()
        set(value) = securePrefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var apiEndpoint: String
        get() = plainPrefs.getString(KEY_API_ENDPOINT, DEFAULT_ENDPOINT).orEmpty().ifBlank { DEFAULT_ENDPOINT }
        set(value) = plainPrefs.edit().putString(KEY_API_ENDPOINT, value.trim().ifBlank { DEFAULT_ENDPOINT }).apply()

    var model: String
        get() = plainPrefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        set(value) = plainPrefs.edit().putString(KEY_MODEL, value.trim().ifBlank { DEFAULT_MODEL }).apply()

    var topicSummaryProfileId: String
        get() = plainPrefs.getString(KEY_TOPIC_SUMMARY_PROFILE_ID, "").orEmpty()
        set(value) = plainPrefs.edit().putString(KEY_TOPIC_SUMMARY_PROFILE_ID, value.trim()).apply()

    var topicSummaryModel: String
        get() = plainPrefs.getString(KEY_TOPIC_SUMMARY_MODEL, "").orEmpty()
        set(value) = plainPrefs.edit().putString(KEY_TOPIC_SUMMARY_MODEL, value.trim()).apply()

    fun topicSummaryProfile(): ApiProfile {
        val available = profiles()
        return available.firstOrNull { it.id == topicSummaryProfileId }
            ?: available.firstOrNull { it.id == selectedApiProfileId }
            ?: available.first()
    }

    var historyCompressionProfileId: String
        get() = plainPrefs.getString(KEY_HISTORY_COMPRESSION_PROFILE_ID, "").orEmpty()
        set(value) = plainPrefs.edit().putString(KEY_HISTORY_COMPRESSION_PROFILE_ID, value.trim()).apply()

    var historyCompressionModel: String
        get() = plainPrefs.getString(KEY_HISTORY_COMPRESSION_MODEL, "").orEmpty()
        set(value) = plainPrefs.edit().putString(KEY_HISTORY_COMPRESSION_MODEL, value.trim()).apply()

    var historyCompressionChunkCount: Int
        get() = plainPrefs.getInt(KEY_HISTORY_COMPRESSION_CHUNK_COUNT, DEFAULT_HISTORY_COMPRESSION_CHUNKS)
            .coerceIn(MIN_HISTORY_COMPRESSION_CHUNKS, MAX_HISTORY_COMPRESSION_CHUNKS)
        set(value) = plainPrefs.edit()
            .putInt(KEY_HISTORY_COMPRESSION_CHUNK_COUNT, value.coerceIn(MIN_HISTORY_COMPRESSION_CHUNKS, MAX_HISTORY_COMPRESSION_CHUNKS))
            .apply()

    fun historyCompressionProfileOrNull(): ApiProfile? {
        if (historyCompressionProfileId.isBlank() || historyCompressionModel.isBlank()) return null
        return profiles().firstOrNull { it.id == historyCompressionProfileId }
    }

    fun mediaGenerationModel(kind: MediaGenerationKind): MediaGenerationModelConfig = MediaGenerationModelConfig(
        kind = kind,
        profileId = plainPrefs.getString(mediaProfileKey(kind), "").orEmpty(),
        model = plainPrefs.getString(mediaModelKey(kind), "").orEmpty(),
    )

    fun mediaGenerationModelOrNull(kind: MediaGenerationKind): Pair<ApiProfile, String>? {
        val config = mediaGenerationModel(kind)
        if (config.profileId.isBlank() || config.model.isBlank()) return null
        val profile = profiles().firstOrNull { it.id == config.profileId } ?: return null
        return profile to config.model
    }

    fun saveMediaGenerationModel(kind: MediaGenerationKind, profileId: String, model: String) {
        plainPrefs.edit()
            .putString(mediaProfileKey(kind), profileId.trim())
            .putString(mediaModelKey(kind), model.trim())
            .apply()
    }

    var visionSupplementEnabled: Boolean
        get() = plainPrefs.getBoolean(KEY_VISION_SUPPLEMENT_ENABLED, false)
        set(value) = plainPrefs.edit().putBoolean(KEY_VISION_SUPPLEMENT_ENABLED, value).apply()

    fun visionUnderstandingConfig(): VisionUnderstandingConfig = VisionUnderstandingConfig(
        enabled = plainPrefs.getBoolean(KEY_VISION_UNDERSTANDING_ENABLED, false),
        sourceType = plainPrefs.getString(KEY_VISION_UNDERSTANDING_SOURCE_TYPE, VISION_SOURCE_MODEL)
            .orEmpty()
            .let { if (it == VISION_SOURCE_MCP) VISION_SOURCE_MCP else VISION_SOURCE_MODEL },
        profileId = plainPrefs.getString(KEY_VISION_UNDERSTANDING_PROFILE_ID, "").orEmpty(),
        model = plainPrefs.getString(KEY_VISION_UNDERSTANDING_MODEL, "").orEmpty(),
        mcpServerId = plainPrefs.getString(KEY_VISION_UNDERSTANDING_MCP_SERVER_ID, "").orEmpty(),
        mcpToolName = plainPrefs.getString(KEY_VISION_UNDERSTANDING_MCP_TOOL_NAME, "").orEmpty(),
        relayPrompt = plainPrefs.getString(KEY_VISION_UNDERSTANDING_RELAY_PROMPT, DEFAULT_VISION_RELAY_PROMPT)
            .orEmpty()
            .ifBlank { DEFAULT_VISION_RELAY_PROMPT },
    )

    fun saveVisionUnderstandingConfig(config: VisionUnderstandingConfig) {
        plainPrefs.edit()
            .putBoolean(KEY_VISION_UNDERSTANDING_ENABLED, config.enabled)
            .putString(
                KEY_VISION_UNDERSTANDING_SOURCE_TYPE,
                if (config.sourceType == VISION_SOURCE_MCP) VISION_SOURCE_MCP else VISION_SOURCE_MODEL,
            )
            .putString(KEY_VISION_UNDERSTANDING_PROFILE_ID, config.profileId.trim())
            .putString(KEY_VISION_UNDERSTANDING_MODEL, config.model.trim())
            .putString(KEY_VISION_UNDERSTANDING_MCP_SERVER_ID, config.mcpServerId.trim())
            .putString(KEY_VISION_UNDERSTANDING_MCP_TOOL_NAME, config.mcpToolName.trim())
            .putString(KEY_VISION_UNDERSTANDING_RELAY_PROMPT, config.relayPrompt.trim().ifBlank { DEFAULT_VISION_RELAY_PROMPT })
            .apply()
    }

    fun visionUnderstandingModelOrNull(): Pair<ApiProfile, String>? {
        val config = visionUnderstandingConfig()
        if (!config.enabled || config.sourceType != VISION_SOURCE_MODEL || config.profileId.isBlank() || config.model.isBlank()) return null
        return profiles().firstOrNull { it.id == config.profileId }?.let { it to config.model }
    }

    fun visionUnderstandingMcpToolOrNull(): Pair<McpServerConfig, McpToolDefinition>? {
        val config = visionUnderstandingConfig()
        if (!config.enabled || config.sourceType != VISION_SOURCE_MCP) return null
        val server = mcpServers().firstOrNull { it.enabled && it.id == config.mcpServerId } ?: return null
        return server.tools.firstOrNull { it.name == config.mcpToolName }?.let { server to it }
    }

    fun ocrModelConfig(): OcrModelConfig = OcrModelConfig(
        enabled = plainPrefs.getBoolean(KEY_OCR_MODEL_ENABLED, false),
        profileId = plainPrefs.getString(KEY_OCR_MODEL_PROFILE_ID, "").orEmpty(),
        model = plainPrefs.getString(KEY_OCR_MODEL, "").orEmpty(),
    )

    fun saveOcrModelConfig(config: OcrModelConfig) {
        plainPrefs.edit()
            .putBoolean(KEY_OCR_MODEL_ENABLED, config.enabled)
            .putString(KEY_OCR_MODEL_PROFILE_ID, config.profileId.trim())
            .putString(KEY_OCR_MODEL, config.model.trim())
            .apply()
    }

    fun ocrModelOrNull(): Pair<ApiProfile, String>? {
        val config = ocrModelConfig()
        if (!config.enabled || config.profileId.isBlank() || config.model.isBlank()) return null
        return profiles().firstOrNull { it.id == config.profileId }?.let { it to config.model }
    }

    fun hasVisionSupplementProvider(): Boolean =
        visionUnderstandingModelOrNull() != null || visionUnderstandingMcpToolOrNull() != null || ocrModelOrNull() != null

    fun isVisionSupplementRoutingEnabled(): Boolean = visionSupplementEnabled && hasVisionSupplementProvider()

    private fun mediaProfileKey(kind: MediaGenerationKind): String = when (kind) {
        MediaGenerationKind.IMAGE -> KEY_IMAGE_GENERATION_PROFILE_ID
        MediaGenerationKind.VIDEO -> KEY_VIDEO_GENERATION_PROFILE_ID
        MediaGenerationKind.MUSIC -> KEY_MUSIC_GENERATION_PROFILE_ID
        MediaGenerationKind.AUDIO -> KEY_AUDIO_GENERATION_PROFILE_ID
    }

    private fun mediaModelKey(kind: MediaGenerationKind): String = when (kind) {
        MediaGenerationKind.IMAGE -> KEY_IMAGE_GENERATION_MODEL
        MediaGenerationKind.VIDEO -> KEY_VIDEO_GENERATION_MODEL
        MediaGenerationKind.MUSIC -> KEY_MUSIC_GENERATION_MODEL
        MediaGenerationKind.AUDIO -> KEY_AUDIO_GENERATION_MODEL
    }

    var darkMode: Boolean
        get() = plainPrefs.getBoolean(KEY_DARK_MODE, false)
        set(value) = plainPrefs.edit().putBoolean(KEY_DARK_MODE, value).apply()

    var appIconId: String
        get() = com.yukisoffd.lyracode.AppIcon.fromId(plainPrefs.getString("app_icon_id", null)).id
        set(value) = plainPrefs.edit().putString("app_icon_id", com.yukisoffd.lyracode.AppIcon.fromId(value).id).apply()

    var themeMode: String
        get() = plainPrefs.getString(KEY_THEME_MODE, if (darkMode) THEME_DARK else THEME_SYSTEM)
            .orEmpty()
            .ifBlank { THEME_SYSTEM }
        set(value) = plainPrefs.edit().putString(KEY_THEME_MODE, value).apply()

    var dynamicColorEnabled: Boolean
        get() = plainPrefs.getBoolean(KEY_DYNAMIC_COLOR_ENABLED, false)
        set(value) = plainPrefs.edit().putBoolean(KEY_DYNAMIC_COLOR_ENABLED, value).apply()

    var predictiveBackEnabled: Boolean
        get() = plainPrefs.getBoolean(KEY_PREDICTIVE_BACK_ENABLED, false)
        set(value) = plainPrefs.edit().putBoolean(KEY_PREDICTIVE_BACK_ENABLED, value).apply()

    var deviceInteractionExperimentalEnabled: Boolean
        get() = plainPrefs.getBoolean(KEY_DEVICE_INTERACTION_EXPERIMENTAL_ENABLED, false)
        set(value) = plainPrefs.edit()
            .putBoolean(KEY_DEVICE_INTERACTION_EXPERIMENTAL_ENABLED, value)
            .apply()

    var customThemeColorEnabled: Boolean
        get() = plainPrefs.getBoolean(KEY_CUSTOM_THEME_COLOR_ENABLED, false)
        set(value) = plainPrefs.edit().putBoolean(KEY_CUSTOM_THEME_COLOR_ENABLED, value).apply()

    var customThemeColor: String
        get() = normalizeHexColor(plainPrefs.getString(KEY_CUSTOM_THEME_COLOR, DEFAULT_CUSTOM_THEME_COLOR).orEmpty())
        set(value) = plainPrefs.edit().putString(KEY_CUSTOM_THEME_COLOR, normalizeHexColor(value)).apply()

    var languageMode: String
        get() = normalizeLanguageMode(
            plainPrefs.getString(KEY_LANGUAGE_MODE, LANGUAGE_SYSTEM)
                .orEmpty()
                .ifBlank { LANGUAGE_SYSTEM },
        )
        set(value) = plainPrefs.edit().putString(KEY_LANGUAGE_MODE, normalizeLanguageMode(value)).apply()

    var refreshRateMode: String
        get() = plainPrefs.getString(KEY_REFRESH_RATE_MODE, REFRESH_RATE_SYSTEM)
            .orEmpty()
            .ifBlank { REFRESH_RATE_SYSTEM }
        set(value) = plainPrefs.edit().putString(KEY_REFRESH_RATE_MODE, value).apply()

    var taskCompletionNotificationsEnabled: Boolean
        get() = plainPrefs.getBoolean(KEY_DOWNLOAD_COMPLETION_NOTIFICATIONS, true)
        set(value) = plainPrefs.edit().putBoolean(KEY_DOWNLOAD_COMPLETION_NOTIFICATIONS, value).apply()

    var downloadCompletionNotificationsEnabled: Boolean
        get() = taskCompletionNotificationsEnabled
        set(value) {
            taskCompletionNotificationsEnabled = value
        }

    var fontScaleMode: String
        get() = plainPrefs.getString(KEY_FONT_SCALE_MODE, FONT_SCALE_SYSTEM)
            .orEmpty()
            .ifBlank { FONT_SCALE_SYSTEM }
        set(value) = plainPrefs.edit().putString(KEY_FONT_SCALE_MODE, value).apply()

    var customFontScale: Float
        get() = plainPrefs.getFloat(KEY_CUSTOM_FONT_SCALE, 1.0f).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        set(value) = plainPrefs.edit().putFloat(KEY_CUSTOM_FONT_SCALE, value.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)).apply()

    fun effectiveFontScale(systemFontScale: Float): Float = when (fontScaleMode) {
        FONT_SCALE_SMALL -> 0.9f
        FONT_SCALE_NORMAL -> 1.0f
        FONT_SCALE_LARGE -> 1.12f
        FONT_SCALE_EXTRA_LARGE -> 1.25f
        FONT_SCALE_CUSTOM -> customFontScale
        else -> systemFontScale
    }.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)

    var textFontPath: String?
        get() = plainPrefs.getString(KEY_TEXT_FONT_PATH, null)?.takeIf { File(it).isFile }
        private set(value) = plainPrefs.edit().putString(KEY_TEXT_FONT_PATH, value).apply()

    var codeFontPath: String?
        get() = plainPrefs.getString(KEY_CODE_FONT_PATH, null)?.takeIf { File(it).isFile }
        private set(value) = plainPrefs.edit().putString(KEY_CODE_FONT_PATH, value).apply()

    var textFontName: String?
        get() = plainPrefs.getString(KEY_TEXT_FONT_NAME, null)
        private set(value) = plainPrefs.edit().putString(KEY_TEXT_FONT_NAME, value).apply()

    var codeFontName: String?
        get() = plainPrefs.getString(KEY_CODE_FONT_NAME, null)
        private set(value) = plainPrefs.edit().putString(KEY_CODE_FONT_NAME, value).apply()

    fun fontLibrary(): List<FontLibraryItem> {
        val saved = runCatching {
            val array = JSONArray(plainPrefs.getString(KEY_FONT_LIBRARY, "[]").orEmpty())
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val path = item.optString("path")
                    if (File(path).isFile) add(
                        FontLibraryItem(
                            id = item.optString("id").ifBlank { path },
                            name = item.optString("name").ifBlank { File(path).name },
                            path = path,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
        val legacy = listOfNotNull(
            textFontPath?.let { FontLibraryItem("legacy_text", textFontName ?: File(it).name, it) },
            codeFontPath?.let { FontLibraryItem("legacy_code", codeFontName ?: File(it).name, it) },
        )
        return (saved + legacy).distinctBy { it.path }
    }

    fun importFonts(uris: List<Uri>): Result<List<FontLibraryItem>> = runCatching {
        require(uris.isNotEmpty()) { "未选择字体文件" }
        val existing = fontLibrary().toMutableList()
        val imported = mutableListOf<FontLibraryItem>()
        val dir = File(appContext.filesDir, "custom_fonts/library").apply { mkdirs() }
        try {
            for (uri in uris) {
                val name = displayName(uri).ifBlank { "font.ttf" }
                val extension = name.substringAfterLast('.', "").lowercase()
                require(extension in SUPPORTED_FONT_EXTENSIONS) { "仅支持 TTF、OTF 和 TTC 字体文件：$name" }
                val id = UUID.randomUUID().toString()
                val target = File(dir, "$id.$extension")
                val temporary = File(dir, "$id.importing")
                try {
                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                        temporary.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("无法读取字体文件：$name")
                    runCatching { Typeface.createFromFile(temporary) }
                        .getOrElse { throw IllegalArgumentException("字体文件无效：$name", it) }
                    require(temporary.renameTo(target)) { "无法保存字体文件：$name" }
                    FontLibraryItem(id, name, target.absolutePath).also {
                        existing += it
                        imported += it
                    }
                } finally {
                    if (temporary.exists()) temporary.delete()
                }
            }
        } catch (error: Throwable) {
            imported.forEach { runCatching { File(it.path).delete() } }
            throw error
        }
        saveFontLibrary(existing)
        imported
    }
    fun selectFont(item: FontLibraryItem?, codeFont: Boolean) {
        if (codeFont) {
            codeFontPath = item?.path
            codeFontName = item?.name
        } else {
            textFontPath = item?.path
            textFontName = item?.name
        }
    }

    fun deleteFont(item: FontLibraryItem) {
        if (textFontPath == item.path) selectFont(null, codeFont = false)
        if (codeFontPath == item.path) selectFont(null, codeFont = true)
        saveFontLibrary(fontLibrary().filterNot { it.path == item.path })
        runCatching { File(item.path).delete() }
    }

    private fun saveFontLibrary(items: List<FontLibraryItem>) {
        val array = JSONArray().also { result ->
            items.distinctBy { it.path }.forEach { item ->
                result.put(JSONObject().put("id", item.id).put("name", item.name).put("path", item.path))
            }
        }
        plainPrefs.edit().putString(KEY_FONT_LIBRARY, array.toString()).apply()
    }

    fun clearFont(codeFont: Boolean) {
        selectFont(null, codeFont)
    }
    var requestRootAccess: Boolean
        get() = plainPrefs.getBoolean(KEY_REQUEST_ROOT_ACCESS, false)
        set(value) = plainPrefs.edit().putBoolean(KEY_REQUEST_ROOT_ACCESS, value).apply()

    var requestShellAccess: Boolean
        get() = plainPrefs.getBoolean(KEY_REQUEST_SHELL_ACCESS, false)
        set(value) = plainPrefs.edit().putBoolean(KEY_REQUEST_SHELL_ACCESS, value).apply()

    var customSuCommand: String
        get() = plainPrefs.getString(KEY_CUSTOM_SU_COMMAND, DEFAULT_SU_COMMAND)
            .orEmpty()
            .trim()
            .ifBlank { DEFAULT_SU_COMMAND }
        set(value) = plainPrefs.edit().putString(
            KEY_CUSTOM_SU_COMMAND,
            value.trim().ifBlank { DEFAULT_SU_COMMAND },
        ).apply()

    var userNickname: String
        get() = plainPrefs.getString(KEY_USER_NICKNAME, "用户").orEmpty().ifBlank { "用户" }
        set(value) = plainPrefs.edit().putString(KEY_USER_NICKNAME, value.trim().ifBlank { "用户" }).apply()

    var userAvatarPath: String?
        get() = plainPrefs.getString(KEY_USER_AVATAR_PATH, null)
        set(value) = plainPrefs.edit().putString(KEY_USER_AVATAR_PATH, value).apply()

    fun memories(): List<MemoryEntry> {
        val raw = plainPrefs.getString(KEY_MEMORIES, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching { parseMemories(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    fun createMemory(
        content: String,
        category: String = MemoryEntry.CATEGORY_OTHER,
        enabled: Boolean = true,
    ): MemoryEntry {
        val cleanContent = normalizeMemoryContent(content)
        require(cleanContent.isNotBlank()) { "记忆内容不能为空" }
        val now = System.currentTimeMillis()
        val existing = memories()
        val duplicate = existing.firstOrNull {
            memoryFingerprint(it.content) == memoryFingerprint(cleanContent)
        }
        val memory = if (duplicate != null) {
            duplicate.copy(
                content = cleanContent,
                category = MemoryEntry.normalizeCategory(category),
                enabled = enabled,
                updatedAt = now,
            )
        } else {
            MemoryEntry(
                id = UUID.randomUUID().toString(),
                content = cleanContent,
                category = MemoryEntry.normalizeCategory(category),
                enabled = enabled,
                createdAt = now,
                updatedAt = now,
            )
        }
        saveMemories(existing.filterNot { it.id == memory.id } + memory)
        return memory
    }

    fun updateMemory(
        id: String,
        content: String? = null,
        category: String? = null,
        enabled: Boolean? = null,
    ): MemoryEntry {
        val existing = memories()
        val current = existing.firstOrNull { it.id == id } ?: error("记忆不存在: $id")
        val nextContent = content?.let(::normalizeMemoryContent) ?: current.content
        require(nextContent.isNotBlank()) { "记忆内容不能为空" }
        val updated = current.copy(
            content = nextContent,
            category = category?.let { MemoryEntry.normalizeCategory(it) } ?: current.category,
            enabled = enabled ?: current.enabled,
            updatedAt = System.currentTimeMillis(),
        )
        saveMemories(existing.map { if (it.id == id) updated else it })
        return updated
    }

    fun deleteMemory(id: String): Boolean {
        val existing = memories()
        val updated = existing.filterNot { it.id == id }
        if (updated.size == existing.size) return false
        saveMemories(updated)
        return true
    }

    fun memoryPrompt(): String {
        val array = JSONArray()
        var usedChars = 0
        memories()
            .asSequence()
            .filter { it.enabled }
            .sortedBy { it.createdAt }
            .forEach { memory ->
                val item = memoryJson(memory)
                val itemChars = item.toString().length
                if (usedChars + itemChars <= MAX_MEMORY_PROMPT_CHARS) {
                    array.put(item)
                    usedChars += itemChars
                }
            }
        return array.toString()
    }

    private fun saveMemories(items: List<MemoryEntry>) {
        val sanitized = items
            .asSequence()
            .mapNotNull { memory ->
                val content = normalizeMemoryContent(memory.content)
                content.takeIf { it.isNotBlank() }?.let {
                    memory.copy(
                        id = memory.id.trim().ifBlank { UUID.randomUUID().toString() },
                        content = it,
                        category = MemoryEntry.normalizeCategory(memory.category),
                    )
                }
            }
            .distinctBy { it.id }
            .sortedByDescending { it.updatedAt }
            .take(MAX_MEMORY_COUNT)
            .toList()
        plainPrefs.edit().putString(
            KEY_MEMORIES,
            JSONArray().also { array -> sanitized.forEach { array.put(memoryJson(it)) } }.toString(),
        ).apply()
    }

    private fun parseMemories(array: JSONArray): List<MemoryEntry> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val content = normalizeMemoryContent(item.optString("content"))
            if (content.isBlank()) continue
            val createdAt = item.optLong("createdAt", System.currentTimeMillis())
            add(
                MemoryEntry(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    content = content,
                    category = MemoryEntry.normalizeCategory(item.optString("category")),
                    enabled = item.optBoolean("enabled", true),
                    createdAt = createdAt,
                    updatedAt = item.optLong("updatedAt", createdAt),
                ),
            )
        }
    }.distinctBy { it.id }

    private fun memoryJson(memory: MemoryEntry): JSONObject = JSONObject()
        .put("id", memory.id)
        .put("content", memory.content)
        .put("category", memory.category)
        .put("enabled", memory.enabled)
        .put("createdAt", memory.createdAt)
        .put("updatedAt", memory.updatedAt)

    private fun mergeMemories(existing: List<MemoryEntry>, imported: List<MemoryEntry>): List<MemoryEntry> {
        val merged = existing.toMutableList()
        imported.forEach { memory ->
            val sameId = merged.indexOfFirst { it.id == memory.id }
            val sameContent = merged.indexOfFirst {
                memoryFingerprint(it.content) == memoryFingerprint(memory.content)
            }
            val index = when {
                sameId >= 0 -> sameId
                sameContent >= 0 -> sameContent
                else -> -1
            }
            if (index < 0) {
                merged += memory
            } else if (memory.updatedAt >= merged[index].updatedAt) {
                merged[index] = memory
            }
        }
        return merged
    }

    private fun normalizeMemoryContent(value: String): String =
        value.replace("\r\n", "\n").replace('\r', '\n').trim().take(MAX_MEMORY_CONTENT_CHARS)

    private fun memoryFingerprint(value: String): String =
        value.trim().replace(Regex("""\s+"""), " ").lowercase(Locale.ROOT)

    var streamingAnimationMode: String
        get() = normalizeStreamingAnimationMode(
            plainPrefs.getString(KEY_STREAMING_ANIMATION_MODE, STREAMING_ANIMATION_TYPEWRITER).orEmpty(),
        )
        set(value) = plainPrefs.edit().putString(KEY_STREAMING_ANIMATION_MODE, normalizeStreamingAnimationMode(value)).apply()
    var chatBackgroundPath: String?
        get() = plainPrefs.getString(KEY_CHAT_BACKGROUND_PATH, null)
        set(value) = plainPrefs.edit().putString(KEY_CHAT_BACKGROUND_PATH, value).apply()

    var chatBackgroundMaskOpacity: Float
        get() = plainPrefs.getFloat(KEY_CHAT_BACKGROUND_MASK_OPACITY, DEFAULT_CHAT_BACKGROUND_MASK_OPACITY).coerceIn(0f, 1f)
        set(value) = plainPrefs.edit().putFloat(KEY_CHAT_BACKGROUND_MASK_OPACITY, value.coerceIn(0f, 1f)).apply()

    var hideTermuxPermissionHint: Boolean
        get() = plainPrefs.getBoolean(KEY_HIDE_TERMUX_PERMISSION_HINT, false)
        set(value) = plainPrefs.edit().putBoolean(KEY_HIDE_TERMUX_PERMISSION_HINT, value).apply()


    fun disabledTools(): Set<String> = plainPrefs.getStringSet(KEY_DISABLED_TOOLS, emptySet()).orEmpty()

    fun setToolEnabled(name: String, enabled: Boolean) {
        val updated = disabledTools().toMutableSet()
        if (enabled) updated -= name else updated += name
        plainPrefs.edit().putStringSet(KEY_DISABLED_TOOLS, updated).apply()
    }


    fun chatInputDraft(key: String): String {
        return plainPrefs.getString("$KEY_CHAT_INPUT_DRAFT_PREFIX$key", "").orEmpty()
    }

    fun setChatInputDraft(key: String, text: String) {
        val cleanKey = key.trim().ifBlank { "normal:0" }
        val prefsKey = "$KEY_CHAT_INPUT_DRAFT_PREFIX$cleanKey"
        val editor = plainPrefs.edit()
        if (text.isBlank()) editor.remove(prefsKey) else editor.putString(prefsKey, text)
        editor.apply()
    }

    fun clearChatInputDrafts() {
        val editor = plainPrefs.edit()
        plainPrefs.all.keys
            .filter { it.startsWith(KEY_CHAT_INPUT_DRAFT_PREFIX) }
            .forEach { editor.remove(it) }
        editor.apply()
    }
    fun hiddenTodoSignature(conversationId: Long): String {
        return plainPrefs.getString("$KEY_HIDDEN_TODO_SIGNATURE_PREFIX$conversationId", "").orEmpty()
    }

    fun setHiddenTodoSignature(conversationId: Long, signature: String) {
        plainPrefs.edit().putString("$KEY_HIDDEN_TODO_SIGNATURE_PREFIX$conversationId", signature).apply()
    }

    fun hiddenFileChangesSignature(conversationId: Long): String {
        return plainPrefs.getString("$KEY_HIDDEN_FILE_CHANGES_SIGNATURE_PREFIX$conversationId", "").orEmpty()
    }

    fun setHiddenFileChangesSignature(conversationId: Long, signature: String) {
        plainPrefs.edit().putString("$KEY_HIDDEN_FILE_CHANGES_SIGNATURE_PREFIX$conversationId", signature).apply()
    }

    var purePromptMode: Boolean
        get() = plainPrefs.getBoolean("pure_prompt_mode", false)
        set(value) { plainPrefs.edit().putBoolean("pure_prompt_mode", value).apply() }

    var selectedSystemPromptId: String
        get() {
            val stored = plainPrefs.getString(KEY_SELECTED_SYSTEM_PROMPT_ID, NATIVE_SYSTEM_PROMPT_ID)
                .orEmpty()
                .takeUnless { it == RETIRED_ROLEPLAY_PROMPT_ID }
                .orEmpty()
                .ifBlank { NATIVE_SYSTEM_PROMPT_ID }
            if (stored == NATIVE_SYSTEM_PROMPT_ID) return stored
            val preserved = customSystemPromptConfigs().any { it.id == stored } ||
                customSystemPrompts()[stored].orEmpty().isNotBlank()
            return if (preserved) stored else NATIVE_SYSTEM_PROMPT_ID
        }
        set(value) = plainPrefs.edit()
            .putString(
                KEY_SELECTED_SYSTEM_PROMPT_ID,
                value.takeUnless { it == RETIRED_ROLEPLAY_PROMPT_ID || it.isBlank() } ?: NATIVE_SYSTEM_PROMPT_ID,
            )
            .apply()

    var reasoningDepth: String
        get() = plainPrefs.getString(KEY_REASONING_DEPTH, REASONING_AUTO)
            .orEmpty()
            .ifBlank { REASONING_AUTO }
            .let { value ->
                when {
                    value == "ultra" -> REASONING_MAX
                    value in reasoningDepthValues -> value
                    else -> REASONING_AUTO
                }
            }
        set(value) = plainPrefs.edit().putString(
            KEY_REASONING_DEPTH,
            value.takeIf { it in reasoningDepthValues } ?: REASONING_AUTO,
        ).apply()

    var subAgentOrchestrationEnabled: Boolean
        get() = plainPrefs.getBoolean(KEY_SUB_AGENT_ORCHESTRATION_ENABLED, false)
        set(value) = plainPrefs.edit().putBoolean(KEY_SUB_AGENT_ORCHESTRATION_ENABLED, value).apply()

    fun subAgents(): List<SubAgentConfig> {
        val raw = plainPrefs.getString(KEY_SUB_AGENT_CONFIGS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching { parseSubAgents(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    fun enabledSubAgents(): List<SubAgentConfig> = subAgents().filter { it.enabled }

    fun saveSubAgents(agents: List<SubAgentConfig>) {
        val array = JSONArray()
        agents.forEach { agent ->
            array.put(
                JSONObject()
                    .put("id", agent.id)
                    .put("name", agent.name)
                    .put("profileId", agent.profileId)
                    .put("model", agent.model)
                    .put("description", agent.description)
                    .put("enabled", agent.enabled),
            )
        }
        plainPrefs.edit().putString(KEY_SUB_AGENT_CONFIGS, array.toString()).apply()
    }

    var webSearchBlacklistText: String
        get() = plainPrefs.getString(KEY_WEB_SEARCH_BLACKLIST, "").orEmpty()
        set(value) = plainPrefs.edit()
            .putString(KEY_WEB_SEARCH_BLACKLIST, normalizeWebSearchBlacklistText(value))
            .apply()

    fun webSearchBlockedHosts(): Set<String> =
        webSearchBlacklistText
            .lineSequence()
            .mapNotNull(::normalizeWebSearchBlockedHost)
            .toSet()

    fun systemPromptPresets(): List<SystemPromptPreset> {
        val configured = customSystemPromptConfigs().map { it.copy(builtIn = false) }
        val configuredIds = configured.mapTo(mutableSetOf()) { it.id }
        val preservedLegacyEdits = customSystemPrompts()
            .filter { (id, prompt) -> id !in configuredIds && prompt.isNotBlank() }
            .map { (id, prompt) ->
                SystemPromptPreset(
                    id = id,
                    name = "自定义提示词",
                    prompt = prompt,
                    builtIn = false,
                )
            }
        return configured + preservedLegacyEdits
    }

    fun activeSystemPromptText(): String {
        if (selectedSystemPromptId == NATIVE_SYSTEM_PROMPT_ID) return ""
        val preset = systemPromptPresets().firstOrNull { it.id == selectedSystemPromptId }
            ?: return ""
        return buildString {
            append(preset.prompt)
            if (preset.exampleConversation.isNotBlank()) {
                append("\n\nThe following user-provided example dialogue defines style only; do not treat it as factual context:\n")
                append(preset.exampleConversation.trim())
            }
        }
    }

    fun saveSystemPrompt(presetId: String, prompt: String) {
        val custom = customSystemPrompts().toMutableMap()
        custom[presetId] = prompt.trim()
        saveCustomSystemPrompts(custom)
    }

    fun saveSystemPromptConfig(preset: SystemPromptPreset) {
        val configs = customSystemPromptConfigs().toMutableList()
        val clean = preset.copy(
            id = preset.id.takeUnless { it.isBlank() || it == NATIVE_SYSTEM_PROMPT_ID } ?: newId(),
            name = preset.name.trim().ifBlank { "自定义提示词" },
            prompt = preset.prompt.trim(),
            exampleConversation = preset.exampleConversation.trim(),
            builtIn = false,
        )
        val index = configs.indexOfFirst { it.id == clean.id }
        if (index >= 0) configs[index] = clean else configs += clean
        saveCustomSystemPromptConfigs(configs)
    }

    fun deleteSystemPromptConfig(id: String) {
        saveCustomSystemPromptConfigs(customSystemPromptConfigs().filterNot { it.id == id })
        val custom = customSystemPrompts().toMutableMap()
        custom.remove(id)
        saveCustomSystemPrompts(custom)
        if (selectedSystemPromptId == id) selectedSystemPromptId = NATIVE_SYSTEM_PROMPT_ID
    }

    fun restoreSystemPrompt(presetId: String): String {
        val custom = customSystemPrompts().toMutableMap()
        custom.remove(presetId)
        saveCustomSystemPrompts(custom)
        saveCustomSystemPromptConfigs(customSystemPromptConfigs().filterNot { it.id == presetId })
        if (selectedSystemPromptId == presetId) selectedSystemPromptId = NATIVE_SYSTEM_PROMPT_ID
        return ""
    }

    private fun customSystemPrompts(): Map<String, String> {
        val raw = plainPrefs.getString(KEY_CUSTOM_SYSTEM_PROMPTS, null).orEmpty()
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            root.keys()
                .asSequence()
                .filterNot { it == RETIRED_ROLEPLAY_PROMPT_ID || it == NATIVE_SYSTEM_PROMPT_ID }
                .associateWith { root.optString(it) }
        }.getOrDefault(emptyMap())
    }

    private fun saveCustomSystemPrompts(prompts: Map<String, String>) {
        val root = JSONObject()
        prompts.filterKeys { it != RETIRED_ROLEPLAY_PROMPT_ID && it != NATIVE_SYSTEM_PROMPT_ID }.forEach { (id, prompt) -> root.put(id, prompt) }
        plainPrefs.edit().putString(KEY_CUSTOM_SYSTEM_PROMPTS, root.toString()).apply()
    }

    private fun customSystemPromptConfigs(): List<SystemPromptPreset> {
        val raw = plainPrefs.getString(KEY_SYSTEM_PROMPT_CONFIGS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").ifBlank { newId() }
                    if (id == RETIRED_ROLEPLAY_PROMPT_ID || id == NATIVE_SYSTEM_PROMPT_ID) continue
                    add(
                        SystemPromptPreset(
                            id = id,
                            name = item.optString("name").ifBlank { "自定义提示词" },
                            prompt = item.optString("prompt"),
                            exampleConversation = item.optString("exampleConversation"),
                            builtIn = false,
                        ),
                    )
                }
            }.filter { it.prompt.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun saveCustomSystemPromptConfigs(prompts: List<SystemPromptPreset>) {
        val array = JSONArray()
        prompts.filterNot { it.id == RETIRED_ROLEPLAY_PROMPT_ID || it.id == NATIVE_SYSTEM_PROMPT_ID }.forEach { preset ->
            array.put(
                JSONObject()
                    .put("id", preset.id)
                    .put("name", preset.name)
                    .put("prompt", preset.prompt)
                    .put("exampleConversation", preset.exampleConversation),
            )
        }
        plainPrefs.edit().putString(KEY_SYSTEM_PROMPT_CONFIGS, array.toString()).apply()
    }

    fun profiles(): List<ApiProfile> {
        val raw = securePrefs.getString(KEY_API_PROFILES, null)
        if (raw.isNullOrBlank()) return listOf(defaultProfile())
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val models = item.optJSONArray("savedModels") ?: JSONArray()
                    val savedModels = buildList {
                        for (modelIndex in 0 until models.length()) add(models.getString(modelIndex))
                    }.filter { it.isNotBlank() }.distinct()
                    val enabledModels = if (item.has("enabledModels")) {
                        val enabled = item.optJSONArray("enabledModels") ?: JSONArray()
                        buildList {
                            for (modelIndex in 0 until enabled.length()) add(enabled.optString(modelIndex))
                        }.filter { it.isNotBlank() }.distinct()
                    } else {
                        savedModels
                    }
                    val apiFormat = item.optString("apiFormat").ifBlank { ApiProfile.API_FORMAT_OPENAI }
                    add(
                        ApiProfile(
                            id = item.optString("id").ifBlank { newId() },
                            presetId = item.optString("presetId"),
                            presetPlanId = item.optString("presetPlanId"),
                            name = item.optString("name").ifBlank { "OpenAI" },
                            apiKey = item.optString("apiKey"),
                            baseUrl = item.optString("baseUrl").ifBlank { DEFAULT_BASE_URL },
                            chatPath = ApiProfile.normalizedChatPath(apiFormat, item.optString("chatPath")),
                            apiFormat = apiFormat,
                            selectedModel = item.optString("selectedModel").ifBlank { DEFAULT_MODEL },
                            savedModels = savedModels,
                            enabledModels = enabledModels,
                            useResponsesApi = apiFormat == ApiProfile.API_FORMAT_OPENAI && item.optBoolean("useResponsesApi", false),
                    modelRequestOverrides = ModelRequestCustomization.decodeMap(item.optJSONObject("modelRequestOverrides")),
                        ),
                    )
                }
            }.ifEmpty { listOf(defaultProfile()) }
        }.getOrDefault(listOf(defaultProfile()))
    }

    fun saveProfiles(profiles: List<ApiProfile>, selectedProfileId: String? = null) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("presetId", profile.presetId)
                    .put("presetPlanId", profile.presetPlanId)
                    .put("name", profile.name)
                    .put("apiKey", profile.apiKey)
                    .put("baseUrl", profile.baseUrl)
                    .put("chatPath", ApiProfile.normalizedChatPath(profile.apiFormat, profile.chatPath))
                    .put("apiFormat", profile.apiFormat)
                    .put("selectedModel", profile.selectedModel)
                    .put("savedModels", JSONArray(profile.savedModels.distinct()))
                    .put("enabledModels", JSONArray(profile.enabledModels.distinct()))
                    .put("useResponsesApi", profile.apiFormat == ApiProfile.API_FORMAT_OPENAI && profile.useResponsesApi)
                    .put("modelRequestOverrides", ModelRequestCustomization.encodeMap(profile.modelRequestOverrides))
            )
        }
        securePrefs.edit().putString(KEY_API_PROFILES, array.toString()).apply()
        selectedProfileId?.let { selectedApiProfileId = it }
        profiles.firstOrNull { it.id == selectedApiProfileId }?.let {
            apiKey = it.apiKey
            apiEndpoint = it.chatEndpoint
            model = it.selectedModel
        }
    }

    var selectedApiProfileId: String
        get() = plainPrefs.getString(KEY_SELECTED_API_PROFILE_ID, null).orEmpty().ifBlank { profiles().first().id }
        set(value) = plainPrefs.edit().putString(KEY_SELECTED_API_PROFILE_ID, value).apply()

    fun selectedProfile(): ApiProfile = profiles().firstOrNull { it.id == selectedApiProfileId } ?: profiles().first()

    fun installedSkills(): List<SkillPack> {
        val enabledIds = enabledSkillIds()
        return skillsRoot().listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val id = dir.name
                val skillFile = findSkillFile(dir) ?: return@mapNotNull null
                val meta = parseSkillMeta(skillFile.readText())
                val name = File(dir, SKILL_NAME_FILE).takeIf { it.exists() }?.readText()?.trim().orEmpty()
                    .ifBlank { meta.first }
                    .ifBlank { id }
                val description = File(dir, SKILL_DESCRIPTION_FILE).takeIf { it.exists() }?.readText()?.trim().orEmpty()
                    .ifBlank { meta.second }
                val fileCount = dir.walkTopDown().count { it.isFile && it.name !in setOf(SKILL_NAME_FILE, SKILL_DESCRIPTION_FILE) }
                SkillPack(id, name, description, enabled = id in enabledIds, fileCount = fileCount)
            }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }

    fun importSkillFile(uri: Uri): Result<SkillPack> = runCatching {
        val sourceName = displayName(uri).ifBlank { "Skill" }
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法读取 Skills 文件")
        if (sourceName.endsWith(".zip", ignoreCase = true) || bytes.isZipBytes()) {
            installSkillZip(sourceName.removeSuffix(".zip").ifBlank { "Skill" }, bytes)
        } else {
            installSkillMarkdown(sourceName, String(bytes, Charsets.UTF_8))
        }
    }

    fun importSkillZip(uri: Uri): Result<SkillPack> = importSkillFile(uri)

    fun importSkillMarkdown(sourceName: String, skillText: String): Result<SkillPack> = runCatching {
        installSkillMarkdown(sourceName.ifBlank { "SKILL.md" }, skillText)
    }

    fun importSkillRepository(repositoryUrl: String): Result<SkillPack> = runCatching {
        val candidates = skillRepositoryCandidates(repositoryUrl.trim())
        require(candidates.isNotEmpty()) { "无法识别仓库链接，请输入 GitHub、Gitee、GitLab 仓库链接、zip 链接或 SKILL.md 原始链接" }
        var lastError: Throwable? = null
        for (candidate in candidates) {
            val result = runCatching {
                val bytes = downloadSkillBytes(candidate.url)
                if (candidate.isZip) {
                    installSkillZip(candidate.sourceName, bytes)
                } else {
                    installSkillMarkdown(candidate.sourceName, String(bytes, Charsets.UTF_8))
                }
            }
            result.onSuccess { return@runCatching it }
            result.onFailure { lastError = it }
        }
        throw lastError ?: IllegalArgumentException("导入仓库失败")
    }

    fun importSkillZipBytes(sourceName: String, bytes: ByteArray): Result<SkillPack> = runCatching {
        installSkillZip(sourceName.removeSuffix(".zip").ifBlank { "Skill" }, bytes)
    }

    private fun installSkillMarkdown(sourceName: String, skillText: String): SkillPack {
        val text = skillText.trim()
        require(text.isNotBlank()) { "SKILL.md 内容不能为空" }
        val id = newId()
        val tempDir = File(skillsRoot(), "$id.tmp").also { it.mkdirs() }
        runCatching {
            File(tempDir, "SKILL.md").writeText(text)
            val meta = parseSkillMeta(text)
            val name = meta.first.ifBlank { sourceName.removeSuffix(".md").ifBlank { "Skill" } }
            File(tempDir, SKILL_NAME_FILE).writeText(name)
            File(tempDir, SKILL_DESCRIPTION_FILE).writeText(meta.second)
            val finalDir = File(skillsRoot(), id)
            if (finalDir.exists()) finalDir.deleteRecursively()
            require(tempDir.renameTo(finalDir)) { "保存 Skill 失败" }
            setSkillEnabled(id, true)
            return SkillPack(id, name, meta.second, enabled = true, fileCount = 1)
        }.onFailure {
            tempDir.deleteRecursively()
            throw it
        }
        error("保存 Skill 失败")
    }

    private fun installSkillZip(sourceName: String, bytes: ByteArray): SkillPack {
        val id = newId()
        val tempDir = File(skillsRoot(), "$id.tmp").also { it.mkdirs() }
        var count = 0
        var totalBytes = 0
        var skillFileRelativePath = ""
        runCatching {
            ByteArrayInputStream(bytes).use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val safePath = safeZipPath(entry.name)
                        if (!entry.isDirectory && safePath != null) {
                            val bytes = zip.readBytes()
                            totalBytes += bytes.size
                            require(totalBytes <= MAX_SKILL_TOTAL_BYTES) { "Skills 包总大小超过 ${MAX_SKILL_TOTAL_BYTES / 1024 / 1024}MB" }
                            val output = File(tempDir, safePath)
                            output.parentFile?.mkdirs()
                            output.writeBytes(bytes)
                            count++
                            if (safePath.substringAfterLast('/').equals("SKILL.md", ignoreCase = true)) {
                                skillFileRelativePath = safePath
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            require(count > 0) { "Skills 压缩包为空" }
            require(skillFileRelativePath.isNotBlank()) { "Skills 压缩包必须包含 SKILL.md" }
        }.onFailure {
            tempDir.deleteRecursively()
            throw it
        }
        val meta = parseSkillMeta(File(tempDir, skillFileRelativePath).readText())
        val name = meta.first.ifBlank { sourceName }
        File(tempDir, SKILL_NAME_FILE).writeText(name)
        File(tempDir, SKILL_DESCRIPTION_FILE).writeText(meta.second)
        val finalDir = File(skillsRoot(), id)
        if (finalDir.exists()) finalDir.deleteRecursively()
        tempDir.renameTo(finalDir)
        setSkillEnabled(id, true)
        return SkillPack(id, name, meta.second, enabled = true, fileCount = count)
    }

    fun setSkillEnabled(id: String, enabled: Boolean) {
        val ids = enabledSkillIds().toMutableSet()
        if (enabled) ids += id else ids -= id
        plainPrefs.edit().putStringSet(KEY_ENABLED_SKILLS, ids).apply()
    }

    fun deleteSkill(id: String) {
        File(skillsRoot(), id).takeIf { it.parentFile == skillsRoot() }?.deleteRecursively()
        setSkillEnabled(id, false)
    }

    fun updateSkillMeta(id: String, name: String? = null, description: String? = null) {
        val dir = skillDir(id)
        name?.trim()?.takeIf { it.isNotBlank() }?.let { File(dir, SKILL_NAME_FILE).writeText(it) }
        description?.trim()?.takeIf { it.isNotBlank() }?.let { File(dir, SKILL_DESCRIPTION_FILE).writeText(it) }
    }

    fun activeSkillsPrompt(forcedSkillIds: Collection<String> = emptyList()): String {
        val forcedIds = forcedSkillIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val installed = installedSkills()
        val skills = (installed.filter { it.enabled } + installed.filter { it.id in forcedIds })
            .distinctBy { it.id }
        if (skills.isEmpty()) return "enabled_skills=[]"
        return buildString {
            appendLine("enabled_skills=[")
            skills.forEach { skill ->
                appendLine("""  {"id":"${skill.id}","name":"${escapeSkillJson(skill.name)}","description":"${escapeSkillJson(skill.description)}","file_count":${skill.fileCount}},""")
            }
            appendLine("]")
            if (forcedIds.isNotEmpty()) {
                appendLine("forced_skill_ids=[${forcedIds.joinToString(",") { "\"${escapeSkillJson(it)}\"" }}]")
                appendLine("The user explicitly selected forced_skill_ids for this request. You must inspect each forced Skill with list_skill_files/read_skill_file, starting from SKILL.md, and apply relevant instructions unless impossible. If a forced Skill cannot be applied, briefly explain why.")
            } else {
                appendLine("Use Skills as optional capability references only. First judge relevance from name/description. If a Skill seems useful, call list_skill_files/read_skill_file to inspect SKILL.md and required files. Do not load every Skill blindly. Some Skills may assume desktop/cloud tools unavailable on Android/Termux; adapt them to Lyra Code's Android environment and current tool limits.")
            }
        }
    }
    fun listSkillFiles(id: String): Result<String> = runCatching {
        val root = skillDir(id)
        root.walkTopDown()
            .filter { it.isFile && it.name !in setOf(SKILL_NAME_FILE, SKILL_DESCRIPTION_FILE) }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted()
            .joinToString("\n")
            .ifBlank { "EMPTY_SKILL" }
    }

    fun readSkillFile(id: String, path: String): Result<String> = runCatching {
        val root = skillDir(id)
        val target = File(root, path.trim().trimStart('/', '\\')).canonicalFile
        require(target.path.startsWith(root.canonicalPath)) { "Skill 文件路径越界" }
        require(target.isFile) { "Skill 文件不存在: $path" }
        require(target.length() <= MAX_SKILL_READ_BYTES) { "Skill 文件超过 ${MAX_SKILL_READ_BYTES / 1024}KB，请读取更小的文件" }
        target.readText()
    }


    fun saveChatBackground(uri: Uri): Result<String> = runCatching {
        val dir = File(appContext.filesDir, "chat_background").apply { mkdirs() }
        dir.listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
        val target = File(dir, "background_${System.currentTimeMillis()}.jpg")
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("无法读取背景图片")
        chatBackgroundPath = target.absolutePath
        target.absolutePath
    }

    fun clearChatBackground() {
        chatBackgroundPath?.let { path -> runCatching { File(path).delete() } }
        File(appContext.filesDir, "chat_background").listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
        chatBackgroundPath = null
        chatBackgroundMaskOpacity = DEFAULT_CHAT_BACKGROUND_MASK_OPACITY
    }


    fun mcpServers(): List<McpServerConfig> {
        val raw = securePrefs.getString(KEY_MCP_SERVERS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val tools = item.optJSONArray("tools") ?: JSONArray()
                    add(
                        McpServerConfig(
                            id = item.optString("id").ifBlank { newId() },
                            name = item.optString("name").ifBlank { "MCP Server" },
                            url = item.optString("url"),
                            authKey = item.optString("authKey"),
                            transport = item.optString("transport").ifBlank { MCP_TRANSPORT_STREAMABLE_HTTP },
                            timeoutSeconds = item.optInt("timeoutSeconds", 30).coerceIn(5, 300),
                            enabled = item.optBoolean("enabled", true),
                            rawJson = item.optString("rawJson").ifBlank { "{}" },
                            tools = buildList {
                                for (toolIndex in 0 until tools.length()) {
                                    val tool = tools.getJSONObject(toolIndex)
                                    add(
                                        McpToolDefinition(
                                            name = tool.optString("name"),
                                            description = tool.optString("description"),
                                            inputSchema = tool.optJSONObject("inputSchema")?.toString()
                                                ?: tool.optString("inputSchema").ifBlank { "{}" },
                                        ),
                                    )
                                }
                            }.filter { it.name.isNotBlank() },
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveMcpServers(servers: List<McpServerConfig>) {
        val array = JSONArray()
        servers.forEach { server ->
            array.put(
                JSONObject()
                    .put("id", server.id)
                    .put("name", server.name)
                    .put("url", server.url)
                    .put("authKey", server.authKey)
                    .put("transport", server.transport)
                    .put("timeoutSeconds", server.timeoutSeconds)
                    .put("enabled", server.enabled)
                    .put("rawJson", server.rawJson.ifBlank { "{}" })
                    .put(
                        "tools",
                        JSONArray().also { tools ->
                            server.tools.forEach { tool ->
                                tools.put(
                                    JSONObject()
                                        .put("name", tool.name)
                                        .put("description", tool.description)
                                        .put("inputSchema", JSONObject(tool.inputSchema.ifBlank { "{}" })),
                                )
                            }
                        },
                    ),
            )
        }
        securePrefs.edit().putString(KEY_MCP_SERVERS, array.toString()).apply()
    }

    fun upsertMcpServer(server: McpServerConfig) {
        val servers = mcpServers().toMutableList()
        val index = servers.indexOfFirst { it.id == server.id }
        if (index >= 0) servers[index] = server else servers += server
        saveMcpServers(servers)
    }

    fun deleteMcpServer(id: String) {
        saveMcpServers(mcpServers().filterNot { it.id == id })
    }

    fun setMcpServerEnabled(id: String, enabled: Boolean) {
        saveMcpServers(mcpServers().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun updateMcpServerTools(id: String, tools: List<McpToolDefinition>) {
        saveMcpServers(mcpServers().map { if (it.id == id) it.copy(tools = tools) else it })
    }

    fun enabledMcpTools(): List<Pair<McpServerConfig, McpToolDefinition>> {
        return mcpServers()
            .filter { it.enabled && it.url.isNotBlank() }
            .flatMap { server -> server.tools.map { tool -> server to tool } }
    }

    fun mcpToolFunctionName(server: McpServerConfig, tool: McpToolDefinition): String {
        val serverPart = safeFunctionPart(server.id).take(12).ifBlank { "server" }
        val toolPart = safeFunctionPart(tool.name).take(42).ifBlank { "tool" }
        return "mcp_${serverPart}_$toolPart".take(64)
    }

    fun resolveMcpTool(functionName: String): Pair<McpServerConfig, McpToolDefinition>? {
        return enabledMcpTools().firstOrNull { (server, tool) -> mcpToolFunctionName(server, tool) == functionName }
    }

    fun localMcpServerConfig(): LocalMcpServerConfig {
        val raw = securePrefs.getString(KEY_LOCAL_MCP_SERVER, null).orEmpty()
        if (raw.isBlank()) return defaultLocalMcpServerConfig()
        return runCatching { parseLocalMcpServerConfig(JSONObject(raw)) }.getOrDefault(defaultLocalMcpServerConfig())
    }

    fun saveLocalMcpServerConfig(config: LocalMcpServerConfig) {
        securePrefs.edit()
            .putString(KEY_LOCAL_MCP_SERVER, localMcpServerConfigJson(config, includeSecrets = true).toString())
            .apply()
    }

    fun sshServers(): List<SshServerConfig> {
        val raw = securePrefs.getString(KEY_SSH_SERVERS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        SshServerConfig(
                            id = item.optString("id").ifBlank { newId() },
                            name = item.optString("name").ifBlank { item.optString("host").ifBlank { "SSH Server" } },
                            host = item.optString("host"),
                            port = item.optInt("port", 22).coerceIn(1, 65535),
                            username = item.optString("username"),
                            authType = item.optString("authType").ifBlank { SSH_AUTH_PASSWORD },
                            password = item.optString("password"),
                            privateKey = item.optString("privateKey"),
                            passphrase = item.optString("passphrase"),
                            timeoutSeconds = item.optInt("timeoutSeconds", 60).coerceIn(5, 600),
                            enabled = item.optBoolean("enabled", true),
                        ),
                    )
                }
            }.filter { it.host.isNotBlank() && it.username.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun saveSshServers(servers: List<SshServerConfig>) {
        val array = JSONArray()
        servers.forEach { server ->
            array.put(
                JSONObject()
                    .put("id", server.id)
                    .put("name", server.name)
                    .put("host", server.host)
                    .put("port", server.port)
                    .put("username", server.username)
                    .put("authType", server.authType)
                    .put("password", server.password)
                    .put("privateKey", server.privateKey)
                    .put("passphrase", server.passphrase)
                    .put("timeoutSeconds", server.timeoutSeconds)
                    .put("enabled", server.enabled),
            )
        }
        securePrefs.edit().putString(KEY_SSH_SERVERS, array.toString()).apply()
    }

    fun upsertSshServer(server: SshServerConfig) {
        val servers = sshServers().toMutableList()
        val index = servers.indexOfFirst { it.id == server.id || it.stableId == server.stableId }
        if (index >= 0) servers[index] = server else servers += server
        saveSshServers(servers)
    }

    fun deleteSshServer(id: String) {
        saveSshServers(sshServers().filterNot { it.id == id || it.stableId == id || it.host == id || it.name == id })
    }

    fun setSshServerEnabled(id: String, enabled: Boolean) {
        saveSshServers(sshServers().map { if (it.id == id || it.stableId == id || it.host == id || it.name == id) it.copy(enabled = enabled) else it })
    }

    fun resolveSshServer(identifier: String): SshServerConfig? {
        val clean = identifier.trim()
        return sshServers()
            .filter { it.enabled }
            .firstOrNull { it.id == clean || it.stableId == clean || it.host == clean || it.name == clean }
    }

    fun emailServers(): List<EmailServerConfig> {
        val raw = securePrefs.getString(KEY_EMAIL_SERVERS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching { parseEmailServers(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    fun saveEmailServers(servers: List<EmailServerConfig>) {
        val array = JSONArray()
        servers.forEach { array.put(emailServerJson(it, includeSecrets = true)) }
        securePrefs.edit().putString(KEY_EMAIL_SERVERS, array.toString()).apply()
    }

    fun upsertEmailServer(server: EmailServerConfig) {
        val servers = emailServers().toMutableList()
        val index = servers.indexOfFirst { it.id == server.id || it.stableId == server.stableId }
        if (index >= 0) servers[index] = server else servers += server
        saveEmailServers(servers)
    }

    fun deleteEmailServer(id: String) {
        saveEmailServers(emailServers().filterNot { it.id == id || it.name == id || it.stableId == id })
    }

    fun setEmailServerEnabled(id: String, enabled: Boolean) {
        saveEmailServers(emailServers().map {
            if (it.id == id || it.name == id || it.stableId == id) it.copy(enabled = enabled) else it
        })
    }

    fun resolveEmailServer(identifier: String): EmailServerConfig? {
        val clean = identifier.trim()
        return emailServers().filter { it.enabled }.firstOrNull {
            it.id == clean || it.name == clean || it.stableId.equals(clean, ignoreCase = true)
        }
    }

    fun webDavServers(): List<WebDavServerConfig> {
        val raw = securePrefs.getString(KEY_WEBDAV_SERVERS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching { parseWebDavServers(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    fun saveWebDavServers(servers: List<WebDavServerConfig>) {
        val array = JSONArray()
        servers.forEach { array.put(webDavServerJson(it, includeSecrets = true)) }
        securePrefs.edit().putString(KEY_WEBDAV_SERVERS, array.toString()).apply()
    }

    fun upsertWebDavServer(server: WebDavServerConfig) {
        val servers = webDavServers().toMutableList()
        val index = servers.indexOfFirst { it.id == server.id || it.stableId == server.stableId }
        if (index >= 0) servers[index] = server else servers += server
        saveWebDavServers(servers)
    }

    fun deleteWebDavServer(id: String) {
        saveWebDavServers(webDavServers().filterNot { it.id == id || it.name == id || it.stableId == id })
    }

    fun setWebDavServerEnabled(id: String, enabled: Boolean) {
        saveWebDavServers(webDavServers().map { if (it.id == id || it.name == id || it.stableId == id) it.copy(enabled = enabled) else it })
    }

    fun resolveWebDavServer(identifier: String): WebDavServerConfig? {
        val clean = identifier.trim()
        return webDavServers()
            .filter { it.enabled }
            .firstOrNull { it.id == clean || it.name == clean || it.url == clean || it.stableId == clean }
    }

    fun fileTransferServers(): List<FileTransferServerConfig> {
        val raw = securePrefs.getString(KEY_FILE_TRANSFER_SERVERS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching { parseFileTransferServers(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    fun saveFileTransferServers(servers: List<FileTransferServerConfig>) {
        val array = JSONArray()
        servers.forEach { array.put(fileTransferServerJson(it, includeSecrets = true)) }
        securePrefs.edit().putString(KEY_FILE_TRANSFER_SERVERS, array.toString()).apply()
    }

    fun upsertFileTransferServer(server: FileTransferServerConfig) {
        val servers = fileTransferServers().toMutableList()
        val index = servers.indexOfFirst { it.id == server.id || it.stableId == server.stableId }
        if (index >= 0) servers[index] = server else servers += server
        saveFileTransferServers(servers)
    }

    fun deleteFileTransferServer(id: String) {
        saveFileTransferServers(fileTransferServers().filterNot { it.id == id || it.name == id || it.stableId == id })
    }

    fun setFileTransferServerEnabled(id: String, enabled: Boolean) {
        saveFileTransferServers(fileTransferServers().map { if (it.id == id || it.name == id || it.stableId == id) it.copy(enabled = enabled) else it })
    }

    fun resolveFileTransferServer(identifier: String): FileTransferServerConfig? {
        val clean = identifier.trim()
        return fileTransferServers()
            .filter { it.enabled }
            .firstOrNull { it.id == clean || it.name == clean || it.stableId == clean || it.host == clean }
    }

    fun miniServerConfig(): MiniServerConfig {
        val raw = securePrefs.getString(KEY_MINI_SERVER_CONFIG, null).orEmpty()
        if (raw.isBlank()) return defaultMiniServerConfig()
        return runCatching { parseMiniServerConfig(JSONObject(raw)) }.getOrDefault(defaultMiniServerConfig())
    }

    fun saveMiniServerConfig(config: MiniServerConfig) {
        securePrefs.edit()
            .putString(KEY_MINI_SERVER_CONFIG, miniServerConfigJson(config, includeSecrets = true).toString())
            .apply()
    }

    fun skillsRootDir(): File = skillsRoot()


    fun exportSettingsJson(includeSecrets: Boolean): JSONObject {
        return JSONObject()
            .put("schema", "lyra_settings_backup_v1")
            .put("appIconId", appIconId)
            .put("themeMode", themeMode)
            .put("dynamicColorEnabled", dynamicColorEnabled)
            .put("predictiveBackEnabled", predictiveBackEnabled)
            .put("customThemeColorEnabled", customThemeColorEnabled)
            .put("customThemeColor", customThemeColor)
            .put("languageMode", languageMode)
            .put("refreshRateMode", refreshRateMode)
            .put("fontScaleMode", fontScaleMode)
            .put("customFontScale", customFontScale.toDouble())
            .put("requestRootAccess", requestRootAccess)
            .put("requestShellAccess", requestShellAccess)
            .put("customSuCommand", customSuCommand)
            .put("userNickname", userNickname)
            .put("userAvatarPath", userAvatarPath.orEmpty())
            .put("memories", JSONArray().also { array -> memories().forEach { array.put(memoryJson(it)) } })
            .put("streamingAnimationMode", streamingAnimationMode)
            .put("chatBackgroundPath", chatBackgroundPath.orEmpty())
            .put("chatBackgroundMaskOpacity", chatBackgroundMaskOpacity.toDouble())
            .put("hideTermuxPermissionHint", hideTermuxPermissionHint)
            .put("purePromptMode", purePromptMode)
            .put("selectedSystemPromptId", selectedSystemPromptId)
            .put("customSystemPrompts", JSONObject(plainPrefs.getString(KEY_CUSTOM_SYSTEM_PROMPTS, "{}").orEmpty().ifBlank { "{}" }))
            .put("systemPromptConfigs", JSONArray(plainPrefs.getString(KEY_SYSTEM_PROMPT_CONFIGS, "[]").orEmpty().ifBlank { "[]" }))
            .put("reasoningDepth", reasoningDepth)
            .put("subAgentOrchestrationEnabled", subAgentOrchestrationEnabled)
            .put("subAgents", JSONArray().also { array ->
                subAgents().forEach { agent ->
                    array.put(
                        JSONObject()
                            .put("id", agent.id)
                            .put("name", agent.name)
                            .put("profileId", agent.profileId)
                            .put("model", agent.model)
                            .put("description", agent.description)
                            .put("enabled", agent.enabled),
                    )
                }
            })
            .put("webSearchBlacklist", webSearchBlacklistText)
            .put("selectedApiProfileId", selectedApiProfileId)
            .put("topicSummaryProfileId", topicSummaryProfileId)
            .put("topicSummaryModel", topicSummaryModel)
            .put("historyCompressionProfileId", historyCompressionProfileId)
            .put("historyCompressionModel", historyCompressionModel)
            .put("historyCompressionChunkCount", historyCompressionChunkCount)
            .put("visionSupplementEnabled", visionSupplementEnabled)
            .put("visionUnderstanding", visionUnderstandingConfig().let { config ->
                JSONObject()
                    .put("enabled", config.enabled)
                    .put("sourceType", config.sourceType)
                    .put("profileId", config.profileId)
                    .put("model", config.model)
                    .put("mcpServerId", config.mcpServerId)
                    .put("mcpToolName", config.mcpToolName)
                    .put("relayPrompt", config.relayPrompt)
            })
            .put("ocrModel", ocrModelConfig().let { config ->
                JSONObject()
                    .put("enabled", config.enabled)
                    .put("profileId", config.profileId)
                    .put("model", config.model)
            })
            .put("mediaGenerationModels", JSONArray().also { array ->
                MediaGenerationKind.entries.forEach { kind ->
                    val config = mediaGenerationModel(kind)
                    if (config.profileId.isNotBlank() && config.model.isNotBlank()) {
                        array.put(
                            JSONObject()
                                .put("kind", kind.value)
                                .put("profileId", config.profileId)
                                .put("model", config.model),
                        )
                    }
                }
            })
            .put("profiles", JSONArray().also { array ->
                profiles().forEach { profile ->
                    array.put(
                        JSONObject()
                            .put("id", profile.id)
                            .put("presetId", profile.presetId)
                            .put("presetPlanId", profile.presetPlanId)
                            .put("name", profile.name)
                            .put("apiKey", if (includeSecrets) profile.apiKey else "")
                            .put("baseUrl", profile.baseUrl)
                            .put("apiFormat", profile.apiFormat)
                            .put("selectedModel", profile.selectedModel)
                            .put("savedModels", JSONArray(profile.savedModels))
                            .put("enabledModels", JSONArray(profile.enabledModels))
                            .put("useResponsesApi", profile.apiFormat == ApiProfile.API_FORMAT_OPENAI && profile.useResponsesApi)
                    .put("modelRequestOverrides", ModelRequestCustomization.encodeMap(profile.modelRequestOverrides))
                    )
                }
            })
            .put("mcpServers", JSONArray().also { array ->
                mcpServers().forEach { server ->
                    array.put(
                        JSONObject()
                            .put("id", server.id)
                            .put("name", server.name)
                            .put("url", server.url)
                            .put("authKey", if (includeSecrets) server.authKey else "")
                            .put("transport", server.transport)
                            .put("timeoutSeconds", server.timeoutSeconds)
                            .put("enabled", server.enabled)
                            .put("rawJson", if (includeSecrets) server.rawJson else server.rawJson.replace(server.authKey, ""))
                            .put("tools", JSONArray().also { tools ->
                                server.tools.forEach { tool ->
                                    tools.put(JSONObject().put("name", tool.name).put("description", tool.description).put("inputSchema", tool.inputSchema))
                                }
                            }),
                    )
                }
            })
            .put("sshServers", JSONArray().also { array ->
                sshServers().forEach { server ->
                    array.put(
                        JSONObject()
                            .put("id", server.id)
                            .put("name", server.name)
                            .put("host", server.host)
                            .put("port", server.port)
                            .put("username", server.username)
                            .put("authType", server.authType)
                            .put("password", if (includeSecrets) server.password else "")
                            .put("privateKey", if (includeSecrets) server.privateKey else "")
                            .put("passphrase", if (includeSecrets) server.passphrase else "")
                            .put("timeoutSeconds", server.timeoutSeconds)
                            .put("enabled", server.enabled),
                    )
                }
            })
            .put("emailServers", JSONArray().also { array ->
                emailServers().forEach { array.put(emailServerJson(it, includeSecrets)) }
            })
            .put("webDavServers", JSONArray().also { array ->
                webDavServers().forEach { array.put(webDavServerJson(it, includeSecrets)) }
            })
            .put("fileTransferServers", JSONArray().also { array ->
                fileTransferServers().forEach { array.put(fileTransferServerJson(it, includeSecrets)) }
            })
            .put("miniServer", miniServerConfigJson(miniServerConfig(), includeSecrets))
            .put("localMcpServer", localMcpServerConfigJson(localMcpServerConfig(), includeSecrets))
    }

    fun importSettingsJson(root: JSONObject, mode: String): String {
        val supplement = mode != "replace"
        val messages = mutableListOf<String>()
        if (root.has("appIconId")) appIconId = root.optString("appIconId")
        root.optString("themeMode").takeIf { it.isNotBlank() }?.let { themeMode = it }
        if (root.has("customThemeColorEnabled")) customThemeColorEnabled = root.optBoolean("customThemeColorEnabled")
        root.optString("customThemeColor").takeIf { it.isNotBlank() }?.let { customThemeColor = it }
        if (root.has("dynamicColorEnabled")) dynamicColorEnabled = root.optBoolean("dynamicColorEnabled")
        if (root.has("predictiveBackEnabled")) predictiveBackEnabled = root.optBoolean("predictiveBackEnabled")
        root.optString("languageMode").takeIf { it.isNotBlank() }?.let { languageMode = it }
        root.optString("refreshRateMode").takeIf { it.isNotBlank() }?.let { refreshRateMode = it }
        root.optString("fontScaleMode").takeIf { it.isNotBlank() }?.let { fontScaleMode = it }
        if (root.has("customFontScale")) customFontScale = root.optDouble("customFontScale", 1.0).toFloat()
        if (root.has("requestRootAccess")) requestRootAccess = root.optBoolean("requestRootAccess")
        if (root.has("requestShellAccess")) requestShellAccess = root.optBoolean("requestShellAccess")
        root.optString("customSuCommand").takeIf { it.isNotBlank() }?.let { customSuCommand = it }
        root.optString("userNickname").takeIf { it.isNotBlank() }?.let { userNickname = it }
        root.optString("userAvatarPath").takeIf { it.isNotBlank() }?.let { userAvatarPath = it }
        root.optJSONArray("memories")?.let { array ->
            val imported = parseMemories(array)
            saveMemories(if (supplement) mergeMemories(memories(), imported) else imported)
            messages += appContext.getString(R.string.backup_import_memories, imported.size)
        }
        root.optString("streamingAnimationMode").takeIf { it.isNotBlank() }?.let { streamingAnimationMode = it }
        root.optString("chatBackgroundPath").takeIf { it.isNotBlank() }?.let { chatBackgroundPath = it }
        if (root.has("chatBackgroundMaskOpacity")) {
            chatBackgroundMaskOpacity = root.optDouble(
                "chatBackgroundMaskOpacity",
                DEFAULT_CHAT_BACKGROUND_MASK_OPACITY.toDouble(),
            ).toFloat()
        }
        if (root.has("hideTermuxPermissionHint")) hideTermuxPermissionHint = root.optBoolean("hideTermuxPermissionHint")
        if (root.has("purePromptMode")) purePromptMode = root.optBoolean("purePromptMode")
        root.optString("selectedSystemPromptId").takeIf { it.isNotBlank() }?.let { selectedSystemPromptId = it }
        root.optJSONObject("customSystemPrompts")?.let { imported ->
            val sanitizedImported = JSONObject().also { output ->
                imported.keys()
                    .asSequence()
                    .filterNot { it == RETIRED_ROLEPLAY_PROMPT_ID }
                    .forEach { id -> output.put(id, imported.optString(id)) }
            }
            val merged = if (supplement) JSONObject().also { output ->
                customSystemPrompts().forEach { (id, prompt) -> output.put(id, prompt) }
                sanitizedImported.keys().asSequence().forEach { id -> output.put(id, sanitizedImported.optString(id)) }
            } else sanitizedImported
            plainPrefs.edit().putString(KEY_CUSTOM_SYSTEM_PROMPTS, merged.toString()).apply()
        }
        root.optJSONArray("systemPromptConfigs")?.let { array ->
            val imported = parseSystemPromptConfigs(array)
            saveCustomSystemPromptConfigs(if (supplement) mergeBy(customSystemPromptConfigs(), imported) { it.id } else imported)
            messages += "系统提示词 ${imported.size} 项"
        }
        root.optString("reasoningDepth").takeIf { it in reasoningDepthValues }?.let { reasoningDepth = it }
        root.optString("topicSummaryProfileId").takeIf { it.isNotBlank() }?.let { topicSummaryProfileId = it }
        root.optString("topicSummaryModel").takeIf { it.isNotBlank() }?.let { topicSummaryModel = it }
        if (root.has("historyCompressionProfileId")) historyCompressionProfileId = root.optString("historyCompressionProfileId")
        if (root.has("historyCompressionModel")) historyCompressionModel = root.optString("historyCompressionModel")
        if (root.has("historyCompressionChunkCount")) {
            historyCompressionChunkCount = root.optInt("historyCompressionChunkCount", DEFAULT_HISTORY_COMPRESSION_CHUNKS)
        }
        if (root.has("visionSupplementEnabled")) visionSupplementEnabled = root.optBoolean("visionSupplementEnabled")
        root.optJSONObject("visionUnderstanding")?.let { item ->
            saveVisionUnderstandingConfig(
                VisionUnderstandingConfig(
                    enabled = item.optBoolean("enabled", false),
                    sourceType = item.optString("sourceType").ifBlank { VISION_SOURCE_MODEL },
                    profileId = item.optString("profileId"),
                    model = item.optString("model"),
                    mcpServerId = item.optString("mcpServerId"),
                    mcpToolName = item.optString("mcpToolName"),
                    relayPrompt = item.optString("relayPrompt").ifBlank { DEFAULT_VISION_RELAY_PROMPT },
                ),
            )
        }
        root.optJSONObject("ocrModel")?.let { item ->
            saveOcrModelConfig(
                OcrModelConfig(
                    enabled = item.optBoolean("enabled", false),
                    profileId = item.optString("profileId"),
                    model = item.optString("model"),
                ),
            )
        }
        root.optJSONArray("mediaGenerationModels")?.let { array ->
            if (!supplement) {
                MediaGenerationKind.entries.forEach { saveMediaGenerationModel(it, "", "") }
            }
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val kind = MediaGenerationKind.fromValue(item.optString("kind")) ?: continue
                saveMediaGenerationModel(kind, item.optString("profileId"), item.optString("model"))
            }
            messages += "媒体生成模型 ${array.length()} 项"
        }
        if (root.has("subAgentOrchestrationEnabled")) subAgentOrchestrationEnabled = root.optBoolean("subAgentOrchestrationEnabled")
        root.optJSONArray("subAgents")?.let { array ->
            val imported = parseSubAgents(array)
            saveSubAgents(if (supplement) mergeBy(subAgents(), imported) { it.id } else imported)
            messages += "子代理 ${imported.size} 项"
        }
        root.optString("webSearchBlacklist").takeIf { it.isNotBlank() }?.let { imported ->
            webSearchBlacklistText = if (supplement) {
                listOf(webSearchBlacklistText, imported).joinToString("\n")
            } else {
                imported
            }
            messages += "联网搜索黑名单 ${webSearchBlockedHosts().size} 项"
        }
        root.optJSONArray("profiles")?.let { array ->
            val imported = parseProfiles(array)
            saveProfiles(if (supplement) mergeProfiles(profiles(), imported) else imported.ifEmpty { profiles() }, root.optString("selectedApiProfileId").ifBlank { null })
            messages += "模型服务 ${imported.size} 项"
        }
        root.optJSONArray("mcpServers")?.let { array ->
            val imported = parseMcpServers(array)
            saveMcpServers(if (supplement) mergeMcpServers(mcpServers(), imported) else imported)
            messages += "MCP ${imported.size} 项"
        }
        root.optJSONArray("sshServers")?.let { array ->
            val imported = parseSshServers(array)
            saveSshServers(if (supplement) mergeSshServers(sshServers(), imported) else imported)
            messages += "SSH ${imported.size} 项"
        }
        root.optJSONArray("emailServers")?.let { array ->
            val imported = parseEmailServers(array)
            saveEmailServers(if (supplement) mergeEmailServers(emailServers(), imported) else imported)
            messages += "邮件服务器 ${imported.size} 项"
        }
        root.optJSONArray("webDavServers")?.let { array ->
            val imported = parseWebDavServers(array)
            saveWebDavServers(if (supplement) mergeWebDavServers(webDavServers(), imported) else imported)
            messages += "WebDAV ${imported.size} 项"
        }
        root.optJSONArray("fileTransferServers")?.let { array ->
            val imported = parseFileTransferServers(array)
            saveFileTransferServers(if (supplement) mergeFileTransferServers(fileTransferServers(), imported) else imported)
            messages += "文件传输 ${imported.size} 项"
        }
        root.optJSONObject("miniServer")?.let { item ->
            val current = miniServerConfig()
            val imported = parseMiniServerConfig(item)
            saveMiniServerConfig(
                if (supplement) {
                    imported.copy(
                        username = imported.username.ifBlank { current.username },
                        password = imported.password.ifBlank { current.password },
                        tlsKeyStoreBase64 = imported.tlsKeyStoreBase64.ifBlank { current.tlsKeyStoreBase64 },
                        tlsKeyStorePassword = imported.tlsKeyStorePassword.ifBlank { current.tlsKeyStorePassword },
                        tlsCertificateChain = imported.tlsCertificateChain.ifBlank { current.tlsCertificateChain },
                        tlsPrivateKey = imported.tlsPrivateKey.ifBlank { current.tlsPrivateKey },
                    )
                } else {
                    imported
                },
            )
            messages += "微型服务器配置"
        }
        root.optJSONObject("localMcpServer")?.let { item ->
            val current = localMcpServerConfig()
            val imported = parseLocalMcpServerConfig(item)
            saveLocalMcpServerConfig(
                if (supplement) imported.copy(authKey = imported.authKey.ifBlank { current.authKey }) else imported,
            )
            messages += "本机 MCP 服务端配置"
        }
        return messages.ifEmpty { listOf("没有可导入的兼容配置") }.joinToString("；")
    }

    private fun defaultProfile(): ApiProfile {
        return ApiProfile(
            id = "default",
            presetId = "openai",
            presetPlanId = "default",
            name = "OpenAI",
            apiKey = apiKey,
            baseUrl = apiEndpoint.removeSuffix("/chat/completions").ifBlank { DEFAULT_BASE_URL },
            chatPath = ApiProfile.DEFAULT_OPENAI_CHAT_PATH,
            apiFormat = ApiProfile.API_FORMAT_OPENAI,
            selectedModel = model,
            savedModels = listOf(model).filter { it.isNotBlank() }.distinct(),
        )
    }

    private fun webDavServerJson(server: WebDavServerConfig, includeSecrets: Boolean): JSONObject {
        return JSONObject()
            .put("id", server.id)
            .put("name", server.name)
            .put("url", server.url)
            .put("username", server.username)
            .put("password", if (includeSecrets) server.password else "")
            .put("userAgent", server.userAgent)
            .put("initialPath", server.initialPath)
            .put("note", server.note)
            .put("trustAllCertificates", server.trustAllCertificates)
            .put("multiThread", server.multiThread)
            .put("hideAddressInDrawer", server.hideAddressInDrawer)
            .put("enabled", server.enabled)
    }

    private fun emailServerJson(server: EmailServerConfig, includeSecrets: Boolean): JSONObject = JSONObject()
        .put("id", server.id)
        .put("name", server.name)
        .put("emailAddress", server.emailAddress)
        .put("username", server.username)
        .put("password", if (includeSecrets) server.password else "")
        .put("imapHost", server.imapHost)
        .put("imapPort", server.imapPort)
        .put("imapSecurity", server.imapSecurity)
        .put("smtpHost", server.smtpHost)
        .put("smtpPort", server.smtpPort)
        .put("smtpSecurity", server.smtpSecurity)
        .put("enabled", server.enabled)

    private fun parseEmailServers(array: JSONArray): List<EmailServerConfig> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val address = item.optString("emailAddress").trim()
            val username = item.optString("username").trim().ifBlank { address }
            val imapHost = item.optString("imapHost").trim()
            val smtpHost = item.optString("smtpHost").trim()
            if (address.isBlank() || username.isBlank() || imapHost.isBlank() || smtpHost.isBlank()) continue
            add(
                EmailServerConfig(
                    id = item.optString("id").ifBlank { newId() },
                    name = item.optString("name").ifBlank { address },
                    emailAddress = address,
                    username = username,
                    password = item.optString("password"),
                    imapHost = imapHost,
                    imapPort = item.optInt("imapPort", 993).coerceIn(1, 65535),
                    imapSecurity = normalizeEmailSecurity(item.optString("imapSecurity", "ssl")),
                    smtpHost = smtpHost,
                    smtpPort = item.optInt("smtpPort", 465).coerceIn(1, 65535),
                    smtpSecurity = normalizeEmailSecurity(item.optString("smtpSecurity", "ssl")),
                    enabled = item.optBoolean("enabled", true),
                ),
            )
        }
    }

    private fun fileTransferServerJson(server: FileTransferServerConfig, includeSecrets: Boolean): JSONObject {
        return JSONObject()
            .put("id", server.id)
            .put("name", server.name)
            .put("protocol", server.protocol)
            .put("host", server.host)
            .put("port", server.port)
            .put("username", server.username)
            .put("password", if (includeSecrets) server.password else "")
            .put("usePrivateKey", server.usePrivateKey)
            .put("privateKey", if (includeSecrets) server.privateKey else "")
            .put("passphrase", if (includeSecrets) server.passphrase else "")
            .put("initialPath", server.initialPath)
            .put("note", server.note)
            .put("encoding", server.encoding)
            .put("passiveMode", server.passiveMode)
            .put("explicitFtps", server.explicitFtps)
            .put("multiThread", server.multiThread)
            .put("syncPermissions", server.syncPermissions)
            .put("hideAddressInDrawer", server.hideAddressInDrawer)
            .put("enabled", server.enabled)
    }

    private fun defaultMiniServerConfig(): MiniServerConfig = MiniServerConfig(
        protocol = MINI_SERVER_PROTOCOL_HTTP,
        host = DEFAULT_MINI_SERVER_HOST,
        port = DEFAULT_MINI_SERVER_PORT,
        username = DEFAULT_MINI_SERVER_USERNAME,
        password = "",
        customDomains = emptyList(),
        forceHttps = false,
        tlsKeyStoreBase64 = "",
        tlsKeyStorePassword = "",
        tlsCertificateChain = "",
        tlsPrivateKey = "",
        spaFallback = true,
        directoryListing = false,
        mdnsEnabled = false,
        mdnsName = DEFAULT_MINI_SERVER_MDNS_NAME,
        enabled = false,
    )

    private fun defaultLocalMcpServerConfig(): LocalMcpServerConfig = LocalMcpServerConfig(
        host = DEFAULT_LOCAL_MCP_SERVER_HOST,
        port = DEFAULT_LOCAL_MCP_SERVER_PORT,
        authKey = "",
        enabled = false,
    )

    private fun localMcpServerConfigJson(config: LocalMcpServerConfig, includeSecrets: Boolean): JSONObject {
        return JSONObject()
            .put("host", config.host.ifBlank { DEFAULT_LOCAL_MCP_SERVER_HOST })
            .put("port", config.port.coerceIn(1, 65535))
            .put("authKey", if (includeSecrets) config.authKey else "")
            .put("enabled", config.enabled)
    }

    private fun parseLocalMcpServerConfig(item: JSONObject): LocalMcpServerConfig {
        return LocalMcpServerConfig(
            host = item.optString("host").ifBlank { DEFAULT_LOCAL_MCP_SERVER_HOST },
            port = item.optInt("port", DEFAULT_LOCAL_MCP_SERVER_PORT).coerceIn(1, 65535),
            authKey = item.optString("authKey"),
            enabled = item.optBoolean("enabled", false),
        )
    }

    private fun miniServerConfigJson(config: MiniServerConfig, includeSecrets: Boolean): JSONObject {
        return JSONObject()
            .put("protocol", config.protocol)
            .put("host", config.host)
            .put("port", config.port)
            .put("username", config.username)
            .put("password", if (includeSecrets) config.password else "")
            .put("customDomains", JSONArray(config.customDomains))
            .put("forceHttps", config.forceHttps)
            .put("tlsKeyStoreBase64", if (includeSecrets) config.tlsKeyStoreBase64 else "")
            .put("tlsKeyStorePassword", if (includeSecrets) config.tlsKeyStorePassword else "")
            .put("tlsCertificateChain", if (includeSecrets) config.tlsCertificateChain else "")
            .put("tlsPrivateKey", if (includeSecrets) config.tlsPrivateKey else "")
            .put("spaFallback", config.spaFallback)
            .put("directoryListing", config.directoryListing)
            .put("mdnsEnabled", config.mdnsEnabled)
            .put("mdnsName", config.mdnsName)
            .put("enabled", config.enabled)
    }

    private fun parseMiniServerConfig(item: JSONObject): MiniServerConfig {
        val protocol = item.optString("protocol").lowercase().let {
            if (it == MINI_SERVER_PROTOCOL_HTTPS) MINI_SERVER_PROTOCOL_HTTPS else MINI_SERVER_PROTOCOL_HTTP
        }
        return MiniServerConfig(
            protocol = protocol,
            host = item.optString("host").ifBlank { DEFAULT_MINI_SERVER_HOST },
            port = item.optInt("port", DEFAULT_MINI_SERVER_PORT).coerceIn(1, 65535),
            username = item.optString("username").ifBlank { DEFAULT_MINI_SERVER_USERNAME },
            password = item.optString("password"),
            customDomains = parseMiniServerDomains(item),
            forceHttps = item.optBoolean("forceHttps", false),
            tlsKeyStoreBase64 = item.optString("tlsKeyStoreBase64"),
            tlsKeyStorePassword = item.optString("tlsKeyStorePassword"),
            tlsCertificateChain = item.optString("tlsCertificateChain"),
            tlsPrivateKey = item.optString("tlsPrivateKey"),
            spaFallback = item.optBoolean("spaFallback", true),
            directoryListing = item.optBoolean("directoryListing", false),
            mdnsEnabled = item.optBoolean("mdnsEnabled", false),
            mdnsName = item.optString("mdnsName").ifBlank { DEFAULT_MINI_SERVER_MDNS_NAME },
            enabled = item.optBoolean("enabled", false),
        )
    }

    private fun parseMiniServerDomains(item: JSONObject): List<String> {
        val array = item.optJSONArray("customDomains")
        if (array != null) {
            return buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            }.distinct()
        }
        return item.optString("customDomains")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private fun normalizeWebSearchBlacklistText(value: String): String =
        value.lineSequence()
            .mapNotNull(::normalizeWebSearchBlockedHost)
            .distinct()
            .joinToString("\n")

    private fun normalizeWebSearchBlockedHost(raw: String): String? {
        val clean = raw.trim().trimEnd('/').trim()
        if (clean.isBlank() || clean.startsWith("#")) return null
        val withoutScheme = clean.substringAfter("://", clean)
        val authority = withoutScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')
        val hostPart = authority
            .let { if (it.startsWith("[")) it.substringBefore(']') + "]" else it.substringBefore(':') }
            .lowercase()
            .trim('.')
        val host = hostPart.removePrefix("*.").trim('.')
        if (host.isBlank()) return null
        if (hostPart.startsWith("*.") && !host.contains('.')) return null
        return if (hostPart.startsWith("*.")) "*.$host" else host
    }

    private fun parseProfiles(array: JSONArray): List<ApiProfile> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val models = item.optJSONArray("savedModels") ?: JSONArray()
            val savedModels = buildList { for (i in 0 until models.length()) add(models.optString(i)) }.filter { it.isNotBlank() }.distinct()
            val enabledModels = if (item.has("enabledModels")) {
                val enabled = item.optJSONArray("enabledModels") ?: JSONArray()
                buildList { for (i in 0 until enabled.length()) add(enabled.optString(i)) }.filter { it.isNotBlank() }.distinct()
            } else {
                savedModels
            }
            val apiFormat = item.optString("apiFormat").ifBlank { ApiProfile.API_FORMAT_OPENAI }
            add(
                ApiProfile(
                    id = item.optString("id").ifBlank { newId() },
                    presetId = item.optString("presetId"),
                    presetPlanId = item.optString("presetPlanId"),
                    name = item.optString("name").ifBlank { "API" },
                    apiKey = item.optString("apiKey"),
                    baseUrl = item.optString("baseUrl").ifBlank { DEFAULT_BASE_URL },
                    chatPath = ApiProfile.normalizedChatPath(apiFormat, item.optString("chatPath")),
                    apiFormat = apiFormat,
                    selectedModel = item.optString("selectedModel").ifBlank { DEFAULT_MODEL },
                    savedModels = savedModels,
                    enabledModels = enabledModels,
                    useResponsesApi = apiFormat == ApiProfile.API_FORMAT_OPENAI && item.optBoolean("useResponsesApi", false),
                    modelRequestOverrides = ModelRequestCustomization.decodeMap(item.optJSONObject("modelRequestOverrides")),
                ),
            )
        }
    }

    private fun parseSubAgents(array: JSONArray): List<SubAgentConfig> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val profileId = item.optString("profileId")
            val model = item.optString("model")
            if (profileId.isBlank() || model.isBlank()) continue
            add(
                SubAgentConfig(
                    id = item.optString("id").ifBlank { newId() },
                    name = item.optString("name").ifBlank { "子代理模型" },
                    profileId = profileId,
                    model = model,
                    description = item.optString("description"),
                    enabled = if (item.has("enabled")) item.optBoolean("enabled") else true,
                ),
            )
        }
    }

    private fun parseMcpServers(array: JSONArray): List<McpServerConfig> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val tools = item.optJSONArray("tools") ?: JSONArray()
            add(
                McpServerConfig(
                    id = item.optString("id").ifBlank { newId() },
                    name = item.optString("name").ifBlank { "MCP Server" },
                    url = item.optString("url"),
                    authKey = item.optString("authKey"),
                    transport = item.optString("transport").ifBlank { MCP_TRANSPORT_STREAMABLE_HTTP },
                    timeoutSeconds = item.optInt("timeoutSeconds", 30).coerceIn(5, 300),
                    enabled = item.optBoolean("enabled", true),
                    rawJson = item.optString("rawJson").ifBlank { "{}" },
                    tools = buildList {
                        for (toolIndex in 0 until tools.length()) {
                            val tool = tools.optJSONObject(toolIndex) ?: continue
                            add(
                                McpToolDefinition(
                                    name = tool.optString("name"),
                                    description = tool.optString("description"),
                                    inputSchema = tool.optJSONObject("inputSchema")?.toString()
                                        ?: tool.optString("inputSchema").ifBlank { "{}" },
                                ),
                            )
                        }
                    }.filter { it.name.isNotBlank() },
                ),
            )
        }
    }.filter { it.url.isNotBlank() }

    private fun parseSshServers(array: JSONArray): List<SshServerConfig> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            add(
                SshServerConfig(
                    id = item.optString("id").ifBlank { newId() },
                    name = item.optString("name").ifBlank { item.optString("host") },
                    host = item.optString("host"),
                    port = item.optInt("port", 22).coerceIn(1, 65535),
                    username = item.optString("username"),
                    authType = item.optString("authType").ifBlank { SSH_AUTH_PASSWORD },
                    password = item.optString("password"),
                    privateKey = item.optString("privateKey"),
                    passphrase = item.optString("passphrase"),
                    timeoutSeconds = item.optInt("timeoutSeconds", 60).coerceIn(5, 600),
                    enabled = item.optBoolean("enabled", true),
                ),
            )
        }
    }.filter { it.host.isNotBlank() && it.username.isNotBlank() }

    private fun parseWebDavServers(array: JSONArray): List<WebDavServerConfig> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            add(
                WebDavServerConfig(
                    id = item.optString("id").ifBlank { newId() },
                    name = item.optString("name").ifBlank { "WebDAV" },
                    url = item.optString("url"),
                    username = item.optString("username"),
                    password = item.optString("password"),
                    userAgent = item.optString("userAgent").ifBlank { "LyraCode/1.0" },
                    initialPath = item.optString("initialPath").ifBlank { "/" },
                    note = item.optString("note"),
                    trustAllCertificates = item.optBoolean("trustAllCertificates", false),
                    multiThread = item.optBoolean("multiThread", true),
                    hideAddressInDrawer = item.optBoolean("hideAddressInDrawer", false),
                    enabled = item.optBoolean("enabled", true),
                ),
            )
        }
    }.filter { it.url.isNotBlank() }

    private fun parseFileTransferServers(array: JSONArray): List<FileTransferServerConfig> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val protocol = normalizeFileTransferProtocol(item.optString("protocol"))
            add(
                FileTransferServerConfig(
                    id = item.optString("id").ifBlank { newId() },
                    name = item.optString("name").ifBlank { protocol.uppercase() },
                    protocol = protocol,
                    host = item.optString("host"),
                    port = item.optInt("port", defaultFileTransferPort(protocol)).coerceIn(1, 65535),
                    username = item.optString("username").ifBlank { if (protocol == FILE_TRANSFER_SFTP) "" else "anonymous" },
                    password = item.optString("password"),
                    usePrivateKey = item.optBoolean("usePrivateKey", false),
                    privateKey = item.optString("privateKey"),
                    passphrase = item.optString("passphrase"),
                    initialPath = item.optString("initialPath").ifBlank { "/" },
                    note = item.optString("note"),
                    encoding = item.optString("encoding").ifBlank { "UTF-8" },
                    passiveMode = item.optBoolean("passiveMode", true),
                    explicitFtps = item.optBoolean("explicitFtps", true),
                    multiThread = item.optBoolean("multiThread", true),
                    syncPermissions = item.optBoolean("syncPermissions", false),
                    hideAddressInDrawer = item.optBoolean("hideAddressInDrawer", false),
                    enabled = item.optBoolean("enabled", true),
                ),
            )
        }
    }.filter { it.host.isNotBlank() && (it.protocol != FILE_TRANSFER_SFTP || it.username.isNotBlank()) }

    private fun parseSystemPromptConfigs(array: JSONArray): List<SystemPromptPreset> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val prompt = item.optString("prompt")
            if (prompt.isBlank()) continue
            val id = item.optString("id")
                .takeUnless { it.isBlank() || it == NATIVE_SYSTEM_PROMPT_ID }
                ?: newId()
            if (id == RETIRED_ROLEPLAY_PROMPT_ID) continue
            add(
                SystemPromptPreset(
                    id = id,
                    name = item.optString("name").ifBlank { "自定义提示词" },
                    prompt = prompt,
                    exampleConversation = item.optString("exampleConversation"),
                    builtIn = false,
                ),
            )
        }
    }

    private fun <T> mergeBy(existing: List<T>, imported: List<T>, key: (T) -> String): List<T> {
        val map = LinkedHashMap<String, T>()
        existing.forEach { map[key(it)] = it }
        imported.forEach { map[key(it)] = it }
        return map.values.toList()
    }

    private fun mergeProfiles(existing: List<ApiProfile>, imported: List<ApiProfile>): List<ApiProfile> {
        val result = existing.toMutableList()
        imported.forEach { incoming ->
            val index = result.indexOfFirst { it.id == incoming.id || sameEndpointProfile(it, incoming) }
            if (index >= 0) {
                val current = result[index]
                result[index] = incoming.copy(
                    id = current.id,
                    apiKey = incoming.apiKey.ifBlank { current.apiKey },
                    savedModels = (incoming.savedModels + current.savedModels).filter { it.isNotBlank() }.distinct(),
                    enabledModels = (incoming.enabledModels + current.enabledModels).filter { it.isNotBlank() }.distinct(),
                )
            } else {
                result += incoming
            }
        }
        return result
    }

    private fun mergeMcpServers(existing: List<McpServerConfig>, imported: List<McpServerConfig>): List<McpServerConfig> {
        val result = existing.toMutableList()
        imported.forEach { incoming ->
            val index = result.indexOfFirst { it.id == incoming.id || sameMcpServer(it, incoming) }
            if (index >= 0) {
                val current = result[index]
                val authKey = incoming.authKey.ifBlank { current.authKey }
                result[index] = incoming.copy(
                    id = current.id,
                    authKey = authKey,
                    rawJson = mergeMcpRawJson(current, incoming, authKey),
                    tools = incoming.tools.ifEmpty { current.tools },
                )
            } else {
                result += incoming
            }
        }
        return result
    }

    private fun mergeSshServers(existing: List<SshServerConfig>, imported: List<SshServerConfig>): List<SshServerConfig> {
        val result = existing.toMutableList()
        imported.forEach { incoming ->
            val index = result.indexOfFirst { it.id == incoming.id || it.stableId == incoming.stableId }
            if (index >= 0) {
                val current = result[index]
                result[index] = incoming.copy(
                    id = current.id,
                    password = incoming.password.ifBlank { current.password },
                    privateKey = incoming.privateKey.ifBlank { current.privateKey },
                    passphrase = incoming.passphrase.ifBlank { current.passphrase },
                )
            } else {
                result += incoming
            }
        }
        return result
    }

    private fun mergeEmailServers(existing: List<EmailServerConfig>, imported: List<EmailServerConfig>): List<EmailServerConfig> {
        val result = existing.toMutableList()
        imported.forEach { incoming ->
            val index = result.indexOfFirst { it.id == incoming.id || it.stableId == incoming.stableId }
            if (index >= 0) {
                val current = result[index]
                result[index] = incoming.copy(
                    id = current.id,
                    password = incoming.password.ifBlank { current.password },
                )
            } else {
                result += incoming
            }
        }
        return result
    }

    private fun mergeWebDavServers(existing: List<WebDavServerConfig>, imported: List<WebDavServerConfig>): List<WebDavServerConfig> {
        val result = existing.toMutableList()
        imported.forEach { incoming ->
            val index = result.indexOfFirst { it.id == incoming.id || it.stableId == incoming.stableId }
            if (index >= 0) {
                val current = result[index]
                result[index] = incoming.copy(
                    id = current.id,
                    password = incoming.password.ifBlank { current.password },
                )
            } else {
                result += incoming
            }
        }
        return result
    }

    private fun mergeFileTransferServers(existing: List<FileTransferServerConfig>, imported: List<FileTransferServerConfig>): List<FileTransferServerConfig> {
        val result = existing.toMutableList()
        imported.forEach { incoming ->
            val index = result.indexOfFirst { it.id == incoming.id || it.stableId == incoming.stableId }
            if (index >= 0) {
                val current = result[index]
                result[index] = incoming.copy(
                    id = current.id,
                    password = incoming.password.ifBlank { current.password },
                    privateKey = incoming.privateKey.ifBlank { current.privateKey },
                    passphrase = incoming.passphrase.ifBlank { current.passphrase },
                )
            } else {
                result += incoming
            }
        }
        return result
    }

    private fun sameEndpointProfile(first: ApiProfile, second: ApiProfile): Boolean {
        return first.name == second.name &&
            first.apiFormat == second.apiFormat &&
            first.baseUrl.trim().trimEnd('/') == second.baseUrl.trim().trimEnd('/')
    }

    private fun sameMcpServer(first: McpServerConfig, second: McpServerConfig): Boolean {
        return first.url.trim().trimEnd('/') == second.url.trim().trimEnd('/') &&
            first.transport == second.transport
    }

    private fun mergeMcpRawJson(current: McpServerConfig, incoming: McpServerConfig, authKey: String): String {
        val incomingRaw = incoming.rawJson.ifBlank { "{}" }
        if (authKey.isBlank()) return incomingRaw
        val incomingHasAuth = runCatching {
            val root = JSONObject(incomingRaw)
            val node = root.optJSONObject("mcpServers")
                ?.let { servers -> servers.keys().asSequence().firstOrNull()?.let { servers.optJSONObject(it) } }
                ?: root
            val headers = node.optJSONObject("headers") ?: root.optJSONObject("headers")
            headers?.optString("Authorization").orEmpty().isNotBlank()
        }.getOrDefault(false)
        if (incomingHasAuth) return incomingRaw
        return current.rawJson.takeIf { it.isNotBlank() && it != "{}" } ?: incomingRaw
    }

    @Suppress("DEPRECATION")
    private fun createSecurePrefs(): SharedPreferences {
        return runCatching {
            val key = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                "lyra_secure_settings",
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            appContext.getSharedPreferences("lyra_secure_settings_fallback", Context.MODE_PRIVATE)
        }
    }

    companion object {
        private const val KEY_WORKSPACE_URI = "workspace_uri"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_API_ENDPOINT = "api_endpoint"
        private const val KEY_MODEL = "model"
        private const val KEY_TOPIC_SUMMARY_PROFILE_ID = "topic_summary_profile_id"
        private const val KEY_TOPIC_SUMMARY_MODEL = "topic_summary_model"
        private const val KEY_HISTORY_COMPRESSION_PROFILE_ID = "history_compression_profile_id"
        private const val KEY_HISTORY_COMPRESSION_MODEL = "history_compression_model"
        private const val KEY_HISTORY_COMPRESSION_CHUNK_COUNT = "history_compression_chunk_count"
        private const val KEY_IMAGE_GENERATION_PROFILE_ID = "image_generation_profile_id"
        private const val KEY_IMAGE_GENERATION_MODEL = "image_generation_model"
        private const val KEY_VISION_SUPPLEMENT_ENABLED = "vision_supplement_enabled"
        private const val KEY_VISION_UNDERSTANDING_ENABLED = "vision_understanding_enabled"
        private const val KEY_VISION_UNDERSTANDING_SOURCE_TYPE = "vision_understanding_source_type"
        private const val KEY_VISION_UNDERSTANDING_PROFILE_ID = "vision_understanding_profile_id"
        private const val KEY_VISION_UNDERSTANDING_MODEL = "vision_understanding_model"
        private const val KEY_VISION_UNDERSTANDING_MCP_SERVER_ID = "vision_understanding_mcp_server_id"
        private const val KEY_VISION_UNDERSTANDING_MCP_TOOL_NAME = "vision_understanding_mcp_tool_name"
        private const val KEY_VISION_UNDERSTANDING_RELAY_PROMPT = "vision_understanding_relay_prompt"
        private const val KEY_OCR_MODEL_ENABLED = "ocr_model_enabled"
        private const val KEY_OCR_MODEL_PROFILE_ID = "ocr_model_profile_id"
        private const val KEY_OCR_MODEL = "ocr_model"
        private const val KEY_VIDEO_GENERATION_PROFILE_ID = "video_generation_profile_id"
        private const val KEY_VIDEO_GENERATION_MODEL = "video_generation_model"
        private const val KEY_MUSIC_GENERATION_PROFILE_ID = "music_generation_profile_id"
        private const val KEY_MUSIC_GENERATION_MODEL = "music_generation_model"
        private const val KEY_AUDIO_GENERATION_PROFILE_ID = "audio_generation_profile_id"
        private const val KEY_AUDIO_GENERATION_MODEL = "audio_generation_model"
        const val MIN_HISTORY_COMPRESSION_CHUNKS = 1
        const val MAX_HISTORY_COMPRESSION_CHUNKS = 16
        const val DEFAULT_HISTORY_COMPRESSION_CHUNKS = 4
        const val VISION_SOURCE_MODEL = "model"
        const val VISION_SOURCE_MCP = "mcp"
        const val DEFAULT_VISION_RELAY_PROMPT = "You are a visual relay for another AI model. Describe only what is actually visible in the supplied image, faithfully and in sufficient detail for the main model to reason from your report. Follow the requested focus, but do not answer the user's underlying question, infer unsupported facts, or take actions. Preserve exact visible text, numbers, spatial relationships, UI state, uncertainty, and relevant visual details. Clearly distinguish observation from uncertainty. Return only the visual report."
        private const val KEY_API_PROFILES = "api_profiles"
        private const val KEY_SELECTED_API_PROFILE_ID = "selected_api_profile_id"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DYNAMIC_COLOR_ENABLED = "dynamic_color_enabled"
        private const val KEY_PREDICTIVE_BACK_ENABLED = "predictive_back_enabled"
        private const val KEY_DEVICE_INTERACTION_EXPERIMENTAL_ENABLED = "device_interaction_experimental_enabled"
        private const val KEY_CUSTOM_THEME_COLOR_ENABLED = "custom_theme_color_enabled"
        private const val KEY_CUSTOM_THEME_COLOR = "custom_theme_color"
        private const val KEY_LANGUAGE_MODE = "language_mode"
        private const val KEY_REFRESH_RATE_MODE = "refresh_rate_mode"
        private const val KEY_DOWNLOAD_COMPLETION_NOTIFICATIONS = "download_completion_notifications"
        private const val KEY_MINI_SERVER_CONFIG = "mini_server_config"
        private const val KEY_FONT_SCALE_MODE = "font_scale_mode"
        private const val KEY_CUSTOM_FONT_SCALE = "custom_font_scale"
        private const val KEY_TEXT_FONT_PATH = "text_font_path"
        private const val KEY_CODE_FONT_PATH = "code_font_path"
        private const val KEY_TEXT_FONT_NAME = "text_font_name"
        private const val KEY_CODE_FONT_NAME = "code_font_name"
        private const val KEY_FONT_LIBRARY = "font_library"
        private const val KEY_REQUEST_ROOT_ACCESS = "request_root_access"
        private const val KEY_REQUEST_SHELL_ACCESS = "request_shell_access"
        private const val KEY_CUSTOM_SU_COMMAND = "custom_su_command"
        private const val KEY_USER_NICKNAME = "user_nickname"
        private const val KEY_USER_AVATAR_PATH = "user_avatar_path"
        private const val KEY_MEMORIES = "memories"
        private const val MAX_MEMORY_COUNT = 200
        private const val MAX_MEMORY_CONTENT_CHARS = 2_000
        private const val MAX_MEMORY_PROMPT_CHARS = 24_000
        private const val KEY_CHAT_BACKGROUND_PATH = "chat_background_path"
        private const val KEY_STREAMING_ANIMATION_MODE = "streaming_animation_mode"
        private const val KEY_CHAT_BACKGROUND_MASK_OPACITY = "chat_background_mask_opacity"
        private const val DEFAULT_CHAT_BACKGROUND_MASK_OPACITY = 0.58f
        private const val KEY_HIDE_TERMUX_PERMISSION_HINT = "hide_termux_permission_hint"
        private const val KEY_DISABLED_TOOLS = "disabled_tools"
        private const val KEY_HIDDEN_TODO_SIGNATURE_PREFIX = "hidden_todo_signature_"
        private const val KEY_CHAT_INPUT_DRAFT_PREFIX = "chat_input_draft_"
        private const val KEY_HIDDEN_FILE_CHANGES_SIGNATURE_PREFIX = "hidden_file_changes_signature_"
        private const val KEY_ENABLED_SKILLS = "enabled_skills"
        private const val KEY_SELECTED_SYSTEM_PROMPT_ID = "selected_system_prompt_id"
        private const val KEY_CUSTOM_SYSTEM_PROMPTS = "custom_system_prompts"
        private const val KEY_SYSTEM_PROMPT_CONFIGS = "system_prompt_configs"
        private const val KEY_REASONING_DEPTH = "reasoning_depth"
        private const val KEY_SUB_AGENT_ORCHESTRATION_ENABLED = "sub_agent_orchestration_enabled"
        private const val KEY_SUB_AGENT_CONFIGS = "sub_agent_configs"
        private const val KEY_WEB_SEARCH_BLACKLIST = "web_search_blacklist"
        private const val KEY_MCP_SERVERS = "mcp_servers"
        private const val KEY_LOCAL_MCP_SERVER = "local_mcp_server"
        private const val KEY_SSH_SERVERS = "ssh_servers"
        private const val KEY_EMAIL_SERVERS = "email_servers"
        private const val KEY_WEBDAV_SERVERS = "webdav_servers"
        private const val KEY_FILE_TRANSFER_SERVERS = "file_transfer_servers"
        private const val SKILL_NAME_FILE = "_name.txt"
        private const val SKILL_DESCRIPTION_FILE = "_description.txt"
        private const val MAX_SKILL_READ_BYTES = 256 * 1024
        private const val MAX_SKILL_TOTAL_BYTES = 8 * 1024 * 1024
        private const val DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions"
        private const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        private const val DEFAULT_MODEL = "gpt-4o-mini"
        const val NATIVE_SYSTEM_PROMPT_ID = "native"
        private const val RETIRED_ROLEPLAY_PROMPT_ID = "roleplay"
        const val STREAMING_ANIMATION_TYPEWRITER = "typewriter"
        const val STREAMING_ANIMATION_FADE = "fade"

        fun normalizeStreamingAnimationMode(value: String): String = when (value.trim().lowercase()) {
            STREAMING_ANIMATION_FADE -> STREAMING_ANIMATION_FADE
            else -> STREAMING_ANIMATION_TYPEWRITER
        }
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val DEFAULT_CUSTOM_THEME_COLOR = "#F6F6F4"

        fun normalizeHexColor(value: String): String {
            val raw = value.trim().removePrefix("#")
            return if (raw.matches(Regex("[0-9A-Fa-f]{6}"))) "#${raw.uppercase()}" else DEFAULT_CUSTOM_THEME_COLOR
        }
        const val LANGUAGE_SYSTEM = "system"
        const val LANGUAGE_ZH_CN = "zh-CN"
        const val LANGUAGE_ZH_TW = "zh-TW"
        const val LANGUAGE_EN = "en"
        const val REFRESH_RATE_SYSTEM = "system"
        const val REFRESH_RATE_30 = "30"
        const val REFRESH_RATE_60 = "60"
        const val REFRESH_RATE_90 = "90"
        const val REFRESH_RATE_120 = "120"
        const val FONT_SCALE_SYSTEM = "system"
        const val FONT_SCALE_SMALL = "small"
        const val FONT_SCALE_NORMAL = "normal"
        const val FONT_SCALE_LARGE = "large"
        const val FONT_SCALE_EXTRA_LARGE = "extra_large"
        const val FONT_SCALE_CUSTOM = "custom"
        const val MIN_FONT_SCALE = 0.5f
        const val MAX_FONT_SCALE = 2.5f
        const val FONT_SCALE_STEP = 0.025f
        private val SUPPORTED_FONT_EXTENSIONS = setOf("ttf", "otf", "ttc")
        const val DEFAULT_SU_COMMAND = "su -c"
        const val MINI_SERVER_PROTOCOL_HTTP = "http"
        const val MINI_SERVER_PROTOCOL_HTTPS = "https"
        const val DEFAULT_MINI_SERVER_HOST = "127.0.0.1"
        const val DEFAULT_MINI_SERVER_PORT = 8787
        const val DEFAULT_MINI_SERVER_USERNAME = "lyra"
        const val DEFAULT_MINI_SERVER_MDNS_NAME = "Lyra Code"
        const val REASONING_AUTO = "auto"
        const val REASONING_LOW = "low"
        const val REASONING_MEDIUM = "medium"
        const val REASONING_HIGH = "high"
        const val REASONING_XHIGH = "xhigh"
        const val REASONING_MAX = "max"
        val reasoningDepthValues = listOf(
            REASONING_AUTO,
            REASONING_LOW,
            REASONING_MEDIUM,
            REASONING_HIGH,
            REASONING_XHIGH,
            REASONING_MAX,
        )
        const val MCP_TRANSPORT_STREAMABLE_HTTP = "streamable_http"
        const val MCP_TRANSPORT_SSE = "sse"
        const val DEFAULT_LOCAL_MCP_SERVER_HOST = "0.0.0.0"
        const val DEFAULT_LOCAL_MCP_SERVER_PORT = 8791
        const val SSH_AUTH_PASSWORD = "password"
        const val SSH_AUTH_KEY = "key"
        const val FILE_TRANSFER_FTP = "ftp"
        const val FILE_TRANSFER_FTPS = "ftps"
        const val FILE_TRANSFER_SFTP = "sftp"
        const val EMAIL_SECURITY_SSL = "ssl"
        const val EMAIL_SECURITY_STARTTLS = "starttls"
        const val EMAIL_SECURITY_NONE = "none"

        fun normalizeEmailSecurity(value: String): String = when (value.trim().lowercase()) {
            EMAIL_SECURITY_STARTTLS -> EMAIL_SECURITY_STARTTLS
            EMAIL_SECURITY_NONE -> EMAIL_SECURITY_NONE
            else -> EMAIL_SECURITY_SSL
        }

        fun normalizeLanguageMode(value: String): String = when (value.trim()) {
            LANGUAGE_ZH_CN -> LANGUAGE_ZH_CN
            LANGUAGE_ZH_TW -> LANGUAGE_ZH_TW
            LANGUAGE_EN -> LANGUAGE_EN
            else -> LANGUAGE_SYSTEM
        }

        fun normalizeFileTransferProtocol(value: String): String = when (value.trim().lowercase()) {
            FILE_TRANSFER_FTPS -> FILE_TRANSFER_FTPS
            FILE_TRANSFER_SFTP -> FILE_TRANSFER_SFTP
            else -> FILE_TRANSFER_FTP
        }

        fun defaultFileTransferPort(protocol: String): Int = when (normalizeFileTransferProtocol(protocol)) {
            FILE_TRANSFER_SFTP -> 22
            FILE_TRANSFER_FTPS -> 21
            else -> 21
        }

        fun newId(): String = System.currentTimeMillis().toString(36)
    }

    private fun skillsRoot(): File = File(appContext.filesDir, "skills").also { it.mkdirs() }


    private fun enabledSkillIds(): Set<String> = plainPrefs.getStringSet(KEY_ENABLED_SKILLS, emptySet()).orEmpty()

    private fun skillDir(id: String): File {
        val root = File(skillsRoot(), id).canonicalFile
        require(root.parentFile == skillsRoot().canonicalFile && root.isDirectory) { "Skill 不存在: $id" }
        return root
    }


    private fun displayName(uri: Uri): String {
        return appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "Skill.zip"
    }

    private fun safeZipPath(raw: String): String? {
        val normalized = raw.replace('\\', '/').trim('/')
        if (normalized.isBlank()) return null
        val parts = normalized.split('/').filter { it.isNotBlank() }
        if (parts.any { it == "." || it == ".." }) return null
        return parts.joinToString("/") { part ->
            part.replace(Regex("""[^A-Za-z0-9._ -]"""), "_").take(96).ifBlank { "_" }
        }
    }

    private data class SkillImportCandidate(
        val url: String,
        val sourceName: String,
        val isZip: Boolean,
    )

    private fun skillRepositoryCandidates(rawUrl: String): List<SkillImportCandidate> {
        if (rawUrl.isBlank()) return emptyList()
        val directSource = rawUrl.substringBefore('?').substringAfterLast('/').ifBlank { "Skill" }
        if (rawUrl.substringBefore('?').endsWith(".zip", ignoreCase = true)) {
            return listOf(SkillImportCandidate(rawUrl, directSource.removeSuffix(".zip"), isZip = true))
        }
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return emptyList()
        val host = uri.host.orEmpty().lowercase()
        val segments = uri.pathSegments.orEmpty().filter { it.isNotBlank() }
        fun cleanRepo(value: String) = value.removeSuffix(".git")
        fun markerIndex(vararg markers: String): Int {
            return segments.indexOfFirst { item -> markers.any { item == it } }
        }
        fun branchAfter(vararg markers: String): String? {
            val index = markerIndex(*markers)
            return if (index >= 0) segments.getOrNull(index + 1) else null
        }
        fun orderedBranches(explicit: String?, default: String?): List<String> {
            return listOfNotNull(explicit, default, "main", "master").distinct()
        }
        fun githubDefaultBranch(owner: String, repo: String): String? {
            return runCatching {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/$owner/$repo")
                    .header("User-Agent", "Lyra-Code")
                    .get()
                    .build()
                skillImportClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    JSONObject(response.body?.string().orEmpty()).optString("default_branch").ifBlank { null }
                }
            }.getOrNull()
        }
        fun giteeDefaultBranch(owner: String, repo: String): String? {
            return runCatching {
                val request = Request.Builder()
                    .url("https://gitee.com/api/v5/repos/$owner/$repo")
                    .header("User-Agent", "Lyra-Code")
                    .get()
                    .build()
                skillImportClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    JSONObject(response.body?.string().orEmpty()).optString("default_branch").ifBlank { null }
                }
            }.getOrNull()
        }
        fun gitlabDefaultBranch(path: String): String? {
            return runCatching {
                val encoded = path.replace("/", "%2F")
                val request = Request.Builder()
                    .url("https://gitlab.com/api/v4/projects/$encoded")
                    .header("User-Agent", "Lyra-Code")
                    .get()
                    .build()
                skillImportClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    JSONObject(response.body?.string().orEmpty()).optString("default_branch").ifBlank { null }
                }
            }.getOrNull()
        }
        fun explicitFilePath(vararg markers: String): String {
            val index = markerIndex(*markers)
            return if (index >= 0) segments.drop(index + 2).joinToString("/") else ""
        }
        if (host == "raw.githubusercontent.com" && segments.size >= 4 && rawUrl.substringBefore('?').endsWith("SKILL.md", ignoreCase = true)) {
            return listOf(SkillImportCandidate(rawUrl, "SKILL.md", isZip = false))
        }
        if (rawUrl.substringBefore('?').endsWith("SKILL.md", ignoreCase = true) && host !in setOf("github.com", "gitee.com", "gitlab.com")) {
            return listOf(SkillImportCandidate(rawUrl, "SKILL.md", isZip = false))
        }
        fun githubCandidates(): List<SkillImportCandidate> {
            if (segments.size < 2) return emptyList()
            val owner = segments[0]
            val repo = cleanRepo(segments[1])
            val branches = orderedBranches(branchAfter("tree", "blob"), githubDefaultBranch(owner, repo))
            val filePath = explicitFilePath("blob")
            if (filePath.endsWith("SKILL.md", ignoreCase = true)) {
                return branches.map { branch ->
                    SkillImportCandidate("https://raw.githubusercontent.com/$owner/$repo/$branch/$filePath", "SKILL.md", isZip = false)
                }
            }
            return branches.map { branch ->
                SkillImportCandidate("https://github.com/$owner/$repo/archive/refs/heads/$branch.zip", repo, isZip = true)
            }
        }
        fun giteeCandidates(): List<SkillImportCandidate> {
            if (segments.size < 2) return emptyList()
            val owner = segments[0]
            val repo = cleanRepo(segments[1])
            val branches = orderedBranches(branchAfter("tree", "blob"), giteeDefaultBranch(owner, repo))
            val filePath = explicitFilePath("blob")
            if (filePath.endsWith("SKILL.md", ignoreCase = true)) {
                return branches.map { branch ->
                    SkillImportCandidate("https://gitee.com/$owner/$repo/raw/$branch/$filePath", "SKILL.md", isZip = false)
                }
            }
            return branches.map { branch ->
                SkillImportCandidate("https://gitee.com/$owner/$repo/repository/archive/$branch.zip", repo, isZip = true)
            }
        }
        fun gitlabCandidates(): List<SkillImportCandidate> {
            val dashIndex = segments.indexOf("-")
            val repoSegments = if (dashIndex >= 0) segments.take(dashIndex) else segments
            if (repoSegments.size < 2) return emptyList()
            val repo = cleanRepo(repoSegments.last())
            val path = repoSegments.joinToString("/")
            val branch = if (dashIndex >= 0 && segments.getOrNull(dashIndex + 1) in setOf("tree", "blob")) segments.getOrNull(dashIndex + 2) else null
            val subPath = if (dashIndex >= 0) segments.drop(dashIndex + 3).joinToString("/") else ""
            val branches = orderedBranches(branch, gitlabDefaultBranch(path))
            if (dashIndex >= 0 && segments.getOrNull(dashIndex + 1) == "blob" && subPath.endsWith("SKILL.md", ignoreCase = true)) {
                return branches.map { item ->
                    SkillImportCandidate("https://gitlab.com/$path/-/raw/$item/$subPath", "SKILL.md", isZip = false)
                }
            }
            return branches.map { item ->
                SkillImportCandidate("https://gitlab.com/$path/-/archive/$item/$repo-$item.zip", repo, isZip = true)
            }
        }
        return when {
            host == "github.com" -> githubCandidates()
            host == "gitee.com" -> giteeCandidates()
            host == "gitlab.com" || host.endsWith(".gitlab.com") -> gitlabCandidates()
            else -> emptyList()
        }
    }

    private fun downloadSkillBytes(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Lyra-Code")
            .get()
            .build()
        skillImportClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "下载失败 ${response.code}: $url" }
            return response.body?.bytes() ?: error("下载内容为空")
        }
    }

    private fun ByteArray.isZipBytes(): Boolean {
        return size >= 2 && this[0] == 0x50.toByte() && this[1] == 0x4B.toByte()
    }

    private fun findSkillFile(dir: File): File? {
        return dir.walkTopDown().firstOrNull { it.isFile && it.name.equals("SKILL.md", ignoreCase = true) }
    }

    private fun parseSkillMeta(skillText: String): Pair<String, String> {
        val frontMatter = if (skillText.trimStart().startsWith("---")) {
            skillText.substringAfter("---").substringBefore("---")
        } else {
            skillText.lineSequence().take(20).joinToString("\n")
        }
        fun field(name: String): String {
            val match = Regex("""(?m)^\s*$name\s*:\s*(.+?)\s*$""").find(frontMatter) ?: return ""
            return match.groupValues[1].trim().trim('"', '\'')
        }
        val fallbackHeading = Regex("""(?m)^#\s+(.+)$""").find(skillText)?.groupValues?.getOrNull(1).orEmpty()
        return field("name").ifBlank { fallbackHeading } to field("description")
    }

    private fun escapeSkillJson(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
    }
}

private fun safeFunctionPart(value: String): String {
    return value.lowercase()
        .replace(Regex("[^a-z0-9_-]+"), "_")
        .trim('_')
}
