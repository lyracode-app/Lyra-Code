package com.yukisoffd.lyracode.interaction.ui

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yukisoffd.lyracode.KimiCardBox
import com.yukisoffd.lyracode.KimiDivider
import com.yukisoffd.lyracode.KimiMuted
import com.yukisoffd.lyracode.R
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.interaction.DeviceInteractionAvailability
import com.yukisoffd.lyracode.interaction.overlay.OverlayPermission
import com.yukisoffd.lyracode.interaction.perception.ScreenProbeController
import com.yukisoffd.lyracode.interaction.service.AccessibilityConnection
import com.yukisoffd.lyracode.interaction.session.ManualControlController

@Composable
internal fun DeviceInteractionSettings(
    settings: AppSettings,
    onOpenScreenProbe: () -> Unit,
    onOpenManualControl: () -> Unit,
) {
    val context = LocalContext.current
    val supported = remember { DeviceInteractionAvailability.isSupported() }
    var experimentEnabled by remember {
        mutableStateOf(supported && settings.deviceInteractionExperimentalEnabled)
    }
    var permissionRevision by remember { mutableIntStateOf(0) }
    val connected by AccessibilityConnection.connected.collectAsState()

    ObserveAccessibilitySetting(context) { permissionRevision++ }
    ObservePermissionResume { permissionRevision++ }

    val serviceEnabled = remember(permissionRevision, connected) {
        AccessibilityConnection.isEnabledInSystem(context)
    }
    val overlayGranted = remember(permissionRevision) { OverlayPermission.isGranted(context) }
    val statusText = when {
        !supported -> context.getString(R.string.device_interaction_status_unsupported)
        !experimentEnabled -> context.getString(R.string.device_interaction_status_experiment_off)
        connected && serviceEnabled -> context.getString(R.string.device_interaction_status_connected)
        serviceEnabled -> context.getString(R.string.device_interaction_status_enabled_not_connected)
        else -> context.getString(R.string.device_interaction_status_service_off)
    }

    KimiCardBox {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.AccessibilityNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    context.getString(R.string.device_interaction_experiment_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    if (supported) {
                        context.getString(R.string.device_interaction_experiment_desc)
                    } else {
                        context.getString(
                            R.string.device_interaction_android_version_requirement,
                            DeviceInteractionAvailability.MIN_SUPPORTED_SDK,
                        )
                    },
                    color = KimiMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = experimentEnabled,
                enabled = supported,
                onCheckedChange = { enabled ->
                    experimentEnabled = enabled
                    settings.deviceInteractionExperimentalEnabled = enabled
                    if (!enabled) {
                        ScreenProbeController.clear()
                        ManualControlController.stop()
                    }
                },
            )
        }
        KimiDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    context.getString(R.string.device_interaction_accessibility_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(statusText, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedButton(
            onClick = { openAccessibilitySettings(context) },
            modifier = Modifier.fillMaxWidth(),
            enabled = supported && experimentEnabled,
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(context.getString(R.string.device_interaction_open_accessibility_settings))
        }
        OutlinedButton(
            onClick = { openAppDetails(context) },
            modifier = Modifier.fillMaxWidth(),
            enabled = supported && experimentEnabled,
        ) {
            Text(context.getString(R.string.device_interaction_open_app_details))
        }
        Text(
            context.getString(R.string.device_interaction_restricted_settings_notice),
            color = KimiMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        KimiDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Layers, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    context.getString(R.string.device_interaction_overlay_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    context.getString(
                        if (overlayGranted) {
                            R.string.device_interaction_overlay_granted
                        } else {
                            R.string.device_interaction_overlay_required
                        },
                    ),
                    color = KimiMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        OutlinedButton(
            onClick = { OverlayPermission.openSettings(context) },
            modifier = Modifier.fillMaxWidth(),
            enabled = supported && experimentEnabled,
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(context.getString(R.string.device_interaction_open_overlay_settings))
        }
    }

    KimiCardBox {
        Text(
            context.getString(R.string.device_interaction_scope_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            context.getString(R.string.device_interaction_scope_desc),
            color = KimiMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            onClick = onOpenScreenProbe,
            modifier = Modifier.fillMaxWidth(),
            enabled = supported && experimentEnabled && connected && serviceEnabled,
        ) {
            Text(context.getString(R.string.device_interaction_open_screen_probe))
        }
        OutlinedButton(
            onClick = onOpenManualControl,
            modifier = Modifier.fillMaxWidth(),
            enabled = supported && experimentEnabled && connected && serviceEnabled && overlayGranted,
        ) {
            Text(context.getString(R.string.device_interaction_open_manual_control))
        }
    }

    Text(
        context.getString(R.string.device_interaction_privacy_notice),
        color = KimiMuted,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun ObservePermissionResume(onResume: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun ObserveAccessibilitySetting(context: Context, onChanged: () -> Unit) {
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onChanged()
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
}

internal fun openAccessibilitySettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        openAppDetails(context)
    }
}

private fun openAppDetails(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                "package:${context.packageName}".toUri(),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
