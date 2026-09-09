package com.yukisoffd.lyracode.interaction.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yukisoffd.lyracode.KimiCardBox
import com.yukisoffd.lyracode.KimiMuted
import com.yukisoffd.lyracode.R
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.interaction.overlay.ManualControlForegroundConnection
import com.yukisoffd.lyracode.interaction.service.AccessibilityConnection
import com.yukisoffd.lyracode.interaction.overlay.ManualControlForegroundService
import com.yukisoffd.lyracode.interaction.overlay.ManualControlForegroundState
import com.yukisoffd.lyracode.interaction.overlay.OverlayPermission
import com.yukisoffd.lyracode.interaction.perception.ScreenProbeController
import com.yukisoffd.lyracode.interaction.session.ManualControlController
import kotlinx.coroutines.delay

@Composable
internal fun ManualControlDebugScreen(settings: AppSettings) {
    val context = LocalContext.current
    val state by ManualControlController.state.collectAsState()
    val connected by AccessibilityConnection.connected.collectAsState()
    val foregroundState by ManualControlForegroundConnection.state.collectAsState()
    var nowEpochMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var permissionRevision by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRevision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.activeUntilEpochMillis) {
        while (state.activeUntilEpochMillis != Long.MAX_VALUE && state.activeUntilEpochMillis > nowEpochMillis) {
            delay(1_000L)
            nowEpochMillis = System.currentTimeMillis()
        }
    }

    val active = state.isActive(nowEpochMillis)
    val overlayGranted = remember(permissionRevision) { OverlayPermission.isGranted(context) }
    val notificationGranted = remember(permissionRevision) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
    val canRequestStart = connected && overlayGranted && settings.deviceInteractionExperimentalEnabled
    val startSession = {
        nowEpochMillis = System.currentTimeMillis()
        ScreenProbeController.stop()
        ManualControlController.start(nowEpochMillis)
        val profile = settings.selectedProfile()
        ManualControlController.updateChat(
            com.yukisoffd.lyracode.interaction.session.DeviceChatState(providerLabel = "${profile.name} · ${profile.selectedModel}"),
        )
        if (!ManualControlForegroundService.start(context.applicationContext)) {
            ManualControlController.stop()
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionRevision++
        if (granted) startSession()
    }

    KimiCardBox {
        Text(context.getString(R.string.manual_control_title), style = MaterialTheme.typography.titleSmall)
        Text(
            context.getString(R.string.manual_control_instructions),
            color = KimiMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            when {
                !connected -> context.getString(R.string.manual_control_service_required)
                !overlayGranted -> context.getString(R.string.manual_control_overlay_required)
                !notificationGranted -> context.getString(R.string.manual_control_notification_required)
                active && foregroundState != ManualControlForegroundState.RUNNING ->
                    context.getString(R.string.manual_control_foreground_starting)
                active -> context.getString(
                    R.string.manual_control_active,
                    state.status.name,
                )
                else -> context.getString(R.string.manual_control_idle)
            },
            color = if (active) MaterialTheme.colorScheme.primary else KimiMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationGranted) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        startSession()
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = canRequestStart && !active,
            ) {
                Text(context.getString(R.string.manual_control_start))
            }
            OutlinedButton(
                onClick = ManualControlController::stop,
                modifier = Modifier.weight(1f),
                enabled = active,
            ) {
                Text(context.getString(R.string.manual_control_stop))
            }
        }
        OutlinedButton(onClick = { context.startActivity(android.content.Intent(context,
            com.yukisoffd.lyracode.interaction.pet.DesktopPetSettingsActivity::class.java)) }) { Text(context.getString(R.string.pet_settings_entry)) }
        state.lastResult?.let { result ->
            Text(
                context.getString(R.string.manual_control_last_result, result.status.name),
                color = KimiMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    KimiCardBox {
        Text(
            context.getString(R.string.manual_control_scope_notice),
            color = KimiMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
