package com.yukisoffd.lyracode.interaction.model

enum class ManualDeviceAction {
    ACTIVATE,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
    SET_TEXT,
}

data class ManualActionSelection(
    val snapshotId: String,
    val elementHandle: String,
    val action: ManualDeviceAction,
    val expectedPackage: String,
    val requestId: String = java.util.UUID.randomUUID().toString(),
    val inputText: String? = null,
    val confirmationStage: Int = 1,
    val confirmationToken: String = requestId,
    val automatic: Boolean = false,
) {
    // Selections cross only the private overlay IPC channel; never log their text payload.
    override fun toString() = "ManualActionSelection(requestId=$requestId, action=$action, expectedPackage=$expectedPackage, textLength=${inputText?.length})"
}

enum class DeviceActionStatus {
    SUCCEEDED,
    STALE,
    AMBIGUOUS,
    BLOCKED,
    SYSTEM_REJECTED,
    NO_CHANGE,
    PACKAGE_CHANGED,
    SERVICE_DISCONNECTED,
    USER_CANCELLED,
    CAPTURE_FAILED,
    TEXT_MISMATCH,
}

data class DeviceActionResult(
    val status: DeviceActionStatus,
    val action: ManualDeviceAction,
    val expectedPackage: String,
    val actualPackage: String?,
    val elementHandle: String,
    val executionMethod: String?,
    val beforeFingerprint: String,
    val afterFingerprint: String? = null,
    val requestId: String? = null,
    val blockReason: String? = null,
)
