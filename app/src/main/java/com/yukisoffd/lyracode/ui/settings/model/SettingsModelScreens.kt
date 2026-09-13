package com.yukisoffd.lyracode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.yukisoffd.lyracode.ai.ModelReachabilityResult
import com.yukisoffd.lyracode.ai.ProviderReachabilityResult
import com.yukisoffd.lyracode.data.ApiProfile
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.MediaGenerationKind
import com.yukisoffd.lyracode.data.OcrModelConfig
import com.yukisoffd.lyracode.data.SubAgentConfig
import com.yukisoffd.lyracode.data.VisionUnderstandingConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.net.URL
import kotlin.math.min
import kotlin.math.max



@Composable
internal fun ProfileSettingsSummary(settings: AppSettings) {
    KimiCardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UserAvatar(settings.userAvatarPath, settings.userNickname.take(1).ifBlank { "L" }, Modifier.size(56.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(settings.userNickname.ifBlank { uiText(R.string.default_user_name) }, style = MaterialTheme.typography.titleMedium)
                Text(uiText(R.string.profile_hint), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun TopicSummaryModelSettings(
    onOpenTopic: () -> Unit,
    onOpenCompression: () -> Unit,
    onOpenVision: () -> Unit,
    onOpenOcr: () -> Unit,
    onOpenMedia: (MediaGenerationKind) -> Unit,
) {
    KimiCardBox {
        KimiMenuRow(
            icon = Icons.Default.Topic,
            title = uiText(R.string.ui_topic_summary_model),
            value = uiText(R.string.ui_generate_short_titles_for_new_conversations),
            onClick = onOpenTopic,
        )
        KimiDivider()
        KimiMenuRow(
            icon = Icons.Default.Compress,
            title = uiText(R.string.label_history_compression_model),
            value = uiText(R.string.ui_choose_the_model_used_for_manual_and_automatic_compression),
            onClick = onOpenCompression,
        )
        KimiDivider()
        KimiMenuRow(
            icon = Icons.Default.Visibility,
            title = uiText(R.string.label_vision_understanding_model),
            value = uiText(R.string.vision_understanding_model_desc),
            onClick = onOpenVision,
        )
        KimiDivider()
        KimiMenuRow(
            icon = Icons.Default.DocumentScanner,
            title = uiText(R.string.label_ocr_model),
            value = uiText(R.string.ocr_model_desc),
            onClick = onOpenOcr,
        )
        MediaGenerationKind.entries.forEach { kind ->
            KimiDivider()
            KimiMenuRow(
                icon = when (kind) {
                    MediaGenerationKind.IMAGE -> Icons.Default.Photo
                    MediaGenerationKind.VIDEO -> Icons.Default.Movie
                    MediaGenerationKind.MUSIC -> Icons.Default.MusicNote
                    MediaGenerationKind.AUDIO -> Icons.Default.GraphicEq
                },
                title = uiText(mediaGenerationTitleRes(kind)),
                value = uiText(mediaGenerationDescriptionRes(kind)),
                onClick = { onOpenMedia(kind) },
            )
        }
    }
}

@Composable
internal fun VisionUnderstandingModelEditor(settings: AppSettings, controller: ChatController) {
    val profiles = controller.profiles.toList()
    val saved = remember(controller.settingsRevision.intValue) { settings.visionUnderstandingConfig() }
    var enabled by remember { mutableStateOf(saved.enabled) }
    var sourceType by remember { mutableStateOf(saved.sourceType) }
    var profileId by remember { mutableStateOf(saved.profileId.ifBlank { settings.selectedProfile().id }) }
    val selectedProfile = profiles.firstOrNull { it.id == profileId } ?: profiles.firstOrNull()
    var model by remember(profileId) {
        mutableStateOf(saved.model.takeIf { profileId == saved.profileId && it.isNotBlank() } ?: selectedProfile?.selectedModel.orEmpty())
    }
    val mcpTools = remember(controller.settingsRevision.intValue) { settings.enabledMcpTools() }
    var mcpServerId by remember { mutableStateOf(saved.mcpServerId) }
    var mcpToolName by remember { mutableStateOf(saved.mcpToolName) }
    val selectedMcp = mcpTools.firstOrNull { (server, tool) -> server.id == mcpServerId && tool.name == mcpToolName }
    var relayPrompt by remember { mutableStateOf(saved.relayPrompt) }
    var notice by remember { mutableStateOf("") }
    val selectionValid = when (sourceType) {
        AppSettings.VISION_SOURCE_MCP -> selectedMcp != null
        else -> selectedProfile != null && model.isNotBlank()
    }
    KimiCardBox {
        Text(uiText(R.string.label_vision_understanding_model), style = MaterialTheme.typography.titleMedium)
        Text(uiText(R.string.vision_understanding_editor_desc), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(uiText(R.string.action_enable_vision_understanding), style = MaterialTheme.typography.titleSmall)
                Text(uiText(R.string.vision_understanding_privacy_hint), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        if (enabled) {
            Text(uiText(R.string.label_vision_source), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MaterialChoiceButton(uiText(R.string.label_model), sourceType == AppSettings.VISION_SOURCE_MODEL) {
                    sourceType = AppSettings.VISION_SOURCE_MODEL
                }
                MaterialChoiceButton(uiText(R.string.label_mcp_source), sourceType == AppSettings.VISION_SOURCE_MCP) {
                    sourceType = AppSettings.VISION_SOURCE_MCP
                }
            }
            if (sourceType == AppSettings.VISION_SOURCE_MODEL) {
                SubAgentDropdownPicker(
                    label = uiText(R.string.label_provider),
                    value = selectedProfile?.name ?: uiText(R.string.label_not_configured_or_na),
                    subtitle = selectedProfile?.selectedModel.orEmpty(),
                    items = profiles,
                    itemTitle = { it.name },
                    itemSubtitle = { it.selectedModel },
                    isSelected = { it.id == profileId },
                    onSelect = { profile -> profileId = profile.id; model = profile.selectedModel },
                )
                SubAgentDropdownPicker(
                    label = uiText(R.string.label_vision_understanding_model),
                    value = model.ifBlank { uiText(R.string.label_not_selected) },
                    items = selectedProfile?.enabledModels.orEmpty(),
                    itemTitle = { it },
                    isSelected = { it == model },
                    onSelect = { model = it },
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(uiText(R.string.label_vision_understanding_model)) },
                    singleLine = true,
                )
            } else {
                SubAgentDropdownPicker(
                    label = uiText(R.string.label_mcp_vision_tool),
                    value = selectedMcp?.let { "${it.first.name} / ${it.second.name}" } ?: uiText(R.string.label_not_selected),
                    subtitle = selectedMcp?.second?.description.orEmpty(),
                    items = mcpTools,
                    itemTitle = { "${it.first.name} / ${it.second.name}" },
                    itemSubtitle = { it.second.description },
                    isSelected = { it.first.id == mcpServerId && it.second.name == mcpToolName },
                    onSelect = { (server, tool) -> mcpServerId = server.id; mcpToolName = tool.name },
                )
                if (mcpTools.isEmpty()) {
                    Text(uiText(R.string.notice_no_enabled_mcp_vision_tools), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedTextField(
                value = relayPrompt,
                onValueChange = { relayPrompt = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
                label = { Text(uiText(R.string.label_vision_relay_prompt)) },
                supportingText = { Text(uiText(R.string.vision_relay_prompt_hint)) },
                minLines = 5,
            )
            TextButton(onClick = { relayPrompt = AppSettings.DEFAULT_VISION_RELAY_PROMPT }) {
                Text(uiText(R.string.action_restore_default_prompt))
            }
        }
        Button(
            enabled = !enabled || selectionValid,
            onClick = {
                settings.saveVisionUnderstandingConfig(
                    VisionUnderstandingConfig(
                        enabled = enabled,
                        sourceType = sourceType,
                        profileId = if (sourceType == AppSettings.VISION_SOURCE_MODEL) selectedProfile?.id.orEmpty() else "",
                        model = if (sourceType == AppSettings.VISION_SOURCE_MODEL) model else "",
                        mcpServerId = if (sourceType == AppSettings.VISION_SOURCE_MCP) mcpServerId else "",
                        mcpToolName = if (sourceType == AppSettings.VISION_SOURCE_MCP) mcpToolName else "",
                        relayPrompt = relayPrompt,
                    ),
                )
                if (!settings.hasVisionSupplementProvider()) settings.visionSupplementEnabled = false
                controller.settingsRevision.intValue++
                notice = uiText(R.string.notice_vision_model_saved)
            },
            shape = KimiPillShape,
        ) { Text(uiText(R.string.file_editor_save)) }
        if (notice.isNotBlank()) Text(notice, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun OcrModelEditor(settings: AppSettings, controller: ChatController) {
    val profiles = controller.profiles.toList()
    val saved = remember(controller.settingsRevision.intValue) { settings.ocrModelConfig() }
    var enabled by remember { mutableStateOf(saved.enabled) }
    var profileId by remember { mutableStateOf(saved.profileId.ifBlank { settings.selectedProfile().id }) }
    val selectedProfile = profiles.firstOrNull { it.id == profileId } ?: profiles.firstOrNull()
    var model by remember(profileId) {
        mutableStateOf(saved.model.takeIf { profileId == saved.profileId && it.isNotBlank() } ?: selectedProfile?.selectedModel.orEmpty())
    }
    var notice by remember { mutableStateOf("") }
    KimiCardBox {
        Text(uiText(R.string.label_ocr_model), style = MaterialTheme.typography.titleMedium)
        Text(uiText(R.string.ocr_model_editor_desc), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(uiText(R.string.action_enable_ocr), style = MaterialTheme.typography.titleSmall)
                Text(uiText(R.string.ocr_no_relay_prompt_hint), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        if (enabled) {
            SubAgentDropdownPicker(
                label = uiText(R.string.label_provider),
                value = selectedProfile?.name ?: uiText(R.string.label_not_configured_or_na),
                subtitle = selectedProfile?.selectedModel.orEmpty(),
                items = profiles,
                itemTitle = { it.name },
                itemSubtitle = { it.selectedModel },
                isSelected = { it.id == profileId },
                onSelect = { profile -> profileId = profile.id; model = profile.selectedModel },
            )
            SubAgentDropdownPicker(
                label = uiText(R.string.label_ocr_model),
                value = model.ifBlank { uiText(R.string.label_not_selected) },
                items = selectedProfile?.enabledModels.orEmpty(),
                itemTitle = { it },
                isSelected = { it == model },
                onSelect = { model = it },
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(uiText(R.string.label_ocr_model)) },
                singleLine = true,
            )
        }
        Button(
            enabled = !enabled || (selectedProfile != null && model.isNotBlank()),
            onClick = {
                settings.saveOcrModelConfig(
                    OcrModelConfig(
                        enabled = enabled,
                        profileId = if (enabled) selectedProfile?.id.orEmpty() else "",
                        model = if (enabled) model else "",
                    ),
                )
                if (!settings.hasVisionSupplementProvider()) settings.visionSupplementEnabled = false
                controller.settingsRevision.intValue++
                notice = uiText(R.string.notice_ocr_model_saved)
            },
            shape = KimiPillShape,
        ) { Text(uiText(R.string.file_editor_save)) }
        if (notice.isNotBlank()) Text(notice, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun TopicSummaryModelEditor(settings: AppSettings, controller: ChatController) {
    val profiles = controller.profiles.toList()
    var profileId by remember { mutableStateOf(settings.topicSummaryProfile().id) }
    val selectedProfile = profiles.firstOrNull { it.id == profileId } ?: profiles.firstOrNull()
    var model by remember(profileId) {
        mutableStateOf(
            settings.topicSummaryModel.takeIf { profileId == settings.topicSummaryProfileId && it.isNotBlank() }
                ?: selectedProfile?.selectedModel.orEmpty(),
        )
    }
    var notice by remember { mutableStateOf("") }
    KimiCardBox {
        Text(uiText(R.string.ui_independent_topic_summary_model), style = MaterialTheme.typography.titleMedium)
        Text(
            uiText(R.string.ui_after_the_first_message_in_each_new_conversation_this),
            color = KimiMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        SubAgentDropdownPicker(
            label = uiText(R.string.detail_model),
            value = selectedProfile?.name ?: uiText(R.string.label_not_configured_or_na),
            subtitle = selectedProfile?.selectedModel.orEmpty(),
            items = profiles,
            itemTitle = { it.name },
            itemSubtitle = { it.selectedModel },
            isSelected = { it.id == profileId },
            onSelect = { profile ->
                profileId = profile.id
                model = profile.selectedModel
            },
        )
        SubAgentDropdownPicker(
            label = uiText(R.string.ui_topic_summary_model),
            value = model.ifBlank { uiText(R.string.label_not_selected) },
            items = selectedProfile?.enabledModels.orEmpty(),
            itemTitle = { it },
            isSelected = { it == model },
            onSelect = { model = it },
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(uiText(R.string.ui_topic_summary_model)) },
            singleLine = true,
        )
        Button(
            enabled = selectedProfile != null && model.isNotBlank(),
            onClick = {
                settings.topicSummaryProfileId = selectedProfile?.id.orEmpty()
                settings.topicSummaryModel = model
                controller.settingsRevision.intValue++
                notice = uiText(R.string.ui_topic_summary_model_saved)
            },
            shape = KimiPillShape,
        ) { Text(uiText(R.string.file_editor_save)) }
        if (notice.isNotBlank()) Text(notice, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun HistoryCompressionModelEditor(settings: AppSettings, controller: ChatController) {
    val profiles = controller.profiles.toList()
    var compressionNotice by remember { mutableStateOf("") }
    var compressionEnabled by remember {
        mutableStateOf(settings.historyCompressionProfileId.isNotBlank() && settings.historyCompressionModel.isNotBlank())
    }
    var compressionProfileId by remember {
        mutableStateOf(settings.historyCompressionProfileId.ifBlank { settings.selectedProfile().id })
    }
    val compressionProfile = profiles.firstOrNull { it.id == compressionProfileId } ?: profiles.firstOrNull()
    var compressionModel by remember(compressionProfileId) {
        mutableStateOf(
            settings.historyCompressionModel.takeIf {
                compressionProfileId == settings.historyCompressionProfileId && it.isNotBlank()
            } ?: compressionProfile?.selectedModel.orEmpty(),
        )
    }
    KimiCardBox {
        Text(uiText(R.string.label_history_compression_model), style = MaterialTheme.typography.titleMedium)
        Text(
            uiText(R.string.ui_used_to_segment_conversation_history_for_manual_or_automatic),
            color = KimiMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(uiText(R.string.ui_use_a_separate_compression_model), style = MaterialTheme.typography.titleSmall)
                Text(
                    if (compressionEnabled) uiText(R.string.skill_status_enabled) else uiText(R.string.ui_use_current_conversation_model),
                    color = KimiMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = compressionEnabled, onCheckedChange = { compressionEnabled = it })
        }
        if (compressionEnabled) {
            SubAgentDropdownPicker(
                label = uiText(R.string.detail_model),
                value = compressionProfile?.name ?: uiText(R.string.label_not_configured_or_na),
                subtitle = compressionProfile?.selectedModel.orEmpty(),
                items = profiles,
                itemTitle = { it.name },
                itemSubtitle = { it.selectedModel },
                isSelected = { it.id == compressionProfileId },
                onSelect = { profile ->
                    compressionProfileId = profile.id
                    compressionModel = profile.selectedModel
                },
            )
            SubAgentDropdownPicker(
                label = uiText(R.string.ui_history_compression_model),
                value = compressionModel.ifBlank { uiText(R.string.label_not_selected) },
                items = compressionProfile?.enabledModels.orEmpty(),
                itemTitle = { it },
                isSelected = { it == compressionModel },
                onSelect = { compressionModel = it },
            )
            OutlinedTextField(
                value = compressionModel,
                onValueChange = { compressionModel = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(uiText(R.string.ui_history_compression_model)) },
                singleLine = true,
            )
        }
        Button(
            enabled = !compressionEnabled || (compressionProfile != null && compressionModel.isNotBlank()),
            onClick = {
                settings.historyCompressionProfileId = if (compressionEnabled) compressionProfile?.id.orEmpty() else ""
                settings.historyCompressionModel = if (compressionEnabled) compressionModel else ""
                controller.settingsRevision.intValue++
                compressionNotice = uiText(R.string.ui_additional_feature_models_saved)
            },
            shape = KimiPillShape,
        ) { Text(uiText(R.string.file_editor_save)) }
        if (compressionNotice.isNotBlank()) Text(compressionNotice, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun MediaGenerationModelEditor(
    settings: AppSettings,
    controller: ChatController,
    kind: MediaGenerationKind,
) {
    val profiles = controller.profiles.toList()
    val saved = remember(kind, controller.settingsRevision.intValue) { settings.mediaGenerationModel(kind) }
    var enabled by remember(kind) { mutableStateOf(saved.profileId.isNotBlank() && saved.model.isNotBlank()) }
    var profileId by remember(kind) {
        mutableStateOf(saved.profileId.ifBlank { settings.selectedProfile().id })
    }
    val selectedProfile = profiles.firstOrNull { it.id == profileId } ?: profiles.firstOrNull()
    var model by remember(kind, profileId) {
        mutableStateOf(
            saved.model.takeIf { profileId == saved.profileId && it.isNotBlank() }
                ?: selectedProfile?.selectedModel.orEmpty(),
        )
    }
    var notice by remember(kind) { mutableStateOf("") }
    KimiCardBox {
        Text(uiText(mediaGenerationTitleRes(kind)), style = MaterialTheme.typography.titleMedium)
        Text(
            uiText(R.string.ui_media_generation_isolation_desc),
            color = KimiMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(uiText(R.string.ui_use_media_generation_model), style = MaterialTheme.typography.titleSmall)
                Text(
                    uiText(mediaGenerationDescriptionRes(kind)),
                    color = KimiMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        if (enabled) {
            SubAgentDropdownPicker(
                label = uiText(R.string.detail_model),
                value = selectedProfile?.name ?: uiText(R.string.label_not_configured_or_na),
                subtitle = selectedProfile?.selectedModel.orEmpty(),
                items = profiles,
                itemTitle = { it.name },
                itemSubtitle = { it.selectedModel },
                isSelected = { it.id == profileId },
                onSelect = { profile ->
                    profileId = profile.id
                    model = profile.selectedModel
                },
            )
            SubAgentDropdownPicker(
                label = uiText(mediaGenerationTitleRes(kind)),
                value = model.ifBlank { uiText(R.string.label_not_selected) },
                items = selectedProfile?.enabledModels.orEmpty(),
                itemTitle = { it },
                isSelected = { it == model },
                onSelect = { model = it },
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(uiText(mediaGenerationTitleRes(kind))) },
                singleLine = true,
            )
        }
        Button(
            enabled = !enabled || (selectedProfile != null && model.isNotBlank()),
            onClick = {
                settings.saveMediaGenerationModel(
                    kind = kind,
                    profileId = if (enabled) selectedProfile?.id.orEmpty() else "",
                    model = if (enabled) model else "",
                )
                controller.settingsRevision.intValue++
                notice = uiText(R.string.ui_media_generation_model_saved)
            },
            shape = KimiPillShape,
        ) { Text(uiText(R.string.file_editor_save)) }
        if (notice.isNotBlank()) Text(notice, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
    }
}

internal fun mediaGenerationTitleRes(kind: MediaGenerationKind): Int = when (kind) {
    MediaGenerationKind.IMAGE -> R.string.ui_image_generation_model
    MediaGenerationKind.VIDEO -> R.string.ui_video_generation_model
    MediaGenerationKind.MUSIC -> R.string.ui_music_generation_model
    MediaGenerationKind.AUDIO -> R.string.ui_audio_generation_model
}

internal fun mediaGenerationDescriptionRes(kind: MediaGenerationKind): Int = when (kind) {
    MediaGenerationKind.IMAGE -> R.string.ui_image_generation_model_desc
    MediaGenerationKind.VIDEO -> R.string.ui_video_generation_model_desc
    MediaGenerationKind.MUSIC -> R.string.ui_music_generation_model_desc
    MediaGenerationKind.AUDIO -> R.string.ui_audio_generation_model_desc
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelServiceSettings(
    settings: AppSettings,
    controller: ChatController,
    predictiveBackEnabled: Boolean = false,
    externalBackRequest: Int = 0,
    onBack: () -> Unit = {},
    onNestedPageChanged: (Boolean, String) -> Unit = { _, _ -> },
) {
    var profiles by remember { mutableStateOf(controller.profiles.toList()) }
    var editingProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var draftNewProfile by remember { mutableStateOf<ApiProfile?>(null) }
    var draftPresetId by rememberSaveable { mutableStateOf<String?>(null) }
    var showProviderPicker by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showReachabilityPage by rememberSaveable { mutableStateOf(false) }
    var showRequestCustomization by rememberSaveable { mutableStateOf(false) }
    var skipNextModelTransition by remember { mutableStateOf(false) }
    val serviceListScrollState = rememberScrollState()
    val providerPickerScrollState = rememberScrollState()
    val editorScrollState = rememberScrollState()
    val reachabilityScrollState = rememberScrollState()
    LaunchedEffect(controller.activeProfileId.value, controller.profiles.size, controller.settingsRevision.intValue) {
        val refreshed = controller.profiles.toList()
        profiles = refreshed
        if (editingProfileId != null && refreshed.none { it.id == editingProfileId } && draftNewProfile?.id != editingProfileId) {
            editingProfileId = null
            showReachabilityPage = false
        }
    }
    fun navigateBackWithinModel() {
        if (showRequestCustomization) {
            showRequestCustomization = false
        } else if (showReachabilityPage) {
            showReachabilityPage = false
        } else if (editingProfileId != null) {
            val isUnsavedNewProfile = draftNewProfile?.id == editingProfileId
            draftNewProfile = null
            draftPresetId = null
            editingProfileId = null
            showProviderPicker = isUnsavedNewProfile
        } else if (showProviderPicker) {
            showProviderPicker = false
        } else {
            draftNewProfile = null
            draftPresetId = null
        }
    }
    val modelNestedPageActive = editingProfileId != null || showProviderPicker
    val modelPage = when {
        showRequestCustomization -> 4
        showReachabilityPage -> 3
        editingProfileId != null -> 2
        showProviderPicker -> 1
        else -> 0
    }
    val predictiveBackState = rememberPredictiveBackGestureState(
        enabled = predictiveBackEnabled && modelNestedPageActive,
        onBack = {
            skipNextModelTransition = true
            navigateBackWithinModel()
        },
    )
    BackHandler(enabled = !predictiveBackEnabled && modelNestedPageActive) {
        navigateBackWithinModel()
    }
    LaunchedEffect(externalBackRequest) {
        if (externalBackRequest > 0 && (editingProfileId != null || showProviderPicker)) {
            navigateBackWithinModel()
        }
    }
    val editingIndex = profiles.indexOfFirst { it.id == editingProfileId }
    val current = if (draftNewProfile?.id == editingProfileId) draftNewProfile else profiles.getOrNull(editingIndex)
    val predictiveTargetPage = when (modelPage) {
        4 -> 2
        3 -> 2
        2 -> if (draftNewProfile?.id == editingProfileId) 1 else 0
        else -> 0
    }
    LaunchedEffect(modelPage) {
        skipNextModelTransition = false
    }
    var platformMenuExpanded by remember { mutableStateOf(false) }
    val editKey = editingProfileId ?: "none"
    LaunchedEffect(editKey) {
        if (editingProfileId != null) {
            editorScrollState.scrollTo(0)
            reachabilityScrollState.scrollTo(0)
        }
    }
    var requestOverrides by remember(editKey) { mutableStateOf(current?.modelRequestOverrides.orEmpty()) }
    var name by remember(editKey) { mutableStateOf(current?.name.orEmpty()) }
    var key by remember(editKey) { mutableStateOf(current?.apiKey.orEmpty()) }
    var baseUrl by remember(editKey) { mutableStateOf(current?.baseUrl.orEmpty()) }
    var apiFormat by remember(editKey) { mutableStateOf(current?.apiFormat ?: ApiProfile.API_FORMAT_OPENAI) }
    var chatPath by remember(editKey) { mutableStateOf(current?.chatPath ?: ApiProfile.defaultChatPath(apiFormat)) }
    var model by remember(editKey) { mutableStateOf(current?.selectedModel.orEmpty()) }
    var savedModels by remember(editKey) { mutableStateOf(current?.savedModels.orEmpty().joinToString("\n")) }
    var enabledModels by remember(editKey) { mutableStateOf(current?.enabledModels.orEmpty().toSet()) }
    var showEnabledModelsDialog by remember(editKey) { mutableStateOf(false) }
    var useResponsesApi by remember(editKey) { mutableStateOf(current?.useResponsesApi == true) }
    var advancedExpanded by remember(editKey) { mutableStateOf(draftNewProfile == null || draftPresetId == null) }
    var selectedReachabilityModels by remember(editKey) { mutableStateOf<Set<String>>(emptySet()) }
    var providerReachabilityResult by remember(editKey) { mutableStateOf<ProviderReachabilityResult?>(null) }
    var modelReachabilityResults by remember(editKey) { mutableStateOf<List<ModelReachabilityResult>>(emptyList()) }
    var reachabilityChecking by remember(editKey) { mutableStateOf(false) }
    var activeReachabilityModel by remember(editKey) { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ApiProfile?>(null) }
    val uriHandler = LocalUriHandler.current
    val matchedPreset = ProviderCatalog.byId(current?.presetId ?: draftPresetId)
    val matchedPlan = matchedPreset?.resolvePlan(current?.presetPlanId, current?.baseUrl.orEmpty())
    val nestedTitle = when {
        showRequestCustomization -> uiText(R.string.request_custom_title)
        showProviderPicker -> uiText(R.string.label_choose_provider)
        showReachabilityPage -> uiText(R.string.ui_reachability_check)
        editingProfileId != null -> current?.name?.ifBlank { uiText(R.string.title_new_model_service) } ?: uiText(R.string.title_new_model_service)
        else -> uiText(R.string.detail_model)
    }
    LaunchedEffect(editingProfileId, showProviderPicker, showReachabilityPage, nestedTitle) {
        onNestedPageChanged(editingProfileId != null || showProviderPicker, nestedTitle)
    }
    val reachabilityModels = remember(savedModels, model) {
        (savedModels.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList() + model.trim())
            .filter { it.isNotBlank() }
            .distinct()
    }
    LaunchedEffect(editKey, reachabilityModels.joinToString("\u0000"), model) {
        val available = reachabilityModels.toSet()
        val retained = selectedReachabilityModels.intersect(available)
        selectedReachabilityModels = if (retained.isNotEmpty()) {
            retained
        } else {
            val preferred = model.trim().takeIf { it in available } ?: reachabilityModels.firstOrNull()
            preferred?.let { setOf(it) } ?: emptySet()
        }
    }
    fun draftProfile(selectedModelOverride: String? = null, savedModelsOverride: List<String>? = null): ApiProfile {
        val models = savedModelsOverride ?: savedModels.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList().distinct()
        val enabled = enabledModels.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val selected = selectedModelOverride
            ?.takeIf { it in enabled }
            ?: model.takeIf { it in enabled }
            ?: enabled.firstOrNull().orEmpty()
        return ApiProfile(
            id = current?.id ?: AppSettings.newId(),
            presetId = current?.presetId.orEmpty(),
            presetPlanId = current?.presetPlanId.orEmpty(),
            name = name.ifBlank { uiText(R.string.label_unnamed_platform) },
            apiKey = key,
            baseUrl = baseUrl.ifBlank { defaultBaseUrlForApiFormat(apiFormat) },
            chatPath = ApiProfile.normalizedChatPath(apiFormat, chatPath),
            apiFormat = apiFormat,
            selectedModel = selected,
            savedModels = models.filter { it.isNotBlank() }.distinct(),
            enabledModels = enabled,
            useResponsesApi = apiFormat == ApiProfile.API_FORMAT_OPENAI && useResponsesApi,
            modelRequestOverrides = requestOverrides,
        )
    }
    fun saveCurrentProfile() {
        val updated = draftProfile()
        val updatedProfiles = if (editingIndex >= 0) {
            profiles.mapIndexed { index, item -> if (index == editingIndex) updated else item }
        } else {
            profiles + updated
        }
        profiles = updatedProfiles
        draftNewProfile = null
        draftPresetId = null
        controller.saveProfiles(updatedProfiles, updated.id)
        editingProfileId = updated.id
        status = ""
        notice = uiText(R.string.notice_model_service_saved)
    }
    fun startReachabilityCheck(targetModels: List<String>) {
        val targets = targetModels.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) {
            status = uiText(R.string.ui_select_at_least_one_model_to_check)
            return
        }
        val draft = draftProfile(savedModelsOverride = targets)
        providerReachabilityResult = null
        modelReachabilityResults = emptyList()
        activeReachabilityModel = ""
        reachabilityChecking = true
        status = uiText(R.string.ui_checking_model_reachability)
        controller.checkReachabilityForProfileIncremental(
            profile = draft,
            models = targets,
            onProviderResult = { result -> providerReachabilityResult = result },
            onModelChecking = { model -> activeReachabilityModel = model },
            onModelResult = { result ->
                modelReachabilityResults = modelReachabilityResults.filterNot { it.model == result.model } + result
            },
            onDone = { result ->
                reachabilityChecking = false
                activeReachabilityModel = ""
                result.fold(
                    onSuccess = {
                        status = ""
                        notice = uiText(R.string.ui_model_reachability_check_complete)
                    },
                    onFailure = { error ->
                        status = error.message.orEmpty().ifBlank { uiText(R.string.ui_model_reachability_check_failed) }
                    },
                )
            },
        )
    }
    deleteTarget?.let { target ->
        ConfirmDeleteDialog(
            title = uiText(R.string.title_delete_model_service),
            message = uiText(R.string.confirm_delete_model_service),
            targetName = target.name.ifBlank { target.baseUrl },
            onDismiss = { deleteTarget = null },
            onConfirm = {
                val remaining = profiles.filterNot { it.id == target.id }
                if (remaining.isNotEmpty()) {
                    profiles = remaining
                    editingProfileId = null
                    controller.saveProfiles(remaining, remaining.first().id)
                }
                status = ""
                notice = uiText(R.string.ui_deleted) + target.name.ifBlank { uiText(R.string.detail_model) }
            },
        )
    }
    if (showEnabledModelsDialog) {
        val selectableModels = remember(savedModels, enabledModels, model) {
            (savedModels.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList() + enabledModels + model.trim())
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
        }
        EnabledModelsDialog(
            models = selectableModels,
            enabledModels = enabledModels,
            onEnabledModelsChange = { enabledModels = it },
            onDismiss = { showEnabledModelsDialog = false },
            onConfirm = {
                if (enabledModels.isEmpty()) {
                    status = uiText(R.string.enable_at_least_one_model)
                } else {
                    showEnabledModelsDialog = false
                    if (model !in enabledModels) model = enabledModels.first()
                    saveCurrentProfile()
                }
            },
        )
    }

    val pageTitles = remember { mutableMapOf<Int, String>() }
    if (current != null) pageTitles[2] = current.name.ifBlank { uiText(R.string.title_new_model_service) }
    val editorTitle = pageTitles[2] ?: uiText(R.string.title_new_model_service)
    val renderModelPage: @Composable (Int) -> Unit = { page ->
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            TopAppBar(
                expandedHeight = 56.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.primary),
                navigationIcon = {
                    IconButton(onClick = { if (page == 0) onBack() else navigateBackWithinModel() }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Default.ArrowBack, contentDescription = uiText(R.string.cd_back))
                    }
                },
                title = { Text(when (page) {
                    1 -> uiText(R.string.label_choose_provider)
                    2 -> editorTitle
                    3 -> uiText(R.string.ui_reachability_check)
                    4 -> uiText(R.string.request_custom_title)
                    else -> uiText(R.string.detail_model)
                }, style = MaterialTheme.typography.titleLarge) },
                actions = {
                    if (page == 2) TextButton(onClick = { showRequestCustomization = true }) { Text(uiText(R.string.request_custom)) }
                },
            )
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(18.dp),
        ) {
        if (page == 0) {
            val filtered = remember(profiles, query) {
                val q = query.trim()
                if (q.isBlank()) profiles else profiles.filter {
                    it.name.contains(q, ignoreCase = true) ||
                        it.baseUrl.contains(q, ignoreCase = true) ||
                        it.selectedModel.contains(q, ignoreCase = true)
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(serviceListScrollState),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CapsuleTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        placeholder = uiText(R.string.search_models_placeholder),
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) },
                    )
                    IconButton(
                        onClick = {
                            showProviderPicker = true
                        },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = uiText(R.string.action_add_model_service),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                if (filtered.isEmpty()) {
                    KimiCardBox {
                        Text(uiText(R.string.notice_no_matching_models), color = KimiMuted)
                    }
                } else {
                    filtered.forEach { profile ->
                        ModelProviderRow(
                            profile = profile,
                            onClick = { editingProfileId = profile.id },
                            onDelete = { if (profiles.size > 1) deleteTarget = profile else notice = uiText(R.string.notice_keep_one_service) },
                        )
                    }
                }
            }
        } else if (page == 1) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(providerPickerScrollState),
            ) {
                ProviderPresetPicker(
                    onSelect = { preset, plan ->
                        val newProfile = preset.createProfile(AppSettings.newId(), plan.id)
                        draftPresetId = preset.id
                        draftNewProfile = newProfile
                        showProviderPicker = false
                        editingProfileId = newProfile.id
                    },
                    onCustom = {
                        val newProfile = ApiProfile(
                            id = AppSettings.newId(),
                            name = uiText(R.string.ui_new_provider),
                            apiKey = "",
                            baseUrl = "https://api.openai.com/v1",
                            chatPath = ApiProfile.DEFAULT_OPENAI_CHAT_PATH,
                            apiFormat = ApiProfile.API_FORMAT_OPENAI,
                            selectedModel = "gpt-4o-mini",
                            savedModels = listOf("gpt-4o-mini"),
                        )
                        draftPresetId = null
                        draftNewProfile = newProfile
                        showProviderPicker = false
                        editingProfileId = newProfile.id
                    },
                )
            }
        } else if (page == 4) {
            ModelRequestSettings(draftProfile()) { selectedModel, customization ->
                requestOverrides = if (customization == null) requestOverrides - selectedModel else requestOverrides + (selectedModel to customization)
                if (editingIndex >= 0) {
                    val updated = profiles[editingIndex].copy(modelRequestOverrides = requestOverrides)
                    profiles = profiles.mapIndexed { index, item -> if (index == editingIndex) updated else item }
                    controller.saveProfiles(profiles, controller.activeProfileId.value)
                }
            }
        } else if (page == 3) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(reachabilityScrollState),
            ) {
                ReachabilitySelectionPage(
                    providerName = current?.name?.ifBlank { uiText(R.string.detail_model) } ?: uiText(R.string.detail_model),
                    models = reachabilityModels,
                    selectedModels = selectedReachabilityModels,
                    checking = reachabilityChecking,
                    provider = providerReachabilityResult,
                    modelResults = modelReachabilityResults,
                    activeModel = activeReachabilityModel,
                    status = status,
                    onSelectedModelsChange = { selectedReachabilityModels = it },
                    onStartCheck = { startReachabilityCheck(reachabilityModels.filter { it in selectedReachabilityModels }) },
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(editorScrollState),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                KimiCardBox {
                    matchedPreset?.let { preset ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AiLogoBadge(
                                logoRes = preset.logoRes,
                                fallback = preset.displayName(),
                                modifier = Modifier.size(52.dp),
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    preset.displayName(),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    if (matchedPlan?.id != null && matchedPlan.id != ProviderPresetPlan.DEFAULT_ID) {
                                        "${apiFormatShortName(apiFormat)} · ${matchedPlan.displayName()}"
                                    } else {
                                        apiFormatShortName(apiFormat)
                                    },
                                    color = KimiMuted,
                                    style = MaterialTheme.typography.bodySmall,
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
                        }
                    }
                    OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_service_name)) }, singleLine = true)
                    OutlinedTextField(value = key, onValueChange = { key = it }, modifier = Modifier.fillMaxWidth(), label = { Text(apiKeyLabel(apiFormat)) }, supportingText = { Text(uiText(R.string.api_key_optional_hint)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { advancedExpanded = !advancedExpanded },
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(19.dp))
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(uiText(R.string.ui_advanced), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    uiText(R.string.ui_endpoint_and_api_format_can_be_changed_at_any),
                                    color = KimiMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Icon(
                                if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible = advancedExpanded,
                        enter = expandVertically(animationSpec = tween(220)) + fadeIn(animationSpec = tween(180)),
                        exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(animationSpec = tween(130)),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(uiText(R.string.label_api_format), style = MaterialTheme.typography.titleSmall)
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                ApiFormatOption("OpenAI SDK", ApiProfile.API_FORMAT_OPENAI, apiFormat) {
                                    apiFormat = it
                                    if (draftNewProfile?.id == editingProfileId) {
                                        if (baseUrl.isBlank() || baseUrl in knownProviderBaseUrls()) baseUrl = defaultBaseUrlForApiFormat(it)
                                        if (chatPath.isBlank() || chatPath in knownProviderChatPaths()) chatPath = ApiProfile.defaultChatPath(it)
                                    }
                                }
                                ApiFormatOption("Anthropic Messages", ApiProfile.API_FORMAT_ANTHROPIC, apiFormat) {
                                    apiFormat = it
                                    useResponsesApi = false
                                    if (draftNewProfile?.id == editingProfileId) {
                                        if (baseUrl.isBlank() || baseUrl in knownProviderBaseUrls()) baseUrl = defaultBaseUrlForApiFormat(it)
                                        if (chatPath.isBlank() || chatPath in knownProviderChatPaths()) chatPath = ApiProfile.defaultChatPath(it)
                                    }
                                }
                                ApiFormatOption("Gemini GenerateContent", ApiProfile.API_FORMAT_GEMINI, apiFormat) {
                                    apiFormat = it
                                    useResponsesApi = false
                                    if (draftNewProfile?.id == editingProfileId) {
                                        if (baseUrl.isBlank() || baseUrl in knownProviderBaseUrls()) baseUrl = defaultBaseUrlForApiFormat(it)
                                        if (chatPath.isBlank() || chatPath in knownProviderChatPaths()) chatPath = ApiProfile.defaultChatPath(it)
                                    }
                                }
                            }
                            Text(
                                apiFormatDescription(apiFormat),
                                color = KimiMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    uiText(R.string.responses_api_enabled),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Switch(
                                    checked = useResponsesApi && apiFormat == ApiProfile.API_FORMAT_OPENAI,
                                    enabled = apiFormat == ApiProfile.API_FORMAT_OPENAI,
                                    onCheckedChange = { useResponsesApi = it },
                                )
                            }
                            OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_base_url)) }, singleLine = true)
                            OutlinedTextField(
                                value = chatPath,
                                onValueChange = { chatPath = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(uiText(R.string.ui_request_path)) },
                                placeholder = { Text(ApiProfile.defaultChatPath(apiFormat)) },
                                singleLine = true,
                            )
                            Text(
                                uiText(R.string.ui_for_providers_that_use_a_non_default_request_path),
                                color = KimiMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (baseUrl.trim().startsWith("http://", ignoreCase = true)) {
                                Text(
                                    uiText(R.string.notice_http_warning),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Text(
                                endpointHint(apiFormat, baseUrl, chatPath, useResponsesApi),
                                color = KimiMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    OutlinedTextField(value = model, onValueChange = { model = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_default_model)) }, singleLine = true)
                    OutlinedTextField(value = savedModels, onValueChange = { savedModels = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.detected_models_one_per_line)) }, minLines = 3)
                    OutlinedButton(
                        enabled = savedModels.isNotBlank() || enabledModels.isNotEmpty(),
                        onClick = { showEnabledModelsDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = KimiPillShape,
                    ) {
                        Icon(Icons.Default.Checklist, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(uiText(R.string.ui_manage_enabled_models_1_s, enabledModels.size), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        enabled = reachabilityModels.isNotEmpty(),
                        onClick = { showReachabilityPage = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = KimiPillShape,
                    ) {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(uiText(R.string.ui_select_models_and_check_reachability), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(enabled = enabledModels.isNotEmpty(), onClick = { saveCurrentProfile() }, shape = KimiPillShape) { Text(uiText(R.string.file_editor_save)) }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val draft = draftProfile()
                                status = uiText(R.string.ui_fetching_models)
                                controller.fetchModelsForProfile(draft) { result ->
                                    result.fold(
                                        onSuccess = { models ->
                                            val distinct = models.filter { it.isNotBlank() }.distinct()
                                            if (distinct.isEmpty()) {
                                                status = uiText(R.string.notice_no_models_fetched)
                                            } else {
                                                savedModels = distinct.joinToString("\n")
                                                val updated = draftProfile(savedModelsOverride = distinct)
                                                val updatedProfiles = if (editingIndex >= 0) {
                                                    profiles.mapIndexed { index, item -> if (index == editingIndex) updated else item }
                                                } else {
                                                    profiles + updated
                                                }
                                                profiles = updatedProfiles
                                                draftNewProfile = null
                                                controller.saveProfiles(updatedProfiles, updated.id)
                                                status = ""
                                                notice = uiText(R.string.ui_fetched_1_s_models_existing_enabled_selections_were_preserved, distinct.size)
                                                showEnabledModelsDialog = true
                                            }
                                        },
                                        onFailure = { status = it.message.orEmpty().ifBlank { uiText(R.string.notice_fetch_failed) } },
                                    )
                                }
                            },
                            shape = KimiPillShape,
                        ) { Text(uiText(R.string.refresh_model_list), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        IconButton(
                            enabled = profiles.size > 1,
                            onClick = { current?.let { deleteTarget = it } },
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = uiText(R.string.cd_delete_platform))
                        }
                    }
                }
                if (status.isNotBlank()) Text(status, color = KimiMuted)
            }
        }
        }
    }

    }

    Box(Modifier.fillMaxSize()) {
        if (predictiveBackState.isInProgress) {
            Box(Modifier.fillMaxSize()) {
                renderModelPage(predictiveTargetPage)
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Color.Black.copy(
                                alpha = 0.22f * (1f - predictiveBackState.progress),
                            ),
                        ),
                )
            }
        }
        AnimatedContent(
            targetState = modelPage,
            modifier = Modifier
                .fillMaxSize()
                .predictiveBackTransform(predictiveBackState),
            transitionSpec = {
                if (skipNextModelTransition) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    val forward = targetState > initialState
                    slideInHorizontally(animationSpec = tween(260)) { if (forward) it else -it / 3 }
                        .togetherWith(slideOutHorizontally(animationSpec = tween(260)) { if (forward) -it / 3 else it })
                }
            },
            label = "model-service-page",
        ) { page ->
            renderModelPage(page)
        }
        ScreenCenterNotice(
            message = notice,
            onDismiss = { notice = "" },
        )
    }
}


@Composable
internal fun EnabledModelsDialog(
    models: List<String>,
    enabledModels: Set<String>,
    onEnabledModelsChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(models, query) {
        val keyword = query.trim()
        if (keyword.isBlank()) models else models.filter { it.contains(keyword, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(uiText(R.string.choose_enabled_models)) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    uiText(R.string.enabled_models_description),
                    color = KimiMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(uiText(R.string.search_models)) },
                    singleLine = true,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { onEnabledModelsChange(models.toSet()) }) { Text(uiText(R.string.ui_select_all)) }
                    TextButton(onClick = { onEnabledModelsChange(emptySet()) }) { Text(uiText(R.string.ui_clear_selection)) }
                }
                if (filtered.isEmpty()) {
                    Text(uiText(R.string.no_matching_models), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(filtered, key = { it }) { modelName ->
                            val checked = modelName in enabledModels
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        onEnabledModelsChange(if (checked) enabledModels - modelName else enabledModels + modelName)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        onEnabledModelsChange(if (checked) enabledModels - modelName else enabledModels + modelName)
                                    },
                                )
                                Text(modelName, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Text(
                    uiText(R.string.enabled_models_count, enabledModels.size),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(enabled = enabledModels.isNotEmpty(), onClick = onConfirm, shape = KimiPillShape) {
                Text(uiText(R.string.save_enabled_models))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiText(R.string.action_cancel)) } },
    )
}


@Composable
internal fun ReachabilitySelectionPage(
    providerName: String,
    models: List<String>,
    selectedModels: Set<String>,
    checking: Boolean,
    provider: ProviderReachabilityResult?,
    modelResults: List<ModelReachabilityResult>,
    activeModel: String,
    status: String,
    onSelectedModelsChange: (Set<String>) -> Unit,
    onStartCheck: () -> Unit,
) {
    val resultByModel = remember(modelResults) { modelResults.associateBy { it.model } }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(providerName, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        KimiCardBox {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${uiText(R.string.streaming_selected)} ${selectedModels.size}/${models.size}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = { onSelectedModelsChange(models.toSet()) }) { Text(uiText(R.string.ui_select_all)) }
                TextButton(onClick = { onSelectedModelsChange(emptySet()) }) { Text(uiText(R.string.ui_clear_selection)) }
            }
            Button(
                enabled = !checking && selectedModels.isNotEmpty(),
                onClick = onStartCheck,
                modifier = Modifier.fillMaxWidth(),
                shape = KimiPillShape,
            ) {
                Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (checking) uiText(R.string.ui_checking) else uiText(R.string.ui_start_check), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val providerLine = when {
                provider != null -> {
                    val providerText = if (provider.available) uiText(R.string.ui_provider_available) else uiText(R.string.ui_provider_unavailable)
                    "$providerText · ${formatReachabilityLatency(provider.latencyMs)} · ${provider.message}"
                }
                checking -> uiText(R.string.ui_checking_provider)
                else -> ""
            }
            if (providerLine.isNotBlank()) {
                Text(
                    providerLine,
                    color = if (provider?.available == false) MaterialTheme.colorScheme.error else KimiMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (status.isNotBlank()) {
                Text(status, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        KimiCardBox {
            Text(uiText(R.string.ui_select_models_to_check), style = MaterialTheme.typography.titleSmall)
            if (models.isEmpty()) {
                Text(uiText(R.string.ui_no_models_to_check), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
            } else {
                models.forEach { model ->
                    val selected = model in selectedModels
                    val result = resultByModel[model]
                    val detail = when {
                        result != null -> "${formatReachabilityLatency(result.latencyMs)} · ${result.message}"
                        checking && selected && activeModel == model -> uiText(R.string.ui_checking)
                        checking && selected -> uiText(R.string.ui_waiting_to_check)
                        else -> ""
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                onSelectedModelsChange(
                                    if (selected) selectedModels - model else selectedModels + model,
                                )
                            }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = { checked ->
                                onSelectedModelsChange(if (checked) selectedModels + model else selectedModels - model)
                            },
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(model, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            if (detail.isNotBlank()) {
                                Text(
                                    detail,
                                    color = if (result?.available == false) MaterialTheme.colorScheme.error else KimiMuted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        when {
                            result != null -> Icon(
                                if (result.available) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = if (result.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                            checking && selected && activeModel == model -> LinearProgressIndicator(modifier = Modifier.width(48.dp))
                        }
                    }
                }
            }
        }
    }
}
@Composable
internal fun ScreenCenterNotice(
    message: String,
    durationMillis: Long = 2400L,
    onDismiss: () -> Unit,
) {
    if (message.isBlank()) return
    LaunchedEffect(message) {
        kotlinx.coroutines.delay(durationMillis)
        onDismiss()
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 320.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.96f),
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            tonalElevation = 8.dp,
        ) {
            Text(
                text = message,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
@Composable
internal fun SubAgentSettings(settings: AppSettings, controller: ChatController) {
    var revision by remember { mutableIntStateOf(0) }
    var agents by remember(revision, controller.settingsRevision.intValue) { mutableStateOf(settings.subAgents()) }
    val profiles = controller.profiles.toList()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<SubAgentConfig?>(null) }
    var deleteTarget by remember { mutableStateOf<SubAgentConfig?>(null) }
    var notice by remember { mutableStateOf("") }
    fun save(updated: List<SubAgentConfig>) {
        settings.saveSubAgents(updated)
        agents = updated
        controller.settingsRevision.intValue++
        revision++
    }
    Box(Modifier.fillMaxSize()) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            KimiCardBox {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.title_sub_agent_orchestration), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.sub_agent_settings_desc), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = settings.subAgentOrchestrationEnabled,
                        onCheckedChange = {
                            settings.subAgentOrchestrationEnabled = it
                            controller.settingsRevision.intValue++
                            revision++
                        },
                    )
                }
            }
            KimiCardBox {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.label_sub_agent_models), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Button(
                        enabled = profiles.isNotEmpty(),
                        onClick = {
                            val profile = profiles.firstOrNull()
                            editing = SubAgentConfig(
                                id = AppSettings.newId(),
                                name = context.getString(R.string.label_sub_agent_default_name),
                                profileId = profile?.id.orEmpty(),
                                model = profile?.selectedModel.orEmpty(),
                                description = context.getString(R.string.sub_agent_default_desc),
                                enabled = true,
                            )
                        },
                        shape = KimiPillShape,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_new))
                    }
                }
                if (agents.isEmpty()) {
                    Text(stringResource(R.string.sub_agent_empty_hint), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
                } else {
                    agents.forEach { agent ->
                        val profile = profiles.firstOrNull { it.id == agent.profileId }
                        SubAgentRow(
                            agent = agent,
                            profileName = profile?.name ?: stringResource(R.string.label_not_configured),
                            onEdit = { editing = agent },
                            onToggle = { enabled -> save(agents.map { if (it.id == agent.id) it.copy(enabled = enabled) else it }) },
                            onDelete = { deleteTarget = agent },
                        )
                        if (agent != agents.last()) KimiDivider()
                    }
                }
            }
        }
        editing?.let { agent ->
            SubAgentEditDialog(
                initial = agent,
                profiles = profiles,
                onDismiss = { editing = null },
                onSave = { saved ->
                    val updated = if (agents.any { it.id == saved.id }) agents.map { if (it.id == saved.id) saved else it } else agents + saved
                    save(updated)
                    editing = null
                    notice = context.getString(R.string.notice_sub_agent_saved)
                },
            )
        }
        deleteTarget?.let { agent ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text(stringResource(R.string.title_delete_sub_agent)) },
                text = { Text(stringResource(R.string.confirm_delete_sub_agent, agent.name)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            save(agents.filterNot { it.id == agent.id })
                            deleteTarget = null
                            notice = context.getString(R.string.notice_sub_agent_deleted)
                        },
                    ) { Text(stringResource(R.string.action_delete)) }
                },
                dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
        TransientNotice(message = notice, modifier = Modifier.align(Alignment.Center).padding(24.dp), onDismiss = { notice = "" })
    }
}

@Composable
internal fun SubAgentRow(agent: SubAgentConfig, profileName: String, onEdit: () -> Unit, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onEdit).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.Hub, contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(agent.name, style = MaterialTheme.typography.titleSmall)
            Text("$profileName · ${agent.model}", color = KimiMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (agent.description.isNotBlank()) Text(agent.description, color = KimiMuted, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Switch(checked = agent.enabled, onCheckedChange = onToggle)
        IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.action_delete)) }
    }
}

@Composable
internal fun SubAgentEditDialog(initial: SubAgentConfig, profiles: List<ApiProfile>, onDismiss: () -> Unit, onSave: (SubAgentConfig) -> Unit) {
    val context = LocalContext.current
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var profileId by remember(initial.id) { mutableStateOf(initial.profileId.ifBlank { profiles.firstOrNull()?.id.orEmpty() }) }
    val selectedProfile = profiles.firstOrNull { it.id == profileId } ?: profiles.firstOrNull()
    var model by remember(initial.id, profileId) { mutableStateOf(initial.model.ifBlank { selectedProfile?.selectedModel.orEmpty() }) }
    var description by remember(initial.id) { mutableStateOf(initial.description) }
    var enabled by remember(initial.id) { mutableStateOf(initial.enabled) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_edit_sub_agent)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.label_sub_agent_name)) }, singleLine = true)
                SubAgentDropdownPicker(
                    label = stringResource(R.string.label_provider),
                    value = selectedProfile?.name ?: stringResource(R.string.label_not_configured),
                    subtitle = selectedProfile?.selectedModel.orEmpty(),
                    items = profiles,
                    itemTitle = { it.name },
                    itemSubtitle = { it.selectedModel },
                    isSelected = { it.id == profileId },
                    onSelect = { profile ->
                        profileId = profile.id
                        model = profile.selectedModel
                    },
                )
                SubAgentDropdownPicker(
                    label = stringResource(R.string.label_model),
                    value = model.ifBlank { stringResource(R.string.label_not_selected) },
                    items = selectedProfile?.enabledModels.orEmpty(),
                    itemTitle = { it },
                    isSelected = { it == model },
                    onSelect = { model = it },
                )
                OutlinedTextField(value = model, onValueChange = { model = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.label_model)) }, singleLine = true)
                OutlinedTextField(value = description, onValueChange = { description = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.label_sub_agent_desc)) }, minLines = 3, maxLines = 6)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.action_enable), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            Button(
                enabled = profileId.isNotBlank() && model.isNotBlank(),
                onClick = { onSave(initial.copy(name = name.trim().ifBlank { context.getString(R.string.label_sub_agent_default_name) }, profileId = profileId, model = model.trim(), description = description.trim(), enabled = enabled)) },
                shape = KimiPillShape,
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
@Composable
internal fun <T> SubAgentDropdownPicker(
    label: String,
    value: String,
    subtitle: String = "",
    items: List<T>,
    itemTitle: (T) -> String,
    itemSubtitle: (T) -> String = { "" },
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.Start) {
                    Text("$label: $value", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    if (subtitle.isNotBlank()) Text(subtitle, color = KimiMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(min = 280.dp).heightIn(max = 320.dp),
            ) {
                items.forEach { item ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(itemTitle(item), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val childSubtitle = itemSubtitle(item)
                                    if (childSubtitle.isNotBlank()) Text(childSubtitle, color = KimiMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                }
                                if (isSelected(item)) Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        },
                        onClick = {
                            onSelect(item)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
@Composable
internal fun ApiFormatOption(label: String, value: String, selected: String, onSelect: (String) -> Unit) {
    MaterialChoiceButton(label = label, selected = selected == value, onClick = { onSelect(value) })
}

@Composable
internal fun MaterialChoiceButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            shape = KimiPillShape,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            shape = KimiPillShape,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}


@Composable
internal fun ReachabilityReportCard(
    provider: ProviderReachabilityResult?,
    modelResults: List<ModelReachabilityResult>,
    checking: Boolean,
) {
    KimiCardBox {
        Text(uiText(R.string.ui_reachability_results), style = MaterialTheme.typography.titleSmall)
        if (provider == null) {
            Text(
                uiText(R.string.ui_checking_provider),
                color = KimiMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            val providerText = if (provider.available) uiText(R.string.ui_provider_available) else uiText(R.string.ui_provider_unavailable)
            Text(
                "$providerText · ${formatReachabilityLatency(provider.latencyMs)} · ${provider.message}",
                color = if (provider.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        modelResults.forEach { result ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (result.available) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = if (result.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(result.model, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${formatReachabilityLatency(result.latencyMs)} · ${result.message}",
                        color = KimiMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (checking) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(uiText(R.string.ui_checking), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
internal fun formatReachabilityLatency(latencyMs: Long): String {
    return if (latencyMs > 0L) "${latencyMs}ms" else uiText(R.string.ui_no_latency_data)
}
internal fun defaultBaseUrlForApiFormat(format: String): String = when (format) {
    ApiProfile.API_FORMAT_ANTHROPIC -> "https://api.anthropic.com/v1"
    ApiProfile.API_FORMAT_GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
    else -> "https://api.openai.com/v1"
}

internal fun knownProviderBaseUrls(): Set<String> =
    ProviderCatalog.presets.flatMapTo(linkedSetOf()) { preset -> preset.plans().map { it.baseUrl } }

internal fun knownProviderChatPaths(): Set<String> = setOf(
    ApiProfile.DEFAULT_OPENAI_CHAT_PATH,
    ApiProfile.DEFAULT_ANTHROPIC_CHAT_PATH,
    "/models/{model}:generateContent",
)
internal fun apiKeyLabel(format: String): String = when (format) {
    ApiProfile.API_FORMAT_ANTHROPIC -> "Anthropic API Key"
    ApiProfile.API_FORMAT_GEMINI -> "Google API Key"
    else -> "API Key"
}

internal fun apiFormatDescription(format: String): String = when (format) {
    ApiProfile.API_FORMAT_ANTHROPIC -> uiText(R.string.format_anthropic_desc)
    ApiProfile.API_FORMAT_GEMINI -> uiText(R.string.format_gemini_desc)
    else -> uiText(R.string.format_openai_desc)
}

internal fun endpointHint(format: String, baseUrl: String, chatPath: String, useResponsesApi: Boolean = false): String {
    val root = baseUrl.trim().trimEnd('/').ifBlank { defaultBaseUrlForApiFormat(format) }
    val path = ApiProfile.normalizedChatPath(format, chatPath)
    return when (format) {
        ApiProfile.API_FORMAT_GEMINI -> uiText(R.string.ui_request_endpoint_1_s_2_s_model_list_3, root, path, root)
        ApiProfile.API_FORMAT_OPENAI -> uiText(
            R.string.ui_request_endpoint_1_s_2_s_model_list_3,
            root,
            if (useResponsesApi) "/responses" else path,
            root,
        )
        else -> uiText(R.string.ui_request_endpoint_1_s_2_s_model_list_3, root, path, root)
    }
}

