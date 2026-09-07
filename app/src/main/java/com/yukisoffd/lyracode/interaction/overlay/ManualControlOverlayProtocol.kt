package com.yukisoffd.lyracode.interaction.overlay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.yukisoffd.lyracode.interaction.model.DeviceActionResult
import com.yukisoffd.lyracode.interaction.model.DeviceActionStatus
import com.yukisoffd.lyracode.interaction.model.ManualActionSelection
import com.yukisoffd.lyracode.interaction.model.ManualDeviceAction
import com.yukisoffd.lyracode.interaction.model.ScreenBounds
import com.yukisoffd.lyracode.interaction.model.ScreenDisplay
import com.yukisoffd.lyracode.interaction.model.ScreenSnapshot
import com.yukisoffd.lyracode.interaction.model.SemanticAction
import com.yukisoffd.lyracode.interaction.model.SemanticNode
import com.yukisoffd.lyracode.interaction.policy.DeviceActionPolicy
import com.yukisoffd.lyracode.interaction.policy.DevicePolicyDecision
import com.yukisoffd.lyracode.interaction.session.ManualControlState
import com.yukisoffd.lyracode.interaction.session.ManualControlStatus

/** Compact Bundle protocol between the accessibility process and the overlay process. */
internal object ManualControlOverlayProtocol {
    const val ACTION_START = "com.yukisoffd.lyracode.action.START_MANUAL_CONTROL"
    const val ACTION_RENDER = "com.yukisoffd.lyracode.action.RENDER_MANUAL_CONTROL"
    const val ACTION_STOP_SERVICE = "com.yukisoffd.lyracode.action.STOP_MANUAL_CONTROL_SERVICE"

    const val COMMAND_SELECT = "com.yukisoffd.lyracode.command.SELECT_MANUAL_CONTROL"
    const val COMMAND_CONFIRM = "com.yukisoffd.lyracode.command.CONFIRM_MANUAL_CONTROL"
    const val COMMAND_CLEAR_SELECTION = "com.yukisoffd.lyracode.command.CLEAR_MANUAL_CONTROL_SELECTION"
    const val COMMAND_STOP = "com.yukisoffd.lyracode.command.STOP_MANUAL_CONTROL"
    const val COMMAND_SUBMIT = "com.yukisoffd.lyracode.command.SUBMIT_DEVICE_TASK"
    const val COMMAND_APPROVAL = "com.yukisoffd.lyracode.command.DEVICE_APPROVAL"
    const val COMMAND_CLEAR_CONTEXT = "com.yukisoffd.lyracode.command.CLEAR_DEVICE_CONTEXT"
    const val COMMAND_PAUSE = "com.yukisoffd.lyracode.command.PAUSE_DEVICE_TASK"
    const val EXTRA_INPUT = "task_input"
    const val EXTRA_REQUEST_ID = "confirmation_request_id"
    const val EXTRA_SNAPSHOT_ID = "confirmation_snapshot_id"
    const val SERVICE_STATE = "com.yukisoffd.lyracode.state.MANUAL_CONTROL_SERVICE"

    const val EXTRA_STATE = "manual_control_state"
    const val EXTRA_ACTIVE_UNTIL = "active_until"
    const val EXTRA_HANDLE = "element_handle"
    const val EXTRA_ACTION = "device_action"
    const val EXTRA_RUNNING = "service_running"
    const val EXTRA_SENT_AT_ELAPSED = "sent_at_elapsed"

    private const val KEY_STATUS = "status"
    private const val KEY_TARGET_PACKAGE = "target_package"
    private const val KEY_SNAPSHOT = "snapshot"
    private const val KEY_SELECTION = "selection"
    private const val KEY_RESULT = "result"
    private const val KEY_SNAPSHOT_ID = "snapshot_id"
    private const val KEY_CAPTURED_AT = "captured_at"
    private const val KEY_ACTIVE_PACKAGE = "active_package"
    private const val KEY_ACTIVE_WINDOW_ID = "active_window_id"
    private const val KEY_FINGERPRINT = "fingerprint"
    private const val KEY_TRUNCATED = "truncated"
    private const val KEY_DISPLAY_ID = "display_id"
    private const val KEY_ROTATION = "rotation"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"
    private const val KEY_NODES = "nodes"
    private const val KEY_HANDLE = "handle"
    private const val KEY_WINDOW_ID = "window_id"
    private const val KEY_PARENT_HANDLE = "parent_handle"
    private const val KEY_DEPTH = "depth"
    private const val KEY_ROLE = "role"
    private const val KEY_CLASS_NAME = "class_name"
    private const val KEY_PACKAGE_NAME = "package_name"
    private const val KEY_TEXT = "text"
    private const val KEY_DESCRIPTION = "description"
    private const val KEY_RESOURCE_ID = "resource_id"
    private const val KEY_LEFT = "left"
    private const val KEY_TOP = "top"
    private const val KEY_RIGHT = "right"
    private const val KEY_BOTTOM = "bottom"
    private const val KEY_ACTIONS = "actions"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_VISIBLE = "visible"
    private const val KEY_EDITABLE = "editable"
    private const val KEY_CLICKABLE = "clickable"
    private const val KEY_LONG_CLICKABLE = "long_clickable"
    private const val KEY_SCROLLABLE = "scrollable"
    private const val KEY_FOCUSABLE = "focusable"
    private const val KEY_CHECKABLE = "checkable"
    private const val KEY_CHECKED = "checked"
    private const val KEY_SELECTED = "selected"
    private const val KEY_PASSWORD = "password"
    private const val KEY_SENSITIVE = "sensitive"
    private const val KEY_REDACTED = "redacted"
    private const val KEY_EXPECTED_PACKAGE = "expected_package"
    private const val KEY_ACTUAL_PACKAGE = "actual_package"
    private const val KEY_EXECUTION_METHOD = "execution_method"
    private const val KEY_BEFORE_FINGERPRINT = "before_fingerprint"
    private const val KEY_AFTER_FINGERPRINT = "after_fingerprint"

    fun encode(state: ManualControlState): Bundle = Bundle().apply {
        putString("pet_event", state.petEvent)
        putLong("session_id", state.sessionId)
        state.approval?.let { a -> putBundle("approval", Bundle().apply {
            putString("id", a.id); putString("title", a.title); putString("detail", a.detail)
            putString("package", a.packageName); putBoolean("twice", a.secondConfirmation); putInt("stage", a.stage)
        }) }
        putBoolean("chat_running", state.chat.running)
        putString("chat_status", state.chat.status.take(240))
        putString("chat_provider", state.chat.providerLabel.take(240))
        putString("agent_target", state.agentTargetPackage)
        putParcelableArrayList("chat_messages", ArrayList(state.chat.messages.takeLast(16).map { message ->
            Bundle().apply { putLong("id", message.id); putString("role", message.role); putString("text", message.text.takeLast(4000)); putString("thinking", message.thinking.takeLast(4000)); putString("tool_name", message.toolName.take(100)); putLong("created_at", message.createdAt) }
        }))
        putLong(EXTRA_ACTIVE_UNTIL, state.activeUntilEpochMillis)
        putString(KEY_STATUS, state.status.name)
        putString(KEY_TARGET_PACKAGE, state.targetPackage)
        state.latestSnapshot?.let { putBundle(KEY_SNAPSHOT, encodeSnapshot(it, state.selection)) }
        state.selection?.let { putBundle(KEY_SELECTION, encodeSelection(it)) }
        state.lastResult?.let { putBundle(KEY_RESULT, encodeResult(it)) }
    }

    fun decode(bundle: Bundle): ManualControlState? {
        val status = enumValueOrNull<ManualControlStatus>(bundle.getString(KEY_STATUS)) ?: return null
        return ManualControlState(
            petEvent = bundle.getString("pet_event"),
            sessionId = bundle.getLong("session_id"),
            approval = bundle.getBundle("approval")?.let { a -> com.yukisoffd.lyracode.interaction.session.DeviceApproval(
                a.getString("id").orEmpty(), a.getString("title").orEmpty(), a.getString("detail").orEmpty().take(16000),
                a.getString("package"), a.getBoolean("twice"), a.getInt("stage", 1)) },
            agentTargetPackage = bundle.getString("agent_target"),
            chat = com.yukisoffd.lyracode.interaction.session.DeviceChatState(
                running = bundle.getBoolean("chat_running"),
                status = bundle.getString("chat_status").orEmpty(),
                providerLabel = bundle.getString("chat_provider").orEmpty(),
                messages = decodeMessages(bundle),
            ),
            activeUntilEpochMillis = bundle.getLong(EXTRA_ACTIVE_UNTIL),
            status = status,
            targetPackage = bundle.getString(KEY_TARGET_PACKAGE),
            latestSnapshot = bundle.getBundle(KEY_SNAPSHOT)?.let(::decodeSnapshot),
            selection = bundle.getBundle(KEY_SELECTION)?.let(::decodeSelection),
            lastResult = bundle.getBundle(KEY_RESULT)?.let(::decodeResult),
        )
    }

    fun sendCommand(
        context: Context,
        command: String,
        handle: String? = null,
        action: ManualDeviceAction? = null,
        input: String? = null,
        snapshotId: String? = null,
        requestId: String? = null,
    ) {
        context.sendBroadcast(
            Intent(context, ManualControlActionReceiver::class.java)
                .setAction(command)
                .putExtra(EXTRA_HANDLE, handle)
                .putExtra(EXTRA_ACTION, action?.name)
                .putExtra(EXTRA_INPUT, input?.take(2000))
                .putExtra(EXTRA_SNAPSHOT_ID, snapshotId)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .putExtra(EXTRA_SENT_AT_ELAPSED, SystemClock.elapsedRealtime()),
        )
    }

    @Suppress("DEPRECATION")
    private fun decodeMessages(bundle: Bundle) = bundle.getParcelableArrayList<Bundle>("chat_messages").orEmpty()
        .takeLast(16).map { com.yukisoffd.lyracode.interaction.session.DeviceChatMessage(
            it.getLong("id"), it.getString("role").orEmpty(), it.getString("text").orEmpty().takeLast(4000), it.getString("thinking").orEmpty().takeLast(4000), it.getString("tool_name").orEmpty().take(100), it.getLong("created_at", System.currentTimeMillis()),
        ) }

    fun reportServiceState(context: Context, running: Boolean) {
        context.sendBroadcast(
            Intent(context, ManualControlActionReceiver::class.java)
                .setAction(SERVICE_STATE)
                .putExtra(EXTRA_RUNNING, running)
                .putExtra(EXTRA_SENT_AT_ELAPSED, SystemClock.elapsedRealtime()),
        )
    }

    private fun encodeSnapshot(snapshot: ScreenSnapshot, selection: ManualActionSelection?): Bundle = Bundle().apply {
        putString(KEY_SNAPSHOT_ID, snapshot.snapshotId)
        putLong(KEY_CAPTURED_AT, snapshot.capturedAtEpochMillis)
        putString(KEY_ACTIVE_PACKAGE, snapshot.activePackage)
        snapshot.activeWindowId?.let { putInt(KEY_ACTIVE_WINDOW_ID, it) }
        putString(KEY_FINGERPRINT, snapshot.uiFingerprint)
        putBoolean(KEY_TRUNCATED, snapshot.truncated)
        putInt(KEY_DISPLAY_ID, snapshot.display.displayId)
        putInt(KEY_ROTATION, snapshot.display.rotation)
        putInt(KEY_WIDTH, snapshot.display.widthPixels)
        putInt(KEY_HEIGHT, snapshot.display.heightPixels)
        val visibleNodes = snapshot.nodes.asSequence()
            .filter { node ->
                node.handle == selection?.elementHandle || ManualDeviceAction.entries.any { action ->
                    DeviceActionPolicy.evaluate(snapshot.activePackage.orEmpty(), node, action) is DevicePolicyDecision.Allowed
                }
            }
            .sortedByDescending { it.handle == selection?.elementHandle }
            .take(MAX_REMOTE_NODES)
            .map(::encodeNode)
            .toCollection(ArrayList())
        putParcelableArrayList(KEY_NODES, visibleNodes)
    }

    private fun decodeSnapshot(bundle: Bundle): ScreenSnapshot {
        @Suppress("DEPRECATION")
        val nodes = bundle.getParcelableArrayList<Bundle>(KEY_NODES).orEmpty().mapNotNull(::decodeNode)
        return ScreenSnapshot(
            snapshotId = bundle.getString(KEY_SNAPSHOT_ID).orEmpty(),
            capturedAtEpochMillis = bundle.getLong(KEY_CAPTURED_AT),
            display = ScreenDisplay(
                displayId = bundle.getInt(KEY_DISPLAY_ID),
                rotation = bundle.getInt(KEY_ROTATION),
                widthPixels = bundle.getInt(KEY_WIDTH),
                heightPixels = bundle.getInt(KEY_HEIGHT),
            ),
            activePackage = bundle.getString(KEY_ACTIVE_PACKAGE),
            activeWindowId = if (bundle.containsKey(KEY_ACTIVE_WINDOW_ID)) bundle.getInt(KEY_ACTIVE_WINDOW_ID) else null,
            windows = emptyList(),
            nodes = nodes,
            uiFingerprint = bundle.getString(KEY_FINGERPRINT).orEmpty(),
            truncated = bundle.getBoolean(KEY_TRUNCATED),
        )
    }

    private fun encodeNode(node: SemanticNode): Bundle = Bundle().apply {
        putString(KEY_HANDLE, node.handle)
        putInt(KEY_WINDOW_ID, node.windowId)
        putString(KEY_PARENT_HANDLE, node.parentHandle)
        putInt(KEY_DEPTH, node.depth)
        putString(KEY_ROLE, node.role)
        putString(KEY_CLASS_NAME, node.className)
        putString(KEY_PACKAGE_NAME, node.packageName)
        putString(KEY_TEXT, node.text)
        putString(KEY_DESCRIPTION, node.contentDescription)
        putString(KEY_RESOURCE_ID, node.resourceId)
        putInt(KEY_LEFT, node.bounds.left)
        putInt(KEY_TOP, node.bounds.top)
        putInt(KEY_RIGHT, node.bounds.right)
        putInt(KEY_BOTTOM, node.bounds.bottom)
        putStringArrayList(KEY_ACTIONS, node.actions.mapTo(ArrayList()) { it.name })
        putBoolean(KEY_ENABLED, node.enabled)
        putBoolean(KEY_VISIBLE, node.visible)
        putBoolean(KEY_EDITABLE, node.editable)
        putString("hint_text", node.hintText)
        putInt("input_type", node.inputType)
        putString("text_fingerprint", node.textFingerprint)
        putBoolean(KEY_CLICKABLE, node.clickable)
        putBoolean(KEY_LONG_CLICKABLE, node.longClickable)
        putBoolean(KEY_SCROLLABLE, node.scrollable)
        putBoolean(KEY_FOCUSABLE, node.focusable)
        putBoolean(KEY_CHECKABLE, node.checkable)
        putBoolean(KEY_CHECKED, node.checked)
        putBoolean(KEY_SELECTED, node.selected)
        putBoolean(KEY_PASSWORD, node.password)
        putBoolean(KEY_SENSITIVE, node.accessibilityDataSensitive)
        putBoolean(KEY_REDACTED, node.redacted)
    }

    private fun decodeNode(bundle: Bundle): SemanticNode? {
        val handle = bundle.getString(KEY_HANDLE) ?: return null
        return SemanticNode(
            handle = handle,
            windowId = bundle.getInt(KEY_WINDOW_ID),
            parentHandle = bundle.getString(KEY_PARENT_HANDLE),
            depth = bundle.getInt(KEY_DEPTH),
            role = bundle.getString(KEY_ROLE).orEmpty(),
            className = bundle.getString(KEY_CLASS_NAME),
            packageName = bundle.getString(KEY_PACKAGE_NAME),
            text = bundle.getString(KEY_TEXT),
            contentDescription = bundle.getString(KEY_DESCRIPTION),
            resourceId = bundle.getString(KEY_RESOURCE_ID),
            bounds = ScreenBounds(
                bundle.getInt(KEY_LEFT),
                bundle.getInt(KEY_TOP),
                bundle.getInt(KEY_RIGHT),
                bundle.getInt(KEY_BOTTOM),
            ),
            actions = bundle.getStringArrayList(KEY_ACTIONS).orEmpty().mapNotNullTo(mutableSetOf()) {
                enumValueOrNull<SemanticAction>(it)
            },
            enabled = bundle.getBoolean(KEY_ENABLED),
            visible = bundle.getBoolean(KEY_VISIBLE),
            editable = bundle.getBoolean(KEY_EDITABLE),
            hintText = bundle.getString("hint_text"),
            inputType = bundle.getInt("input_type"),
            textFingerprint = bundle.getString("text_fingerprint"),
            clickable = bundle.getBoolean(KEY_CLICKABLE),
            longClickable = bundle.getBoolean(KEY_LONG_CLICKABLE),
            scrollable = bundle.getBoolean(KEY_SCROLLABLE),
            focusable = bundle.getBoolean(KEY_FOCUSABLE),
            checkable = bundle.getBoolean(KEY_CHECKABLE),
            checked = bundle.getBoolean(KEY_CHECKED),
            selected = bundle.getBoolean(KEY_SELECTED),
            password = bundle.getBoolean(KEY_PASSWORD),
            accessibilityDataSensitive = bundle.getBoolean(KEY_SENSITIVE),
            redacted = bundle.getBoolean(KEY_REDACTED),
        )
    }

    private fun encodeSelection(selection: ManualActionSelection): Bundle = Bundle().apply {
        putString(KEY_SNAPSHOT_ID, selection.snapshotId)
        putString(EXTRA_REQUEST_ID, selection.requestId)
        putString(KEY_HANDLE, selection.elementHandle)
        putString(EXTRA_ACTION, selection.action.name)
        putString(KEY_EXPECTED_PACKAGE, selection.expectedPackage)
        putBoolean("automatic", selection.automatic)
        putInt("confirmation_stage", selection.confirmationStage)
        putString("confirmation_token", selection.confirmationToken)
        putString("input_text", selection.inputText)
    }

    private fun decodeSelection(bundle: Bundle): ManualActionSelection? {
        val action = enumValueOrNull<ManualDeviceAction>(bundle.getString(EXTRA_ACTION)) ?: return null
        val inputText = bundle.getString("input_text")
        if (action == ManualDeviceAction.SET_TEXT &&
            !com.yukisoffd.lyracode.interaction.policy.TextInputPolicy.isValidText(inputText)) return null
        if (action != ManualDeviceAction.SET_TEXT && inputText != null) return null
        return ManualActionSelection(
            snapshotId = bundle.getString(KEY_SNAPSHOT_ID) ?: return null,
            requestId = bundle.getString(EXTRA_REQUEST_ID) ?: return null,
            elementHandle = bundle.getString(KEY_HANDLE) ?: return null,
            action = action,
            expectedPackage = bundle.getString(KEY_EXPECTED_PACKAGE) ?: return null,
            inputText = inputText,
            confirmationStage = bundle.getInt("confirmation_stage", 1),
            automatic = bundle.getBoolean("automatic"),
            confirmationToken = bundle.getString("confirmation_token") ?: bundle.getString(EXTRA_REQUEST_ID) ?: return null,
        )
    }

    private fun encodeResult(result: DeviceActionResult): Bundle = Bundle().apply {
        putString(EXTRA_REQUEST_ID, result.requestId)
        putString(KEY_STATUS, result.status.name)
        putString(EXTRA_ACTION, result.action.name)
        putString(KEY_EXPECTED_PACKAGE, result.expectedPackage)
        putString(KEY_ACTUAL_PACKAGE, result.actualPackage)
        putString(KEY_HANDLE, result.elementHandle)
        putString("block_reason", result.blockReason)
        putString(KEY_EXECUTION_METHOD, result.executionMethod)
        putString(KEY_BEFORE_FINGERPRINT, result.beforeFingerprint)
        putString(KEY_AFTER_FINGERPRINT, result.afterFingerprint)
    }

    private fun decodeResult(bundle: Bundle): DeviceActionResult? {
        val status = enumValueOrNull<DeviceActionStatus>(bundle.getString(KEY_STATUS)) ?: return null
        val action = enumValueOrNull<ManualDeviceAction>(bundle.getString(EXTRA_ACTION)) ?: return null
        return DeviceActionResult(
            status = status,
            action = action,
            expectedPackage = bundle.getString(KEY_EXPECTED_PACKAGE) ?: return null,
            actualPackage = bundle.getString(KEY_ACTUAL_PACKAGE),
            elementHandle = bundle.getString(KEY_HANDLE) ?: return null,
            executionMethod = bundle.getString(KEY_EXECUTION_METHOD),
            blockReason = bundle.getString("block_reason"),
            beforeFingerprint = bundle.getString(KEY_BEFORE_FINGERPRINT).orEmpty(),
            requestId = bundle.getString(EXTRA_REQUEST_ID),
            afterFingerprint = bundle.getString(KEY_AFTER_FINGERPRINT),
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String?): T? =
        value?.let { candidate -> enumValues<T>().firstOrNull { it.name == candidate } }

    private const val MAX_REMOTE_NODES = 32
}
