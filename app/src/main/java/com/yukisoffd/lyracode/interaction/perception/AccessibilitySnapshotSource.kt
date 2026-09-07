package com.yukisoffd.lyracode.interaction.perception

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import androidx.core.util.size
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

internal sealed interface SnapshotCaptureResult {
    data class Success(val snapshot: ScreenSnapshot) : SnapshotCaptureResult
    data class Failure(val code: ScreenProbeFailureCode) : SnapshotCaptureResult
}

/**
 * Builds an immutable, bounded semantic snapshot. Android accessibility objects never leave this
 * call and are not cached between observations.
 */
@RequiresApi(35)
internal class AccessibilitySnapshotSource(
    private val service: AccessibilityService,
) {
    fun capture(): SnapshotCaptureResult {
        return try {
            captureInternal()
        } catch (_: SecurityException) {
            SnapshotCaptureResult.Failure(ScreenProbeFailureCode.SECURITY_RESTRICTION)
        } catch (_: Exception) {
            SnapshotCaptureResult.Failure(ScreenProbeFailureCode.CAPTURE_FAILED)
        }
    }

    private fun captureInternal(): SnapshotCaptureResult {
        val capturedAt = System.currentTimeMillis()
        val snapshotId = SnapshotIds.next(capturedAt)
        val textBudget = SnapshotTextBudget(MAX_TOTAL_TEXT_CHARS, MAX_TEXT_CHARS_PER_VALUE)
        val capturedWindows = collectWindows()
        if (capturedWindows.isEmpty()) {
            val fallbackRoot = service.rootInActiveWindow
                ?: return SnapshotCaptureResult.Failure(ScreenProbeFailureCode.NO_WINDOWS)
            capturedWindows += CapturedWindow(
                window = null,
                root = fallbackRoot,
                windowId = fallbackRoot.windowId,
                displayId = 0,
            )
        }

        var truncated = false
        val semanticNodes = mutableListOf<SemanticNode>()
        val semanticWindows = mutableListOf<SemanticWindow>()
        var activePackage: String? = null
        var activeWindowId: Int? = null
        var activeDisplayId = 0

        val orderedWindows = capturedWindows
            .sortedWith(compareBy<CapturedWindow> { it.displayId }.thenBy { it.layer }.thenBy { it.windowId })
        val activeCapturedWindow = orderedWindows.firstOrNull { it.window?.isActive == true }
            ?: orderedWindows.firstOrNull { it.window?.isFocused == true }
            ?: orderedWindows.first()

        orderedWindows.forEach { captured ->
                val window = captured.window
                val root = captured.root
                val rootPackage = safeString(root?.packageName)
                val active = captured === activeCapturedWindow
                if (active) {
                    activeWindowId = captured.windowId
                    activePackage = rootPackage
                    activeDisplayId = captured.displayId
                }

                val redactWindowText = rootPackage in REDACTED_WINDOW_PACKAGES
                val title = if (redactWindowText && window?.title != null) {
                    textBudget.redacted()
                } else {
                    textBudget.take(window?.title)
                }
                val bounds = window?.let(::windowBounds) ?: root?.let(::nodeBounds) ?: EMPTY_BOUNDS
                val rootHandle = root?.let {
                    val result = collectNodes(
                        snapshotId = snapshotId,
                        windowId = captured.windowId,
                        root = it,
                        redactWindowText = redactWindowText,
                        textBudget = textBudget,
                        destination = semanticNodes,
                    )
                    truncated = truncated || result.truncated
                    result.rootHandle
                }

                semanticWindows += SemanticWindow(
                    windowId = captured.windowId,
                    displayId = captured.displayId,
                    type = windowType(window?.type),
                    layer = window?.layer ?: 0,
                    bounds = bounds,
                    title = title,
                    active = active,
                    focused = window?.isFocused ?: active,
                    accessibilityFocused = window?.isAccessibilityFocused ?: false,
                    rootHandle = rootHandle,
                )
            }

        if (semanticNodes.isEmpty()) {
            return SnapshotCaptureResult.Failure(ScreenProbeFailureCode.NO_ROOT_NODES)
        }
        truncated = truncated || textBudget.truncated
        val display = displayInfo(activeDisplayId)
        val fingerprint = ScreenSnapshotFingerprint.create(
            display = display,
            activePackage = activePackage,
            activeWindowId = activeWindowId,
            windows = semanticWindows,
            nodes = semanticNodes,
        )
        return SnapshotCaptureResult.Success(
            ScreenSnapshot(
                snapshotId = snapshotId,
                capturedAtEpochMillis = capturedAt,
                display = display,
                activePackage = activePackage,
                activeWindowId = activeWindowId,
                windows = semanticWindows,
                nodes = semanticNodes,
                uiFingerprint = fingerprint,
                truncated = truncated,
            ),
        )
    }

    private fun collectWindows(): MutableList<CapturedWindow> {
        val result = mutableListOf<CapturedWindow>()
        val allDisplays = service.windowsOnAllDisplays
        for (index in 0 until allDisplays.size) {
            val displayId = allDisplays.keyAt(index)
            allDisplays.valueAt(index).orEmpty().forEach { window ->
                if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return@forEach
                val root = runCatching { window.root }.getOrNull()
                if (root?.packageName?.toString() == service.packageName) return@forEach
                result += CapturedWindow(
                    window = window,
                    root = root,
                    windowId = window.id,
                    displayId = displayId,
                )
            }
        }
        if (result.isEmpty()) {
            service.windows.orEmpty().forEach { window ->
                if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return@forEach
                val root = runCatching { window.root }.getOrNull()
                if (root?.packageName?.toString() == service.packageName) return@forEach
                result += CapturedWindow(
                    window = window,
                    root = root,
                    windowId = window.id,
                    displayId = runCatching { window.displayId }.getOrDefault(0),
                )
            }
        }
        return result
    }

    private fun collectNodes(
        snapshotId: String,
        windowId: Int,
        root: AccessibilityNodeInfo,
        redactWindowText: Boolean,
        textBudget: SnapshotTextBudget,
        destination: MutableList<SemanticNode>,
    ): NodeCollectionResult {
        val queue = ArrayDeque<NodeFrame>()
        queue.add(NodeFrame(root, parentHandle = null, depth = 0))
        var rootHandle: String? = null
        var truncated = false

        while (queue.isNotEmpty()) {
            if (destination.size >= MAX_NODE_COUNT) {
                truncated = true
                break
            }
            val frame = queue.removeFirst()
            if (frame.depth > MAX_TREE_DEPTH) {
                truncated = true
                continue
            }
            val node = frame.node
            val handle = "$snapshotId:w$windowId:n${destination.size}"
            if (rootHandle == null) rootHandle = handle
            val password = safeBoolean { node.isPassword }
            val dataSensitive = safeBoolean { node.isAccessibilityDataSensitive } ||
                (node.isEditable && com.yukisoffd.lyracode.interaction.policy.TextInputPolicy.isSensitive(node.inputType,
                    node.hintText?.toString(), node.contentDescription?.toString(), node.viewIdResourceName?.substringAfterLast('/')))
            val packageName = textBudget.take(safeString(node.packageName))
            val redacted = redactWindowText || password || dataSensitive || packageName in REDACTED_WINDOW_PACKAGES
            val text = if (redacted) textBudget.redacted() else textBudget.take(node.text)
            val description = if (redacted) null else textBudget.take(node.contentDescription)
            val className = textBudget.take(node.className)

            destination += SemanticNode(
                handle = handle,
                windowId = windowId,
                parentHandle = frame.parentHandle,
                depth = frame.depth,
                role = semanticRole(className, node),
                className = className,
                packageName = packageName,
                text = text,
                contentDescription = description,
                resourceId = textBudget.take(node.viewIdResourceName),
                bounds = nodeBounds(node),
                actions = semanticActions(node),
                enabled = safeBoolean(default = true) { node.isEnabled },
                visible = safeBoolean { node.isVisibleToUser },
                editable = safeBoolean { node.isEditable },
                clickable = safeBoolean { node.isClickable },
                longClickable = safeBoolean { node.isLongClickable },
                scrollable = safeBoolean { node.isScrollable },
                focusable = safeBoolean { node.isFocusable },
                checkable = safeBoolean { node.isCheckable },
                checked = isNodeChecked(node),
                selected = safeBoolean { node.isSelected },
                password = password,
                accessibilityDataSensitive = dataSensitive,
                redacted = redacted,
                hintText = if (redacted) null else textBudget.take(node.hintText),
                inputType = node.inputType,
                textFingerprint = if (!redacted && node.isEditable)
                    com.yukisoffd.lyracode.interaction.policy.TextInputPolicy.fingerprint(node.text) else null,
            )

            if (frame.depth == MAX_TREE_DEPTH && node.childCount > 0) {
                truncated = true
                continue
            }
            for (childIndex in 0 until node.childCount.coerceAtMost(MAX_CHILDREN_PER_NODE)) {
                runCatching { node.getChild(childIndex) }.getOrNull()?.let { child ->
                    queue.add(NodeFrame(child, parentHandle = handle, depth = frame.depth + 1))
                }
            }
            if (node.childCount > MAX_CHILDREN_PER_NODE) truncated = true
        }
        return NodeCollectionResult(rootHandle = rootHandle, truncated = truncated)
    }

    private fun semanticRole(className: String?, node: AccessibilityNodeInfo): String {
        val simpleName = className.orEmpty().substringAfterLast('.').lowercase()
        return when {
            safeBoolean { node.isEditable } || "edittext" in simpleName -> "text_field"
            "button" in simpleName -> "button"
            "checkbox" in simpleName -> "checkbox"
            "radiobutton" in simpleName -> "radio_button"
            "switch" in simpleName || "toggle" in simpleName -> "switch"
            "image" in simpleName -> "image"
            "webview" in simpleName -> "web_view"
            "recyclerview" in simpleName || "listview" in simpleName -> "list"
            "scrollview" in simpleName -> "scroll_container"
            "textview" in simpleName -> "text"
            safeBoolean { node.isScrollable } -> "scroll_container"
            else -> simpleName.ifBlank { "node" }
        }
    }

    private fun semanticActions(node: AccessibilityNodeInfo): Set<SemanticAction> = buildSet {
        node.actionList.orEmpty().forEach { action ->
            when (action.id) {
                AccessibilityNodeInfo.ACTION_CLICK -> add(SemanticAction.ACTIVATE)
                AccessibilityNodeInfo.ACTION_LONG_CLICK -> add(SemanticAction.LONG_PRESS)
                AccessibilityNodeInfo.ACTION_SET_TEXT -> add(SemanticAction.SET_TEXT)
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> add(SemanticAction.SCROLL_FORWARD)
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> add(SemanticAction.SCROLL_BACKWARD)
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id -> add(SemanticAction.SCROLL_UP)
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id -> add(SemanticAction.SCROLL_DOWN)
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id -> add(SemanticAction.SCROLL_LEFT)
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id -> add(SemanticAction.SCROLL_RIGHT)
                AccessibilityNodeInfo.ACTION_FOCUS -> add(SemanticAction.FOCUS)
                AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> add(SemanticAction.CLEAR_FOCUS)
                AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND.id -> add(SemanticAction.EXPAND)
                AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE.id -> add(SemanticAction.COLLAPSE)
                AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id -> add(SemanticAction.DISMISS)
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id -> add(SemanticAction.SET_PROGRESS)
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id -> add(SemanticAction.SHOW_ON_SCREEN)
            }
        }
    }

    private fun displayInfo(displayId: Int): ScreenDisplay {
        val metrics = service.resources.displayMetrics
        val display = service.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
        return ScreenDisplay(
            displayId = displayId,
            rotation = display?.rotation ?: 0,
            widthPixels = metrics.widthPixels,
            heightPixels = metrics.heightPixels,
        )
    }

    private fun nodeBounds(node: AccessibilityNodeInfo): ScreenBounds {
        val rect = Rect()
        runCatching { node.getBoundsInScreen(rect) }
        return rect.toScreenBounds()
    }

    private fun windowBounds(window: AccessibilityWindowInfo): ScreenBounds {
        val rect = Rect()
        runCatching { window.getBoundsInScreen(rect) }
        return rect.toScreenBounds()
    }

    private fun Rect.toScreenBounds() = ScreenBounds(left, top, right, bottom)

    private fun windowType(type: Int?): SemanticWindowType = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION, null -> SemanticWindowType.APPLICATION
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> SemanticWindowType.INPUT_METHOD
        AccessibilityWindowInfo.TYPE_SYSTEM -> SemanticWindowType.SYSTEM
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> SemanticWindowType.ACCESSIBILITY_OVERLAY
        AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY -> SemanticWindowType.MAGNIFICATION_OVERLAY
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> SemanticWindowType.SPLIT_SCREEN_DIVIDER
        AccessibilityWindowInfo.TYPE_WINDOW_CONTROL -> SemanticWindowType.WINDOW_CONTROL
        else -> SemanticWindowType.UNKNOWN
    }

    private inline fun safeBoolean(default: Boolean = false, read: () -> Boolean): Boolean =
        runCatching(read).getOrDefault(default)

    @Suppress("DEPRECATION")
    private fun isNodeChecked(node: AccessibilityNodeInfo): Boolean = safeBoolean { node.isChecked }

    private fun safeString(value: CharSequence?): String? = value?.toString()

    private data class CapturedWindow(
        val window: AccessibilityWindowInfo?,
        val root: AccessibilityNodeInfo?,
        val windowId: Int,
        val displayId: Int,
    ) {
        val layer: Int get() = window?.layer ?: 0
    }

    private data class NodeFrame(
        val node: AccessibilityNodeInfo,
        val parentHandle: String?,
        val depth: Int,
    )

    private data class NodeCollectionResult(
        val rootHandle: String?,
        val truncated: Boolean,
    )

    private object SnapshotIds {
        private val sequence = AtomicLong(0L)

        fun next(capturedAtEpochMillis: Long): String =
            "${capturedAtEpochMillis.toString(36)}-${sequence.incrementAndGet().toString(36)}"
    }

    private companion object {
        const val MAX_NODE_COUNT = 400
        const val MAX_TREE_DEPTH = 32
        const val MAX_CHILDREN_PER_NODE = 100
        const val MAX_TOTAL_TEXT_CHARS = 32_000
        const val MAX_TEXT_CHARS_PER_VALUE = 180
        val EMPTY_BOUNDS = ScreenBounds(0, 0, 0, 0)
        val REDACTED_WINDOW_PACKAGES = setOf("com.android.systemui")
    }
}
