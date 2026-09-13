package com.yukisoffd.lyracode.data



data class ApiProfile(
    val id: String,
    val name: String,
    val apiKey: String,
    val baseUrl: String,
    val chatPath: String = DEFAULT_OPENAI_CHAT_PATH,
    val apiFormat: String = API_FORMAT_OPENAI,
    val selectedModel: String,
    val savedModels: List<String>,
    val enabledModels: List<String> = savedModels,
    val useResponsesApi: Boolean = false,
    val presetId: String = "",
    val presetPlanId: String = "",
    val modelRequestOverrides: Map<String, ModelRequestCustomization> = emptyMap(),
) {
    val chatEndpoint: String
        get() = "${baseUrl.trimEnd('/')}${normalizedChatPath(apiFormat, chatPath)}"

    val modelsEndpoint: String
        get() = "${baseUrl.trimEnd('/')}/models"

    val responsesEndpoint: String
        get() = "${baseUrl.trimEnd('/')}/responses"

    fun geminiGenerateContentEndpoint(model: String): String {
        val encoded = model.trim().removePrefix("models/")
        val path = normalizedChatPath(apiFormat, chatPath).replace("{model}", encoded)
        return "${baseUrl.trimEnd('/')}$path"
    }

    companion object {
        const val DEFAULT_OPENAI_CHAT_PATH = "/chat/completions"
        const val DEFAULT_ANTHROPIC_CHAT_PATH = "/messages"
        const val API_FORMAT_OPENAI = "openai"
        const val API_FORMAT_ANTHROPIC = "anthropic_messages"
        const val API_FORMAT_GEMINI = "gemini_generate_content"

        fun defaultChatPath(apiFormat: String): String = when (apiFormat) {
            API_FORMAT_ANTHROPIC -> DEFAULT_ANTHROPIC_CHAT_PATH
            API_FORMAT_GEMINI -> "/models/{model}:generateContent"
            else -> DEFAULT_OPENAI_CHAT_PATH
        }

        fun normalizedChatPath(apiFormat: String, value: String): String {
            val fallback = defaultChatPath(apiFormat)
            val trimmed = value.trim().ifBlank { fallback }
            return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        }
    }
}

enum class MediaGenerationKind(val value: String) {
    IMAGE("image"),
    VIDEO("video"),
    MUSIC("music"),
    AUDIO("audio");

    companion object {
        fun fromValue(value: String): MediaGenerationKind? = entries.firstOrNull { it.value == value }
    }
}

data class MediaGenerationModelConfig(
    val kind: MediaGenerationKind,
    val profileId: String,
    val model: String,
)

data class VisionUnderstandingConfig(
    val enabled: Boolean,
    val sourceType: String,
    val profileId: String,
    val model: String,
    val mcpServerId: String,
    val mcpToolName: String,
    val relayPrompt: String,
)

data class OcrModelConfig(
    val enabled: Boolean,
    val profileId: String,
    val model: String,
)

data class SystemPromptPreset(
    val id: String,
    val name: String,
    val prompt: String,
    val exampleConversation: String = "",
    val builtIn: Boolean = true,
)

data class SkillPack(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
    val fileCount: Int,
)

data class SubAgentConfig(
    val id: String,
    val name: String,
    val profileId: String,
    val model: String,
    val description: String,
    val enabled: Boolean,
)


data class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: String,
)

data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val authKey: String,
    val transport: String,
    val timeoutSeconds: Int,
    val enabled: Boolean,
    val rawJson: String,
    val tools: List<McpToolDefinition>,
)

data class LocalMcpServerConfig(
    val host: String,
    val port: Int,
    val authKey: String,
    val enabled: Boolean,
)

data class SshServerConfig(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val authType: String,
    val password: String,
    val privateKey: String,
    val passphrase: String,
    val timeoutSeconds: Int,
    val enabled: Boolean,
) {
    val stableId: String
        get() = "${host.trim()}:${port.coerceIn(1, 65535)}"
}

data class EmailServerConfig(
    val id: String,
    val name: String,
    val emailAddress: String,
    val username: String,
    val password: String,
    val imapHost: String,
    val imapPort: Int = 993,
    val imapSecurity: String = "ssl",
    val smtpHost: String,
    val smtpPort: Int = 465,
    val smtpSecurity: String = "ssl",
    val enabled: Boolean = true,
) {
    val stableId: String
        get() = emailAddress.trim().lowercase()
}

data class MiniServerConfig(
    val protocol: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val customDomains: List<String>,
    val forceHttps: Boolean,
    val tlsKeyStoreBase64: String,
    val tlsKeyStorePassword: String,
    val tlsCertificateChain: String,
    val tlsPrivateKey: String,
    val spaFallback: Boolean,
    val directoryListing: Boolean,
    val mdnsEnabled: Boolean,
    val mdnsName: String,
    val enabled: Boolean,
)

data class FileTransferServerConfig(
    val id: String,
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val usePrivateKey: Boolean,
    val privateKey: String,
    val passphrase: String,
    val initialPath: String,
    val note: String,
    val encoding: String,
    val passiveMode: Boolean,
    val explicitFtps: Boolean,
    val multiThread: Boolean,
    val syncPermissions: Boolean,
    val hideAddressInDrawer: Boolean,
    val enabled: Boolean,
) {
    val stableId: String
        get() = "${protocol.trim().lowercase()}://${username.trim()}@${host.trim()}:${port.coerceIn(1, 65535)}"
}

data class FontLibraryItem(
    val id: String,
    val name: String,
    val path: String,
) {
    val extension: String
        get() = name.substringAfterLast('.', "").uppercase()
}

data class WebDavServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val username: String,
    val password: String,
    val userAgent: String,
    val initialPath: String,
    val note: String,
    val trustAllCertificates: Boolean,
    val multiThread: Boolean,
    val hideAddressInDrawer: Boolean,
    val enabled: Boolean,
) {
    val stableId: String
        get() = url.trim().trimEnd('/')
}

