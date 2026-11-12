package com.yukisoffd.lyracode.interaction.session

internal data class DeviceChatMessage(val id: Long, val role: String, val text: String, val thinking: String = "", val toolName: String = "", val createdAt: Long = System.currentTimeMillis(), val toolInput: String = "")

internal fun com.yukisoffd.lyracode.ai.ChatRecord.toDeviceChatMessage(): DeviceChatMessage {
    // Keep raw screen observations outside the overlay, as before. Other tool results
    // retain their full text; preview limits must never become transcript limits.
    val screenData = role == "tool" && toolName in setOf("device_observe", "device_screenshot") &&
        runCatching { org.json.JSONObject(content).optString("classification") == "UNTRUSTED_SCREEN_CONTENT" }.getOrDefault(false)
    val displayText = when {
        screenData && toolName == "device_observe" -> "已观察当前页面"
        screenData && toolName == "device_screenshot" -> "截图识别完成"
        else -> content
    }
    return DeviceChatMessage(id, role, displayText, thinking, toolName, createdAt, toolInput)
}

/** Complete session transcript; screen trees, bitmaps and credentials stay outside the UI state. */
internal data class DeviceChatState(
    val messages: List<DeviceChatMessage> = emptyList(),
    val running: Boolean = false,
    val status: String = "",
    val providerLabel: String = "",
    val modelLabel: String = "",
    val workspaceLabel: String = "",
    val configurationOptions: String = "{}",
    val pureMode: Boolean = false,
    val compactOptions: com.yukisoffd.lyracode.interaction.model.CompactOverlayOptions = com.yukisoffd.lyracode.interaction.model.CompactOverlayOptions(),
)
