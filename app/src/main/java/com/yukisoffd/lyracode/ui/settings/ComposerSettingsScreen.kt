package com.yukisoffd.lyracode

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yukisoffd.lyracode.data.AppSettings

/**
 * Settings detail page for mid-run composer delivery. Writes each change to
 * [AppSettings] immediately so the policy can read it at send time.
 */
@Composable
internal fun ComposerSettingsScreen(settings: AppSettings) {
    var enabled by remember { mutableStateOf(settings.composerDeliveryEnabled) }
    ComposerSettingsSection(
        enabled = enabled,
        onEnabledChange = { value ->
            enabled = value
            settings.composerDeliveryEnabled = value
        },
    )
}

/**
 * Stateless Composer section. Kept internal so the instrumentation test can
 * mount it directly with `createComposeRule`.
 */
@Composable
internal fun ComposerSettingsSection(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    KimiCardBox {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Send,
                contentDescription = null,
                modifier = Modifier.width(36.dp).size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.composer_delivery_enabled_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.composer_delivery_enabled_desc),
                    color = KimiMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        if (!enabled) {
            KimiDivider()
            Text(
                stringResource(R.string.composer_delivery_disabled_hint),
                color = KimiMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
