package com.yukisoffd.lyracode

import com.yukisoffd.lyracode.data.ApiProfile
import java.net.URI
import java.util.Locale

internal data class ProviderPresetPlan(
    val id: String,
    val nameZh: String,
    val nameEn: String = nameZh,
    val baseUrl: String,
    val chatPath: String = ApiProfile.DEFAULT_OPENAI_CHAT_PATH,
    val apiFormat: String = ApiProfile.API_FORMAT_OPENAI,
) {
    fun displayName(): String = if (AppStrings.isEnglish()) nameEn else nameZh

    companion object {
        const val DEFAULT_ID = "default"
    }
}

internal data class ProviderPreset(
    val id: String,
    val nameZh: String,
    val nameEn: String = nameZh,
    val websiteUrl: String,
    val baseUrl: String,
    val chatPath: String = ApiProfile.DEFAULT_OPENAI_CHAT_PATH,
    val apiFormat: String = ApiProfile.API_FORMAT_OPENAI,
    val logoRes: Int,
    val aliases: Set<String> = emptySet(),
    val additionalPlans: List<ProviderPresetPlan> = emptyList(),
) {
    fun displayName(): String = if (AppStrings.isEnglish()) nameEn else nameZh

    fun plans(): List<ProviderPresetPlan> = listOf(
        ProviderPresetPlan(
            id = ProviderPresetPlan.DEFAULT_ID,
            nameZh = "普通",
            nameEn = "Default",
            baseUrl = baseUrl,
            chatPath = chatPath,
            apiFormat = apiFormat,
        ),
    ) + additionalPlans

    fun resolvePlan(planId: String?, currentBaseUrl: String = ""): ProviderPresetPlan {
        return plans().firstOrNull { it.id == planId }
            ?: plans().firstOrNull { normalizeBaseUrl(it.baseUrl) == normalizeBaseUrl(currentBaseUrl) }
            ?: plans().first()
    }

    fun createProfile(id: String, planId: String = ProviderPresetPlan.DEFAULT_ID): ApiProfile {
        val plan = resolvePlan(planId)
        return ApiProfile(
            id = id,
            presetId = this.id,
            presetPlanId = plan.id,
            name = displayName(),
            apiKey = "",
            baseUrl = plan.baseUrl,
            chatPath = plan.chatPath,
            apiFormat = plan.apiFormat,
            selectedModel = "",
            savedModels = emptyList(),
        )
    }
}

internal object ProviderCatalog {
    val presets: List<ProviderPreset> = listOf(
        ProviderPreset(
            id = "ai302",
            nameZh = "302 AI",
            websiteUrl = "https://302.ai/",
            baseUrl = "https://api.302.ai/v1",
            logoRes = R.drawable.ai_service_ai302,
            aliases = setOf("302", "302ai"),
        ),
        ProviderPreset(
            id = "aihubmix",
            nameZh = "推理时代",
            nameEn = "AiHubMix",
            websiteUrl = "https://console.aihubmix.com/",
            baseUrl = "https://aihubmix.com/v1",
            logoRes = R.drawable.ai_service_aihubmix,
            aliases = setOf("aihubmix", "推理时代"),
        ),
        ProviderPreset(
            id = "anthropic",
            nameZh = "Anthropic",
            websiteUrl = "https://platform.claude.com/",
            baseUrl = "https://api.anthropic.com/v1",
            chatPath = ApiProfile.DEFAULT_ANTHROPIC_CHAT_PATH,
            apiFormat = ApiProfile.API_FORMAT_ANTHROPIC,
            logoRes = R.drawable.ai_service_anthropic,
            aliases = setOf("claude"),
        ),
        ProviderPreset(
            id = "bailian",
            nameZh = "阿里云百炼",
            nameEn = "Alibaba Cloud Bailian",
            websiteUrl = "https://bailian.console.aliyun.com/",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            logoRes = R.drawable.ai_service_bailian,
            aliases = setOf("bailian", "dashscope", "aliyun", "alibaba", "百炼", "阿里云"),
            additionalPlans = listOf(
                ProviderPresetPlan(
                    id = "token_plan",
                    nameZh = "Token Plan",
                    baseUrl = "https://token-plan.cn-beijing.maas.aliyuncs.com/v1",
                ),
            ),
        ),
        ProviderPreset(
            id = "deepseek",
            nameZh = "DeepSeek",
            websiteUrl = "https://platform.deepseek.com/",
            baseUrl = "https://api.deepseek.com/v1",
            logoRes = R.drawable.ai_service_deepseek,
        ),
        ProviderPreset(
            id = "gemini",
            nameZh = "Gemini",
            websiteUrl = "https://aistudio.google.com/",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            chatPath = "/models/{model}:generateContent",
            apiFormat = ApiProfile.API_FORMAT_GEMINI,
            logoRes = R.drawable.ai_service_gemini,
            aliases = setOf("google ai", "googleai", "aistudio"),
        ),
        ProviderPreset(
            id = "minimax",
            nameZh = "MiniMax",
            websiteUrl = "https://platform.minimaxi.com/",
            baseUrl = "https://api.minimaxi.com/v1",
            logoRes = R.drawable.ai_service_minimax,
            aliases = setOf("minimaxi"),
        ),
        ProviderPreset(
            id = "moonshot",
            nameZh = "月之暗面",
            nameEn = "Moonshot AI",
            websiteUrl = "https://platform.kimi.com/",
            baseUrl = "https://api.moonshot.cn/v1",
            logoRes = R.drawable.ai_service_moonshot,
            aliases = setOf("moonshot", "kimi", "月之暗面"),
        ),
        ProviderPreset(
            id = "nvidia",
            nameZh = "NVIDIA",
            websiteUrl = "https://build.nvidia.com/",
            baseUrl = "https://integrate.api.nvidia.com/v1",
            logoRes = R.drawable.ai_service_nvidia,
        ),
        ProviderPreset(
            id = "openai",
            nameZh = "OpenAI",
            websiteUrl = "https://platform.openai.com/",
            baseUrl = "https://api.openai.com/v1",
            logoRes = R.drawable.ai_service_openai,
        ),
        ProviderPreset(
            id = "openrouter",
            nameZh = "OpenRouter",
            websiteUrl = "https://openrouter.ai/",
            baseUrl = "https://openrouter.ai/api/v1",
            logoRes = R.drawable.ai_service_openrouter,
        ),
        ProviderPreset(
            id = "sensenova",
            nameZh = "商汤",
            nameEn = "SenseNova",
            websiteUrl = "https://platform.sensenova.cn/",
            baseUrl = "https://token.sensenova.cn/v1",
            logoRes = R.drawable.ai_service_sensenova,
            aliases = setOf("sensenova", "sensetime", "商汤"),
        ),
        ProviderPreset(
            id = "siliconcloud",
            nameZh = "硅基流动",
            nameEn = "SiliconFlow",
            websiteUrl = "https://siliconflow.cn/",
            baseUrl = "https://api.siliconflow.cn/v1",
            logoRes = R.drawable.ai_service_siliconcloud,
            aliases = setOf("siliconflow", "siliconcloud", "硅基流动"),
        ),
        ProviderPreset(
            id = "stepfun",
            nameZh = "阶跃星辰",
            nameEn = "StepFun",
            websiteUrl = "https://platform.stepfun.com/",
            baseUrl = "https://api.stepfun.com/v1",
            logoRes = R.drawable.ai_service_stepfun,
            aliases = setOf("stepfun", "阶跃星辰"),
            additionalPlans = listOf(
                ProviderPresetPlan(
                    id = "step_plan",
                    nameZh = "Step Plan",
                    baseUrl = "https://api.stepfun.com/step_plan/v1",
                ),
            ),
        ),
        ProviderPreset(
            id = "volcengine",
            nameZh = "火山方舟",
            nameEn = "Volcano Engine Ark",
            websiteUrl = "https://ark.volcengine.com/",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            logoRes = R.drawable.ai_service_volcengine,
            aliases = setOf("volcengine", "volces", "ark", "火山方舟", "豆包"),
            additionalPlans = listOf(
                ProviderPresetPlan(
                    id = "agent_plan",
                    nameZh = "Agent Plan",
                    baseUrl = "https://ark.cn-beijing.volces.com/api/plan/v3",
                ),
                ProviderPresetPlan(
                    id = "coding_plan",
                    nameZh = "Coding Plan",
                    baseUrl = "https://ark.cn-beijing.volces.com/api/coding/v3",
                ),
            ),
        ),
        ProviderPreset(
            id = "xai",
            nameZh = "xAI",
            websiteUrl = "https://console.x.ai/",
            baseUrl = "https://api.x.ai/v1",
            logoRes = R.drawable.ai_service_xai,
            aliases = setOf("grok"),
        ),
        ProviderPreset(
            id = "xiaomimimo",
            nameZh = "Xiaomi MiMo",
            websiteUrl = "https://platform.xiaomimimo.com/",
            baseUrl = "https://api.xiaomimimo.com/v1",
            logoRes = R.drawable.ai_service_xiaomimimo,
            aliases = setOf("xiaomi", "xiaomimimo", "mimo", "小米"),
            additionalPlans = listOf(
                ProviderPresetPlan(
                    id = "token_plan",
                    nameZh = "Token Plan",
                    baseUrl = "https://token-plan-cn.xiaomimimo.com/v1",
                ),
            ),
        ),
        ProviderPreset(
            id = "zai",
            nameZh = "智谱 AI",
            nameEn = "Zhipu AI",
            websiteUrl = "https://open.bigmodel.cn/",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            logoRes = R.drawable.ai_service_zai,
            aliases = setOf("zhipu", "bigmodel", "智谱", "z.ai", "zai"),
            additionalPlans = listOf(
                ProviderPresetPlan(
                    id = "coding_plan",
                    nameZh = "Coding Plan",
                    baseUrl = "https://open.bigmodel.cn/api/coding/paas/v4",
                ),
            ),
        ),
    )

    fun byId(id: String?): ProviderPreset? {
        val normalizedId = if (id == "xiaomi") "xiaomimimo" else id
        return presets.firstOrNull { it.id == normalizedId }
    }

    fun match(profile: ApiProfile): ProviderPreset? {
        val profileHost = hostOf(profile.baseUrl)
        if (profileHost.isNotBlank()) {
            presets.firstOrNull { preset -> preset.plans().any { hostOf(it.baseUrl) == profileHost } }?.let { return it }
        }
        val searchable = normalizeName("${profile.name} ${profile.baseUrl}")
        return presets.firstOrNull { preset ->
            (preset.aliases + preset.id + preset.nameZh + preset.nameEn)
                .map(::normalizeName)
                .filter { it.length >= 3 }
                .any(searchable::contains)
        }
    }

    fun logoRes(profile: ApiProfile): Int? = when {
        profile.isChatGpt -> R.drawable.ai_service_openai
        profile.id.startsWith("newapi:") -> R.drawable.ai_service_newapi
        else -> match(profile)?.logoRes
    }

    private fun hostOf(value: String): String = runCatching {
        URI(value.trim()).host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
    }.getOrDefault("")

    private fun normalizeName(value: String): String = value
        .lowercase(Locale.ROOT)
        .filter { it.isLetterOrDigit() }
}

internal fun canonicalModelName(rawName: String): String {
    val cleaned = rawName.trim().trim('/', '\\')
    if (cleaned.isBlank()) return ""
    return cleaned.substringAfterLast('/').substringAfterLast('\\').trim()
}

internal fun modelLogoRes(modelName: String): Int? {
    val normalized = canonicalModelName(modelName).lowercase(Locale.ROOT)
    return when {
        "claude" in normalized -> R.drawable.ai_model_claude
        "deepseek" in normalized -> R.drawable.ai_model_deepseek
        "doubao" in normalized || "seed" in normalized -> R.drawable.ai_model_doubao
        "gemini" in normalized || "gemma" in normalized -> R.drawable.ai_model_gemini
        "grok" in normalized -> R.drawable.ai_model_grok
        "hy3" in normalized || "hunyuan" in normalized -> R.drawable.ai_model_hunyuan
        "kimi" in normalized -> R.drawable.ai_model_kimi
        "longcat" in normalized -> R.drawable.ai_model_longcat
        "llama" in normalized -> R.drawable.ai_model_meta
        "mimo" in normalized -> R.drawable.ai_model_xiaomimimo
        "minimax" in normalized -> R.drawable.ai_model_minimax
        "mistral" in normalized -> R.drawable.ai_model_mistral
        "gpt" in normalized -> R.drawable.ai_model_openai
        "qwen" in normalized -> R.drawable.ai_model_qwen
        "sensenova" in normalized -> R.drawable.ai_model_sensenova
        "step" in normalized -> R.drawable.ai_model_stepfun
        "ernie" in normalized -> R.drawable.ai_model_wenxin
        normalized == "yi" ||
            normalized.startsWith("yi-") ||
            normalized.startsWith("yi_") ||
            normalized.startsWith("yi.") -> R.drawable.ai_model_zeroone
        "glm" in normalized -> R.drawable.ai_model_zhipu
        else -> null
    }
}

private fun normalizeBaseUrl(value: String): String = value.trim().trimEnd('/').lowercase(Locale.ROOT)
