package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PendingToolCallReplayTest {
    private fun message(role: String, raw: String? = null, toolId: String? = null, content: String = "x") =
        ChatMessage(1, 1, role, content, "", "", "", toolId, raw, createdAt = 0)

    private fun assistant(vararg calls: Triple<String, String, String>): ChatMessage {
        val array = JSONArray()
        calls.forEach { (id, name, args) ->
            array.put(JSONObject().put("id", id).put("function", JSONObject().put("name", name).put("arguments", args)))
        }
        return message("assistant", JSONObject().put("tool_calls", array).toString())
    }

    @Test fun preservesPartialGroupAndOrder() {
        val messages = listOf(
            assistant(Triple("a", "read_file", "{}"), Triple("b", "run_sub_agents", "{}")),
            message("tool", toolId = "a"),
        )
        val group = PendingToolCallReplay.plan(messages)!!
        assertEquals(listOf("a", "b"), group.calls.map { it.id })
        assertEquals(setOf("a"), group.answered)
        assertEquals("{}", group.calls[1].rawArguments)
        val afterReplay = PendingToolCallReplay.plan(messages + message("tool", toolId = "b"))!!
        assertEquals(setOf("a", "b"), afterReplay.answered)
    }

    @Test(expected = IllegalStateException::class)
    fun malformedAssistantCannotHideBehindLocalErrorPrefix() {
        PendingToolCallReplay.plan(listOf(message("assistant", "{broken", content = "Request interrupted")))
    }

    @Test(expected = IllegalStateException::class)
    fun malformedArgumentsStopBeforeReplay() {
        PendingToolCallReplay.plan(listOf(assistant(Triple("a", "run_sub_agents", "{broken"))))
    }

    @Test(expected = IllegalStateException::class)
    fun malformedToolCallsStopBeforeReplay() {
        PendingToolCallReplay.plan(listOf(message("assistant", "{\"tool_calls\":{}}")))
    }

    @Test fun ignoresEmptyPlaceholderButStopsAtNewUserTurn() {
        val group = assistant(Triple("a", "run_sub_agents", "{}"))
        val placeholder = message("assistant", content = "")
        assertEquals(1, PendingToolCallReplay.plan(listOf(group, placeholder))!!.calls.size)
        assertNull(PendingToolCallReplay.plan(listOf(group, message("user"))))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsDuplicateIds() {
        PendingToolCallReplay.plan(listOf(assistant(Triple("a", "one", "{}"), Triple("a", "two", "{}"))))
    }

    @Test fun replayRunsBeforeModelAndIsIdempotent() = runBlocking {
        val calls = PendingToolCallReplay.plan(listOf(assistant(
            Triple("a", "read", "{}"), Triple("b", "run_sub_agents", "{}"), Triple("c", "write", "{}"),
        ), message("tool", toolId = "a")))!!
        val events = mutableListOf<String>()
        val answered = calls.answered.toMutableSet()
        suspend fun pass(group: PendingToolCallReplay.Group) {
            PendingToolCallReplay.run(group, "b", { events += "execute:${it.id}"; "result" },
                { events += "interrupted:${it.id}"; "error" },
                { call, _ -> events += "persist:${call.id}"; answered += call.id },
                { events += "close:${it.id}" }, { })
            events += "model"
        }
        pass(calls)
        pass(calls.copy(answered = answered))
        assertEquals(listOf("execute:b", "persist:b", "close:b", "interrupted:c", "persist:c", "model", "model"), events)
    }

    @Test fun cancellationNeverPersistsOrCloses() = runBlocking {
        val group = PendingToolCallReplay.plan(listOf(assistant(Triple("b", "run_sub_agents", "{}"))))!!
        val events = mutableListOf<String>()
        try {
            PendingToolCallReplay.run(group, "b", { throw CancellationException("killed") },
                { "error" }, { _, _ -> events += "persist" }, { events += "close" }, { })
        } catch (_: CancellationException) { }
        assertEquals(emptyList<String>(), events)
    }


    @Test fun repeatedDeathsPreserveCompletedCalls() = runBlocking {
        val group = PendingToolCallReplay.plan(listOf(assistant(
            Triple("a", "run_sub_agents", "{}"), Triple("b", "write", "{}"),
        )))!!
        val answered = mutableSetOf<String>()
        val events = mutableListOf<String>()
        repeat(2) {
            try {
                PendingToolCallReplay.run(group.copy(answered = answered.toSet()), "a",
                    { events += "execute:${it.id}"; "ok" },
                    { throw CancellationException("death") },
                    { call, _ -> answered += call.id; events += "persist:${call.id}" },
                    { events += "close:${it.id}" }, { })
            } catch (_: CancellationException) { }
        }
        assertEquals(listOf("execute:a", "persist:a", "close:a"), events)
        assertEquals(setOf("a"), answered)
    }


    @Test fun cancelledBeforeExecutionDoesNotInvokeTool() = runBlocking {
        val group = PendingToolCallReplay.plan(listOf(assistant(Triple("a", "run_sub_agents", "{}"))))!!
        val events = mutableListOf<String>()
        val job = launch {
            cancel()
            PendingToolCallReplay.run(group, "a", { events += "execute"; "ok" },
                { "error" }, { _, _ -> events += "persist" }, { events += "close" }, { })
        }
        job.join()
        assertEquals(emptyList<String>(), events)
    }

    @Test fun answeredOnlyDoesNothing() = runBlocking {
        val group = PendingToolCallReplay.plan(listOf(assistant(Triple("a", "run_sub_agents", "{}")), message("tool", toolId = "a")))!!
        val events = mutableListOf<String>()
        PendingToolCallReplay.run(group, "a", { events += "execute"; "ok" },
            { events += "interrupted"; "error" }, { _, _ -> events += "persist" },
            { events += "close" }, { events += "updated" })
        assertEquals(emptyList<String>(), events)
    }

    @Test fun failedPersistenceDoesNotClose() = runBlocking {
        val group = PendingToolCallReplay.plan(listOf(assistant(Triple("a", "run_sub_agents", "{}"))))!!
        val events = mutableListOf<String>()
        try {
            PendingToolCallReplay.run(group, "a", { "ok" }, { "error" },
                { _, _ -> throw IllegalStateException("write failed") },
                { events += "close" }, { })
        } catch (_: IllegalStateException) { }
        assertEquals(emptyList<String>(), events)
    }


    @Test fun identicalAnsweredAndMissingMatchesFailClosed() {
        val calls = PendingToolCallReplay.plan(listOf(assistant(
            Triple("a", "run_sub_agents", "{}"), Triple("b", "run_sub_agents", "{}"),
        )))!!.calls
        assertNull(PendingToolCallReplay.uniqueUnansweredMatch(calls, setOf("a")))
        assertEquals("b", PendingToolCallReplay.uniqueUnansweredMatch(listOf(calls[1]), setOf("a"))!!.id)
    }

    @Test fun subAgentEnvelopeIsNeverTruncated() {
        val payload = "x".repeat(600_000)
        assertEquals(payload, PendingToolCallReplay.storedOutput("run_sub_agents", payload, 500_000))
        assertEquals(500_000, PendingToolCallReplay.storedOutput("read_file", payload, 500_000).length)
    }

}
