package com.yukisoffd.lyracode

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.material.icons.filled.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.ContextCompat
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.ConversationStore
import com.yukisoffd.lyracode.termux.TermuxExecutor
import com.yukisoffd.lyracode.workspace.WorkspaceFileReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max


@Composable
internal fun rememberAnimatedKeyboardAvoidanceOffsetPx(): Int {
    val targetOffsetPx = rememberKeyboardAvoidanceOffsetPx()
    val bottomOffsetPx by animateIntAsState(
        targetValue = targetOffsetPx,
        animationSpec = tween(durationMillis = 180),
        label = "keyboardAvoidanceOffset",
    )
    return bottomOffsetPx
}

internal fun Modifier.keyboardAwareInputOffset(bottomOffsetPx: Int): Modifier {
    return offset { IntOffset(x = 0, y = -bottomOffsetPx) }
}

@Composable
internal fun rememberKeyboardAvoidanceOffsetPx(): Int {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val composeImeBottom = WindowInsets.ime.getBottom(density)
    val navigationBottom = WindowInsets.navigationBars.getBottom(density)
    var legacyMetrics by remember(view, context) { mutableStateOf(KeyboardAvoidanceMetrics()) }

    DisposableEffect(view, context) {
        val visibleFrame = Rect()
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            val rootView = view.rootView ?: view
            val rootHeight = rootView.height.takeIf { it > 0 } ?: view.height
            if (rootHeight <= 0) {
                legacyMetrics = KeyboardAvoidanceMetrics()
                return@OnGlobalLayoutListener
            }
            rootView.getWindowVisibleDisplayFrame(visibleFrame)
            val composeViewHeight = view.height.takeIf { it > 0 } ?: rootHeight
            val realHeight = context.realDisplayHeightPx().takeIf { it > 0 } ?: rootHeight
            val keyboardThreshold = (realHeight * LEGACY_IME_VISIBLE_THRESHOLD).toInt()
            val windowResizedByIme =
                realHeight - rootHeight > keyboardThreshold ||
                    realHeight - composeViewHeight > keyboardThreshold ||
                    rootHeight - composeViewHeight > keyboardThreshold
            val rootHiddenBottom = (rootHeight - visibleFrame.bottom).coerceAtLeast(0)
            val realHiddenBottom = (realHeight - visibleFrame.bottom).coerceAtLeast(0)
            val frameHiddenBottom = if (windowResizedByIme) {
                0
            } else {
                max(rootHiddenBottom, realHiddenBottom)
            }
            val compatImeBottom = ViewCompat.getRootWindowInsets(rootView)
                ?.getInsets(WindowInsetsCompat.Type.ime())
                ?.bottom
                ?: 0
            legacyMetrics = KeyboardAvoidanceMetrics(
                frameHiddenBottom = frameHiddenBottom,
                compatImeBottom = compatImeBottom,
                windowResizedByIme = windowResizedByIme,
            )
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        listener.onGlobalLayout()
        onDispose {
            if (view.viewTreeObserver.isAlive) {
                view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            }
        }
    }

    if (legacyMetrics.windowResizedByIme) {
        return 0
    }
    val keyboardBottom = max(composeImeBottom, max(legacyMetrics.compatImeBottom, legacyMetrics.frameHiddenBottom))
    val minKeyboardBottom = with(density) { LEGACY_IME_MIN_BOTTOM_DP.dp.roundToPx() }
    if (keyboardBottom < minKeyboardBottom) {
        return 0
    }
    return (keyboardBottom - navigationBottom).coerceAtLeast(0)
}

private const val LEGACY_IME_VISIBLE_THRESHOLD = 0.15f
private const val LEGACY_IME_MIN_BOTTOM_DP = 80

private data class KeyboardAvoidanceMetrics(
    val frameHiddenBottom: Int = 0,
    val compatImeBottom: Int = 0,
    val windowResizedByIme: Boolean = false,
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.realDisplayHeightPx(): Int {
    val activity = findActivity()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && activity != null) {
        activity.windowManager.currentWindowMetrics.bounds.height()
    } else {
        @Suppress("DEPRECATION")
        val display = activity?.windowManager?.defaultDisplay
        if (display != null) {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            metrics.heightPixels
        } else {
            resources.displayMetrics.heightPixels
        }
    }
}

@Composable
internal fun ChatScreen(
    controller: ChatController,
    settings: AppSettings,
    termuxExecutor: TermuxExecutor,
    keyboardLiftPx: Int? = null,
) {
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    var actionStatus by remember { mutableStateOf("") }
    var attachmentMenuOpen by rememberSaveable { mutableStateOf(false) }
    var attachmentMenuPage by rememberSaveable { mutableStateOf("root") }
    var attachmentMenuSearch by rememberSaveable { mutableStateOf("") }
    var contextInfoOpen by rememberSaveable { mutableStateOf(false) }
    var cropUploadUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var selectionResetKey by remember { mutableIntStateOf(0) }
    var selectedWorkspaceFiles by remember { mutableStateOf<List<WorkspaceFileReference>>(emptyList()) }
    var workspaceFileMatches by remember { mutableStateOf<List<WorkspaceFileReference>>(emptyList()) }
    var workspaceFileSearchLoading by remember { mutableStateOf(false) }
    val fileUploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) controller.attachUploadedFile(uri)
    }
    val imageUploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) cropUploadUri = uri
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap: Bitmap? ->
        if (bitmap != null) cropUploadUri = saveTemporaryUploadImage(context, bitmap)
    }
    fun launchCamera() {
        runCatching { cameraLauncher.launch(null) }
            .onFailure { actionStatus = context.getString(R.string.notice_camera_unavailable) }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchCamera()
        } else {
            actionStatus = context.getString(R.string.notice_camera_permission_required)
        }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val resolvedKeyboardLiftPx = if (keyboardLiftPx == null) {
        rememberAnimatedKeyboardAvoidanceOffsetPx()
    } else {
        keyboardLiftPx
    }
    val keyboardLiftDp = with(LocalDensity.current) { resolvedKeyboardLiftPx.toDp() }
    val messageSnapshot = controller.messages.value
    val pendingUploads = controller.pendingUploads
    val isRunning = controller.isActiveConversationRunning()
    val isGenerating = controller.isActiveConversationGenerating()
    val isCompressing = controller.isActiveHistoryCompressionRunning()
    val renderItems = remember(messageSnapshot, isRunning) {
        chatRenderItems(messageSnapshot, isStreaming = isRunning)
    }
    val hasWorkspace = controller.hasWorkspace()
    var forcedSkillIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val installedSkills = settings.installedSkills()
    val canSend = (input.isNotBlank() || pendingUploads.isNotEmpty() || selectedWorkspaceFiles.isNotEmpty()) && !isRunning
    val draftKey = controller.inputDraftKey()
    var loadedDraftKey by remember { mutableStateOf("") }
    LaunchedEffect(draftKey) {
        input = controller.loadInputDraft()
        selectedWorkspaceFiles = emptyList()
        workspaceFileMatches = emptyList()
        workspaceFileSearchLoading = false
        loadedDraftKey = draftKey
    }
    LaunchedEffect(input, loadedDraftKey) {
        if (loadedDraftKey == draftKey) controller.saveInputDraft(input)
    }
    LaunchedEffect(input, controller.activeConversationId.value, controller.settingsRevision.intValue) {
        val query = workspaceFilePickerQuery(input)
        if (query == null || !hasWorkspace) {
            workspaceFileMatches = emptyList()
            workspaceFileSearchLoading = false
            return@LaunchedEffect
        }
        workspaceFileSearchLoading = true
        try {
            delay(120L)
            workspaceFileMatches = withContext(Dispatchers.IO) {
                controller.searchWorkspaceFiles(query)
            }
        } finally {
            workspaceFileSearchLoading = false
        }
    }
    var autoFollowOutput by remember(controller.activeConversationId.value) { mutableStateOf(true) }
    var keyboardShouldLiftOutput by remember(controller.activeConversationId.value) { mutableStateOf(false) }
    var navigationVisible by remember(controller.activeConversationId.value) { mutableStateOf(false) }
    var navigationRevealToken by remember(controller.activeConversationId.value) { mutableIntStateOf(0) }
    val navigationSwipeGuard = remember { NavigationSwipeGuard() }
    LaunchedEffect(navigationRevealToken) {
        if (navigationRevealToken <= 0) return@LaunchedEffect
        navigationVisible = true
        val token = navigationRevealToken
        delay(2600L)
        if (navigationRevealToken == token) navigationVisible = false
    }
    val isInterrupted = controller.activeConversation()?.status == ConversationStore.STATUS_INTERRUPTED
    val showInterruptedAction = isInterrupted && messageSnapshot.isNotEmpty()
    // This is the declarative index of the final anchor, independent of the
    // previous LazyColumn measurement kept in listState.layoutInfo.
    val bottomAnchorIndex = renderItems.size + if (showInterruptedAction) 1 else 0
    if (contextInfoOpen) {
        ContextWindowInfoDialog(
            controller = controller,
            settings = settings,
            isGenerating = isGenerating,
            isCompressing = isCompressing,
            onDismiss = { contextInfoOpen = false },
        )
    }
    controller.pendingToolApproval.value?.let { pending ->
        ToolApprovalDialog(
            pending = pending,
            onApprove = { rememberConversation ->
                controller.answerToolApproval(approved = true, rememberForConversation = rememberConversation, feedback = "")
            },
            onReject = { feedback ->
                controller.answerToolApproval(approved = false, rememberForConversation = false, feedback = feedback)
            },
        )
    }
    controller.pendingUserQuestion.value?.let { pending ->
        UserQuestionDialog(
            pending = pending,
            onActivity = { controller.markUserQuestionInteraction(pending.id) },
            onSubmit = { selectedOptions, freeText ->
                controller.answerUserQuestion(selectedOptions, freeText)
            },
        )
    }
    cropUploadUri?.let { uri ->
        ImageCropUploadDialog(
            uri = uri,
            onDismiss = { cropUploadUri = null },
            onUseOriginal = {
                controller.attachUploadedFile(uri)
                cropUploadUri = null
            },
            onCropped = { cropped ->
                controller.attachUploadedFile(cropped)
                cropUploadUri = null
            },
        )
    }
    if (attachmentMenuOpen) {
        AttachmentActionBottomSheet(
            controller = controller,
            settings = settings,
            page = attachmentMenuPage,
            search = attachmentMenuSearch,
            onPageChange = {
                attachmentMenuPage = it
                attachmentMenuSearch = ""
            },
            onSearchChange = { attachmentMenuSearch = it },
            onDismiss = {
                attachmentMenuOpen = false
                attachmentMenuPage = "root"
                attachmentMenuSearch = ""
            },
            onPickFile = {
                attachmentMenuOpen = false
                runCatching { fileUploadLauncher.launch("*/*") }
                    .onFailure { actionStatus = context.getString(R.string.notice_file_picker_unavailable) }
            },
            onPickImage = {
                attachmentMenuOpen = false
                runCatching {
                    imageUploadLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }.onFailure { actionStatus = context.getString(R.string.notice_album_unavailable) }
            },
            onTakePhoto = {
                attachmentMenuOpen = false
                val hasCameraPermission = runCatching {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                }.getOrDefault(false)
                if (hasCameraPermission) {
                    launchCamera()
                } else {
                    runCatching { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }
                        .onFailure { actionStatus = context.getString(R.string.notice_camera_permission_required) }
                }
            },
        )
    }
    LaunchedEffect(actionStatus) {
        val currentStatus = actionStatus
        if (currentStatus.isNotBlank()) {
            kotlinx.coroutines.delay(2400L)
            if (actionStatus == currentStatus) {
                actionStatus = ""
            }
        }
    }

    val chatBackground = remember(settings.chatBackgroundPath) {
        settings.chatBackgroundPath
            ?.let { path -> BitmapFactory.decodeFile(path)?.asImageBitmap() }
    }
    val chatBackgroundMaskAlpha = 1f - settings.chatBackgroundMaskOpacity.coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (chatBackground != null) {
            Image(
                bitmap = chatBackground,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = chatBackgroundMaskAlpha)),
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        TodoProgressPanel(settings, controller.activeConversationId.value, controller.todoItems)
        ConversationChangesPanel(settings, controller.activeConversationId.value, messageSnapshot)
        val isNearOutputEnd by remember {
            derivedStateOf {
                val total = listState.layoutInfo.totalItemsCount
                if (total == 0) {
                    true
                } else {
                    val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                    val bottomDistance = if (last != null && last.index == total - 1) {
                        listState.layoutInfo.viewportEndOffset - (last.offset + last.size)
                    } else {
                        Int.MIN_VALUE
                    }
                    !listState.canScrollForward || bottomDistance >= -220
                }
            }
        }
        LaunchedEffect(resolvedKeyboardLiftPx, isNearOutputEnd) {
            if (resolvedKeyboardLiftPx == 0) {
                keyboardShouldLiftOutput = isNearOutputEnd
            } else if (isNearOutputEnd) {
                keyboardShouldLiftOutput = true
            }
        }
        LaunchedEffect(isRunning) {
            if (isRunning) autoFollowOutput = true
        }
        LaunchedEffect(listState.isScrollInProgress, listState.canScrollForward) {
            val userSettledAtBottom = !listState.isScrollInProgress && !listState.canScrollForward
            if (userSettledAtBottom) {
                autoFollowOutput = true
            }
        }
        LaunchedEffect(controller.activeConversationId.value, bottomAnchorIndex) {
            // Follow measured output, including typewriter frames after the last
            // network chunk. Never compete with a drag or its settling fling.
            snapshotFlow {
                Triple(listState.layoutInfo, listState.isScrollInProgress, autoFollowOutput)
            }.collect { (layout, scrolling, following) ->
                if (following && !scrolling && layout.totalItemsCount > 0 && listState.canScrollForward) {
                    listState.scrollToItem(bottomAnchorIndex)
                }
            }
        }
        LaunchedEffect(resolvedKeyboardLiftPx) {
            if (resolvedKeyboardLiftPx > 0 && messageSnapshot.isNotEmpty() && keyboardShouldLiftOutput) {
                listState.scrollToItem(bottomAnchorIndex)
            }
        }
        val blankTapInteraction = remember { MutableInteractionSource() }
        val activeProcessKey = if (isRunning) {
            renderItems.lastOrNull { it.process.isNotEmpty() }?.key
        } else {
            null
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .observeLeftSwipe(controller.activeConversationId.value, navigationSwipeGuard) { navigationRevealToken++ }
                .clickable(
                    interactionSource = blankTapInteraction,
                    indication = null,
                    onClick = { selectionResetKey++ },
                ),
        ) {
            CompositionLocalProvider(LocalNavigationSwipeGuard provides navigationSwipeGuard) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(remember(listState, controller.activeConversationId.value) {
                        object : NestedScrollConnection {
                            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                                    autoFollowOutput = false
                                }
                                return Offset.Zero
                            }

                            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                                // Latch the bottom during the gesture, before another
                                // streaming frame can move it away on finger release.
                                if ((consumed.y < 0f || available.y < 0f) && !listState.canScrollForward) {
                                    autoFollowOutput = true
                                }
                                return Offset.Zero
                            }
                        }
                    }),
                contentPadding = PaddingValues(bottom = if (keyboardShouldLiftOutput) keyboardLiftDp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(renderItems, key = { it.key }) { item ->
                    if (item.process.isNotEmpty()) {
                        AgentProcessSummary(
                            messages = item.process,
                            selectionResetKey = selectionResetKey,
                            active = item.key == activeProcessKey,
                            streamingAnimationMode = settings.streamingAnimationMode,
                            startedAtOverride = item.processStartedAt,
                            finishedAtOverride = item.processFinishedAt,
                        )
                    } else if (item.message != null) {
                        MessageCard(
                            message = item.message,
                            selectionResetKey = selectionResetKey,
                            streamingAnimationMode = settings.streamingAnimationMode,
                            isStreaming = isRunning && item.message.id == messageSnapshot.lastOrNull { it.role == "assistant" }?.id,
                            onEditAndRegenerate = controller::editAndRegenerateUserMessage,
                            onCreateBranch = controller::createConversationBranch,
                        )
                    }
                }
                if (showInterruptedAction) {
                    item(key = "continue-interrupted") {
                        ContinueInterruptedRow(onContinue = { controller.continueActive() })
                    }
                }
                item(key = "bottom-anchor") {
                    Spacer(Modifier.height(1.dp))
                }
            }
            }
            ConversationNavigationVisibility(
                visible = navigationVisible && messageSnapshot.isNotEmpty(),
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
            ) {
                val userItemIndices = remember(renderItems) {
                    renderItems.mapIndexedNotNull { index, item -> index.takeIf { item.message?.role == "user" } }
                }
                ConversationNavigationControls(
                    onInteraction = { navigationRevealToken++ },
                    onTop = { autoFollowOutput = false; scope.launch { listState.animateScrollToItem(0) } },
                    onPreviousUser = {
                        autoFollowOutput = false
                        val target = userItemIndices.lastOrNull { it < listState.firstVisibleItemIndex } ?: 0
                        scope.launch { listState.animateScrollToItem(target) }
                    },
                    onNextUser = {
                        autoFollowOutput = false
                        val target = userItemIndices.firstOrNull { it > listState.firstVisibleItemIndex }
                            ?: (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                        scope.launch { listState.animateScrollToItem(target) }
                    },
                    onBottom = {
                        scope.launch {
                            listState.animateScrollToItem(bottomAnchorIndex)
                            autoFollowOutput = true
                        }
                    },
                )
            }
        }
        val statusLine = listOf(controller.status.value, controller.uploadingStatus.value, actionStatus)
            .filter { it.isNotBlank() && it != uiText(R.string.status_done) }
            .joinToString(" ")
        if (statusLine.isNotBlank()) {
            AnimatedConversationStatus(statusLine)
        }
        Card(
            Modifier.fillMaxWidth().keyboardAwareInputOffset(resolvedKeyboardLiftPx),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ForcedSkillControls(
                    input = input,
                    installedSkills = installedSkills,
                    forcedSkillIds = forcedSkillIds,
                    enabled = !isRunning,
                    onSkillIdsChange = { forcedSkillIds = it },
                    onInputChange = { input = it },
                )
                WorkspaceFileMentionPicker(
                    input = input,
                    matches = workspaceFileMatches,
                    selected = selectedWorkspaceFiles,
                    enabled = !isRunning,
                    hasWorkspace = hasWorkspace,
                    loading = workspaceFileSearchLoading,
                    onToggle = { file ->
                        selectedWorkspaceFiles = if (selectedWorkspaceFiles.any { it.relativePath == file.relativePath }) {
                            selectedWorkspaceFiles.filterNot { it.relativePath == file.relativePath }
                        } else {
                            (selectedWorkspaceFiles + file).distinctBy { it.relativePath }.take(24)
                        }
                    },
                    onRemove = { file ->
                        selectedWorkspaceFiles = selectedWorkspaceFiles.filterNot { it.relativePath == file.relativePath }
                    },
                    onDone = {
                        input = removeWorkspaceMentionPrefix(input)
                        workspaceFileMatches = emptyList()
                    },
                )
                if (pendingUploads.isNotEmpty()) {
                    PendingUploadStrip(pendingUploads, onRemove = controller::removePendingUpload)
                }
                ChatMessageComposer(
                    controller = controller,
                    settings = settings,
                    value = input,
                    onValueChange = { input = it },
                    enabled = !isRunning,
                    canSend = canSend,
                    isRunning = isRunning,
                    onOpenMenu = {
                        attachmentMenuPage = "root"
                        attachmentMenuOpen = !attachmentMenuOpen
                    },
                    onStop = { controller.stopActive() },
                    onOpenReasoning = {
                        attachmentMenuPage = "reasoning"
                        attachmentMenuOpen = true
                    },
                    onOpenContextInfo = {
                        controller.refreshContextWindowUsage()
                        contextInfoOpen = true
                    },
                    onSend = {
                        val text = input
                        val skills = forcedSkillIds
                        val workspaceFiles = selectedWorkspaceFiles
                        controller.clearInputDraft()
                        input = ""
                        forcedSkillIds = emptyList()
                        selectedWorkspaceFiles = emptyList()
                        workspaceFileMatches = emptyList()
                        attachmentMenuOpen = false
                        controller.send(text, skills, workspaceFiles)
                    },
                )
            }
        }
    }
}
}

@Composable
private fun AnimatedConversationStatus(status: String) {
    val transition = rememberInfiniteTransition(label = "conversation-status-glow")
    // Keep the animated value read inside the draw pass so every frame only
    // invalidates the border, rather than recomposing the status contents.
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = LinearEasing),
        ),
        label = "conversation-status-glow-phase",
    )
    val accent = MaterialTheme.colorScheme.primary
    val glowColors = remember(accent) {
        listOf(
            accent,
            accent.copy(alpha = 0.48f),
            Color.Transparent,
        )
    }
    val shape = RoundedCornerShape(11.dp)
    Box(
        Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f), shape)
            .drawWithContent {
                drawContent()
                val inset = 2.dp.toPx()
                val cornerRadius = 11.dp.toPx()
                val horizontalLength = (size.width - inset * 2).coerceAtLeast(0f)
                val verticalLength = (size.height - inset * 2).coerceAtLeast(0f)
                val perimeter = (horizontalLength + verticalLength) * 2f
                val travel = phase.value * perimeter
                val glowCenter = when {
                    travel <= horizontalLength -> Offset(inset + travel, inset)
                    travel <= horizontalLength + verticalLength ->
                        Offset(size.width - inset, inset + travel - horizontalLength)
                    travel <= horizontalLength * 2f + verticalLength ->
                        Offset(
                            size.width - inset - (travel - horizontalLength - verticalLength),
                            size.height - inset,
                        )
                    else ->
                        Offset(
                            inset,
                            size.height - inset - (travel - horizontalLength * 2f - verticalLength),
                        )
                }
                val glowBrush = Brush.radialGradient(
                    colors = glowColors,
                    center = glowCenter,
                    radius = 42.dp.toPx(),
                )
                drawRoundRect(
                    color = accent.copy(alpha = 0.10f),
                    topLeft = Offset(inset, inset),
                    size = Size(
                        (size.width - inset * 2).coerceAtLeast(0f),
                        (size.height - inset * 2).coerceAtLeast(0f),
                    ),
                    cornerRadius = CornerRadius(cornerRadius, cornerRadius),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                )
                drawRoundRect(
                    brush = glowBrush,
                    topLeft = Offset(inset, inset),
                    size = Size(
                        (size.width - inset * 2).coerceAtLeast(0f),
                        (size.height - inset * 2).coerceAtLeast(0f),
                    ),
                    cornerRadius = CornerRadius(cornerRadius, cornerRadius),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx()),
                    alpha = 0.22f,
                )
                drawRoundRect(
                    brush = glowBrush,
                    topLeft = Offset(inset, inset),
                    size = Size(
                        (size.width - inset * 2).coerceAtLeast(0f),
                        (size.height - inset * 2).coerceAtLeast(0f),
                    ),
                    cornerRadius = CornerRadius(cornerRadius, cornerRadius),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2.dp.toPx()),
                )
            }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = status,
            color = KimiMuted,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

