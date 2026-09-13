package com.yukisoffd.lyracode.interaction.model

data class ScreenSnapshot(
    val snapshotId: String,
    val capturedAtEpochMillis: Long,
    val display: ScreenDisplay,
    val activePackage: String?,
    val activeWindowId: Int?,
    val windows: List<SemanticWindow>,
    val nodes: List<SemanticNode>,
    val uiFingerprint: String,
    val truncated: Boolean,
)

data class ScreenDisplay(
    val displayId: Int,
    val rotation: Int,
    val widthPixels: Int,
    val heightPixels: Int,
)

data class SemanticWindow(
    val windowId: Int,
    val displayId: Int,
    val type: SemanticWindowType,
    val layer: Int,
    val bounds: ScreenBounds,
    val title: String?,
    val active: Boolean,
    val focused: Boolean,
    val accessibilityFocused: Boolean,
    val rootHandle: String?,
)

enum class SemanticWindowType {
    APPLICATION,
    INPUT_METHOD,
    SYSTEM,
    ACCESSIBILITY_OVERLAY,
    MAGNIFICATION_OVERLAY,
    SPLIT_SCREEN_DIVIDER,
    WINDOW_CONTROL,
    UNKNOWN,
}

data class SemanticNode(
    val handle: String,
    val windowId: Int,
    val parentHandle: String?,
    val depth: Int,
    val role: String,
    val className: String?,
    val packageName: String?,
    val text: String?,
    val contentDescription: String?,
    val resourceId: String?,
    val bounds: ScreenBounds,
    val actions: Set<SemanticAction>,
    val enabled: Boolean,
    val visible: Boolean,
    val editable: Boolean,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val focusable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val password: Boolean,
    val accessibilityDataSensitive: Boolean,
    val redacted: Boolean,
    val hintText: String? = null,
    val inputType: Int = 0,
    val textFingerprint: String? = null,
)

data class ScreenBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

enum class SemanticAction {
    ACTIVATE,
    LONG_PRESS,
    SET_TEXT,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
    SCROLL_UP,
    SCROLL_DOWN,
    SCROLL_LEFT,
    SCROLL_RIGHT,
    FOCUS,
    CLEAR_FOCUS,
    EXPAND,
    COLLAPSE,
    DISMISS,
    SET_PROGRESS,
    SHOW_ON_SCREEN,
}
