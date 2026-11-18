package com.yukisoffd.lyracode.data

import org.json.JSONArray
import org.json.JSONObject

/** The same codec is used for encrypted storage and optional secret-bearing backups. */
internal object ApiProfileJson {
    fun encode(profile: ApiProfile, includeSecrets: Boolean = true): JSONObject = JSONObject()
        .put("id", profile.id)
        .put("chatGptAccountId", if (profile.isDeepSeekHarness) "" else profile.chatGptAccountId)
        .put("modelDisplayNames", JSONObject(profile.modelDisplayNames))
        .put("presetId", profile.presetId)
        .put("presetPlanId", profile.presetPlanId)
        .put("name", profile.name)
        .put("apiKey", if (includeSecrets && !profile.usesAccountCredential) profile.apiKey else "")
        .put("secondaryApiKeys", JSONArray(if (includeSecrets) profile.allApiKeys.filter { it != profile.apiKey.trim() } else emptyList()))
        .put("baseUrl", when {
            profile.isDeepSeekHarness -> DeepSeekHarnessProtocol.BASE
            profile.isChatGpt -> profile.chatGptBaseUrl
            else -> profile.baseUrl
        })
        .put("chatPath", when {
            profile.isDeepSeekHarness -> ApiProfile.DEFAULT_ANTHROPIC_CHAT_PATH
            profile.isChatGpt -> ApiProfile.DEFAULT_OPENAI_CHAT_PATH
            else -> ApiProfile.normalizedChatPath(profile.apiFormat, profile.chatPath)
        })
        .put("apiFormat", when {
            profile.isDeepSeekHarness -> ApiProfile.API_FORMAT_ANTHROPIC
            profile.isChatGpt -> ApiProfile.API_FORMAT_OPENAI
            else -> profile.apiFormat
        })
        .put("selectedModel", profile.selectedModel)
        .put("savedModels", JSONArray(profile.savedModels.distinct()))
        .put("enabledModels", JSONArray(profile.enabledModels.distinct()))
        .put("useResponsesApi", !profile.isDeepSeekHarness && (profile.isChatGpt || (profile.apiFormat == ApiProfile.API_FORMAT_OPENAI && profile.usesResponsesApi)))
        .put("gzipRequestBodies", !profile.isDeepSeekHarness && profile.gzipRequestBodies)
        .put("modelRequestOverrides", ModelRequestCustomization.encodeMap(if (profile.usesAccountCredential) emptyMap() else profile.modelRequestOverrides))

    fun decode(item: JSONObject, defaultBaseUrl: String, defaultModel: String, defaultName: String = "API"): ApiProfile {
        fun strings(name: String): List<String> {
            val array = item.optJSONArray(name) ?: return emptyList()
            return (0 until array.length()).map { array.optString(it).trim() }.filter(String::isNotBlank).distinct()
        }
        val id = item.optString("id").ifBlank { AppSettings.newId() }
        val isHarness = id.startsWith(DeepSeekHarnessProtocol.PROFILE_PREFIX)
        val chatGptAccountId = item.optString("chatGptAccountId").takeUnless { isHarness }.orEmpty()
        val isChatGpt = chatGptAccountId.isNotBlank()
        val accountCredential = isHarness || isChatGpt
        val format = when {
            isHarness -> ApiProfile.API_FORMAT_ANTHROPIC
            isChatGpt -> ApiProfile.API_FORMAT_OPENAI
            else -> item.optString("apiFormat").ifBlank { ApiProfile.API_FORMAT_OPENAI }
        }
        val models = strings("savedModels")
        val keys = (listOf(item.optString("apiKey")) + strings("secondaryApiKeys"))
            .map(String::trim).filter(String::isNotBlank).distinct()
        val storedName = item.optString("name").ifBlank { defaultName }
        val displayName = if (item.optString("id").startsWith("newapi:")) {
            storedName.replaceFirst(Regex("^(梦汐|夢汐) API · "), "New API · ")
        } else storedName
        return ApiProfile(
            id = id,
            name = displayName,
            chatGptAccountId = chatGptAccountId,
            modelDisplayNames = item.optJSONObject("modelDisplayNames")?.let { names ->
                names.keys().asSequence().associateWith { names.optString(it) }
            }.orEmpty(),
            apiKey = if (accountCredential) "" else keys.firstOrNull().orEmpty(),
            secondaryApiKeys = if (accountCredential) emptyList() else keys.drop(1),
            baseUrl = when {
                isHarness -> DeepSeekHarnessProtocol.BASE
                chatGptAccountId.startsWith("codex:") -> ChatGptCodexProtocol.BASE
                isChatGpt -> ChatGptProtocol.RESOURCE
                else -> item.optString("baseUrl").ifBlank { defaultBaseUrl }
            },
            chatPath = if (isHarness) ApiProfile.DEFAULT_ANTHROPIC_CHAT_PATH else ApiProfile.normalizedChatPath(format, item.optString("chatPath")),
            apiFormat = format,
            selectedModel = if (item.has("selectedModel")) item.optString("selectedModel") else defaultModel,
            savedModels = models,
            enabledModels = if (item.has("enabledModels")) strings("enabledModels") else models,
            useResponsesApi = isChatGpt || (format == ApiProfile.API_FORMAT_OPENAI && item.optBoolean("useResponsesApi")),
            gzipRequestBodies = !isHarness && item.optBoolean("gzipRequestBodies", ApiProfile.defaultGzipRequestBodies(format)),
            presetId = item.optString("presetId"),
            presetPlanId = item.optString("presetPlanId"),
            modelRequestOverrides = if (accountCredential) emptyMap() else ModelRequestCustomization.decodeMap(item.optJSONObject("modelRequestOverrides")),
        )
    }

    fun merge(current: ApiProfile, incoming: ApiProfile): ApiProfile {
        val keys = if (incoming.usesAccountCredential) emptyList() else (incoming.allApiKeys + current.allApiKeys).distinct()
        return incoming.copy(
            id = current.id,
            apiKey = keys.firstOrNull().orEmpty(),
            secondaryApiKeys = keys.drop(1),
            savedModels = (incoming.savedModels + current.savedModels).filter(String::isNotBlank).distinct(),
            enabledModels = (incoming.enabledModels + current.enabledModels).filter(String::isNotBlank).distinct(),
        )
    }
}
