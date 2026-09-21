package com.yukisoffd.lyracode

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yukisoffd.lyracode.data.AppSettings

@Composable
internal fun AppIconSettings(settings: AppSettings) {
    val context = LocalContext.current
    val activity = remember(context) { context.appIconActivity() }
    var restarting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(AppIcon.fromId(settings.appIconId)) }
    var active by remember { mutableStateOf(AppIcon.entries.firstOrNull { AppIconManager.isActive(context, it) }) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                active = AppIcon.entries.firstOrNull { AppIconManager.isActive(context, it) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    KimiCardBox {
        Text(stringResource(R.string.app_icon_description), style = MaterialTheme.typography.bodyMedium, color = KimiMuted)
        Column(Modifier.selectableGroup()) {
            AppIcon.entries.forEach { icon ->
                val preview = remember(icon, context) {
                    requireNotNull(context.getDrawable(icon.preview)).toBitmap(192, 192).asImageBitmap()
                }
                Row(
                    Modifier.fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .selectable(selected = selected == icon, role = Role.RadioButton, onClick = {
                            settings.appIconId = icon.id
                            selected = icon
                        })
                        .padding(vertical = 16.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Image(preview, contentDescription = null,
                        modifier = Modifier.size(64.dp).clip(MaterialTheme.shapes.medium))
                    Text(stringResource(icon.title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    RadioButton(selected = selected == icon, onClick = null)
                }
            }
        }
        if (selected != active) {
            Text(stringResource(R.string.app_icon_restart_required), color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = {
                    activity?.let {
                        restarting = true
                        AppIconManager.restart(it)
                    }
                },
                enabled = activity != null && !restarting,
            ) {
                Text(stringResource(R.string.app_icon_restart_now))
            }
        }
    }
}

private tailrec fun Context.appIconActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.appIconActivity()
    else -> null
}
