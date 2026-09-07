package com.yukisoffd.lyracode.interaction.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.yukisoffd.lyracode.interaction.model.ManualDeviceAction
import com.yukisoffd.lyracode.interaction.service.ManualControlCommandBridge
import com.yukisoffd.lyracode.interaction.session.ManualControlController

/** Receives explicit, app-internal commands from the isolated overlay process. */
class ManualControlActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sentAt = intent.getLongExtra(ManualControlOverlayProtocol.EXTRA_SENT_AT_ELAPSED, 0L)
        val transportMillis = if (sentAt > 0L) SystemClock.elapsedRealtime() - sentAt else -1L
        Log.i(LOG_TAG, "main_command_received action=${intent.action} transport_ms=$transportMillis pid=${Process.myPid()}")
        when (intent.action) {
            ManualControlOverlayProtocol.COMMAND_SELECT -> {
                val handle = intent.getStringExtra(ManualControlOverlayProtocol.EXTRA_HANDLE) ?: return
                val action = intent.getStringExtra(ManualControlOverlayProtocol.EXTRA_ACTION)
                    ?.let { name -> ManualDeviceAction.entries.firstOrNull { it.name == name } }
                    ?: return
                ManualControlController.select(handle, action)
            }
            ManualControlOverlayProtocol.COMMAND_CONFIRM -> ManualControlCommandBridge.requestConfirm(
                intent.getStringExtra(ManualControlOverlayProtocol.EXTRA_SNAPSHOT_ID),
                intent.getStringExtra(ManualControlOverlayProtocol.EXTRA_HANDLE),
                intent.getStringExtra(ManualControlOverlayProtocol.EXTRA_REQUEST_ID),
            )
            ManualControlOverlayProtocol.COMMAND_CLEAR_SELECTION -> ManualControlController.rejectSelection(
                intent.getStringExtra(ManualControlOverlayProtocol.EXTRA_SNAPSHOT_ID),
                intent.getStringExtra(ManualControlOverlayProtocol.EXTRA_HANDLE),
                intent.getStringExtra(ManualControlOverlayProtocol.EXTRA_REQUEST_ID),
            )
            ManualControlOverlayProtocol.COMMAND_STOP -> ManualControlController.stop()
            ManualControlOverlayProtocol.COMMAND_SUBMIT -> com.yukisoffd.lyracode.interaction.session.DeviceTaskCoordinator.submit(
                context, intent.getStringExtra(ManualControlOverlayProtocol.EXTRA_INPUT).orEmpty(),
            )
            ManualControlOverlayProtocol.COMMAND_APPROVAL -> com.yukisoffd.lyracode.interaction.session.DeviceApprovalBroker.respond(
                intent.getStringExtra(ManualControlOverlayProtocol.EXTRA_REQUEST_ID).orEmpty(),
                intent.getStringExtra(ManualControlOverlayProtocol.EXTRA_INPUT) == "approve")
            ManualControlOverlayProtocol.COMMAND_CLEAR_CONTEXT -> com.yukisoffd.lyracode.interaction.session.DeviceTaskCoordinator.clearContext()
            ManualControlOverlayProtocol.COMMAND_PAUSE -> com.yukisoffd.lyracode.interaction.session.DeviceTaskCoordinator.pause()
            ManualControlOverlayProtocol.SERVICE_STATE -> {
                if (intent.getBooleanExtra(ManualControlOverlayProtocol.EXTRA_RUNNING, false)) {
                    ManualControlForegroundConnection.markRunning()
                } else {
                    ManualControlForegroundConnection.markStopped()
                    if (ManualControlController.state.value.isActive()) ManualControlController.stop()
                }
            }
        }
    }

    private companion object {
        const val LOG_TAG = "LyraManualControl"
    }
}
