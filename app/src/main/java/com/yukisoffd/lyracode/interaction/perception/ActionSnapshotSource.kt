package com.yukisoffd.lyracode.interaction.perception

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.yukisoffd.lyracode.interaction.model.ScreenBounds
import com.yukisoffd.lyracode.interaction.model.ScreenDisplay
import com.yukisoffd.lyracode.interaction.model.ScreenSnapshot
import com.yukisoffd.lyracode.interaction.model.ScreenSnapshotFingerprint
import com.yukisoffd.lyracode.interaction.model.SemanticAction
import com.yukisoffd.lyracode.interaction.model.SemanticNode
import com.yukisoffd.lyracode.interaction.model.SemanticWindow
import com.yukisoffd.lyracode.interaction.model.SemanticWindowType
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import com.yukisoffd.lyracode.interaction.policy.TextInputPolicy

/**
 * Low-latency snapshot used by the floating controller. It inspects only the active window,
 * samples a bounded amount of context text, and retains every native click/scroll candidate seen.
 */
@RequiresApi(35)
internal class ActionSnapshotSource(
    private val context: android.content.Context,
    private val activeRoot: () -> AccessibilityNodeInfo?,
) {
    constructor(service: AccessibilityService) : this(service, { service.rootInActiveWindow })
    fun capture(): SnapshotCaptureResult = try {
        captureInternal()
    } catch (_: SecurityException) {
        SnapshotCaptureResult.Failure(ScreenProbeFailureCode.SECURITY_RESTRICTION)
    } catch (_: Exception) {
        SnapshotCaptureResult.Failure(ScreenProbeFailureCode.CAPTURE_FAILED)
    }

    private fun captureInternal(): SnapshotCaptureResult {
        val root = activeRoot()
            ?: return SnapshotCaptureResult.Failure(ScreenProbeFailureCode.NO_ROOT_NODES)
        val packageName = root.packageName?.toString()
        val capturedAt = System.currentTimeMillis()
        val snapshotId = "a${capturedAt.toString(36)}-${sequence.incrementAndGet().toString(36)}"
        val windowId = root.windowId
        val textBudget = SnapshotTextBudget(MAX_TEXT_CHARS, MAX_TEXT_PER_VALUE)
        val nodes = mutableListOf<SemanticNode>()
        val queue = ArrayDeque<NodeFrame>()
        queue.add(NodeFrame(root, 0))
        var visited = 0
        var contextNodes = 0
        var actionNodes = 0

        while (queue.isNotEmpty() && visited < MAX_VISITED_NODES && nodes.size < MAX_RETAINED_NODES) {
            val frame = queue.removeFirst()
            val node = frame.node
            visited++
            val actions = semanticActions(node)
            // Disabled/empty editors may expose no actions or text. Retain their identity and
            // safety metadata so the policy can explicitly reject them (and sensitive forms).
            val manualActionable = actions.any(MANUAL_ACTIONS::contains) || safeBoolean { node.isEditable }
            val visible = safeBoolean { node.isVisibleToUser }
            val password = safeBoolean { node.isPassword }
            val sensitive = safeBoolean { node.isAccessibilityDataSensitive } ||
                (node.isEditable && TextInputPolicy.isSensitive(node.inputType, node.hintText?.toString(),
                    node.contentDescription?.toString(), node.viewIdResourceName?.substringAfterLast('/')))
            val nodePackage = node.packageName?.toString() ?: packageName
            val redacted = password || sensitive || nodePackage == SYSTEM_UI_PACKAGE
            val rawText = if (redacted) null else node.text?.toString()
            val rawDescription = if (redacted) null else node.contentDescription?.toString()
            val hasContext = visible && (!rawText.isNullOrBlank() || !rawDescription.isNullOrBlank())
            val retain =
                (manualActionable && actionNodes < MAX_ACTION_NODES) ||
                    (hasContext && contextNodes < MAX_CONTEXT_NODES) ||
                    redacted || visited == 1

            if (retain) {
                if (manualActionable) actionNodes++ else contextNodes++
                val className = node.className?.toString()
                nodes += SemanticNode(
                    handle = "$snapshotId:w$windowId:n${nodes.size}",
                    windowId = windowId,
                    parentHandle = null,
                    depth = frame.depth,
                    role = semanticRole(className, node),
                    className = textBudget.take(className),
                    packageName = textBudget.take(nodePackage),
                    text = if (redacted) textBudget.redacted() else textBudget.take(rawText),
                    contentDescription = if (redacted) null else textBudget.take(rawDescription),
                    resourceId = if (manualActionable) textBudget.take(node.viewIdResourceName) else null,
                    bounds = bounds(node),
                    actions = actions,
                    enabled = safeBoolean(default = true) { node.isEnabled },
                    visible = visible,
                    editable = manualActionable && safeBoolean { node.isEditable },
                    clickable = manualActionable && safeBoolean { node.isClickable },
                    longClickable = manualActionable && safeBoolean { node.isLongClickable },
                    scrollable = manualActionable && safeBoolean { node.isScrollable },
                    focusable = manualActionable && safeBoolean { node.isFocusable },
                    checkable = manualActionable && safeBoolean { node.isCheckable },
                    checked = manualActionable && isChecked(node),
                    selected = manualActionable && safeBoolean { node.isSelected },
                    password = password,
                    accessibilityDataSensitive = sensitive,
                    redacted = redacted,
                    hintText = if (redacted) null else textBudget.take(node.hintText),
                    inputType = node.inputType,
                    textFingerprint = if (!redacted && SemanticAction.SET_TEXT in actions)
                        TextInputPolicy.fingerprint(rawText) else null,
                )
            }

            if (frame.depth < MAX_DEPTH) {
                for (index in 0 until node.childCount.coerceAtMost(MAX_CHILDREN)) {
                    runCatching { node.getChild(index) }.getOrNull()?.let {
                        queue.add(NodeFrame(it, frame.depth + 1))
                    }
                }
            }
            if (actionNodes >= MAX_ACTION_NODES && contextNodes >= MAX_CONTEXT_NODES) break
        }

        val rootBounds = bounds(root)
        val display = displayInfo()
        val window = SemanticWindow(
            windowId = windowId,
            displayId = display.displayId,
            type = SemanticWindowType.APPLICATION,
            layer = 0,
            bounds = rootBounds,
            title = null,
            active = true,
            focused = true,
            accessibilityFocused = false,
            rootHandle = nodes.firstOrNull()?.handle,
        )
        val fingerprint = ScreenSnapshotFingerprint.create(
            display,
            packageName,
            windowId,
            listOf(window),
            nodes,
        )
        return SnapshotCaptureResult.Success(
            ScreenSnapshot(
                snapshotId = snapshotId,
                capturedAtEpochMillis = capturedAt,
                display = display,
                activePackage = packageName,
                activeWindowId = windowId,
                windows = listOf(window),
                nodes = nodes,
                uiFingerprint = fingerprint,
                truncated = queue.isNotEmpty() || textBudget.truncated,
            ),
        )
    }

    private fun semanticActions(node: AccessibilityNodeInfo): Set<SemanticAction> = buildSet {
        node.actionList.orEmpty().forEach { action ->
            when (action.id) {
                AccessibilityNodeInfo.ACTION_CLICK -> add(SemanticAction.ACTIVATE)
                AccessibilityNodeInfo.ACTION_SET_TEXT -> add(SemanticAction.SET_TEXT)
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> add(SemanticAction.SCROLL_FORWARD)
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> add(SemanticAction.SCROLL_BACKWARD)
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id -> add(SemanticAction.SCROLL_UP)
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id -> add(SemanticAction.SCROLL_DOWN)
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id -> add(SemanticAction.SCROLL_LEFT)
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id -> add(SemanticAction.SCROLL_RIGHT)
            }
        }
    }

    private fun semanticRole(className: String?, node: AccessibilityNodeInfo): String {
        val simpleName = className.orEmpty().substringAfterLast('.').lowercase()
        return when {
            safeBoolean { node.isEditable } -> "text_input"
            "button" in simpleName -> "button"
            "checkbox" in simpleName -> "checkbox"
            "switch" in simpleName -> "switch"
            "image" in simpleName -> "image"
            "textview" in simpleName -> "text"
            safeBoolean { node.isScrollable } -> "scroll_container"
            else -> simpleName.ifBlank { "node" }
        }
    }

    private fun bounds(node: AccessibilityNodeInfo): ScreenBounds {
        val rect = Rect()
        runCatching { node.getBoundsInScreen(rect) }
        return ScreenBounds(rect.left, rect.top, rect.right, rect.bottom)
    }

    private fun displayInfo(): ScreenDisplay {
        val metrics = context.resources.displayMetrics
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(0)
        return ScreenDisplay(0, display?.rotation ?: 0, metrics.widthPixels, metrics.heightPixels)
    }

    private inline fun safeBoolean(default: Boolean = false, read: () -> Boolean): Boolean =
        runCatching(read).getOrDefault(default)

    @Suppress("DEPRECATION")
    private fun isChecked(node: AccessibilityNodeInfo): Boolean = safeBoolean { node.isChecked }

    private data class NodeFrame(val node: AccessibilityNodeInfo, val depth: Int)

    private companion object {
        const val MAX_VISITED_NODES = 240
        const val MAX_RETAINED_NODES = 80
        const val MAX_ACTION_NODES = 48
        const val MAX_CONTEXT_NODES = 32
        const val MAX_DEPTH = 24
        const val MAX_CHILDREN = 64
        const val MAX_TEXT_CHARS = 8_000
        const val MAX_TEXT_PER_VALUE = 120
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        val MANUAL_ACTIONS = setOf(
            SemanticAction.SET_TEXT,
            SemanticAction.ACTIVATE,
            SemanticAction.SCROLL_FORWARD,
            SemanticAction.SCROLL_BACKWARD,
            SemanticAction.SCROLL_UP,
            SemanticAction.SCROLL_DOWN,
            SemanticAction.SCROLL_LEFT,
            SemanticAction.SCROLL_RIGHT,
        )
        val sequence = AtomicLong(0L)
    }
}
