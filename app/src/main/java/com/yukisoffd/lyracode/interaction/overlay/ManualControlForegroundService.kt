package com.yukisoffd.lyracode.interaction.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yukisoffd.lyracode.MainActivity
import com.yukisoffd.lyracode.R
import com.yukisoffd.lyracode.interaction.model.ManualDeviceAction
import com.yukisoffd.lyracode.interaction.service.ManualControlCommandService
import com.yukisoffd.lyracode.interaction.session.ManualControlController
import com.yukisoffd.lyracode.interaction.session.ManualControlState
import com.yukisoffd.lyracode.interaction.session.ManualControlStatus

/** Hosts the overlay in a process that has no background Activity or Compose runtime. */
class ManualControlForegroundService : Service() {
    private lateinit var overlayHandler: Handler
    private var taskHud: TaskHudController? = null
    private var lastRenderedState: ManualControlState? = null
    private var sessionWakeLock: PowerManager.WakeLock? = null
    private var screenReceiverRegistered = false
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (destroyed) return
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    sendControlCommand(ManualControlCommandService.PAUSE, ManualControlOverlayProtocol.COMMAND_PAUSE)
                    sessionWakeLock?.takeIf { it.isHeld }?.release()
                }
                Intent.ACTION_SCREEN_ON -> acquireSessionWakeLock()
            }
        }
    }

    @Volatile
    private var commandMessenger: Messenger? = null
    private var commandServiceBound = false
    private val commandConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            commandMessenger = service?.let(::Messenger)
            Log.i(LOG_TAG, "command_channel_connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            commandMessenger = null
            Log.w(LOG_TAG, "command_channel_disconnected")
        }
    }
    private val expireSession = Runnable {
        sendControlCommand(ManualControlCommandService.STOP, ManualControlOverlayProtocol.COMMAND_STOP)
        stopSelf()
    }

    @Volatile
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            startAsForeground()
            acquireSessionWakeLock()
            androidx.core.content.ContextCompat.registerReceiver(applicationContext, screenReceiver,
                android.content.IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) },
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            screenReceiverRegistered = true
        } catch (error: RuntimeException) {
            ManualControlOverlayProtocol.reportServiceState(applicationContext, running = false)
            stopSelf()
            throw error
        }
        // IME-initiated hide requests reach InputMethodManager on the process main looper.
        // A ViewRoot on a HandlerThread crashes when that path starts its insets animation.
        // This process is already isolated from the Activity/agent, so keep all overlay UI here.
        overlayHandler = Handler(Looper.getMainLooper())
        bindCommandChannel()
        ManualControlOverlayProtocol.reportServiceState(applicationContext, running = true)
        Log.i(LOG_TAG, "overlay_process_started pid=${Process.myPid()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!OverlayPermission.isGranted(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ManualControlOverlayProtocol.ACTION_START -> {
                scheduleExpiry(intent.getLongExtra(ManualControlOverlayProtocol.EXTRA_ACTIVE_UNTIL, 0L))
            }
            ManualControlOverlayProtocol.ACTION_RENDER -> {
                val sentAt = intent.getLongExtra(ManualControlOverlayProtocol.EXTRA_SENT_AT_ELAPSED, 0L)
                val transportMillis = if (sentAt > 0L) SystemClock.elapsedRealtime() - sentAt else -1L
                val encodedState = intent.getBundleExtra(ManualControlOverlayProtocol.EXTRA_STATE)
                overlayHandler.post {
                    val state = encodedState?.let(ManualControlOverlayProtocol::decode)
                    if (state == null) {
                        Log.w(LOG_TAG, "Ignoring invalid overlay state")
                    } else {
                        Log.i(LOG_TAG, "overlay_state_received transport_ms=$transportMillis")
                        scheduleExpiry(state.activeUntilEpochMillis)
                        renderOverlayState(state)
                    }
                }
            }
            ManualControlOverlayProtocol.ACTION_STOP_SERVICE -> {
                sendControlCommand(ManualControlCommandService.STOP, ManualControlOverlayProtocol.COMMAND_STOP)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        if (screenReceiverRegistered) { applicationContext.unregisterReceiver(screenReceiver); screenReceiverRegistered = false }
        if (::overlayHandler.isInitialized) {
            overlayHandler.removeCallbacksAndMessages(null)
            taskHud?.destroy()
            taskHud = null
            lastRenderedState = null
        }
        sessionWakeLock?.let { wakeLock ->
            if (wakeLock.isHeld) wakeLock.release()
        }
        sessionWakeLock = null
        commandMessenger = null
        if (commandServiceBound) {
            runCatching { unbindService(commandConnection) }
            commandServiceBound = false
        }
        ManualControlOverlayProtocol.reportServiceState(applicationContext, running = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun renderOverlayState(state: ManualControlState) {
        if (destroyed) return
        if (!state.isActive() || !OverlayPermission.isGranted(applicationContext)) {
            taskHud?.destroy()
            taskHud = null
            lastRenderedState = null
            stopSelf()
            return
        }
        lastRenderedState = state
        val hud = taskHud ?: TaskHudController(
            context = applicationContext,
            onConfirm = { selection ->
                sendControlCommand(
                    ManualControlCommandService.CONFIRM,
                    ManualControlOverlayProtocol.COMMAND_CONFIRM,
                    handle = selection.elementHandle, snapshotId = selection.snapshotId, requestId = selection.confirmationToken,
                )
            },
            onCancelSelection = { selection ->
                clearSelectionLocally()
                sendControlCommand(
                    ManualControlCommandService.CLEAR_SELECTION,
                    ManualControlOverlayProtocol.COMMAND_CLEAR_SELECTION,
                    handle = selection.elementHandle, snapshotId = selection.snapshotId, requestId = selection.confirmationToken,
                )
            },
            onStop = {
                sendControlCommand(
                    ManualControlCommandService.STOP,
                    ManualControlOverlayProtocol.COMMAND_STOP,
                )
                taskHud?.destroy()
                taskHud = null
                stopSelf()
            },
            onSubmit = { input ->
                sendControlCommand(ManualControlCommandService.SUBMIT, ManualControlOverlayProtocol.COMMAND_SUBMIT, input = input)
            },
            onApproval = { id, approved ->
                sendControlCommand(ManualControlCommandService.APPROVAL, ManualControlOverlayProtocol.COMMAND_APPROVAL, requestId = id, input = if (approved) "approve" else "reject")
            },
            onClearContext = {
                sendControlCommand(ManualControlCommandService.CLEAR_CONTEXT, ManualControlOverlayProtocol.COMMAND_CLEAR_CONTEXT)
            },
            onPause = {
                sendControlCommand(ManualControlCommandService.PAUSE, ManualControlOverlayProtocol.COMMAND_PAUSE)
            },
        ).also { taskHud = it }
        hud.render(state)
    }

    private fun bindCommandChannel() {
        commandServiceBound = runCatching {
            bindService(
                Intent(this, ManualControlCommandService::class.java),
                commandConnection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
            )
        }.getOrDefault(false)
    }

    private fun sendControlCommand(
        what: Int,
        fallbackCommand: String,
        handle: String? = null,
        action: ManualDeviceAction? = null,
        input: String? = null,
        snapshotId: String? = null,
        requestId: String? = null,
    ) {
        val sentAt = SystemClock.elapsedRealtime()
        val message = Message.obtain(null, what).apply {
            data = Bundle().apply {
                putString(ManualControlOverlayProtocol.EXTRA_HANDLE, handle)
                putString(ManualControlOverlayProtocol.EXTRA_ACTION, action?.name)
                putString(ManualControlOverlayProtocol.EXTRA_INPUT, input?.take(2000))
                putString(ManualControlOverlayProtocol.EXTRA_SNAPSHOT_ID, snapshotId)
                putString(ManualControlOverlayProtocol.EXTRA_REQUEST_ID, requestId)
                putLong(ManualControlOverlayProtocol.EXTRA_SENT_AT_ELAPSED, sentAt)
            }
        }
        val delivered = runCatching {
            val endpoint = commandMessenger ?: return@runCatching false
            endpoint.send(message)
            true
        }.getOrDefault(false)
        if (!delivered) {
            Log.w(LOG_TAG, "command_channel_fallback what=$what")
            ManualControlOverlayProtocol.sendCommand(applicationContext, fallbackCommand, handle, action, input, snapshotId, requestId)
        }
    }

    private fun clearSelectionLocally() {
        val state = lastRenderedState ?: return
        renderOverlayState(
            state.copy(
                status = if (state.latestSnapshot == null) {
                    ManualControlStatus.OBSERVING
                } else {
                    ManualControlStatus.READY
                },
                selection = null,
            ),
        )
    }

    private fun scheduleExpiry(activeUntilEpochMillis: Long) {
        overlayHandler.removeCallbacks(expireSession)
        if (activeUntilEpochMillis == Long.MAX_VALUE) return
        val remainingMillis = activeUntilEpochMillis - System.currentTimeMillis()
        if (remainingMillis <= 0L) {
            expireSession.run()
        } else {
            overlayHandler.postDelayed(expireSession, remainingMillis)
        }
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.manual_control_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun startAsForeground() {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ManualControlForegroundService::class.java)
                .setAction(ManualControlOverlayProtocol.ACTION_STOP_SERVICE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.manual_control_notification_title))
            .setContentText(getString(R.string.manual_control_notification_text))
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.manual_control_notification_stop), stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireSessionWakeLock() {
        if (!getSystemService(PowerManager::class.java).isInteractive || sessionWakeLock?.isHeld == true) return
        sessionWakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:manual-control")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    internal companion object {
        private const val CHANNEL_ID = "lyra_manual_control"
        private const val NOTIFICATION_ID = 7314
        private const val LOG_TAG = "LyraManualControl"

        fun start(context: Context): Boolean {
            val appContext = context.applicationContext
            ManualControlForegroundConnection.markStarting()
            return runCatching {
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, ManualControlForegroundService::class.java)
                        .setAction(ManualControlOverlayProtocol.ACTION_START)
                        .putExtra(
                            ManualControlOverlayProtocol.EXTRA_ACTIVE_UNTIL,
                            ManualControlController.state.value.activeUntilEpochMillis,
                        )
                        .putExtra(
                            ManualControlOverlayProtocol.EXTRA_SENT_AT_ELAPSED,
                            SystemClock.elapsedRealtime(),
                        ),
                )
                ManualControlOverlayPublisher.start(appContext)
            }.onFailure {
                ManualControlForegroundConnection.markStopped()
            }.isSuccess
        }

    }
}
