package com.yukisoffd.lyracode.interaction.session

import com.yukisoffd.lyracode.interaction.model.DeviceActionResult
import com.yukisoffd.lyracode.interaction.model.DeviceActionStatus
import com.yukisoffd.lyracode.interaction.model.ManualActionSelection
import com.yukisoffd.lyracode.interaction.model.ManualDeviceAction
import com.yukisoffd.lyracode.interaction.model.ScreenSnapshot
import com.yukisoffd.lyracode.interaction.policy.DeviceActionPolicy
import com.yukisoffd.lyracode.interaction.policy.DevicePolicyDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class ManualControlStatus {
    IDLE,
    OBSERVING,
    READY,
    TARGET_SELECTED,
    EXECUTING,
    VERIFYING,
    PAUSED_PACKAGE_CHANGED,
    FAILED,
    CANCELLED,
}

internal data class ManualControlState(
    val activeUntilEpochMillis: Long = 0L,
    val sessionId: Long = 0L,
    val status: ManualControlStatus = ManualControlStatus.IDLE,
    val targetPackage: String? = null,
    val latestSnapshot: ScreenSnapshot? = null,
    val selection: ManualActionSelection? = null,
    val lastResult: DeviceActionResult? = null,
    val agentTargetPackage: String? = null,
    val chat: DeviceChatState = DeviceChatState(),
    val approval: DeviceApproval? = null,
    val petEvent: String? = null,
) {
    fun isActive(nowEpochMillis: Long = System.currentTimeMillis()): Boolean =
        activeUntilEpochMillis > nowEpochMillis
}

internal object ManualControlController {
    const val DEFAULT_DURATION_MILLIS = 0L
    private val sessions = java.util.concurrent.atomic.AtomicLong()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(ManualControlState())
    val state: StateFlow<ManualControlState> = _state.asStateFlow()
    private var expiryJob: Job? = null

    @Synchronized
    fun start(
        nowEpochMillis: Long = System.currentTimeMillis(),
        durationMillis: Long = DEFAULT_DURATION_MILLIS,
    ) {
        expiryJob?.cancel()
        val boundedDuration = if (durationMillis <= 0) 0L else durationMillis.coerceIn(10_000L, 180_000L)
        _state.value = ManualControlState(
            activeUntilEpochMillis = if (boundedDuration == 0L) Long.MAX_VALUE else nowEpochMillis + boundedDuration,
            sessionId = sessions.incrementAndGet(),
            status = ManualControlStatus.OBSERVING,
        )
        expiryJob = if (boundedDuration == 0L) null else scope.launch {
            delay(boundedDuration)
            stop()
        }
    }

    @Synchronized
    fun stop() {
        expiryJob?.cancel()
        expiryJob = null
        _state.value = ManualControlState(status = ManualControlStatus.CANCELLED)
    }

    @Synchronized
    fun publish(snapshot: ScreenSnapshot) {
        val current = _state.value
        if (!current.isActive()) return
        val packageName = snapshot.activePackage.orEmpty()
        if (!DeviceActionPolicy.isPackageAllowed(packageName)) {
            _state.value = current.copy(
                status = ManualControlStatus.PAUSED_PACKAGE_CHANGED,
                targetPackage = null,
                latestSnapshot = null,
                selection = null,
            )
            return
        }

        if (
            current.targetPackage == packageName &&
            current.latestSnapshot?.uiFingerprint == snapshot.uiFingerprint
        ) {
            return
        }

        val packageChanged = current.targetPackage != null && packageName != current.targetPackage
        val keepSelection = !packageChanged && current.selection?.snapshotId == snapshot.snapshotId
        _state.value = current.copy(
            status = if (keepSelection) ManualControlStatus.TARGET_SELECTED else ManualControlStatus.READY,
            targetPackage = packageName,
            latestSnapshot = snapshot,
            selection = current.selection.takeIf { keepSelection },
        )
    }

    @Synchronized
    fun select(handle: String, action: ManualDeviceAction, inputText: String? = null, automatic: Boolean = false): Boolean {
        if (action == ManualDeviceAction.SET_TEXT) {
            if (!com.yukisoffd.lyracode.interaction.policy.TextInputPolicy.isValidText(inputText)) return false
        } else if (inputText != null) return false
        val current = _state.value
        if (!current.isActive() || current.status in setOf(ManualControlStatus.EXECUTING, ManualControlStatus.VERIFYING)) return false
        val snapshot = current.latestSnapshot ?: return false
        val targetPackage = current.targetPackage ?: return false
        val node = snapshot.nodes.firstOrNull { it.handle == handle } ?: return false
        if (DeviceActionPolicy.evaluate(targetPackage, node, action, snapshot.nodes) !is DevicePolicyDecision.Allowed) {
            return false
        }
        _state.value = current.copy(
            status = ManualControlStatus.TARGET_SELECTED,
            selection = ManualActionSelection(snapshot.snapshotId, handle, action, targetPackage, inputText = inputText, automatic = automatic),
            lastResult = null,
        )
        return true
    }

    @Synchronized
    fun clearSelection() {
        val current = _state.value
        if (!current.isActive()) return
        _state.value = current.copy(
            status = if (current.latestSnapshot == null) ManualControlStatus.OBSERVING else ManualControlStatus.READY,
            selection = null,
        )
    }

    @Synchronized
    fun invalidateObservation() {
        val current = _state.value
        if (!current.isActive() || current.status in setOf(ManualControlStatus.EXECUTING, ManualControlStatus.VERIFYING)) return
        _state.value = current.copy(latestSnapshot = null, selection = null,
            status = ManualControlStatus.OBSERVING)
    }

    @Synchronized
    fun beginExecution(expectedSnapshotId: String? = null, expectedHandle: String? = null, expectedRequestId: String? = null): Pair<ManualActionSelection, ScreenSnapshot>? {
        val current = _state.value
        if (!current.isActive() || current.status != ManualControlStatus.TARGET_SELECTED) return null
        val selection = current.selection ?: return null
        if (expectedRequestId != null && selection.confirmationToken != expectedRequestId) return null
        if (expectedSnapshotId != null && selection.snapshotId != expectedSnapshotId) return null
        if (expectedHandle != null && selection.elementHandle != expectedHandle) return null
        val snapshot = current.latestSnapshot ?: return null
        if (selection.snapshotId != snapshot.snapshotId) return null
        val node = snapshot.nodes.firstOrNull { it.handle == selection.elementHandle } ?: return null
        if (DeviceActionPolicy.requiresSecondConfirmation(node, selection.action, snapshot.nodes) && expectedRequestId == null) return null
        if (DeviceActionPolicy.requiresSecondConfirmation(node, selection.action, snapshot.nodes) && selection.confirmationStage < 2) {
            _state.value = current.copy(selection = selection.copy(confirmationStage = 2, confirmationToken = java.util.UUID.randomUUID().toString()))
            return null
        }
        _state.value = current.copy(status = ManualControlStatus.EXECUTING)
        return selection to snapshot
    }

    @Synchronized
    fun rejectSelection(snapshotId: String?, handle: String?, requestId: String? = null) {
        val selection = _state.value.selection ?: return
        if (requestId != null && requestId != selection.confirmationToken) return
        if (selection.snapshotId != snapshotId || selection.elementHandle != handle) return
        if (_state.value.status != ManualControlStatus.TARGET_SELECTED) return
        val fingerprint = _state.value.latestSnapshot?.uiFingerprint.orEmpty()
        clearSelection()
        _state.value = _state.value.copy(lastResult = DeviceActionResult(DeviceActionStatus.USER_CANCELLED,
            selection.action, selection.expectedPackage, selection.expectedPackage, selection.elementHandle,
            null, fingerprint, requestId = selection.requestId))
    }

    @Synchronized
    fun markVerifying(selection: ManualActionSelection? = null) {
        if (selection != null && _state.value.selection !== selection) return
        if (!_state.value.isActive() || _state.value.status != ManualControlStatus.EXECUTING) return
        _state.value = _state.value.copy(status = ManualControlStatus.VERIFYING)
    }

    @Synchronized
    fun finish(result: DeviceActionResult, snapshot: ScreenSnapshot? = null) {
        val current = _state.value
        if (!current.isActive() || current.selection?.elementHandle != result.elementHandle) return
        if (result.requestId != null && current.selection.requestId != result.requestId) return
        val canContinue = current.isActive()
        _state.value = current.copy(
            status = if (canContinue) ManualControlStatus.READY else ManualControlStatus.FAILED,
            latestSnapshot = snapshot ?: current.latestSnapshot,
            selection = null,
            lastResult = result,
        )
    }

    @Synchronized
    fun updateChat(chat: DeviceChatState, targetPackage: String? = _state.value.agentTargetPackage) {
        if (!_state.value.isActive()) return
        _state.value = _state.value.copy(chat = chat, agentTargetPackage = targetPackage)
    }

    @Synchronized fun setPetEvent(event: String) {
        if (_state.value.isActive()) _state.value = _state.value.copy(petEvent = event)
    }

    @Synchronized fun setApproval(approval: DeviceApproval?) {
        if (_state.value.isActive()) _state.value = _state.value.copy(approval = approval)
    }

    @Synchronized
    fun selectAgentTarget(snapshotId: String, handle: String, action: ManualDeviceAction, inputText: String? = null, automatic: Boolean = false): Boolean {
        if (_state.value.latestSnapshot?.snapshotId != snapshotId) return false
        return select(handle, action, inputText, automatic)
    }

    /** Stop and dispatch share the same lock: queued actions cannot run after stop returns. */
    @Synchronized
    fun dispatchIfCurrent(selection: ManualActionSelection, dispatch: () -> Boolean): Boolean {
        val current = _state.value
        if (!current.isActive() || current.status != ManualControlStatus.EXECUTING || current.selection !== selection) return false
        return dispatch()
    }
}
