package com.yukisoffd.lyracode.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.yukisoffd.lyracode.MainActivity
import com.yukisoffd.lyracode.R

/**
 * Raises the process out of the background while a conversation run is in flight.
 *
 * This only makes a kill less likely. It cannot survive a force-stop, a low-memory kill, or the
 * user removing the task, so resumable batches remain the correctness fix.
 */
class AgentRunForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var instanceToken = 0L
    private var lastWakeRenewAtMs = 0L

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.agent_run_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, javaClass).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        startForeground(
            NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_floating_workspace)
                .setContentTitle(getString(R.string.agent_run_title))
                .setContentText(getString(R.string.agent_run_notification))
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, getString(R.string.agent_run_stop), stop).build())
                .build(),
        )
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:agentRun")
            // A run can wait indefinitely on a tool approval or a user question. A bounded
            // acquire keeps a forgotten service from holding the CPU awake forever. Streaming
            // progress re-arms the timeout, so only a run with no progress for 30 minutes loses
            // the lock: it keeps running, just without the CPU held awake.
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        lastWakeRenewAtMs = SystemClock.elapsedRealtime()
        instance = this
        // A stop can arrive between startForegroundService and this call. The system then sees
        // a service brought down before it ever reached startForeground and kills the process
        // with "did not then call Service.startForeground()". Reach startForeground first, then
        // honour the deferred stop.
        val start = lifecycle.onForegroundStarted()
        instanceToken = start.instance
        if (start.stopNow) stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopRuns?.invoke()
            if (lifecycle.onStopRequested() == StopDecision.STOP_NOW) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        lifecycle.onDestroyed(instanceToken)
        if (instance === this) instance = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Re-arms the wake-lock timeout. A streaming run calls this on every update; the throttle
     * keeps that to one acquire per [WAKE_LOCK_RENEW_INTERVAL_MS]. After 30 minutes with no
     * progress the lock expires and the run continues without it.
     */
    internal fun renewWakeLock(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastWakeRenewAtMs < WAKE_LOCK_RENEW_INTERVAL_MS) return
        lastWakeRenewAtMs = now
        wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    companion object {
        private const val TAG = "AgentRunService"
        private const val CHANNEL = "agent_runs"
        private const val STOP = "com.yukisoffd.lyracode.STOP_AGENT_RUN"
        private const val NOTIFICATION_ID = 8793
        private const val WAKE_LOCK_TIMEOUT_MS = 30L * 60L * 1000L
        private const val WAKE_LOCK_RENEW_INTERVAL_MS = 60L * 1000L

        /** The start-foreground/stop ordering; a separate object so a unit test can drive it. */
        internal val lifecycle = AgentRunForegroundLifecycle()

        /** The live instance, so a streaming run can renew the wake lock it owns. */
        @Volatile
        private var instance: AgentRunForegroundService? = null

        private var stopRunsOwner: Any? = null

        /** Set by [com.yukisoffd.lyracode.ChatController]; the notification's stop action calls it. */
        internal var stopRuns: (() -> Unit)? = null
            private set

        fun bindStopRuns(owner: Any, handler: () -> Unit) {
            stopRunsOwner = owner
            stopRuns = handler
        }

        fun unbindStopRuns(owner: Any) {
            if (stopRunsOwner === owner) {
                stopRunsOwner = null
                stopRuns = null
            }
        }

        /**
         * Records a run that began anywhere in the process and asks for the foreground.
         * [finish] is its pair; the service stops only when the last run in the process ends,
         * because a recreated controller may own a newer run than the caller.
         */
        fun start(context: Context) {
            lifecycle.onRunStarted()
            instance?.renewWakeLock(force = true)
            runCatching { ContextCompat.startForegroundService(context, Intent(context, AgentRunForegroundService::class.java)) }
                .onFailure { error ->
                    // Android 12 and later can refuse a background start. The run still goes
                    // ahead without foreground priority; say so instead of failing silently.
                    Log.w(TAG, "Foreground service start was blocked; the run continues unprotected", error)
                }
        }

        /** Pairs with [start]; stops the service only when no run is left in the process. */
        fun finish(context: Context) {
            if (!lifecycle.onRunFinished()) return
            stop(context)
        }

        /** Extends the wake lock from a streaming update; a no-op when no service is running. */
        fun renewWakeLock() {
            instance?.renewWakeLock()
        }

        fun stop(context: Context) {
            when (lifecycle.onStopRequested()) {
                StopDecision.STOP_NOW -> runCatching { context.stopService(Intent(context, AgentRunForegroundService::class.java)) }
                    .onFailure { error -> Log.w(TAG, "Stopping the foreground service failed", error) }
                StopDecision.DEFER, StopDecision.IGNORE -> Unit
            }
        }
    }
}

/** What a stop request may do right now. */
internal enum class StopDecision {
    /** The service is foreground; stop it. */
    STOP_NOW,

    /** The service has not reached startForeground yet; onCreate stops it afterwards. */
    DEFER,

    /** Nothing is running, or the stop was already served. */
    IGNORE,
}

/** What a finished `onCreate` must do: the token its teardown reports, and whether to stop at once. */
internal class ForegroundStart(val instance: Long, val stopNow: Boolean)

/**
 * Orders `startForegroundService`, `startForeground` and `stopService` so a run that ends
 * before `onCreate` reaches `startForeground` cannot crash the process, and so one run's
 * teardown cannot unarm the next one.
 *
 * Pure and main-thread only. [AgentRunForegroundService] calls it from `onCreate`,
 * `onStartCommand` and `onDestroy`; a unit test drives the same transitions.
 */
internal class AgentRunForegroundLifecycle {
    /** Runs that began and have not finished anywhere in the process. */
    private var activeRuns = 0

    /** Bumped for every run start; a service instance belongs to the generation it was created for. */
    private var generation = 0L

    /** The newest run still wants the foreground. */
    private var startRequested = false

    /** The generation served by the live foreground instance; 0 when none is in the foreground. */
    private var instanceGeneration = 0L

    private var stopPending = false

    fun onRunStarted(): Long {
        activeRuns++
        generation++
        startRequested = true
        // A start after a deferred stop belongs to a newer run, so the old stop is stale. This
        // also covers a blocked start that never created the service: its stop must not bring
        // down the next service.
        stopPending = false
        return generation
    }

    /** Pairs with [onRunStarted]. True when the ended run was the last one in the process. */
    fun onRunFinished(): Boolean {
        if (activeRuns > 0) activeRuns--
        return activeRuns == 0
    }

    /** `onCreate`, after `startForeground`. The token is reported back by [onDestroyed]. */
    fun onForegroundStarted(): ForegroundStart {
        instanceGeneration = generation
        // A service created after its run already stopped, or with a stop deferred until the
        // foreground existed, must come down at once.
        val stopNow = stopPending || !startRequested
        stopPending = false
        return ForegroundStart(instanceGeneration, stopNow)
    }

    fun onStopRequested(): StopDecision {
        if (!startRequested) return StopDecision.IGNORE
        startRequested = false
        if (instanceGeneration == 0L) {
            stopPending = true
            return StopDecision.DEFER
        }
        instanceGeneration = 0L
        return StopDecision.STOP_NOW
    }

    /**
     * The instance is gone. Only the live instance may clear the foreground state: an older
     * instance's teardown can arrive after the next run already started, and clearing
     * [startRequested] there is what left the notification and wake lock stuck until the next
     * run ended.
     */
    fun onDestroyed(instance: Long) {
        if (instance != instanceGeneration) {
            // The instance already stood down, or a newer one owns the foreground now.
            return
        }
        instanceGeneration = 0L
        stopPending = false
    }
}

