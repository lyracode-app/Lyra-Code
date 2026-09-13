package com.yukisoffd.lyracode.interaction.service

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object AccessibilityConnection {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun isEnabledInSystem(context: Context): Boolean {
        return runCatching {
            val expected = ComponentName(context, LyraAccessibilityService::class.java)
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()

            enabledServices
                .split(':')
                .asSequence()
                .mapNotNull(ComponentName::unflattenFromString)
                .any { it == expected }
        }.getOrDefault(false)
    }

    fun markConnected() {
        _connected.value = true
    }

    fun markDisconnected() {
        _connected.value = false
    }
}
