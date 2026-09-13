package com.yukisoffd.lyracode.interaction.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class ManualControlForegroundState {
    STOPPED,
    STARTING,
    RUNNING,
}

/** Main-process acknowledgement reported by the isolated foreground service. */
internal object ManualControlForegroundConnection {
    private val _state = MutableStateFlow(ManualControlForegroundState.STOPPED)
    val state: StateFlow<ManualControlForegroundState> = _state.asStateFlow()

    fun markStarting() {
        _state.value = ManualControlForegroundState.STARTING
    }

    fun markRunning() {
        _state.value = ManualControlForegroundState.RUNNING
    }

    fun markStopped() {
        _state.value = ManualControlForegroundState.STOPPED
    }
}
