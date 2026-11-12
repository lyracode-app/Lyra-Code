package com.yukisoffd.lyracode

import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.ai.*
import com.yukisoffd.lyracode.data.*
import com.yukisoffd.lyracode.interaction.agent.DeviceAgentFactory
import com.yukisoffd.lyracode.interaction.session.*
import com.yukisoffd.lyracode.workspace.UploadedFileManager
import com.yukisoffd.lyracode.workspace.WorkspaceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class DeviceConversationHistoryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun scopedFinishPersistsLongSummaryThinkingAndToolResultsInOrder() = runBlocking {
        val summary = "最终总结\n".repeat(1200) + "结尾"
        val thinking = "检查结果\n".repeat(800) + "检查结束"
        // Exceed both the old overlay preview and the generic tool-history cap.
        val output = "工具输出\n".repeat(110_000) + "末行"
        ModelFixture(listOf(
            response("中间说明", "第一轮思考", "first", "fixture_read"),
            response("", thinking, "finish", "fixture_finish"),
        )).use { server ->
            ConversationStore(context, inMemory = true).use { store ->
                val profile = profile(server, "engine-${System.nanoTime()}")
                val id = store.createConversation(profile.id, profile.selectedModel)
                val agent = DeviceAgentFactory.create(context, AppSettings(context), store)
                agent.scopedTools = object : ScopedAgentTools {
                    override val conversationId = id
                    override val systemPrompt = "Local transcript test"
                    override var finished = false
                    override val completionSummary get() = if (finished) summary else null
                    override fun definitions() = JSONArray()
                    override fun checkRound() = Unit
                    override suspend fun execute(name: String, arguments: JSONObject): String {
                        if (name == "fixture_finish") finished = true
                        return if (finished) "TASK_FINISHED" else output
                    }
                }
                agent.chat(id, "test history", profile, profile.selectedModel, propagateErrors = true) {}
                val messages = store.messages(id).filterNot { it.role == RUNTIME_CONTEXT_ROLE }
                assertEquals(listOf("user", "assistant", "tool", "assistant", "tool", "assistant"), messages.map { it.role })
                assertEquals(output, messages[2].content)
                assertEquals(thinking, messages[3].thinking)
                assertEquals("TASK_FINISHED", messages[4].content)
                assertEquals(summary, messages.last().content)
                assertEquals(ConversationStore.STATUS_IDLE, store.conversation(id)!!.status)
                val records = enrichToolRecords(messages.map { it.toRecord() })
                assertEquals(listOf("fixture_read", "fixture_finish"), records.filter { it.role == "tool" }.map { it.toolName })
                assertEquals(summary, chatRenderItems(records).last().message!!.content)
                assertEquals(2, server.requests.size)
            }
        }
    }

    @Test fun interruptedScopedToolKeepsItsRowAndEarlierReasoning() = runBlocking {
        ModelFixture(listOf(response("正在检查", "保留这段思考", "cancel", "fixture_cancel"))).use { server ->
            ConversationStore(context, inMemory = true).use { store ->
                val profile = profile(server, "cancel-${System.nanoTime()}")
                val id = store.createConversation(profile.id, profile.selectedModel)
                val agent = DeviceAgentFactory.create(context, AppSettings(context), store)
                agent.scopedTools = object : ScopedAgentTools {
                    override val conversationId = id
                    override val systemPrompt = "Local cancellation test"
                    override val finished = false
                    override fun definitions() = JSONArray()
                    override fun checkRound() = Unit
                    override suspend fun execute(name: String, arguments: JSONObject): String = throw CancellationException("fixture stopped")
                }
                try {
                    agent.chat(id, "interrupt", profile, profile.selectedModel, propagateErrors = true) {}
                    fail("Expected cancellation")
                } catch (_: CancellationException) { }
                val messages = store.messages(id).filterNot { it.role == RUNTIME_CONTEXT_ROLE }
                assertEquals(listOf("user", "assistant", "tool"), messages.map { it.role })
                assertEquals("保留这段思考", messages[1].thinking)
                assertTrue(messages.last().content.contains("fixture stopped"))
                assertEquals(ConversationStore.STATUS_INTERRUPTED, store.conversation(id)!!.status)
            }
        }
    }

    @Test fun coordinatorRefreshesOpenHistoryWithoutAnotherSendAndReusesPersistedIds() {
        val settings = AppSettings(context)
        val profiles = settings.profiles()
        val selected = settings.selectedProfile().id
        val pure = settings.purePromptMode
        val experimental = settings.deviceInteractionExperimentalEnabled
        val prefix = "history-${System.nanoTime().toString().takeLast(10)}"
        val summary = "已完成设备任务\n".repeat(700) + "最终结尾"
        val release = CountDownLatch(1)
        val received = CountDownLatch(1)
        val store = ConversationStore(context)
        var controller: ChatController? = null
        ModelFixture(listOf(
            response("先观察", "第一段思考", "observe", "device_observe"),
            response("", "第二段思考", "finish", "device_finish", JSONObject().put("summary", summary)),
            response("后续回复", "后续思考"),
        ), received, release).use { server ->
            try {
                val profile = profile(server, prefix)
                settings.saveProfiles(profiles + profile, profile.id)
                settings.purePromptMode = false
                settings.deviceInteractionExperimentalEnabled = false
                val agent = DeviceAgentFactory.create(context, settings, store)
                instrumentation.runOnMainSync {
                    controller = ChatController(context, settings, store, UploadedFileManager(context), WorkspaceManager(context, settings), agent)
                }
                ManualControlController.start()
                DeviceTaskCoordinator.submit(context, prefix)
                assertTrue(received.await(20, TimeUnit.SECONDS))
                var id = 0L
                await {
                    instrumentation.runOnMainSync { id = controller!!.conversations.firstOrNull { it.title.contains(prefix) }?.id ?: 0L }
                    id > 0L
                }
                // The history is already visible while the very first model request waits.
                instrumentation.runOnMainSync { controller!!.selectConversation(id) }
                release.countDown()
                await { !DeviceTaskCoordinator.isRunningConversation(id) }
                assertEquals("已完成", ManualControlController.state.value.chat.status)
                var displayed = emptyList<ChatRecord>()
                await {
                    instrumentation.runOnMainSync { displayed = controller!!.messages.value }
                    displayed.lastOrNull()?.content == summary
                }
                val saved = store.messages(id).filterNot { it.role == RUNTIME_CONTEXT_ROLE }
                assertEquals(saved.map { it.id }, displayed.map { it.id })
                assertEquals(saved.map { it.id }, ManualControlController.state.value.chat.messages.map { it.id })
                assertEquals(saved.map { it.thinking }, displayed.map { it.thinking })
                assertEquals(listOf("device_observe", "device_finish"), displayed.filter { it.role == "tool" }.map { it.toolName })
                assertEquals(summary, ManualControlController.state.value.chat.messages.last().text)
                instrumentation.runOnMainSync {
                    assertEquals(ConversationStore.STATUS_IDLE, controller!!.conversations.single { it.id == id }.status)
                }
                ConversationStore(context).use { reopened -> assertEquals(summary, reopened.messages(id).last().content) }

                DeviceTaskCoordinator.submit(context, "后续任务")
                await { !DeviceTaskCoordinator.isRunningConversation(id) }
                await {
                    instrumentation.runOnMainSync { displayed = controller!!.messages.value }
                    displayed.lastOrNull()?.content == "后续回复"
                }
                assertEquals(2, store.messages(id).count { it.role == "user" })
                assertEquals(1, store.conversations().count { it.title.contains(prefix) })
                val replay = server.requests.last().getJSONArray("messages")
                assertTrue((0 until replay.length()).any { replay.getJSONObject(it).optString("content") == summary })
            } finally {
                release.countDown()
                DeviceTaskCoordinator.pause()
                ManualControlController.stop()
                instrumentation.runOnMainSync { controller?.close() }
                store.conversations().filter { it.profileId == prefix }.forEach { store.deleteConversation(it.id) }
                store.close()
                settings.saveProfiles(profiles, selected)
                settings.purePromptMode = pure
                settings.deviceInteractionExperimentalEnabled = experimental
                listOf("lyra_secure_settings", "lyra_settings").forEach {
                    context.getSharedPreferences(it, android.content.Context.MODE_PRIVATE).edit().commit()
                }
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
        while (!condition() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue("Timed out waiting for history synchronization", condition())
    }

    private fun profile(server: ModelFixture, id: String) = ApiProfile(
        id, "History fixture", "", "http://127.0.0.1:${server.port}/v1",
        selectedModel = "history-fixture", savedModels = listOf("history-fixture"), enabledModels = listOf("history-fixture"),
    )

    private fun response(content: String, thinking: String, callId: String = "", name: String = "", args: JSONObject = JSONObject()): JSONObject =
        JSONObject().put("content", content).put("reasoning_content", thinking).apply {
            if (name.isNotBlank()) put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", callId)
                .put("type", "function").put("function", JSONObject().put("name", name).put("arguments", args.toString()))))
        }

    private class ModelFixture(
        responses: List<JSONObject>,
        received: CountDownLatch? = null,
        release: CountDownLatch? = null,
    ) : java.io.Closeable {
        private val server = ServerSocket(0).apply { soTimeout = 30_000 }
        val port get() = server.localPort
        val requests = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        private val worker = thread(isDaemon = true) {
            runCatching {
                responses.forEachIndexed { index, delta ->
                    server.accept().use { socket ->
                        socket.soTimeout = 30_000
                        val input = socket.getInputStream().buffered()
                        fun line(): String {
                            val bytes = java.io.ByteArrayOutputStream()
                            while (true) { val c = input.read(); if (c < 0 || c == 10) break; if (c != 13) bytes.write(c) }
                            return bytes.toString("UTF-8")
                        }
                        var length = 0
                        while (true) {
                            val header = line()
                            if (header.isEmpty()) break
                            if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                        }
                        val body = ByteArray(length)
                        var count = 0
                        while (count < length) {
                            val n = input.read(body, count, length - count)
                            check(n > 0)
                            count += n
                        }
                        requests += JSONObject(String(body, Charsets.UTF_8))
                        if (index == 0) { received?.countDown(); check(release?.await(30, TimeUnit.SECONDS) != false) }
                        val chunk = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta)))
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\ndata: $chunk\n\ndata: [DONE]\n\n".toByteArray())
                            flush()
                        }
                    }
                }
            }
        }
        override fun close() { server.close(); worker.join(2000) }
    }
}
