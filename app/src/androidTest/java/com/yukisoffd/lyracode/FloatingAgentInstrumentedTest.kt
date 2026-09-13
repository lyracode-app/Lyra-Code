package com.yukisoffd.lyracode

import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.data.*
import com.yukisoffd.lyracode.interaction.session.*
import com.yukisoffd.lyracode.interaction.agent.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

class FloatingAgentInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun flushSettings() {
        // Instrumentation can terminate before apply() writes reach disk.
        listOf("lyra_secure_settings", "lyra_settings").forEach {
            context.getSharedPreferences(it, android.content.Context.MODE_PRIVATE).edit().commit()
        }
    }
    @Test fun cleanupTemporaryProfiles() {
        val settings = AppSettings(context)
        val profiles = settings.profiles()
        val keep = profiles.filterNot { (it.id.startsWith("overlay-test-") || it.id.startsWith("ovtest-")) && it.name == "Local test" && it.selectedModel == "local-test" }
        val store = ConversationStore(context)
        try {
            val selected = settings.selectedApiProfileId
            val restore = keep.firstOrNull { it.id == selected } ?: store.conversations()
                .firstNotNullOfOrNull { c -> keep.firstOrNull { it.id == c.profileId } } ?: keep.first()
            if (keep.size != profiles.size) settings.saveProfiles(keep, restore.id)
            flushSettings()
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("stream", "Restored provider: ${restore.name}; model: ${restore.selectedModel}; removed test profiles: ${profiles.size - keep.size}\n")
            })
        } finally { store.close() }
    }
    @Test fun devicePermissionLossKeepsChatAndDeviceToolsSoftBlock() = runBlocking {
        ManualControlController.start()
        ManualControlController.updateChat(DeviceChatState(running = true))
        try {
            ManualControlController.clearDeviceState()
            assertTrue(ManualControlController.state.value.isActive())
            assertTrue(ManualControlController.state.value.chat.running)
            val provider = DeviceInteractionToolProvider(1, "", Long.MAX_VALUE,
                ExecutionBudget(android.os.SystemClock::elapsedRealtime), {}, {}, {})
            provider.deviceAvailable = { false }
            assertTrue(provider.execute("device_observe", JSONObject()).contains("ACCESSIBILITY_UNAVAILABLE"))
            provider.nativeExecute = { _, _ -> "native-result" }
            assertEquals("native-result", provider.execute("list_directory", JSONObject()))
        } finally { ManualControlController.stop() }
    }
    @Test fun noAccessibilityChatStreamsIntoPersistentHistoryAndReusesConversation() {
        val settings = AppSettings(context)
        val profiles = settings.profiles(); val selected = settings.selectedProfile().id
        val oldExperimental = settings.deviceInteractionExperimentalEnabled
        val pure = settings.purePromptMode
        val store = ConversationStore(context)
        val prefix = "ovtest-${System.nanoTime().toString().takeLast(8)}"
        val server = ServerSocket(0)
        server.soTimeout = 30000
        val requests = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        val requestHeaders = java.util.concurrent.CopyOnWriteArrayList<Map<String, String>>()
        val worker = thread {
            runCatching { repeat(3) {
                server.accept().use { socket ->
                    val input = socket.getInputStream().buffered()
                    fun line(): String {
                        val bytes = java.io.ByteArrayOutputStream()
                        while (true) { val c = input.read(); if (c < 0 || c == 10) break; if (c != 13) bytes.write(c) }
                        return bytes.toString("UTF-8")
                    }
                    val headers = mutableMapOf<String, String>()
                    var length = 0
                    while (true) {
                        val line = line()
                        if (line.isEmpty()) break
                        if (line.contains(':')) headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    val body = ByteArray(length); var n = 0
                    while (n < length) { val read = input.read(body, n, length - n); if (read < 0) break; n += read }
                    val parsed = JSONObject(String(body, 0, n, Charsets.UTF_8))
                    requests += parsed
                    requestHeaders += headers
                    val out = socket.getOutputStream()
                    if (!parsed.optBoolean("stream", true)) {
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write("""{"choices":[{"message":{"role":"assistant","content":"Hello world"},"finish_reason":"stop"}]}""".toByteArray())
                        out.flush()
                        return@use
                    }
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write("data: {\"choices\":[{\"delta\":{\"content\":\"Hello\",\"reasoning_content\":\"Fixture thought\"}}]}\n\n".toByteArray()); out.flush()
                    Thread.sleep(250)
                    out.write("data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}\n\ndata: [DONE]\n\n".toByteArray()); out.flush()
                }
            } }
        }
        try {
            val profile = ApiProfile(prefix, "Local test", "", "http://127.0.0.1:${server.localPort}/v1", selectedModel = "local-test", savedModels = listOf("local-test", "local-second"), enabledModels = listOf("local-test", "local-second"), modelRequestOverrides = mapOf("local-second" to ModelRequestCustomization(headers = mapOf("X-Lyra-Fixture" to "custom"), body = "{\"fixture_key\":true,\"temperature\":0.55,\"stream\":false}", replayThinking = false)))
            settings.saveProfiles(profiles + profile, profile.id)
            settings.deviceInteractionExperimentalEnabled = false; settings.purePromptMode = false
            ManualControlController.start()
            fun send(text: String) {
                DeviceTaskCoordinator.submit(context, text)
                val until = android.os.SystemClock.elapsedRealtime() + 30000
                while (ManualControlController.state.value.chat.running && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(50)
                assertEquals("已完成", ManualControlController.state.value.chat.status)
            }
            send(prefix)
            DeviceTaskCoordinator.configure(context, JSONObject().put("profile", profile.id).put("model", "local-second").toString())
            assertEquals("local-second", ManualControlController.state.value.chat.modelLabel)
            val options = ManualControlController.state.value.chat.configurationOptions
            assertFalse(options.contains("apiKey"))
            assertFalse(options.contains("baseUrl"))
            send("follow-up")
            assertEquals("local-second", requests.last().getString("model"))
            assertTrue(requests.last().getBoolean("fixture_key"))
            assertEquals("custom", requestHeaders.last()["x-lyra-fixture"])
            val history = requests.last().getJSONArray("messages")
            assertFalse((0 until history.length()).any { history.getJSONObject(it).has("reasoning_content") })
            assertEquals(0.55, requests.last().getDouble("temperature"), 0.001)
            val saved = store.conversations().single { it.title.contains(prefix) }
            assertEquals(2, store.messages(saved.id).count { it.role == "user" })
            assertTrue(store.messages(saved.id).any { it.content == "Hello world" })
            val tools = requests.first().getJSONArray("tools")
            val names = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }
            assertTrue(names.containsAll(listOf("list_directory", "list_installed_apps", "device_observe", "ask_user")))
            settings.purePromptMode = true
            DeviceTaskCoordinator.clearContext()
            send(prefix + "new")
            assertEquals(2, store.conversations().count { it.title.contains(prefix) })
            assertFalse(requests.last().has("tools"))
        } finally {
            DeviceTaskCoordinator.pause(); ManualControlController.stop()
            server.close(); worker.join(2000)
            store.conversations().filter { it.title.contains(prefix) || it.title.startsWith("悬浮对话 · overlay-test-") }.forEach { store.deleteConversation(it.id) }
            store.close(); settings.saveProfiles(profiles, selected)
            settings.deviceInteractionExperimentalEnabled = oldExperimental; settings.purePromptMode = pure
            flushSettings()
        }
    }
}

