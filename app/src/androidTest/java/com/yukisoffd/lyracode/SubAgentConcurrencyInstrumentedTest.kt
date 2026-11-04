package com.yukisoffd.lyracode

import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.data.ApiProfile
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.ConversationStore
import com.yukisoffd.lyracode.data.SubAgentConfig
import com.yukisoffd.lyracode.interaction.agent.DeviceAgentFactory
import com.yukisoffd.lyracode.webaccess.WebAccessHttp
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentConcurrencyInstrumentedTest {
    @Test fun configuredLimitBoundsRealRequestsAndResultsRemainInTaskOrder() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = AppSettings(context)
        val profiles = settings.profiles()
        val selected = settings.selectedApiProfileId
        val agents = settings.subAgents()
        val enabled = settings.subAgentOrchestrationEnabled
        val toolEnabled = "run_sub_agents" !in settings.disabledTools()
        val maxTasks = settings.subAgentMaxTasks
        val maxConcurrency = settings.subAgentMaxConcurrency
        val store = ConversationStore(context, inMemory = true)
        try {
            settings.subAgentMaxTasks = 4
            settings.subAgentOrchestrationEnabled = true
            settings.setToolEnabled("run_sub_agents", true)
            for (limit in listOf(1, 3)) {
                Fixture(limit).use { fixture ->
                    val profile = ApiProfile(
                        "concurrency-test-$limit", "Concurrency fixture", "fixture-key",
                        "http://127.0.0.1:${fixture.port}/v1", selectedModel = "fixture-model",
                        savedModels = listOf("fixture-model"), enabledModels = listOf("fixture-model"),
                    )
                    settings.saveProfiles(profiles + profile, profile.id)
                    settings.saveSubAgents(listOf(SubAgentConfig("fixture", "Fixture", profile.id, "fixture-model", "", true)))
                    settings.subAgentMaxConcurrency = limit
                    val parent = store.createConversation(profile.id, "fixture-model", title = "isolated parent $limit")
                    val agent = DeviceAgentFactory.create(context, settings, store, workspaceUri = null)
                    val tasks = JSONArray().apply {
                        repeat(4) { index ->
                            put(JSONObject().put("task", "fixture-task-$index")
                                .put("read_only", true).put("write_paths", JSONArray())
                                .put("sub_agent_id", "fixture"))
                        }
                    }
                    val response = withTimeout(30000) {
                        agent.executeFloatingTool(parent, "run_sub_agents", JSONObject().put("tasks", tasks))
                    }
                    val envelope = JSONObject(response)
                    assertTrue(response, envelope.getBoolean("ok"))
                    val results = JSONObject(envelope.getString("content")).getJSONArray("results")
                    assertEquals(4, results.length())
                    repeat(4) { index ->
                        val result = results.getJSONObject(index)
                        assertEquals(index + 1, result.getInt("index"))
                        assertEquals("fixture-task-$index", result.getString("task"))
                        assertEquals("answer-$index", result.getString("result"))
                    }
                    val diagnostics = fixture.diagnostics()
                    assertEquals(diagnostics, 4, fixture.requests.size)
                    assertEquals(diagnostics, (0..3).toSet(), fixture.requests.toSet())
                    assertEquals(diagnostics, limit, fixture.peak.get())
                    if (limit == 3) assertTrue(diagnostics, fixture.completions.indexOf(fixture.requests[2]) < fixture.completions.indexOf(fixture.requests[0]))
                    assertTrue(diagnostics, fixture.errors.isEmpty())
                    assertFalse(agent.isSubAgentRunning(parent))
                }
            }
        } finally {
            store.close()
            settings.saveSubAgents(agents)
            settings.saveProfiles(profiles, selected)
            settings.subAgentMaxTasks = maxTasks
            settings.subAgentMaxConcurrency = maxConcurrency
            settings.subAgentOrchestrationEnabled = enabled
            settings.setToolEnabled("run_sub_agents", toolEnabled)
            listOf("lyra_secure_settings", "lyra_settings").forEach {
                context.getSharedPreferences(it, android.content.Context.MODE_PRIVATE).edit().commit()
            }
        }
    }

    private class Fixture(private val limit: Int) : AutoCloseable {
        private companion object { const val MAX_EVENTS = 24 }
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        val requests = CopyOnWriteArrayList<Int>()
        val completions = CopyOnWriteArrayList<Int>()
        val errors = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<String>()
        val peak = AtomicInteger()
        private val started = System.nanoTime()
        private val outstanding = AtomicInteger()
        private val firstWave = CountDownLatch(limit)
        private val completed = Array(4) { CountDownLatch(1) }
        private val workers = CopyOnWriteArrayList<Thread>()
        private val acceptor = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                workers += thread(isDaemon = true) {
                    var counted = false
                    var arrival = -1
                    try {
                        socket.use {
                            it.soTimeout = 10000
                            val input = it.getInputStream().buffered()
                            val header = WebAccessHttp.readRequest(input)
                            val body = JSONObject(WebAccessHttp.readBody(input, header.length).toString(Charsets.UTF_8))
                            val match = Regex("fixture-task-([0-3])").find(body.toString())
                                ?: error("request has no task marker")
                            val index = match.groupValues[1].toInt()
                            synchronized(outstanding) {
                                requests += index
                                arrival = requests.size - 1
                                counted = true
                                val active = outstanding.incrementAndGet()
                                peak.updateAndGet { old -> maxOf(old, active) }
                                record("arrival=$arrival task=$index active=$active ${header.method} ${header.path} ${shape(body)}")
                            }
                            firstWave.countDown()
                            check(firstWave.await(10, TimeUnit.SECONDS)) { "first wave did not start" }
                            if (limit == 1 && arrival == 0) Thread.sleep(500)
                            if (arrival >= completed.size) errors += "unexpected arrival=$arrival task=$index"
                            if (limit == 3 && arrival < 2) {
                                check(completed[arrival + 1].await(10, TimeUnit.SECONDS)) { "reverse release timed out" }
                            }
                            val answer = "answer-$index"
                            val output = it.getOutputStream()
                            synchronized(outstanding) {
                                if (body.optBoolean("stream")) {
                                    WebAccessHttp.eventHeaders(output)
                                    val delta = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", answer))))
                                    output.write("data: $delta\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toByteArray())
                                    output.flush()
                                } else {
                                    val json = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("content", answer)).put("finish_reason", "stop")))
                                    WebAccessHttp.respond(output, 200, "application/json", json.toString().toByteArray())
                                }
                                completions += index
                                outstanding.decrementAndGet()
                                counted = false
                            }
                            record("done arrival=$arrival task=$index")
                            completed.getOrNull(arrival)?.countDown()
                        }
                    } catch (e: Exception) {
                        errors += "arrival=$arrival ${e.javaClass.simpleName}: ${e.message?.take(160)}"
                    } finally {
                        if (counted) outstanding.decrementAndGet()
                    }
                }
            }
        }
        private fun elapsed() = (System.nanoTime() - started) / 1_000_000

        private fun record(event: String) {
            if (events.size < MAX_EVENTS) events += "+${elapsed()}ms $event"
        }

        // Request shape without content: stream flag, message roles, body size and hash.
        // A transport retry repeats the hash; a second model turn adds messages.
        private fun shape(body: JSONObject): String {
            val messages = body.optJSONArray("messages") ?: JSONArray()
            val roles = (0 until messages.length()).joinToString(",") { messages.optJSONObject(it)?.optString("role").orEmpty() }
            val text = body.toString()
            return "stream=${body.optBoolean("stream")} roles=[$roles] bytes=${text.length} hash=${Integer.toHexString(text.hashCode())}"
        }

        fun diagnostics() = "limit=$limit requests=${requests.take(MAX_EVENTS)} completions=${completions.take(MAX_EVENTS)} " +
            "peak=${peak.get()} errors=${errors.take(MAX_EVENTS)} events=$events"

        override fun close() {
            server.close()
            acceptor.join(2000)
            workers.forEach { it.join(2000) }
        }
    }
}
