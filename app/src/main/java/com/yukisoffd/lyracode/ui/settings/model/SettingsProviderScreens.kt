package com.yukisoffd.lyracode

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.yukisoffd.lyracode.data.ApiProfile
import com.yukisoffd.lyracode.data.AppSettings
import java.net.URL



@Composable
internal fun ProviderLogoBadge(profile: ApiProfile, modifier: Modifier = Modifier) {
    AiLogoBadge(
        logoRes = ProviderCatalog.logoRes(profile),
        fallback = profile.name.ifBlank { profile.baseUrl },
        modifier = modifier,
    )
}

@Composable
internal fun ProviderPresetPicker(
    onSelect: (ProviderPreset, ProviderPresetPlan) -> Unit,
    onCustom: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            uiText(R.string.ui_choose_a_preset_to_configure_its_endpoint_and_api),
            color = KimiMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        CustomProviderPresetRow(onClick = onCustom)
        ProviderCatalog.presets.forEach { preset ->
            preset.plans().forEach { plan ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(preset, plan) },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AiLogoBadge(
                            logoRes = preset.logoRes,
                            fallback = preset.displayName(),
                            modifier = Modifier,
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                preset.displayName(),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                buildString {
                                    append(apiFormatShortName(plan.apiFormat))
                                    if (plan.id != ProviderPresetPlan.DEFAULT_ID) {
                                        append(" · ")
                                        append(plan.displayName())
                                    }
                                },
                                color = KimiMuted,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = { runCatching { uriHandler.openUri(preset.websiteUrl) } },
                        ) {
                            Icon(
                                Icons.Default.OpenInNew,
                                contentDescription = uiText(R.string.ui_open_provider_website),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = KimiMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomProviderPresetRow(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(uiText(R.string.ui_custom_provider), style = MaterialTheme.typography.titleMedium)
                Text(
                    uiText(R.string.ui_configure_the_api_format_base_url_and_request_path),
                    color = KimiMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = KimiMuted)
        }
    }
}

@Composable
internal fun ModelProviderRow(
    profile: ApiProfile,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ProviderLogoBadge(profile)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(profile.name.ifBlank { uiText(R.string.label_unnamed_platform) }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (profile.baseUrl.isNotBlank()) {
                    Text(profile.baseUrl, color = KimiMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    uiText(R.string.label_models_count, profile.enabledModels.size),
                    modifier = Modifier
                        .clip(KimiPillShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (!profile.isDeepSeekHarness) IconButton(onClick = onDelete) {
                Icon(Icons.Default.DeleteOutline, contentDescription = uiText(R.string.file_action_delete), tint = KimiMuted)
            }
        }
    }
}

internal fun apiFormatShortName(format: String): String = when (format) {
    ApiProfile.API_FORMAT_ANTHROPIC -> "Anthropic"
    ApiProfile.API_FORMAT_GEMINI -> "Gemini"
    else -> "OpenAI"
}

@Composable
internal fun WebSearchBlacklistSettings(
    settings: AppSettings,
    externalRevision: Int = 0,
    onChanged: () -> Unit,
) {
    var blacklist by rememberSaveable(externalRevision) { mutableStateOf(settings.webSearchBlacklistText) }
    var notice by remember { mutableStateOf("") }
    val blockedCount = remember(blacklist, externalRevision) {
        blacklist.lineSequence()
            .map { raw ->
                val clean = raw.trim().trimEnd('/').trim()
                if (clean.isBlank() || clean.startsWith("#")) "" else {
                    val withoutScheme = clean.substringAfter("://", clean)
                    val authority = withoutScheme
                        .substringBefore('/')
                        .substringBefore('?')
                        .substringBefore('#')
                        .substringAfterLast('@')
                    val hostPart = authority
                        .let { if (it.startsWith("[")) it.substringBefore(']') + "]" else it.substringBefore(':') }
                        .lowercase()
                        .trim('.')
                    val host = hostPart.removePrefix("*.").trim('.')
                    when {
                        host.isBlank() -> ""
                        hostPart.startsWith("*.") && !host.contains('.') -> ""
                        hostPart.startsWith("*.") -> "*.$host"
                        else -> host
                    }
                }
            }
            .filter { it.isNotBlank() }
            .distinct()
            .count()
    }
    KimiCardBox {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(uiText(R.string.title_domain_blacklist), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    uiText(R.string.blacklist_desc),
                    color = KimiMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        KimiDivider()
        OutlinedTextField(
            value = blacklist,
            onValueChange = {
                blacklist = it
                notice = ""
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(uiText(R.string.label_one_domain_per_line)) },
            placeholder = { Text("x.com\nwww.x.com\n*.example.com\nhttps://baijiahao.baidu.com/") },
            minLines = 8,
            maxLines = 14,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
        Text(
            uiText(R.string.blacklist_normalize_hint),
            color = KimiMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    settings.webSearchBlacklistText = blacklist
                    blacklist = settings.webSearchBlacklistText
                    notice = uiText(R.string.blocked_hosts_saved_count, settings.webSearchBlockedHosts().size)
                    onChanged()
                },
                shape = KimiPillShape,
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(uiText(R.string.file_editor_save))
            }
            OutlinedButton(
                onClick = {
                    blacklist = ""
                    settings.webSearchBlacklistText = ""
                    notice = uiText(R.string.notice_blacklist_cleared)
                    onChanged()
                },
                shape = KimiPillShape,
            ) {
                Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(uiText(R.string.action_clear))
            }
        }
        val summary = if (notice.isNotBlank()) notice else uiText(R.string.ui_will_save_1_s_domains, blockedCount)
        Text(summary, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
    }
}

