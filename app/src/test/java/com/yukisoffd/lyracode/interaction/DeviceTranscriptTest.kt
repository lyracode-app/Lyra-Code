package com.yukisoffd.lyracode.interaction

import com.yukisoffd.lyracode.ai.ChatRecord
import com.yukisoffd.lyracode.ai.enrichToolRecords
import com.yukisoffd.lyracode.interaction.session.toDeviceChatMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeviceTranscriptTest {
    @Test fun toolsKeepIdentityArgumentsOrderAndFullOutputAcrossPresentations() {
        val calls = JSONArray().put(JSONObject().put("id", "call-1").put("function",
            JSONObject().put("name", "execute_shell_command").put("arguments", "{\"command\":\"echo fixture\"}")))
        val output = "output\n".repeat(1000) + "last line"
        val records = enrichToolRecords(listOf(
            ChatRecord(1, "assistant", "先检查", thinking = "计划", rawJson = JSONObject().put("tool_calls", calls).toString()),
            ChatRecord(2, "tool", output, toolCallId = "call-1"),
            ChatRecord(3, "assistant", "完成", thinking = "验证"),
        ))
        val overlay = records.map { it.toDeviceChatMessage() }
        assertEquals(records.map { it.id }, overlay.map { it.id })
        assertEquals(records.map { it.thinking }, overlay.map { it.thinking })
        assertEquals(output, overlay[1].text)
        assertEquals("execute_shell_command", overlay[1].toolName)
        assertEquals("echo fixture", JSONObject(overlay[1].toolInput).getString("command"))
    }

    @Test fun screenTreesStayOutsideOverlayWhileErrorsArePreserved() {
        val observation = """{"classification":"UNTRUSTED_SCREEN_CONTENT","nodes":["screen tree"]}"""
        assertEquals("已观察当前页面", ChatRecord(1, "tool", observation, toolName = "device_observe").toDeviceChatMessage().text)
        val blocked = "BLOCKED: ACCESSIBILITY_UNAVAILABLE"
        assertEquals(blocked, ChatRecord(2, "tool", blocked, toolName = "device_observe").toDeviceChatMessage().text)
        assertEquals(blocked, ChatRecord(3, "tool", blocked, toolName = "device_screenshot").toDeviceChatMessage().text)
    }
}
