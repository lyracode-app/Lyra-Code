package com.yukisoffd.lyracode.interaction.overlay

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.yukisoffd.lyracode.interaction.session.ManualControlController
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Publishes immutable UI state to the isolated overlay process. */
internal object ManualControlOverlayPublisher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        scope.launch {
            ManualControlController.state.collect { state ->
                if (!state.isActive() && ManualControlForegroundConnection.state.value == ManualControlForegroundState.STOPPED) {
                    return@collect
                }
                val action = if (state.isActive()) {
                    ManualControlOverlayProtocol.ACTION_RENDER
                } else {
                    ManualControlOverlayProtocol.ACTION_STOP_SERVICE
                }
                runCatching {
                    appContext.startService(
                        Intent(appContext, ManualControlForegroundService::class.java)
                            .setAction(action)
                            .putExtra(
                                ManualControlOverlayProtocol.EXTRA_STATE,
                                ManualControlOverlayProtocol.encode(state),
                            )
                            .putExtra(
                                ManualControlOverlayProtocol.EXTRA_SENT_AT_ELAPSED,
                                SystemClock.elapsedRealtime(),
                            ),
                    )
                }.onFailure { error ->
                    Log.w(LOG_TAG, "Unable to publish overlay state", error)
                }
                // StateFlow conflates streaming deltas while keeping the latest stop/approval state.
                delay(80)
            }
        }
    }

    private const val LOG_TAG = "LyraManualControl"
}
