package com.yukisoffd.lyracode.interaction.session

internal data class DeviceChatMessage(val id: Long, val role: String, val text: String, val thinking: String = "", val toolName: String = "", val createdAt: Long = System.currentTimeMillis())

/** Bounded UI transcript; no credentials, bitmaps or raw tool results cross the overlay IPC. */
internal data class DeviceChatState(
    val messages: List<DeviceChatMessage> = emptyList(),
    val running: Boolean = false,
    val status: String = "",
    val providerLabel: String = "",
    val modelLabel: String = "",
    val workspaceLabel: String = "",
    val configurationOptions: String = "{}",
    val pureMode: Boolean = false,
)
