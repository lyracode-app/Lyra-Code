package com.yukisoffd.lyracode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.workspace.UploadedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.max


@Composable
internal fun ChatMessageComposer(
    controller: ChatController,
    settings: AppSettings,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    canSend: Boolean,
    isRunning: Boolean,
    onOpenMenu: () -> Unit,
    onOpenReasoning: () -> Unit,
    onOpenContextInfo: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    var fullscreen by rememberSaveable { mutableStateOf(false) }

    ComposerTextEditor(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        fullscreen = false,
    )
    ComposerActionBar(
        controller = controller,
        settings = settings,
        canSend = canSend,
        isRunning = isRunning,
        onFullscreen = { fullscreen = true },
        onOpenMenu = onOpenMenu,
        onOpenReasoning = onOpenReasoning,
        onOpenContextInfo = onOpenContextInfo,
        onSend = onSend,
        onStop = onStop,
    )

    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .navigationBarsPadding()
                    .padding(12.dp),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(
                    Modifier.fillMaxSize().padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.title_fullscreen_input),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        IconButton(onClick = { fullscreen = false }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_exit_fullscreen_input))
                        }
                    }
                    ComposerTextEditor(
                        value = value,
                        onValueChange = onValueChange,
                        enabled = enabled,
                        fullscreen = true,
                        modifier = Modifier.weight(1f),
                    )
                    ComposerActionBar(
                        controller = controller,
                        settings = settings,
                        canSend = canSend,
                        isRunning = isRunning,
                        showFullscreen = false,
                        onFullscreen = {},
                        onOpenMenu = {
                            fullscreen = false
                            onOpenMenu()
                        },
                        onOpenReasoning = {
                            fullscreen = false
                            onOpenReasoning()
                        },
                        onOpenContextInfo = {
                            fullscreen = false
                            onOpenContextInfo()
                        },
                        onSend = {
                            fullscreen = false
                            onSend()
                        },
                        onStop = onStop,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerTextEditor(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    fullscreen: Boolean,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .then(if (fullscreen) Modifier.fillMaxHeight() else Modifier.heightIn(min = 24.dp, max = 108.dp))
            .padding(horizontal = 8.dp, vertical = if (fullscreen) 6.dp else 2.dp),
        enabled = enabled,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        minLines = if (fullscreen) 8 else 1,
        maxLines = if (fullscreen) Int.MAX_VALUE else 4,
        decorationBox = { innerTextField ->
            Box(Modifier.fillMaxWidth()) {
                if (value.isEmpty()) {
                    Text(
                        stringResource(R.string.placeholder_input_message),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun ComposerActionBar(
    controller: ChatController,
    settings: AppSettings,
    canSend: Boolean,
    isRunning: Boolean,
    showFullscreen: Boolean = true,
    onFullscreen: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenReasoning: () -> Unit,
    onOpenContextInfo: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    var autoApproveConfirmOpen by rememberSaveable { mutableStateOf(false) }
    val composerAccent = composerSystemAccentColors()
    val autoApprovalEnabled = controller.settingsRevision.intValue.let {
        controller.isAutoApprovalEnabledForActiveSession()
    }

    if (autoApproveConfirmOpen) {
        AlertDialog(
            onDismissRequest = { autoApproveConfirmOpen = false },
            title = { Text(stringResource(R.string.title_enable_auto_approve)) },
            text = { Text(stringResource(R.string.auto_approve_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    controller.setAutoApprovalForActiveSession(true)
                    autoApproveConfirmOpen = false
                }) { Text(stringResource(R.string.action_enable_auto_approve)) }
            },
            dismissButton = {
                TextButton(onClick = { autoApproveConfirmOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ComposerIconButton(
            icon = Icons.Default.AdminPanelSettings,
            description = stringResource(R.string.label_auto_approve),
            selected = autoApprovalEnabled,
            onClick = {
                if (autoApprovalEnabled) controller.setAutoApprovalForActiveSession(false)
                else autoApproveConfirmOpen = true
            },
        )
        ComposerIconButton(
            icon = Icons.Default.Lightbulb,
            description = "${stringResource(R.string.label_reasoning_depth)}: ${reasoningDepthLabel(settings.reasoningDepth)}",
            onClick = onOpenReasoning,
        )
        if (showFullscreen) {
            ComposerIconButton(
                icon = Icons.Default.OpenInFull,
                description = stringResource(R.string.action_fullscreen_input),
                onClick = onFullscreen,
            )
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .clickable(onClick = onOpenContextInfo),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.DataUsage,
                contentDescription = stringResource(R.string.cd_context_window_usage),
                modifier = Modifier.size(19.dp),
                tint = if (controller.contextWindowUsage.value.hasCompressedHistory) composerAccent.first else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .border(1.25.dp, composerAccent.first, CircleShape)
                .clickable(onClick = onOpenMenu),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = stringResource(R.string.cd_add_attachment),
                modifier = Modifier.size(16.dp),
                tint = composerAccent.first,
            )
        }
        AnimatedVisibility(isRunning) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(composerAccent.first)
                    .clickable(onClick = onStop),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Stop,
                    contentDescription = stringResource(R.string.cd_stop),
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        AnimatedVisibility(canSend && !isRunning) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(composerAccent.first)
                    .clickable(enabled = canSend, onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.ArrowUpward,
                    contentDescription = stringResource(R.string.cd_send),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun ComposerIconButton(
    icon: ImageVector,
    description: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val accent = composerSystemAccentColors()
    val inactiveForeground = if (dark) Color(0xFFCAC4D0) else Color(0xFF49454F)
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(38.dp),
    ) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.size(20.dp),
            tint = if (selected) accent.first else inactiveForeground,
        )
    }
}

@Composable
private fun composerSystemAccentColors(): Triple<Color, Color, Color> {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        return Triple(scheme.primary, scheme.primaryContainer, scheme.onPrimaryContainer)
    }
    return if (dark) {
        Triple(Color(0xFFD0BCFF), Color(0xFF4F378B), Color(0xFFEADDFF))
    } else {
        Triple(Color(0xFF6750A4), Color(0xFFEADDFF), Color(0xFF21005D))
    }
}

@Composable
internal fun ContextWindowInfoDialog(
    controller: ChatController,
    settings: AppSettings,
    isGenerating: Boolean,
    isCompressing: Boolean,
    onDismiss: () -> Unit,
) {
    val usage = controller.contextWindowUsage.value
    var customInstruction by rememberSaveable(controller.activeConversationId.value) { mutableStateOf("") }
    var chunkCountText by rememberSaveable(controller.activeConversationId.value) {
        mutableStateOf(settings.historyCompressionChunkCount.toString())
    }
    var resultMessage by rememberSaveable(controller.activeConversationId.value) { mutableStateOf("") }
    val chunkCount = chunkCountText.toIntOrNull()
    val chunkCountValid = chunkCount != null && chunkCount in
        AppSettings.MIN_HISTORY_COMPRESSION_CHUNKS..AppSettings.MAX_HISTORY_COMPRESSION_CHUNKS
    val dialogState = contextCompressionDialogState(isGenerating, isCompressing)
    AlertDialog(
        onDismissRequest = { if (dialogState.canDismiss) onDismiss() },
        title = { Text(stringResource(R.string.title_context_window_usage)) },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.context_tokens_used, usage.estimatedTokens),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.context_usage_details, usage.contextMessageCount, usage.turnsSinceCompression),
                    color = KimiMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (usage.hasCompressedHistory) {
                    Text(stringResource(R.string.context_contains_summary), color = MaterialTheme.colorScheme.primary)
                }
                Text(stringResource(R.string.context_estimate_warning), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
                KimiDivider()
                Text(stringResource(R.string.label_history_compression_model), style = MaterialTheme.typography.titleSmall)
                Text(
                    settings.historyCompressionModel.ifBlank {
                        stringResource(R.string.current_conversation_model, controller.activeModel.value)
                    },
                    color = KimiMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = chunkCountText,
                    onValueChange = { value ->
                        chunkCountText = value.filter(Char::isDigit).take(2)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = dialogState.canEdit,
                    label = { Text(stringResource(R.string.label_compression_chunk_count)) },
                    supportingText = {
                        Text(
                            stringResource(
                                    R.string.compression_chunk_count_hint,
                                    AppSettings.MIN_HISTORY_COMPRESSION_CHUNKS,
                                    AppSettings.MAX_HISTORY_COMPRESSION_CHUNKS,
                                ),
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !chunkCountValid,
                    singleLine = true,
                )
                OutlinedTextField(
                    value = customInstruction,
                    onValueChange = { customInstruction = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = dialogState.canEdit,
                    label = { Text(stringResource(R.string.label_custom_compression_instruction)) },
                    supportingText = { Text(stringResource(R.string.custom_compression_instruction_hint)) },
                    minLines = 3,
                    maxLines = 6,
                )
                when (dialogState.status) {
                    ContextCompressionDialogStatus.COMPRESSING -> {
                        Text(stringResource(R.string.status_compressing_history), color = MaterialTheme.colorScheme.primary)
                    }
                    ContextCompressionDialogStatus.WAITING_FOR_RESPONSE -> {
                        Text(
                            stringResource(R.string.status_history_compression_wait_for_response),
                            color = KimiMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    ContextCompressionDialogStatus.IDLE -> if (resultMessage.isNotBlank()) {
                        Text(resultMessage, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = dialogState.canStart && usage.contextMessageCount > 0 && !usage.updating && chunkCountValid,
                onClick = {
                    resultMessage = ""
                    settings.historyCompressionChunkCount = chunkCount!!
                    controller.compressActiveHistory(customInstruction, chunkCount) { result ->
                        resultMessage = result.fold(
                            onSuccess = { uiText(R.string.status_history_compressed) },
                            onFailure = { it.message.orEmpty().ifBlank { uiText(R.string.ui_conversation_history_compression_failed) } },
                        )
                    }
                },
            ) { Text(stringResource(R.string.action_compress_history)) }
        },
        dismissButton = {
            TextButton(onClick = {
                if (isCompressing) controller.stopActive()
                onDismiss()
            }) {
                Text(stringResource(if (isCompressing) R.string.action_cancel else R.string.action_close))
            }
        },
    )
}

internal enum class ContextCompressionDialogStatus {
    IDLE,
    WAITING_FOR_RESPONSE,
    COMPRESSING,
}

internal data class ContextCompressionDialogState(
    val canDismiss: Boolean,
    val canEdit: Boolean,
    val canStart: Boolean,
    val status: ContextCompressionDialogStatus,
)

internal fun contextCompressionDialogState(
    isGenerating: Boolean,
    isCompressing: Boolean,
): ContextCompressionDialogState = ContextCompressionDialogState(
    canDismiss = !isCompressing,
    canEdit = !isCompressing,
    canStart = !isGenerating && !isCompressing,
    status = when {
        isCompressing -> ContextCompressionDialogStatus.COMPRESSING
        isGenerating -> ContextCompressionDialogStatus.WAITING_FOR_RESPONSE
        else -> ContextCompressionDialogStatus.IDLE
    },
)

private val SelectionListNestedScrollConnection = object : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        return if (source == NestedScrollSource.UserInput) {
            Offset(x = 0f, y = available.y)
        } else {
            Offset.Zero
        }
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        return Velocity(x = 0f, y = available.y)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentActionBottomSheet(
    controller: ChatController,
    settings: AppSettings,
    page: String,
    search: String,
    onPageChange: (String) -> Unit,
    onSearchChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onPickFile: () -> Unit,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
) {
    val profiles = controller.profiles.toList()
    val activeProfile = profiles.firstOrNull { it.id == controller.activeProfileId.value } ?: profiles.firstOrNull()
    val prompts = remember(controller.settingsRevision.intValue) {
        settings.systemPromptPresets()
    }
    val activePrompt = prompts.firstOrNull { it.id == settings.selectedSystemPromptId } ?: prompts.firstOrNull()
    val autoCompressionConfig = controller.settingsRevision.intValue.let { controller.autoCompressionConfig() }
    val selectionPage by rememberUpdatedState(page == "providers" || page == "models")
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target -> target != SheetValue.Hidden || !selectionPage },
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 8.dp)
                    .size(width = 54.dp, height = 6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AnimatedContent(
                targetState = page,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "attachment-action-sheet-page",
            ) { targetPage ->
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when (targetPage) {
                        "providers" -> {
                            SheetBackTitle(stringResource(R.string.label_choose_provider)) { onPageChange("root") }
                            CapsuleTextField(
                                value = search,
                                onValueChange = onSearchChange,
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = stringResource(R.string.search_provider_placeholder),
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) },
                            )
                            val filteredProfiles = profiles.filter { search.isBlank() || it.name.contains(search, ignoreCase = true) }
                            LazyColumn(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp)
                                    .nestedScroll(SelectionListNestedScrollConnection),
                            ) {
                                items(filteredProfiles, key = { it.id }) { profile ->
                                    ActionSheetRow(
                                        icon = Icons.Default.Cloud,
                                        logoRes = ProviderCatalog.logoRes(profile),
                                        logoFallback = profile.name.ifBlank { profile.baseUrl },
                                        title = profile.name,
                                        subtitle = "${profile.apiFormat} · ${profile.baseUrl}",
                                        trailing = if (profile.id == controller.activeProfileId.value) Icons.Default.Check else null,
                                        onClick = {
                                            controller.selectProfile(profile.id)
                                            onDismiss()
                                        },
                                    )
                                }
                            }
                        }
                        "models" -> {
                            SheetBackTitle(stringResource(R.string.label_choose_model)) { onPageChange("root") }
                            CapsuleTextField(
                                value = search,
                                onValueChange = onSearchChange,
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = stringResource(R.string.search_model_placeholder),
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) },
                            )
                            val filteredModels = activeProfile?.enabledModels.orEmpty()
                                .filter { search.isBlank() || it.contains(search, ignoreCase = true) }
                            LazyColumn(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp)
                                    .nestedScroll(SelectionListNestedScrollConnection),
                            ) {
                                items(filteredModels, key = { it }) { modelName ->
                                    ActionSheetRow(
                                        icon = Icons.Default.SmartToy,
                                        logoRes = modelLogoRes(modelName),
                                        logoFallback = canonicalModelName(modelName).ifBlank { modelName },
                                        title = modelName,
                                        subtitle = activeProfile?.name.orEmpty(),
                                        trailing = if (modelName == controller.activeModel.value) Icons.Default.Check else null,
                                        onClick = {
                                            controller.selectModel(modelName)
                                            onDismiss()
                                        },
                                    )
                                }
                            }
                        }
                        "prompts" -> {
                            SheetBackTitle(stringResource(R.string.label_switch_prompt)) { onPageChange("root") }
                            CapsuleTextField(
                                value = search,
                                onValueChange = onSearchChange,
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = stringResource(R.string.search_prompt_placeholder),
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) },
                            )
                            val filteredPrompts = prompts.filter {
                                search.isBlank() ||
                                    it.name.contains(search, ignoreCase = true) ||
                                    it.prompt.contains(search, ignoreCase = true)
                            }
                            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                                if (
                                    search.isBlank() ||
                                    uiText(R.string.ui_app_native_prompt).contains(search, ignoreCase = true)
                                ) {
                                    item(key = "native-prompt") {
                                        ActionSheetRow(
                                            icon = Icons.Default.SmartToy,
                                            title = uiText(R.string.ui_app_native_prompt),
                                            subtitle = uiText(R.string.ui_adapted_to_lyra_code_s_current_tools_and_android),
                                            trailing = if (activePrompt == null) Icons.Default.Check else null,
                                            onClick = {
                                                controller.selectSystemPrompt(AppSettings.NATIVE_SYSTEM_PROMPT_ID)
                                                onDismiss()
                                            },
                                        )
                                    }
                                }
                                items(filteredPrompts, key = { it.id }) { prompt ->
                                    ActionSheetRow(
                                        icon = Icons.Default.EditNote,
                                        title = prompt.name,
                                        subtitle = prompt.prompt.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                                        trailing = if (prompt.id == settings.selectedSystemPromptId) Icons.Default.Check else null,
                                        onClick = {
                                            controller.selectSystemPrompt(prompt.id)
                                            onDismiss()
                                        },
                                    )
                                }
                            }
                        }
                        "reasoning" -> {
                            ReasoningDepthSelector(
                                selectedDepth = settings.reasoningDepth,
                                modelName = controller.activeModel.value,
                                onDepthSelected = controller::selectReasoningDepth,
                            )
                        }
                        "auto_compression" -> {
                            SheetBackTitle(stringResource(R.string.title_auto_compression)) { onPageChange("root") }
                            val mode = autoCompressionConfig.first
                            ActionSheetSwitchRow(
                                icon = Icons.Default.Compress,
                                title = stringResource(R.string.action_auto_compression),
                                subtitle = stringResource(R.string.auto_compression_session_hint),
                                checked = mode != com.yukisoffd.lyracode.data.ConversationStore.AUTO_COMPRESSION_OFF,
                                onCheckedChange = { enabled ->
                                    controller.setAutoCompressionForActiveSession(
                                        if (enabled) com.yukisoffd.lyracode.data.ConversationStore.AUTO_COMPRESSION_TURNS else com.yukisoffd.lyracode.data.ConversationStore.AUTO_COMPRESSION_OFF,
                                        autoCompressionConfig.second,
                                        autoCompressionConfig.third,
                                    )
                                },
                            )
                            if (mode != com.yukisoffd.lyracode.data.ConversationStore.AUTO_COMPRESSION_OFF) {
                                Text(stringResource(R.string.label_compression_mode), style = MaterialTheme.typography.titleSmall)
                                ActionSheetRow(
                                    icon = Icons.Default.Repeat,
                                    title = stringResource(R.string.mode_fixed_turns),
                                    subtitle = stringResource(R.string.mode_fixed_turns_desc),
                                    trailing = if (mode == com.yukisoffd.lyracode.data.ConversationStore.AUTO_COMPRESSION_TURNS) Icons.Default.Check else null,
                                    onClick = {
                                        controller.setAutoCompressionForActiveSession(
                                            com.yukisoffd.lyracode.data.ConversationStore.AUTO_COMPRESSION_TURNS,
                                            autoCompressionConfig.second,
                                            autoCompressionConfig.third,
                                        )
                                    },
                                )
                                ActionSheetRow(
                                    icon = Icons.Default.DataUsage,
                                    title = stringResource(R.string.mode_context_threshold),
                                    subtitle = stringResource(R.string.mode_context_threshold_desc),
                                    trailing = if (mode == com.yukisoffd.lyracode.data.ConversationStore.AUTO_COMPRESSION_TOKENS) Icons.Default.Check else null,
                                    onClick = {
                                        controller.setAutoCompressionForActiveSession(
                                            com.yukisoffd.lyracode.data.ConversationStore.AUTO_COMPRESSION_TOKENS,
                                            autoCompressionConfig.second,
                                            autoCompressionConfig.third,
                                        )
                                    },
                                )
                                if (mode == com.yukisoffd.lyracode.data.ConversationStore.AUTO_COMPRESSION_TURNS) {
                                    var turnThresholdText by remember(controller.activeConversationId.value, mode) {
                                        mutableStateOf(autoCompressionConfig.second.toString())
                                    }
                                    OutlinedTextField(
                                        value = turnThresholdText,
                                        onValueChange = { value ->
                                            turnThresholdText = value.filter(Char::isDigit)
                                            turnThresholdText.toIntOrNull()?.let { turns ->
                                                controller.setAutoCompressionForActiveSession(mode, turns, autoCompressionConfig.third)
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text(stringResource(R.string.label_turn_threshold)) },
                                        supportingText = { Text(stringResource(R.string.turn_definition_hint)) },
                                        singleLine = true,
                                    )
                                } else {
                                    var tokenThresholdText by remember(controller.activeConversationId.value, mode) {
                                        mutableStateOf(autoCompressionConfig.third.toString())
                                    }
                                    OutlinedTextField(
                                        value = tokenThresholdText,
                                        onValueChange = { value ->
                                            tokenThresholdText = value.filter(Char::isDigit)
                                            tokenThresholdText.toLongOrNull()?.let { tokens ->
                                                controller.setAutoCompressionForActiveSession(mode, autoCompressionConfig.second, tokens)
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text(stringResource(R.string.label_token_threshold)) },
                                        supportingText = { Text(stringResource(R.string.token_threshold_hint)) },
                                        singleLine = true,
                                    )
                                }
                                Text(
                                    stringResource(R.string.auto_compression_limit_warning),
                                    color = KimiMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        else -> {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ActionSheetTile(Icons.Default.PhotoLibrary, stringResource(R.string.action_album), Modifier.weight(1f), onPickImage)
                                ActionSheetTile(Icons.Default.PhotoCamera, stringResource(R.string.action_camera), Modifier.weight(1f), onTakePhoto)
                                ActionSheetTile(Icons.Default.AttachFile, stringResource(R.string.action_file), Modifier.weight(1f), onPickFile)
                            }
                            KimiDivider()
                            ActionSheetRow(
                                icon = Icons.Default.Cloud,
                                title = stringResource(R.string.label_provider),
                                subtitle = activeProfile?.name ?: stringResource(R.string.label_not_configured),
                                trailing = Icons.Default.ChevronRight,
                                onClick = { onPageChange("providers") },
                            )
                            ActionSheetRow(
                                icon = Icons.Default.SmartToy,
                                title = stringResource(R.string.label_model),
                                subtitle = controller.activeModel.value.ifBlank { activeProfile?.selectedModel.orEmpty().ifBlank { stringResource(R.string.label_not_selected) } },
                                trailing = Icons.Default.ChevronRight,
                                onClick = { onPageChange("models") },
                            )
                            ActionSheetRow(
                                icon = Icons.Default.EditNote,
                                title = stringResource(R.string.label_prompt),
                                subtitle = activePrompt?.name ?: stringResource(R.string.label_default_assistant),
                                trailing = Icons.Default.ChevronRight,
                                onClick = { onPageChange("prompts") },
                            )
                            ActionSheetRow(
                                icon = Icons.Default.Compress,
                                title = stringResource(R.string.action_auto_compression),
                                subtitle = when (autoCompressionConfig.first) {
                                    com.yukisoffd.lyracode.data.ConversationStore.AUTO_COMPRESSION_TURNS -> stringResource(R.string.auto_compression_turns_summary, autoCompressionConfig.second)
                                    com.yukisoffd.lyracode.data.ConversationStore.AUTO_COMPRESSION_TOKENS -> stringResource(R.string.auto_compression_tokens_summary, autoCompressionConfig.third)
                                    else -> stringResource(R.string.status_off)
                                },
                                trailing = Icons.Default.ChevronRight,
                                onClick = { onPageChange("auto_compression") },
                            )
                            val hasVisionSupplement = settings.hasVisionSupplementProvider()
                            ActionSheetSwitchRow(
                                icon = Icons.Default.Visibility,
                                title = stringResource(R.string.label_enable_vision_supplement),
                                subtitle = if (hasVisionSupplement) {
                                    stringResource(R.string.vision_supplement_toggle_desc)
                                } else {
                                    stringResource(R.string.vision_supplement_not_configured)
                                },
                                checked = settings.isVisionSupplementRoutingEnabled(),
                                enabled = hasVisionSupplement,
                                onCheckedChange = { enabled ->
                                    settings.visionSupplementEnabled = enabled
                                    controller.settingsRevision.intValue++
                                },
                            )
                            val hasSubAgents = settings.enabledSubAgents().isNotEmpty()
                            ActionSheetSwitchRow(
                                icon = Icons.Default.AccountTree,
                                title = stringResource(R.string.label_sub_agent_orchestration),
                                subtitle = if (hasSubAgents) {
                                    stringResource(R.string.subtitle_sub_agent_orchestration)
                                } else {
                                    stringResource(R.string.subtitle_sub_agent_no_models)
                                },
                                checked = settings.subAgentOrchestrationEnabled && hasSubAgents,
                                enabled = hasSubAgents,
                                onCheckedChange = { enabled ->
                                    settings.subAgentOrchestrationEnabled = enabled
                                    controller.settingsRevision.intValue++
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun reasoningDepthLabel(value: String): String = when (value) {
    AppSettings.REASONING_LOW -> stringResource(R.string.reasoning_low)
    AppSettings.REASONING_MEDIUM -> stringResource(R.string.reasoning_medium)
    AppSettings.REASONING_HIGH -> stringResource(R.string.reasoning_high)
    AppSettings.REASONING_XHIGH -> stringResource(R.string.reasoning_xhigh)
    AppSettings.REASONING_MAX -> stringResource(R.string.reasoning_max)
    else -> stringResource(R.string.reasoning_auto)
}

internal fun reasoningDepthEnglishLabel(value: String): String = when (value) {
    AppSettings.REASONING_LOW -> "Low"
    AppSettings.REASONING_MEDIUM -> "Medium"
    AppSettings.REASONING_HIGH -> "High"
    AppSettings.REASONING_XHIGH -> "XHigh"
    AppSettings.REASONING_MAX -> "Max"
    else -> "Auto"
}

@Composable
internal fun SheetBackTitle(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cd_back))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun ActionSheetTile(icon: ImageVector, title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .height(108.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun ActionSheetRow(
    icon: ImageVector,
    logoRes: Int? = null,
    logoFallback: String? = null,
    title: String,
    subtitle: String = "",
    trailing: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (logoFallback != null) {
            AiLogoBadge(
                logoRes = logoRes,
                fallback = logoFallback,
                modifier = Modifier.size(34.dp),
            )
        } else {
            Icon(icon, contentDescription = null, modifier = Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = KimiMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.let { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
internal fun ActionSheetSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String = "",
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val alpha = if (enabled) 1f else 0.48f
        Icon(icon, contentDescription = null, modifier = Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = KimiMuted.copy(alpha = alpha), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun DropdownSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
        CapsuleTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.widthIn(min = 220.dp, max = 320.dp),
            placeholder = placeholder,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) },
        )
    }
}

@Composable
internal fun PendingUploadStrip(files: List<UploadedFile>, onRemove: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        files.forEachIndexed { index, file ->
            if (file.mediaKind == "image") {
                MediaThumb(file.name, file.content.ifBlank { file.uri }, "image", onRemove = { onRemove(index) })
            } else if (file.mediaKind == "video" || file.mediaKind == "audio") {
                MediaPlaceholder(file.name, file.mediaKind, source = file.content.ifBlank { file.uri }, onRemove = { onRemove(index) })
            } else {
                KimiChip("${file.name} ×", onClick = { onRemove(index) })
            }
        }
    }
}

@Composable
internal fun MediaThumb(name: String, uri: String, kind: String, onRemove: (() -> Unit)? = null) {
    val context = LocalContext.current
    var previewOpen by remember { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) { decodeMediaThumbnail(context, uri) }
    }
    val thumbnail = bitmap
    Box(
        Modifier
            .size(78.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (thumbnail != null) {
            Image(
                thumbnail.asImageBitmap(),
                contentDescription = name,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { previewOpen = true },
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                kind,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clickable(enabled = kind == "video" || kind == "audio") { previewOpen = true },
                color = KimiMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (onRemove != null) {
            TextButton(
                onClick = onRemove,
                modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = uiText(R.string.cd_remove), modifier = Modifier.size(16.dp))
            }
        }
    }
    if (previewOpen) {
        FullscreenMediaPreviewDialog(
            title = name,
            source = uri,
            kind = kind,
            bitmap = bitmap,
            onDismiss = { previewOpen = false },
        )
    }
}

private fun decodeMediaThumbnail(context: Context, source: String): Bitmap? = runCatching {
    val bytes = decodeMediaPayload(source)?.bytes ?: readMediaBytes(context, source, MAX_THUMBNAIL_SOURCE_BYTES)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sampleSize = 1
    while (max(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_THUMBNAIL_DIMENSION_PX) {
        sampleSize *= 2
    }
    BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        },
    )
}.getOrNull()

private const val MAX_THUMBNAIL_DIMENSION_PX = 512
private const val MAX_THUMBNAIL_SOURCE_BYTES = 8 * 1024 * 1024

@Composable
internal fun MediaPlaceholder(name: String, kind: String, source: String = "", onRemove: (() -> Unit)? = null) {
    var previewOpen by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(width = 118.dp, height = 78.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = source.isNotBlank() && (kind == "video" || kind == "audio")) { previewOpen = true }
            .padding(8.dp),
    ) {
        Column(Modifier.align(Alignment.CenterStart)) {
            Text(if (kind == "video") uiText(R.string.label_video) else uiText(R.string.media_preview_audio), style = MaterialTheme.typography.labelMedium)
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = KimiMuted, style = MaterialTheme.typography.labelSmall)
        }
        if (onRemove != null) {
            TextButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).size(28.dp), contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Default.Close, contentDescription = uiText(R.string.cd_remove), modifier = Modifier.size(16.dp))
            }
        }
    }
    if (previewOpen) {
        FullscreenMediaPreviewDialog(
            title = name,
            source = source,
            kind = kind,
            onDismiss = { previewOpen = false },
        )
    }
}


