package com.yukisoffd.lyracode

import com.yukisoffd.lyracode.data.ApiProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderCatalogTest {
    @Test
    fun customProfileGetsPresetLogoFromEndpointOrName() {
        val endpointMatched = profile(
            name = "我的主力服务",
            baseUrl = "https://openrouter.ai/api/v1",
        )
        val nameMatched = profile(
            name = "DeepSeek 备用",
            baseUrl = "https://example.com/v1",
        )

        assertEquals("openrouter", ProviderCatalog.match(endpointMatched)?.id)
        assertEquals("deepseek", ProviderCatalog.match(nameMatched)?.id)
    }

    @Test
    fun modelLogoMatchingUsesCanonicalLeafName() {
        assertEquals(R.drawable.ai_model_deepseek, modelLogoRes("deepseek-ai/deepseek-v4-pro"))
        assertEquals(R.drawable.ai_model_qwen, modelLogoRes("vendor/nested/qwen3-235b"))
        assertEquals(R.drawable.ai_model_xiaomimimo, modelLogoRes("xiaomi/mimo-v2-flash"))
        assertEquals(R.drawable.ai_model_doubao, modelLogoRes("volc/doubao-seed-2.0"))
        assertEquals(R.drawable.ai_model_meta, modelLogoRes("meta-llama/llama-4"))
        assertEquals(R.drawable.ai_model_wenxin, modelLogoRes("baidu/ernie-5.1"))
        assertEquals(R.drawable.ai_model_mistral, modelLogoRes("mistralai/mistral-medium-3.5"))
        assertEquals(R.drawable.ai_model_sensenova, modelLogoRes("sensetime/sensenova-6.7"))
        assertEquals(R.drawable.ai_model_stepfun, modelLogoRes("stepfun-ai/step-3.7"))
        assertEquals(R.drawable.ai_model_zeroone, modelLogoRes("01-ai/yi-lightning"))
        assertNull(modelLogoRes("vendor/unknown-model"))
    }

    @Test
    fun senseNovaPresetMatchesOfficialEndpointAndLogo() {
        val preset = requireNotNull(ProviderCatalog.byId("sensenova"))
        val profile = preset.createProfile("sensenova-test")

        assertEquals("https://token.sensenova.cn/v1/chat/completions", profile.chatEndpoint)
        assertEquals("sensenova", ProviderCatalog.match(profile)?.id)
        assertEquals(R.drawable.ai_service_sensenova, ProviderCatalog.logoRes(profile))
    }

    @Test
    fun openCodeGoPresetMatchesOfficialEndpointAndLogo() {
        val preset = requireNotNull(ProviderCatalog.byId("opencodego"))
        val profile = preset.createProfile("opencodego-test")

        assertEquals("https://opencode.ai/inference/go/v1/chat/completions", profile.chatEndpoint)
        assertEquals("opencodego", ProviderCatalog.match(profile)?.id)
        assertEquals(R.drawable.ai_service_opencode, ProviderCatalog.logoRes(profile))
    }

    @Test
    fun providerPlanSelectsItsOwnEditableBaseUrl() {
        val volcengine = requireNotNull(ProviderCatalog.byId("volcengine"))
        val defaultPlan = volcengine.createProfile("volc-default")
        val agentPlan = volcengine.createProfile("volc-agent", "agent_plan")
        val codingPlan = volcengine.createProfile("volc-coding", "coding_plan")

        assertEquals(volcengine.displayName(), defaultPlan.name)
        assertEquals(volcengine.displayName(), agentPlan.name)
        assertEquals(volcengine.displayName(), codingPlan.name)
        assertEquals("agent_plan", agentPlan.presetPlanId)
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/plan/v3/chat/completions",
            agentPlan.chatEndpoint,
        )
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/coding/v3/chat/completions",
            codingPlan.chatEndpoint,
        )
    }

    @Test
    fun legacyXiaomiPresetAndTokenPlanResolveToXiaomiMiMo() {
        val preset = requireNotNull(ProviderCatalog.byId("xiaomi"))
        val tokenPlan = preset.createProfile("mimo-plan", "token_plan")

        assertEquals("xiaomimimo", preset.id)
        assertEquals("Xiaomi MiMo", preset.nameZh)
        assertEquals(preset.displayName(), tokenPlan.name)
        assertEquals(
            "https://token-plan-cn.xiaomimimo.com/v1/chat/completions",
            tokenPlan.chatEndpoint,
        )
        assertEquals("xiaomimimo", ProviderCatalog.match(tokenPlan)?.id)
    }

    @Test
    fun documentedProviderPlansUseTheirDedicatedEndpoints() {
        val cases = listOf(
            Triple("bailian", "token_plan", "https://token-plan.cn-beijing.maas.aliyuncs.com/v1/chat/completions"),
            Triple("stepfun", "step_plan", "https://api.stepfun.com/step_plan/v1/chat/completions"),
            Triple("zai", "coding_plan", "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions"),
        )

        cases.forEach { (providerId, planId, expectedEndpoint) ->
            val profile = requireNotNull(ProviderCatalog.byId(providerId)).createProfile("$providerId-$planId", planId)
            assertEquals(expectedEndpoint, profile.chatEndpoint)
        }
        assertEquals("aihubmix", ProviderCatalog.byId("aihubmix")?.id)
        assertEquals("volcengine", ProviderCatalog.byId("volcengine")?.id)
    }

    @Test
    fun geminiCustomPathIsUsedByRequestEndpoint() {
        val profile = profile(
            name = "Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        ).copy(
            apiFormat = ApiProfile.API_FORMAT_GEMINI,
            chatPath = "/custom/models/{model}:generateContent",
        )

        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/custom/models/gemini-test:generateContent",
            profile.geminiGenerateContentEndpoint("gemini-test"),
        )
    }

    private fun profile(name: String, baseUrl: String) = ApiProfile(
        id = "test",
        name = name,
        apiKey = "",
        baseUrl = baseUrl,
        selectedModel = "",
        savedModels = emptyList(),
    )
}
