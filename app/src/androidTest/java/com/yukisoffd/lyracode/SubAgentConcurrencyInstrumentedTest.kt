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
                    assertEquals(4, fixture.requests.size)
                    assertEquals((0..3).toSet(), fixture.requests.toSet())
                    assertEquals(limit, fixture.peak.get())
                    if (limit == 3) assertTrue(fixture.completions.indexOf(fixture.requests[2]) < fixture.completions.indexOf(fixture.requests[0]))
                    assertTrue("fixture errors: ${fixture.errors}", fixture.errors.isEmpty())
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
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        val requests = CopyOnWriteArrayList<Int>()
        val completions = CopyOnWriteArrayList<Int>()
        val errors = CopyOnWriteArrayList<String>()
        val peak = AtomicInteger()
        private val outstanding = AtomicInteger()
        private val firstWave = CountDownLatch(limit)
        private val completed = Array(4) { CountDownLatch(1) }
        private val workers = CopyOnWriteArrayList<Thread>()
        private val acceptor = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                workers += thread(isDaemon = true) {
                    var counted = false
                    try {
                        socket.use {
                            it.soTimeout = 10000
                            val input = it.getInputStream().buffered()
                            val header = WebAccessHttp.readRequest(input)
                            val body = JSONObject(WebAccessHttp.readBody(input, header.length).toString(Charsets.UTF_8))
                            val match = Regex("fixture-task-([0-3])").find(body.toString())
                                ?: error("request has no task marker")
                            val index = match.groupValues[1].toInt()
                            val arrival: Int
                            synchronized(outstanding) {
                                requests += index
                                arrival = requests.size - 1
                                counted = true
                                val active = outstanding.incrementAndGet()
                                peak.updateAndGet { old -> maxOf(old, active) }
                            }
                            firstWave.countDown()
                            check(firstWave.await(10, TimeUnit.SECONDS)) { "first wave did not start" }
                            if (limit == 1 && arrival == 0) Thread.sleep(500)
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
                            completed[arrival].countDown()
                        }
                    } catch (e: Exception) {
                        errors += e.toString()
                    } finally {
                        if (counted) outstanding.decrementAndGet()
                    }
                }
            }
        }
        override fun close() {
            server.close()
            acceptor.join(2000)
            workers.forEach { it.join(2000) }
        }
    }
}
