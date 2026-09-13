package com.yukisoffd.lyracode

import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.data.*
import com.yukisoffd.lyracode.ai.AgentReachabilityService
import com.yukisoffd.lyracode.interaction.agent.DeviceAgentFactory
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalModelInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun pureModeRemovesNativePromptAndPreservesExactCustomText() {
        val settings = AppSettings(context)
        val oldMode = settings.purePromptMode
        val oldId = settings.selectedSystemPromptId
        val id = "pure-mode-test-${System.nanoTime()}"
        val store = ConversationStore(context)
        try {
            val agent = DeviceAgentFactory.create(context, settings, store)
            val method = agent.javaClass.getDeclaredMethod("systemMessagesFor", java.lang.Long.TYPE).apply { isAccessible = true }
            fun prompts() = (method.invoke(agent, -999L) as List<*>).map { (it as JSONObject).getString("content") }
            settings.selectedSystemPromptId = AppSettings.NATIVE_SYSTEM_PROMPT_ID
            settings.purePromptMode = false
            assertTrue(prompts().joinToString().length > 1000)
            settings.purePromptMode = true
            assertTrue(prompts().isEmpty())
            for (name in listOf("toolDefinitionsFor", "anthropicToolsFor", "geminiFunctionDeclarationsFor")) {
                val toolMethod = agent.javaClass.getDeclaredMethod(name, java.lang.Long.TYPE).apply { isAccessible = true }
                assertEquals(name, 0, (toolMethod.invoke(agent, -999L) as org.json.JSONArray).length())
            }
            val responsesTools = agent.javaClass.getDeclaredMethod("responsesToolDefinitions", java.lang.Long.TYPE, ApiProfile::class.java).apply { isAccessible = true }
            val profile = ApiProfile("test", "test", "", "https://api.deepseek.com", selectedModel = "deepseek-chat", savedModels = emptyList(), useResponsesApi = true)
            assertEquals(0, (responsesTools.invoke(agent, -999L, profile) as org.json.JSONArray).length())
            assertTrue(AppSettings(context).purePromptMode)
            settings.saveSystemPromptConfig(SystemPromptPreset(id, "test", "Exact custom prompt", builtIn = false))
            settings.selectedSystemPromptId = id
            assertEquals(listOf("Exact custom prompt"), prompts())
            settings.purePromptMode = false
            assertTrue(prompts().any { it.contains("Exact custom prompt") })
        } finally {
            settings.deleteSystemPromptConfig(id)
            settings.selectedSystemPromptId = oldId
            settings.purePromptMode = oldMode
            store.close()
        }
    }
    @Test fun noKeyRequestsOmitCredentialsAndKeyedRequestsStillAuthenticate() {
        AppStrings.initialize(context)
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"data\":[{\"id\":\"local-model\"}]}".toResponseBody()).build()
        }.build()
        val service = AgentReachabilityService(client, client)
        for (format in listOf(ApiProfile.API_FORMAT_OPENAI, ApiProfile.API_FORMAT_ANTHROPIC, ApiProfile.API_FORMAT_GEMINI)) {
            for (key in listOf("", "test-key")) {
                val profile = ApiProfile("test", "local", key, "http://127.0.0.1:8080/v1", apiFormat = format,
                    selectedModel = "local-model", savedModels = listOf("local-model"))
                requests.clear()
                service.checkProviderReachability(profile)
                service.checkModelReachability(profile, "local-model")
                assertEquals(2, requests.size)
                requests.forEach {
                    val auth = it.header("Authorization") ?: it.header("x-api-key") ?: it.header("x-goog-api-key")
                    if (key.isEmpty()) assertNull(auth) else assertTrue(auth.orEmpty().contains(key))
                }
                if (format == ApiProfile.API_FORMAT_OPENAI) assertEquals(listOf("local-model"), service.fetchModels(profile).getOrThrow())
            }
        }
    }
}

