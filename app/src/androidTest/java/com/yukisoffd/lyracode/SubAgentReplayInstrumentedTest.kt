package com.yukisoffd.lyracode

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.data.*
import com.yukisoffd.lyracode.interaction.agent.DeviceAgentFactory
import com.yukisoffd.lyracode.webaccess.WebAccessHttp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class SubAgentReplayInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val task = "reuse this completed read"
    private val batchId = "saved-replay-batch"

    private fun withFixture(block: (AppSettings, ConversationStore, ApiProfile, ParentProvider) -> Unit) {
        val settings = AppSettings(context)
        val profiles = settings.profiles()
        val selected = settings.selectedApiProfileId
        val agents = settings.subAgents()
        val enabled = settings.subAgentOrchestrationEnabled
        val toolEnabled = "run_sub_agents" !in settings.disabledTools()
        val pure = settings.purePromptMode
        try {
            ParentProvider().use { provider ->
                val profile = ApiProfile("replay-fixture", "Replay fixture", "fixture-key",
                    "http://127.0.0.1:${provider.port}/v1", selectedModel = "fixture-model",
                    savedModels = listOf("fixture-model"), enabledModels = listOf("fixture-model"))
                settings.saveProfiles(profiles + profile, profile.id)
                settings.saveSubAgents(listOf(SubAgentConfig("fixture", "Fixture", profile.id, profile.selectedModel, "", true)))
                settings.subAgentOrchestrationEnabled = true
                settings.purePromptMode = false
                settings.setToolEnabled("run_sub_agents", true)
                ConversationStore(context, inMemory = true).use { block(settings, it, profile, provider) }
            }
        } finally {
            settings.saveSubAgents(agents)
            settings.saveProfiles(profiles, selected)
            settings.subAgentOrchestrationEnabled = enabled
            settings.purePromptMode = pure
            settings.setToolEnabled("run_sub_agents", toolEnabled)
        }
    }

    private fun arguments() = JSONObject().put("tasks", JSONArray().put(JSONObject()
        .put("task", task).put("read_only", true).put("write_paths", JSONArray()).put("sub_agent_id", "fixture")))

    private fun seed(store: ConversationStore, profile: ApiProfile, savedAt: Long, savedCall: String): Pair<Long, Long> {
        val parent = store.createConversation(profile.id, profile.selectedModel)
        val user = store.addMessage(parent, "user", "complete the saved batch")
        val child = store.createConversation(profile.id, profile.selectedModel, mode = ConversationStore.MODE_SUBAGENT)
        store.addMessage(child, "assistant", "saved-child-answer")
        store.setConversationMeta(child, status = ConversationStore.STATUS_IDLE)
        store.beginSubAgentBatch(parent, batchId, user,
            listOf(SubAgentTaskSeed(0, task, emptyList(), true, "", "", "fixture")), toolCallId = savedCall)
        store.attachSubAgentTaskChild(parent, batchId, 0, child)
        store.setSubAgentTaskStatus(parent, batchId, 0, SubAgentTaskStatus.COMPLETED)
        store.writableDatabase.execSQL("UPDATE sub_agent_tasks SET updated_at=? WHERE batch_id=?", arrayOf(savedAt, batchId))
        return parent to child
    }

    private fun payload(output: String): JSONObject {
        val envelope = JSONObject(output)
        assertTrue(output, envelope.getBoolean("ok"))
        return JSONObject(envelope.getString("content"))
    }

    private fun assertSavedTiming(result: JSONObject, savedAt: Long) {
        assertEquals(Instant.ofEpochMilli(savedAt).truncatedTo(ChronoUnit.SECONDS).toString(), result.getString("interrupted_at"))
        assertTrue(result.toString(), result.getLong("interrupted_seconds_ago") >= 120)
        assertTrue(result.getBoolean("resumed"))
        assertTrue(result.getJSONArray("results").getJSONObject(0).getBoolean("reused"))
        assertEquals("saved-child-answer", result.getJSONArray("results").getJSONObject(0).getString("result"))
    }

    @Test fun sameTurnRetryRepointsSavedCallWithoutLosingOriginalActivityTime() = withFixture { settings, store, profile, provider ->
        val savedAt = System.currentTimeMillis() - 120_000
        val (parent, child) = seed(store, profile, savedAt, "previous-call")
        val agent = DeviceAgentFactory.create(context, settings, store, workspaceUri = null)
        val output = runBlocking { withTimeout(15_000) { agent.executeFloatingTool(parent, "run_sub_agents", arguments()) } }
        assertSavedTiming(payload(output), savedAt)
        val batch = requireNotNull(store.openSubAgentBatch(parent))
        assertTrue(batch.toolCallId.isNotBlank())
        assertNotEquals("previous-call", batch.toolCallId)
        assertEquals(child, batch.tasks.single().childConversationId)
        assertTrue(provider.requests.toString(), provider.requests.isEmpty())
    }

    @Test fun continuePersistsSavedResultBeforeTheNextParentRequestAndClosesBatch() = withFixture { settings, store, profile, provider ->
        val savedAt = System.currentTimeMillis() - 120_000
        val (parent, child) = seed(store, profile, savedAt, "saved-call")
        val call = JSONObject().put("id", "saved-call").put("type", "function")
            .put("function", JSONObject().put("name", "run_sub_agents").put("arguments", arguments().toString()))
        store.addMessage(parent, "assistant", "", rawJson = JSONObject().put("role", "assistant")
            .put("content", "").put("tool_calls", JSONArray().put(call)).toString())
        val agent = DeviceAgentFactory.create(context, settings, store, workspaceUri = null)
        runBlocking { withTimeout(15_000) { agent.continueConversation(parent, profile, profile.selectedModel) {} } }
        assertTrue(provider.errors.toString(), provider.errors.isEmpty())
        assertEquals(1, provider.requests.size)
        val messages = provider.requests.single().getJSONArray("messages")
        val delivered = (0 until messages.length()).map { messages.getJSONObject(it) }
            .single { it.optString("role") == "tool" && it.optString("tool_call_id") == "saved-call" }
        assertSavedTiming(payload(delivered.getString("content")), savedAt)
        assertNull(store.openSubAgentBatch(parent))
        assertEquals("parent-done", store.messages(parent).last().content)
        assertEquals("saved-child-answer", store.messages(child).last().content)
        assertEquals(1, store.messages(parent).count { it.role == "tool" && it.toolCallId == "saved-call" })
        assertEquals(ConversationStore.STATUS_IDLE, store.conversation(parent)?.status)
    }

    private class ParentProvider : AutoCloseable {
        private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        val requests = CopyOnWriteArrayList<JSONObject>()
        val errors = CopyOnWriteArrayList<String>()
        private val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                try {
                    socket.use {
                        it.soTimeout = 10_000
                        val input = it.getInputStream().buffered()
                        val header = WebAccessHttp.readRequest(input)
                        val body = JSONObject(WebAccessHttp.readBody(input, header.length).toString(Charsets.UTF_8))
                        requests += body
                        val output = it.getOutputStream()
                        if (body.optBoolean("stream")) {
                            WebAccessHttp.eventHeaders(output)
                            output.write("data: {\"choices\":[{\"delta\":{\"content\":\"parent-done\"}}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toByteArray())
                            output.flush()
                        } else {
                            val body = JSONObject().put("choices", JSONArray().put(JSONObject()
                                .put("message", JSONObject().put("role", "assistant").put("content", "parent-done"))
                                .put("finish_reason", "stop")))
                            WebAccessHttp.respond(output, 200, "application/json", body.toString().toByteArray())
                        }
                    }
                } catch (error: Exception) { errors += error.toString() }
            }
        }
        override fun close() { server.close(); worker.join(2000) }
    }
}
