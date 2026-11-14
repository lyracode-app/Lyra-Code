package com.yukisoffd.lyracode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yukisoffd.lyracode.data.UpdateCheckChannel
import com.yukisoffd.lyracode.data.UpdateManager
import com.yukisoffd.lyracode.data.UpdateManifestSource

@Composable
internal fun UpdateManagementScreen(
    onOpenChannels: () -> Unit,
    onOpenManifest: () -> Unit,
    onOpenAcceleration: () -> Unit,
    onOpenPrompts: () -> Unit,
) {
    KimiCardBox {
        KimiMenuRow(
            Icons.Default.Sync,
            uiText(R.string.update_channels_title),
            onClick = onOpenChannels,
        )
        KimiDivider()
        KimiMenuRow(
            Icons.Default.CloudDownload,
            uiText(R.string.menu_update_manifest),
            onClick = onOpenManifest,
        )
        KimiDivider()
        KimiMenuRow(
            Icons.Default.Speed,
            uiText(R.string.github_acceleration_title),
            uiText(R.string.github_acceleration_entry_desc),
            onClick = onOpenAcceleration,
        )
        KimiDivider()
        KimiMenuRow(
            Icons.Default.NotificationsOff,
            uiText(R.string.update_prompt_settings_title),
            onClick = onOpenPrompts,
        )
    }
}

@Composable
internal fun UpdateManifestSettingsScreen(onManifestChanged: () -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { UpdateManager(context) }
    var selected by remember(manager) { mutableStateOf(manager.manifestSource()) }
    Text(uiText(R.string.update_manifest_description), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
    KimiCardBox {
        Column(Modifier.selectableGroup()) {
            UpdateManifestSource.entries.forEachIndexed { index, source ->
                if (index > 0) KimiDivider()
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .selectable(selected = selected == source, role = Role.RadioButton) {
                            if (source != selected) {
                                manager.setManifestSource(source)
                                selected = source
                                onManifestChanged()
                            }
                        }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected == source, onClick = null)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(uiText(source.title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            source.url.ifBlank { uiText(R.string.label_not_configured_or_na) },
                            color = KimiMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun UpdatePromptSettingsScreen() {
    val context = LocalContext.current
    val manager = remember(context) { UpdateManager(context) }
    var disabled by remember(manager) { mutableStateOf(manager.updatePromptDisabled()) }
    val title = uiText(R.string.label_no_update_prompt)
    KimiCardBox {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(uiText(R.string.no_update_prompt_desc), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = disabled,
                onCheckedChange = {
                    disabled = it
                    manager.setUpdatePromptDisabled(it)
                },
                modifier = Modifier.semantics { contentDescription = title },
            )
        }
    }
}

@Composable
internal fun UpdateChannelsScreen(onUpdateAvailabilityChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { UpdateManager(context) }
    var channels by remember(manager) { mutableStateOf(manager.checkChannels()) }

    fun save(order: List<UpdateCheckChannel>) {
        manager.setCheckChannels(order)
        channels = order
        onUpdateAvailabilityChange(manager.hasAvailableUpdate())
    }

    Text(uiText(R.string.update_channels_description), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
    KimiCardBox {
        val ordered = channels + UpdateCheckChannel.entries.filterNot { it in channels }
        ordered.forEachIndexed { index, channel ->
            if (index > 0) KimiDivider()
            val enabled = channel in channels
            val title = uiText(channel.title)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (enabled && channels.size > 1) {
                    val movingUp = index > 0
                    val targetIndex = if (movingUp) index - 1 else index + 1
                    IconButton(onClick = {
                        save(channels.toMutableList().apply { add(targetIndex, removeAt(index)) })
                    }) {
                        Icon(
                            if (movingUp) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                            contentDescription = uiText(
                                if (movingUp) R.string.update_channel_move_up else R.string.update_channel_move_down,
                                title,
                            ),
                        )
                    }
                }
                Switch(
                    checked = enabled,
                    enabled = !enabled || channels.size > 1,
                    onCheckedChange = { checked -> save(if (checked) channels + channel else channels - channel) },
                    modifier = Modifier.semantics { contentDescription = title },
                )
            }
        }
    }
}
