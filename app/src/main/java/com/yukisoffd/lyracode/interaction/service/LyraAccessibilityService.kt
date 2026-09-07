package com.yukisoffd.lyracode.interaction.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.interaction.DeviceInteractionAvailability
import com.yukisoffd.lyracode.interaction.action.ActionVerifier
import com.yukisoffd.lyracode.interaction.action.NodeResolution
import com.yukisoffd.lyracode.interaction.action.SemanticNodeResolver
import com.yukisoffd.lyracode.interaction.model.DeviceActionResult
import com.yukisoffd.lyracode.interaction.model.DeviceActionStatus
import com.yukisoffd.lyracode.interaction.model.ManualActionSelection
import com.yukisoffd.lyracode.interaction.model.ScreenSnapshot
import com.yukisoffd.lyracode.interaction.perception.AccessibilitySnapshotSource
import com.yukisoffd.lyracode.interaction.perception.ActionSnapshotSource
import com.yukisoffd.lyracode.interaction.perception.ScreenProbeController
import com.yukisoffd.lyracode.interaction.perception.ScreenProbeFailureCode
import com.yukisoffd.lyracode.interaction.perception.SnapshotCaptureResult
import com.yukisoffd.lyracode.interaction.policy.DeviceActionPolicy
import com.yukisoffd.lyracode.interaction.policy.DevicePolicyDecision
import com.yukisoffd.lyracode.interaction.session.ManualControlController
import com.yukisoffd.lyracode.interaction.session.ManualControlStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android capability bridge for the device interaction experiment.
 *
 * This service only exposes Android capabilities. It never calls a model directly and never keeps
 * AccessibilityNodeInfo instances beyond a single bounded observation.
 */
class LyraAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val settings by lazy { AppSettings(this) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val captureDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(
            {
                Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
                runnable.run()
            },
            "lyra-accessibility-worker",
        ).apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val actionDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(
            {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
                runnable.run()
            },
            "lyra-accessibility-action",
        ).apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val captureRunnable = Runnable {
        captureScheduled.set(false)
        requestCapture()
    }
    private val captureScheduled = AtomicBoolean(false)
    private val captureInFlight = AtomicBoolean(false)
    private val capturePending = AtomicBoolean(false)

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityConnection.markConnected()
        com.yukisoffd.lyracode.interaction.perception.DeviceScreenshotSource.service = this
        ManualControlCommandBridge.attach(::executeSelectedAction)
        serviceScope.launch {
            ManualControlController.state
                .map { it.isActive() }
                .distinctUntilChanged()
                .collectLatest { active ->
                    while (active && ManualControlController.state.value.isActive()) {
                        if (ManualControlController.state.value.status !in EXECUTION_STATUSES) {
                            requestCapture()
                        }
                        delay(MANUAL_CAPTURE_INTERVAL_MILLIS)
                    }
                }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!probeIsAllowed()) {
            ScreenProbeController.clear()
            ManualControlController.stop()
            return
        }
        if (!ScreenProbeController.isActive() && !ManualControlController.state.value.isActive()) return
        val eventPackage = event?.packageName?.toString() ?: return
        if (eventPackage == packageName) return
        if (ManualControlController.state.value.status in EXECUTION_STATUSES) return

        scheduleCapture(EVENT_CAPTURE_DELAY_MILLIS)
    }

    override fun onInterrupt() {
        mainHandler.removeCallbacks(captureRunnable)
        captureScheduled.set(false)
        capturePending.set(false)
        if (ScreenProbeController.isActive()) {
            ScreenProbeController.fail(ScreenProbeFailureCode.SERVICE_INTERRUPTED, stop = true)
        }
        ManualControlController.stop()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        mainHandler.removeCallbacks(captureRunnable)
        captureScheduled.set(false)
        capturePending.set(false)
        if (ScreenProbeController.isActive()) {
            ScreenProbeController.fail(ScreenProbeFailureCode.SERVICE_DISCONNECTED, stop = true)
        }
        ManualControlController.stop()
        ManualControlCommandBridge.detach()
        com.yukisoffd.lyracode.interaction.perception.DeviceScreenshotSource.service = null
        AccessibilityConnection.markDisconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(captureRunnable)
        captureScheduled.set(false)
        ManualControlController.stop()
        ManualControlCommandBridge.detach()
        com.yukisoffd.lyracode.interaction.perception.DeviceScreenshotSource.service = null
        serviceScope.cancel()
        captureDispatcher.close()
        actionDispatcher.close()
        AccessibilityConnection.markDisconnected()
        super.onDestroy()
    }

    private fun requestCapture() {
        if (Build.VERSION.SDK_INT < DeviceInteractionAvailability.MIN_SUPPORTED_SDK) return
        val manualActive = ManualControlController.state.value.isActive()
        if (!probeIsAllowed() || (!ScreenProbeController.isActive() && !manualActive)) return
        if (!captureInFlight.compareAndSet(false, true)) {
            capturePending.set(true)
            return
        }
        val captureStartedAt = SystemClock.elapsedRealtime()
        serviceScope.launch(captureDispatcher) {
            val result = if (ScreenProbeController.isActive()) {
                AccessibilitySnapshotSource(this@LyraAccessibilityService).capture()
            } else {
                ActionSnapshotSource(this@LyraAccessibilityService).capture()
            }
            val captureDuration = SystemClock.elapsedRealtime() - captureStartedAt
            if (captureDuration >= SLOW_OPERATION_LOG_MILLIS) {
                val nodeCount = (result as? SnapshotCaptureResult.Success)?.snapshot?.nodes?.size ?: 0
                Log.i(LOG_TAG, "slow_capture duration_ms=$captureDuration nodes=$nodeCount")
            }
            publishCapture(result)
            captureInFlight.set(false)
            if (capturePending.getAndSet(false)) {
                requestCapture()
            }
        }
    }

    private fun publishCapture(result: SnapshotCaptureResult) {
        when (result) {
            is SnapshotCaptureResult.Success -> {
                if (result.snapshot.activePackage != packageName) {
                    if (ScreenProbeController.isActive()) ScreenProbeController.publish(result.snapshot)
                    val manualState = ManualControlController.state.value
                    if (manualState.isActive() && manualState.status !in EXECUTION_STATUSES) {
                        ManualControlController.publish(result.snapshot)
                    }
                }
            }
            is SnapshotCaptureResult.Failure -> {
                if (ScreenProbeController.isActive()) ScreenProbeController.fail(result.code)
                ManualControlController.invalidateObservation()
            }
        }
    }

    private fun executeSelectedAction(snapshotId: String, handle: String, requestId: String) {
        if (Build.VERSION.SDK_INT < DeviceInteractionAvailability.MIN_SUPPORTED_SDK || !probeIsAllowed()) return
        val confirmReceivedAt = SystemClock.elapsedRealtime()
        val (selection, before) = ManualControlController.beginExecution(snapshotId, handle, requestId) ?: return
        Log.i(LOG_TAG, "confirm_received action=${selection.action.name}")
        val expectedNode = before.nodes.firstOrNull { it.handle == selection.elementHandle }
        if (selection.action == com.yukisoffd.lyracode.interaction.model.ManualDeviceAction.SET_TEXT &&
            !com.yukisoffd.lyracode.interaction.policy.TextInputPolicy.isValidText(selection.inputText)) {
            finishWithStatus(selection, before, DeviceActionStatus.BLOCKED)
            return
        }
        if (expectedNode == null) {
            finishWithStatus(selection, before, DeviceActionStatus.STALE)
            return
        }
        val policy = DeviceActionPolicy.evaluate(selection.expectedPackage, expectedNode, selection.action, before.nodes)
        if (policy is DevicePolicyDecision.Blocked) {
            finishWithStatus(selection, before, DeviceActionStatus.BLOCKED, blockReason = policy.reason.name)
            return
        }
        mainHandler.removeCallbacks(captureRunnable)
        captureScheduled.set(false)
        capturePending.set(false)
        serviceScope.launch(actionDispatcher) {
            val workerStartedAt = SystemClock.elapsedRealtime()
            Log.i(LOG_TAG, "confirm_worker_started queue_ms=${workerStartedAt - confirmReceivedAt}")
            val activeRoot = rootInActiveWindow
            val activePackage = activeRoot?.packageName?.toString()
            if (activePackage != selection.expectedPackage) {
                finishWithStatus(selection, before, DeviceActionStatus.PACKAGE_CHANGED, actualPackage = activePackage)
                return@launch
            }

            val fresh = (ActionSnapshotSource(this@LyraAccessibilityService).capture() as? SnapshotCaptureResult.Success)?.snapshot
            if (fresh == null || fresh.uiFingerprint != before.uiFingerprint) {
                finishWithStatus(selection, before, DeviceActionStatus.STALE)
                fresh?.let(ManualControlController::publish)
                return@launch
            }
            val resolutionStartedAt = SystemClock.elapsedRealtime()
            when (val resolution = SemanticNodeResolver(this@LyraAccessibilityService).resolve(
                expectedNode,
                selection.expectedPackage,
                selection.action,
                preferredRoot = activeRoot,
            )) {
                NodeResolution.NotFound -> {
                    Log.i(
                        LOG_TAG,
                        "action_resolution_failed result=not_found resolution_ms=${SystemClock.elapsedRealtime() - resolutionStartedAt} " +
                            "total_ms=${SystemClock.elapsedRealtime() - confirmReceivedAt}",
                    )
                    finishWithStatus(selection, before, DeviceActionStatus.STALE)
                }
                NodeResolution.Ambiguous -> {
                    Log.i(
                        LOG_TAG,
                        "action_resolution_failed result=ambiguous resolution_ms=${SystemClock.elapsedRealtime() - resolutionStartedAt} " +
                            "total_ms=${SystemClock.elapsedRealtime() - confirmReceivedAt}",
                    )
                    finishWithStatus(selection, before, DeviceActionStatus.AMBIGUOUS)
                }
                is NodeResolution.Resolved -> {
                    val accepted = ManualControlController.dispatchIfCurrent(selection) {
                        if (!probeIsAllowed() || !getSystemService(android.os.PowerManager::class.java).isInteractive ||
                            getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) false
                        else runCatching {
                            if (selection.action == com.yukisoffd.lyracode.interaction.model.ManualDeviceAction.SET_TEXT)
                                com.yukisoffd.lyracode.interaction.action.NativeTextAction.perform(resolution.node, selection.inputText!!)
                            else resolution.node.performAction(resolution.actionId)
                        }.getOrDefault(false)
                    }
                    Log.i(
                        LOG_TAG,
                        "action_dispatched accepted=$accepted resolution_ms=${SystemClock.elapsedRealtime() - resolutionStartedAt} " +
                            "total_ms=${SystemClock.elapsedRealtime() - confirmReceivedAt}",
                    )
                    if (!accepted) {
                        finishWithStatus(selection, before, DeviceActionStatus.SYSTEM_REJECTED)
                    } else {
                        ManualControlController.markVerifying(selection)
                        serviceScope.launch {
                            delay(ACTION_SETTLE_MILLIS)
                            verifyAction(selection, before, resolution.actionId)
                        }
                    }
                }
            }
        }
    }

    private fun verifyAction(
        selection: ManualActionSelection,
        before: ScreenSnapshot,
        actionId: Int,
    ) {
        if (Build.VERSION.SDK_INT < DeviceInteractionAvailability.MIN_SUPPORTED_SDK) return
        serviceScope.launch(captureDispatcher) {
            val capture = ActionSnapshotSource(this@LyraAccessibilityService).capture()
            when (capture) {
                is SnapshotCaptureResult.Failure -> finishWithStatus(
                    selection,
                    before,
                    DeviceActionStatus.CAPTURE_FAILED,
                    executionMethod = "node_action:$actionId",
                )
                is SnapshotCaptureResult.Success -> {
                    val after = capture.snapshot
                    val status = if (selection.action == com.yukisoffd.lyracode.interaction.model.ManualDeviceAction.SET_TEXT) {
                        com.yukisoffd.lyracode.interaction.action.TextActionVerifier.verify(selection, before, after)
                    } else ActionVerifier.verify(before, after, selection.expectedPackage)
                    ManualControlController.finish(
                        actionResult(
                            selection = selection,
                            before = before,
                            status = status,
                            actualPackage = after.activePackage,
                            after = after,
                            executionMethod = "node_action:$actionId",
                        ),
                        snapshot = after,
                    )
                    if (ScreenProbeController.isActive()) ScreenProbeController.publish(after)
                }
            }
        }
    }

    private fun finishWithStatus(
        selection: ManualActionSelection,
        before: ScreenSnapshot,
        status: DeviceActionStatus,
        actualPackage: String? = before.activePackage,
        executionMethod: String? = null,
        blockReason: String? = null,
    ) {
        ManualControlController.finish(
            actionResult(selection, before, status, actualPackage, executionMethod = executionMethod).copy(blockReason = blockReason),
        )
    }

    private fun actionResult(
        selection: ManualActionSelection,
        before: ScreenSnapshot,
        status: DeviceActionStatus,
        actualPackage: String?,
        after: ScreenSnapshot? = null,
        executionMethod: String? = null,
    ) = DeviceActionResult(
        status = status,
        requestId = selection.requestId,
        action = selection.action,
        expectedPackage = selection.expectedPackage,
        actualPackage = actualPackage,
        elementHandle = selection.elementHandle,
        executionMethod = executionMethod,
        beforeFingerprint = before.uiFingerprint,
        afterFingerprint = after?.uiFingerprint,
    )

    private fun probeIsAllowed(): Boolean =
        DeviceInteractionAvailability.isSupported() && settings.deviceInteractionExperimentalEnabled

    private fun scheduleCapture(delayMillis: Long) {
        if (!captureScheduled.compareAndSet(false, true)) return
        if (!mainHandler.postDelayed(captureRunnable, delayMillis)) {
            captureScheduled.set(false)
        }
    }

    private companion object {
        const val EVENT_CAPTURE_DELAY_MILLIS = 75L
        const val MANUAL_CAPTURE_INTERVAL_MILLIS = 350L
        const val ACTION_SETTLE_MILLIS = 300L
        const val SLOW_OPERATION_LOG_MILLIS = 500L
        const val LOG_TAG = "LyraManualControl"
        val EXECUTION_STATUSES = setOf(ManualControlStatus.EXECUTING, ManualControlStatus.VERIFYING)
    }
}
