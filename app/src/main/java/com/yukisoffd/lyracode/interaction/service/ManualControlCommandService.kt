package com.yukisoffd.lyracode.interaction.service

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.yukisoffd.lyracode.interaction.model.ManualDeviceAction
import com.yukisoffd.lyracode.interaction.overlay.ManualControlOverlayProtocol
import com.yukisoffd.lyracode.interaction.session.ManualControlController

/**
 * Bound IPC endpoint in the accessibility process. Messenger dispatches on a dedicated thread, so
 * overlay commands do not wait for the background Activity's main looper or broadcast queue.
 */
class ManualControlCommandService : Service() {
    private lateinit var commandThread: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        commandThread = HandlerThread(COMMAND_THREAD_NAME, Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        messenger = Messenger(Handler(commandThread.looper, ::handleCommand))
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        commandThread.quitSafely()
        super.onDestroy()
    }

    private fun handleCommand(message: Message): Boolean {
        val sentAt = message.data.getLong(ManualControlOverlayProtocol.EXTRA_SENT_AT_ELAPSED, 0L)
        val transportMillis = if (sentAt > 0L) SystemClock.elapsedRealtime() - sentAt else -1L
        Log.i(
            LOG_TAG,
            "messenger_command_received what=${message.what} transport_ms=$transportMillis pid=${Process.myPid()}",
        )
        when (message.what) {
            SELECT -> {
                val handle = message.data.getString(ManualControlOverlayProtocol.EXTRA_HANDLE) ?: return true
                val action = message.data.getString(ManualControlOverlayProtocol.EXTRA_ACTION)
                    ?.let { name -> ManualDeviceAction.entries.firstOrNull { it.name == name } }
                    ?: return true
                ManualControlController.select(handle, action)
            }
            CONFIRM -> ManualControlCommandBridge.requestConfirm(
                message.data.getString(ManualControlOverlayProtocol.EXTRA_SNAPSHOT_ID),
                message.data.getString(ManualControlOverlayProtocol.EXTRA_HANDLE),
                message.data.getString(ManualControlOverlayProtocol.EXTRA_REQUEST_ID),
            )
            CLEAR_SELECTION -> ManualControlController.rejectSelection(
                message.data.getString(ManualControlOverlayProtocol.EXTRA_SNAPSHOT_ID),
                message.data.getString(ManualControlOverlayProtocol.EXTRA_HANDLE),
                message.data.getString(ManualControlOverlayProtocol.EXTRA_REQUEST_ID),
            )
            STOP -> ManualControlController.stop()
            SUBMIT -> com.yukisoffd.lyracode.interaction.session.DeviceTaskCoordinator.submit(
                applicationContext, message.data.getString(ManualControlOverlayProtocol.EXTRA_INPUT).orEmpty(),
            )
            APPROVAL -> com.yukisoffd.lyracode.interaction.session.DeviceApprovalBroker.respond(
                message.data.getString(ManualControlOverlayProtocol.EXTRA_REQUEST_ID).orEmpty(),
                message.data.getString(ManualControlOverlayProtocol.EXTRA_INPUT) == "approve")
            CLEAR_CONTEXT -> com.yukisoffd.lyracode.interaction.session.DeviceTaskCoordinator.clearContext()
            PAUSE -> com.yukisoffd.lyracode.interaction.session.DeviceTaskCoordinator.pause()
            else -> return false
        }
        return true
    }

    internal companion object {
        const val SELECT = 1
        const val CONFIRM = 2
        const val CLEAR_SELECTION = 3
        const val STOP = 4
        const val SUBMIT = 5
        const val PAUSE = 6
        const val APPROVAL = 7
        const val CLEAR_CONTEXT = 8
        private const val COMMAND_THREAD_NAME = "lyra-control-command"
        private const val LOG_TAG = "LyraManualControl"
    }
}
