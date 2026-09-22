@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.yukisoffd.lyracode.filemanager

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.yukisoffd.lyracode.ChatController
import com.yukisoffd.lyracode.ChatScreen
import com.yukisoffd.lyracode.R
import com.yukisoffd.lyracode.ToolApprovalDialog
import com.yukisoffd.lyracode.predictiveBackTransform
import com.yukisoffd.lyracode.rememberPredictiveBackGestureState
import com.yukisoffd.lyracode.ai.ChatRecord
import com.yukisoffd.lyracode.ai.AgentFileEditResult
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.debian.ProotLinuxInstance
import com.yukisoffd.lyracode.debian.ProotLinuxManager
import com.yukisoffd.lyracode.system.SystemCommandExecutor
import com.yukisoffd.lyracode.termux.TermuxExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import kotlin.math.abs
import rikka.shizuku.Shizuku

@Composable
internal fun FileManagerScreen(
    controller: ChatController,
    settings: AppSettings,
    termuxExecutor: TermuxExecutor,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val systemCommandExecutor = remember(context, settings) { SystemCommandExecutor(context, settings) }
    val fileOperations = remember(context, settings, systemCommandExecutor) {
        PrivilegedFileOperations(context, settings, systemCommandExecutor)
    }
    var permissionRevision by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionRevision++
    }
    val legacyPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionRevision++
    }
    val hasPermission = remember(permissionRevision) { hasFileManagerPermission(context) }
    val prootInstancesDirectory = remember(context) {
        File(context.filesDir, "proot-linux/instances").apply { mkdirs() }
    }
    val prootLinuxManager = remember(context) { ProotLinuxManager.getInstance(context) }
    val prootLinuxState by prootLinuxManager.state.collectAsState()
    LaunchedEffect(prootLinuxManager) { prootLinuxManager.refresh() }
    val requestSharedStorageAccess: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val packageUri = Uri.parse("package:${context.packageName}")
            val permissionIntents = listOf(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
            )
            val launched = permissionIntents.any { intent ->
                runCatching {
                    permissionLauncher.launch(intent)
                    true
                }.getOrDefault(false)
            }
            if (!launched) {
                Toast.makeText(
                    context,
                    context.getString(R.string.file_permission_settings_unavailable),
                    Toast.LENGTH_LONG,
                ).show()
            }
        } else {
            runCatching { legacyPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE) }
                .onFailure { showError(context, it) }
        }
        Unit
    }
    BackHandler(enabled = !settings.predictiveBackEnabled, onBack = onExit)

    var openFile by remember { mutableStateOf<Pair<File, TextFileContent>?>(null) }
    var previewFile by remember { mutableStateOf<Pair<File, MediaPreviewKind>?>(null) }
    var pendingPackageInstall by remember { mutableStateOf<File?>(null) }
    val scope = rememberCoroutineScope()
    val packageInstaller = remember(context, settings, systemCommandExecutor, termuxExecutor) {
        AndroidPackageInstaller(context, settings, systemCommandExecutor, termuxExecutor)
    }
    LaunchedEffect(openFile?.first?.absolutePath) {
        controller.setEditorContextPath(openFile?.first?.absolutePath)
    }
    DisposableEffect(Unit) {
        onDispose { controller.setEditorContextPath(null) }
    }
    val followAiFile = controller.editorFileFollowRequests.firstOrNull()
    val pendingAiMutation = controller.editorFileMutations.firstOrNull { !it.committed }
    val aiFileChangeRevision = controller.editorFileChangeRevision.intValue
    LaunchedEffect(followAiFile?.id, pendingAiMutation?.id) {
        val followPath = followAiFile?.path ?: pendingAiMutation?.path ?: return@LaunchedEffect
        val beforeSnapshot = followAiFile?.content ?: pendingAiMutation?.beforeContent
        val current = openFile?.first
        val target = File(followPath)
        val sameFile = current?.let { filesReferToSamePath(it, target) } == true
        if (!sameFile) {
            if (beforeSnapshot != null) {
                openFile = target to TextFileContent(beforeSnapshot, hasUtf8Errors = false)
            } else {
                withContext(Dispatchers.IO) { fileOperations.readUtf8(target) }
                    .onSuccess { openFile = target to it }
            }
        }
        followAiFile?.let { controller.consumeEditorFileFollowRequest(it.id) }
    }
    Box(Modifier.fillMaxSize()) {
        DualPaneFileManager(
            fileOperations = fileOperations,
            settings = settings,
            systemCommandExecutor = systemCommandExecutor,
            onExit = onExit,
            externalRefreshRevision = aiFileChangeRevision,
            prootInstancesDirectory = prootInstancesDirectory,
            prootInstances = prootLinuxState.instances,
            sharedStorageGranted = hasPermission,
            onRequestSharedStorageAccess = requestSharedStorageAccess,
            onOpenFile = { file ->
                if (isAndroidPackageFile(file)) {
                    pendingPackageInstall = file
                    return@DualPaneFileManager
                }
                val mediaKind = mediaPreviewKind(file)
                if (mediaKind != null) {
                    openFile = null
                    scope.launch {
                        fileOperations.prepareReadableCopy(file)
                            .onSuccess { previewFile = it to mediaKind }
                            .onFailure { showError(context, it) }
                    }
                    return@DualPaneFileManager
                }
                scope.launch {
                    val result = withContext(Dispatchers.IO) { fileOperations.readUtf8(file) }
                    result.onSuccess { openFile = file to it }
                        .onFailure { openExternalFile(context, file) }
                }
            },
            onOpenFileAs = { file, mode ->
                when (mode) {
                    FileOpenMode.TEXT -> {
                        previewFile = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                fileOperations.readUtf8(file, allowBinaryPreview = true)
                            }.onSuccess { openFile = file to it }
                                .onFailure { showError(context, it) }
                        }
                    }
                    FileOpenMode.IMAGE, FileOpenMode.VIDEO, FileOpenMode.AUDIO -> {
                        openFile = null
                        val mediaKind = when (mode) {
                            FileOpenMode.IMAGE -> MediaPreviewKind.IMAGE
                            FileOpenMode.VIDEO -> MediaPreviewKind.VIDEO
                            FileOpenMode.AUDIO -> MediaPreviewKind.AUDIO
                            FileOpenMode.TEXT -> error("Text mode is handled separately")
                        }
                        scope.launch {
                            fileOperations.prepareReadableCopy(file)
                                .onSuccess { previewFile = it to mediaKind }
                                .onFailure { showError(context, it) }
                        }
                    }
                }
            },
        )
        pendingPackageInstall?.let { packageFile ->
            AlertDialog(
                onDismissRequest = { pendingPackageInstall = null },
                title = { Text(context.getString(R.string.apk_install_title)) },
                text = { Text(context.getString(R.string.apk_install_message, packageFile.name)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingPackageInstall = null
                            Toast.makeText(context, R.string.apk_installing, Toast.LENGTH_SHORT).show()
                            scope.launch {
                                val result = packageInstaller.install(packageFile)
                                if (result.installed) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.apk_install_success, result.mode?.displayName.orEmpty()),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                } else {
                                    val readableFile = withContext(Dispatchers.IO) {
                                        fileOperations.prepareReadableCopy(packageFile).getOrElse { packageFile }
                                    }
                                    runCatching { launchSystemPackageInstaller(context, readableFile) }
                                        .onFailure { showError(context, it) }
                                    if (readableFile != packageFile) readableFile.delete()
                                }
                            }
                        },
                    ) { Text(context.getString(R.string.apk_install_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingPackageInstall = null }) {
                        Text(context.getString(android.R.string.cancel))
                    }
                },
            )
        }
        AnimatedContent(
            targetState = previewFile,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
            contentKey = { it?.first?.absolutePath },
            label = "media-preview-transition",
            modifier = Modifier.fillMaxSize(),
        ) { previewState ->
            previewState?.let { (file, kind) ->
                MediaPreviewScreen(
                    file = file,
                    kind = kind,
                    predictiveBackEnabled = settings.predictiveBackEnabled,
                    onClose = { previewFile = null },
                    onOpenExternal = { openExternalFile(context, file) },
                )
            }
        }
        AnimatedContent(
            targetState = openFile,
            transitionSpec = {
                when {
                    initialState == null && targetState != null ->
                        (slideInHorizontally(tween(240)) { it / 3 } + fadeIn(tween(180))) togetherWith
                            fadeOut(tween(120))
                    initialState != null && targetState == null ->
                        fadeIn(tween(160)) togetherWith
                            (slideOutHorizontally(tween(240)) { it / 3 } + fadeOut(tween(180)))
                    else -> fadeIn(tween(160)) togetherWith fadeOut(tween(140))
                }
            },
            contentKey = { it?.first?.absolutePath },
            label = "file-editor-transition",
            modifier = Modifier.fillMaxSize(),
        ) { editorState ->
            editorState?.let { (file, content) ->
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    FileEditorScreen(
                        file = file,
                        loaded = content,
                        controller = controller,
                        settings = settings,
                        termuxExecutor = termuxExecutor,
                        fileOperations = fileOperations,
                        onClose = { openFile = null },
                    )
                }
            }
        }
    }
}

@Composable
private fun PrivilegedFileAccessDialog(
    settings: AppSettings,
    executor: SystemCommandExecutor,
    fileOperations: PrivilegedFileOperations,
    revision: Int,
    status: String,
    onStatus: (String) -> Unit,
    onRevision: () -> Unit,
    onDismiss: () -> Unit,
    onAccessReady: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shizukuRunning = remember(revision) { executor.isShizukuRunning() }
    val shellGranted = remember(revision) { executor.hasShellPermission() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.file_privileged_access)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    context.getString(R.string.file_privileged_access_message),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        settings.requestRootAccess = true
                        onStatus(context.getString(R.string.file_privileged_testing_root))
                        onRevision()
                        scope.launch {
                            val result = executor.probeRoot()
                            val rootReady = result.ok && result.stdout.trim().lineSequence().lastOrNull() == "0"
                            if (!rootReady) {
                                settings.requestRootAccess = false
                                onRevision()
                            }
                            onStatus(if (rootReady) {
                                context.getString(R.string.file_privileged_root_ready)
                            } else {
                                result.stderr.ifBlank { result.message }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(context.getString(R.string.file_privileged_use_root))
                }
                OutlinedButton(
                    onClick = {
                        settings.requestShellAccess = true
                        onRevision()
                        when {
                            !shizukuRunning -> onStatus(context.getString(R.string.file_privileged_shizuku_not_running))
                            !shellGranted -> {
                                onStatus(context.getString(R.string.file_privileged_requesting_shell))
                                Shizuku.requestPermission(FILE_SHIZUKU_PERMISSION_REQUEST_CODE)
                            }
                            else -> onStatus(context.getString(R.string.file_privileged_shell_ready))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(context.getString(R.string.file_privileged_use_shizuku))
                }
                Button(
                    onClick = {
                        onStatus(context.getString(R.string.file_privileged_testing_access))
                        scope.launch {
                            fileOperations.testAccess()
                                .onSuccess { mode ->
                                    onStatus(context.getString(R.string.file_privileged_access_ready, mode))
                                    onAccessReady()
                                }
                                .onFailure { onStatus(it.message.orEmpty()) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(context.getString(R.string.file_privileged_test_access))
                }
                if (status.isNotBlank()) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (
                            status.startsWith(context.getString(R.string.file_privileged_access_ready_prefix))
                        ) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(context.getString(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    settings.requestRootAccess = false
                    settings.requestShellAccess = false
                    onRevision()
                    onStatus(context.getString(R.string.file_privileged_disabled))
                },
            ) {
                Text(context.getString(R.string.file_privileged_disable))
            }
        },
    )
}

@Composable
private fun DualPaneFileManager(
    fileOperations: PrivilegedFileOperations,
    settings: AppSettings,
    systemCommandExecutor: SystemCommandExecutor,
    onOpenFile: (File) -> Unit,
    onOpenFileAs: (File, FileOpenMode) -> Unit,
    onExit: () -> Unit,
    externalRefreshRevision: Int,
    prootInstancesDirectory: File,
    prootInstances: List<ProotLinuxInstance>,
    sharedStorageGranted: Boolean,
    onRequestSharedStorageAccess: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val root = LocalFileOperations.storageRoot.absolutePath
    val privateRoot = prootInstances.firstOrNull()?.rootfsDir ?: prootInstancesDirectory
    val initialPath = if (sharedStorageGranted) root else privateRoot.absolutePath
    var leftPath by rememberSaveable(initialPath) { mutableStateOf(initialPath) }
    var rightPath by rememberSaveable(initialPath) { mutableStateOf(initialPath) }
    var leftNavigationRootPath by rememberSaveable(initialPath) { mutableStateOf(initialPath) }
    var rightNavigationRootPath by rememberSaveable(initialPath) { mutableStateOf(initialPath) }
    var leftRefresh by remember { mutableIntStateOf(0) }
    var rightRefresh by remember { mutableIntStateOf(0) }
    var activePane by rememberSaveable { mutableIntStateOf(0) }
    val leftScrollPositions = remember { mutableMapOf<String, PaneScrollPosition>() }
    val rightScrollPositions = remember { mutableMapOf<String, PaneScrollPosition>() }
    var action by remember { mutableStateOf<PaneAction?>(null) }
    var deleteTarget by remember { mutableStateOf<PaneAction?>(null) }
    var renameTarget by remember { mutableStateOf<PaneAction?>(null) }
    var propertiesTarget by remember { mutableStateOf<File?>(null) }
    var openWithTarget by remember { mutableStateOf<File?>(null) }
    var createFolderPane by remember { mutableStateOf<Int?>(null) }
    var createFilePane by remember { mutableStateOf<Int?>(null) }
    var leftSelection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var rightSelection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingBatchOperation by remember { mutableStateOf<PendingBatchOperation?>(null) }
    var leftSummary by remember { mutableStateOf(PaneSummary()) }
    var rightSummary by remember { mutableStateOf(PaneSummary()) }
    var menuOpen by remember { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchScopeName by rememberSaveable { mutableStateOf(FileSearchScope.ALL.name) }
    var searchResults by remember { mutableStateOf<List<LocalFileEntry>>(emptyList()) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf("") }
    var leftSortName by rememberSaveable { mutableStateOf(FileSortMode.NAME.name) }
    var rightSortName by rememberSaveable { mutableStateOf(FileSortMode.NAME.name) }
    var leftSortDescending by rememberSaveable { mutableStateOf(false) }
    var rightSortDescending by rememberSaveable { mutableStateOf(false) }
    var privilegedDialogOpen by rememberSaveable { mutableStateOf(false) }
    var privilegedRevision by remember { mutableIntStateOf(0) }
    var privilegedStatus by remember { mutableStateOf("") }
    var skipNextLeftTransition by remember { mutableStateOf(false) }
    var skipNextRightTransition by remember { mutableStateOf(false) }
    var instancesWarningPane by remember { mutableStateOf<Int?>(null) }
    val shizukuPermissionListener = remember {
        Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == FILE_SHIZUKU_PERMISSION_REQUEST_CODE) privilegedRevision++
        }
    }
    DisposableEffect(Unit) {
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        onDispose { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
    }

    val activePath = if (activePane == 0) leftPath else rightPath
    val activeNavigationRootPath = if (activePane == 0) leftNavigationRootPath else rightNavigationRootPath
    val activeRefresh = if (activePane == 0) leftRefresh else rightRefresh
    val activeSummary = if (activePane == 0) leftSummary else rightSummary
    val activeSortMode = FileSortMode.valueOf(if (activePane == 0) leftSortName else rightSortName)
    val activeSortDescending = if (activePane == 0) leftSortDescending else rightSortDescending
    val searchScope = FileSearchScope.valueOf(searchScopeName)

    LaunchedEffect(activePath, activeRefresh, searchOpen, searchQuery, searchScope) {
        if (!searchOpen || searchQuery.isBlank()) {
            searchResults = emptyList()
            searchLoading = false
            searchError = ""
            return@LaunchedEffect
        }
        searchLoading = true
        searchError = ""
        delay(280)
        val result = withContext(Dispatchers.IO) {
            fileOperations.search(
                directory = File(activePath),
                query = searchQuery,
                includeFiles = searchScope != FileSearchScope.FOLDERS,
                includeDirectories = searchScope != FileSearchScope.FILES,
            )
        }
        result.onSuccess { searchResults = it }.onFailure { searchError = it.message.orEmpty() }
        searchLoading = false
    }

    fun refreshBoth() {
        leftRefresh++
        rightRefresh++
    }

    LaunchedEffect(externalRefreshRevision) {
        if (externalRefreshRevision > 0) refreshBoth()
    }

    fun perform(onSuccess: () -> Unit = {}, block: suspend () -> Result<*>) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { block() }
            result.onSuccess {
                onSuccess()
                refreshBoth()
                Toast.makeText(context, context.getString(R.string.file_operation_done), Toast.LENGTH_SHORT).show()
            }.onFailure { showError(context, it) }
        }
    }

    fun navigate(pane: Int, directory: File, navigationRoot: File? = null) {
        activePane = pane
        if (pane == 0) {
            leftSelection = emptySet()
            leftPath = directory.absolutePath
            navigationRoot?.let { leftNavigationRootPath = it.absolutePath }
        } else {
            rightSelection = emptySet()
            rightPath = directory.absolutePath
            navigationRoot?.let { rightNavigationRootPath = it.absolutePath }
        }
    }

    fun toggleSelection(pane: Int, file: File) {
        activePane = pane
        val path = file.absolutePath
        if (pane == 0) {
            rightSelection = emptySet()
            leftSelection = if (path in leftSelection) leftSelection - path else leftSelection + path
        } else {
            leftSelection = emptySet()
            rightSelection = if (path in rightSelection) rightSelection - path else rightSelection + path
        }
    }

    fun clearSelection() {
        leftSelection = emptySet()
        rightSelection = emptySet()
    }

    fun activeBatchSelection(): BatchSelection {
        return if (leftSelection.isNotEmpty()) {
            BatchSelection(0, leftSelection.map(::File))
        } else {
            BatchSelection(1, rightSelection.map(::File))
        }
    }

    fun navigateActivePaneBack() {
        val current = File(if (activePane == 0) leftPath else rightPath)
        val navigationRoot = File(if (activePane == 0) leftNavigationRootPath else rightNavigationRootPath)
        if (isFileManagerNavigationRoot(current, navigationRoot)) {
            onExit()
        } else {
            current.parentFile?.let { navigate(activePane, it) } ?: onExit()
        }
    }

    val predictiveBackState = rememberPredictiveBackGestureState(
        // At the storage root the app shell owns the gesture so it can draw the
        // conversation page underneath the complete file-manager surface.
        enabled = settings.predictiveBackEnabled && !isFileManagerNavigationRoot(File(activePath), File(activeNavigationRootPath)),
        onBack = {
            if (activePane == 0) skipNextLeftTransition = true else skipNextRightTransition = true
            navigateActivePaneBack()
        },
    )
    BackHandler(enabled = !settings.predictiveBackEnabled, onBack = ::navigateActivePaneBack)
    LaunchedEffect(leftPath) { skipNextLeftTransition = false }
    LaunchedEffect(rightPath) { skipNextRightTransition = false }

    if (privilegedDialogOpen) {
        PrivilegedFileAccessDialog(
            settings = settings,
            executor = systemCommandExecutor,
            fileOperations = fileOperations,
            revision = privilegedRevision,
            status = privilegedStatus,
            onStatus = { privilegedStatus = it },
            onRevision = { privilegedRevision++ },
            onDismiss = { privilegedDialogOpen = false },
            onAccessReady = {
                refreshBoth()
                privilegedDialogOpen = false
            },
        )
    }
    instancesWarningPane?.let { pane ->
        AlertDialog(
            onDismissRequest = { instancesWarningPane = null },
            title = { Text(context.getString(R.string.file_open_proot_instances)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(context.getString(R.string.file_proot_instances_warning))
                    if (prootInstances.isEmpty()) {
                        Text(
                            context.getString(R.string.file_proot_instances_empty),
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        prootInstances.forEach { instance ->
                            TextButton(
                                onClick = {
                                    instancesWarningPane = null
                                    navigate(pane, instance.rootfsDir, instance.rootfsDir)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(instance.name)
                                    Text(
                                        context.getString(R.string.file_proot_instance_id, instance.id),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { instancesWarningPane = null }) {
                    Text(context.getString(android.R.string.cancel))
                }
            },
        )
    }

    action?.let { selected ->
        FileActionDialog(
            target = selected.file,
            onDismiss = { action = null },
            onCopy = {
                action = null
                val destination = File(if (selected.pane == 0) rightPath else leftPath)
                perform { fileOperations.copy(selected.file, destination) }
            },
            onMove = {
                action = null
                val destination = File(if (selected.pane == 0) rightPath else leftPath)
                perform { fileOperations.move(selected.file, destination) }
            },
            onRename = { action = null; renameTarget = selected },
            onProperties = { action = null; propertiesTarget = selected.file },
            onOpenWith = {
                action = null
                openWithTarget = selected.file
            },
            onPreviewHtml = {
                action = null
                previewHtmlInBrowser(context, selected.file)
            },
            onUnzip = {
                action = null
                perform { fileOperations.unzip(selected.file, selected.file.parentFile ?: File(root)) }
            },
            onDelete = { action = null; deleteTarget = selected },
        )
    }
    deleteTarget?.let { selected ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(context.getString(R.string.file_delete_title)) },
            text = { Text(context.getString(R.string.file_delete_message, selected.file.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    perform { fileOperations.delete(selected.file) }
                }) { Text(context.getString(R.string.file_action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(context.getString(android.R.string.cancel)) } },
        )
    }
    pendingBatchOperation?.let { pending ->
        val title = when (pending.operation) {
            BatchOperation.COPY -> R.string.file_batch_copy_title
            BatchOperation.MOVE -> R.string.file_batch_move_title
            BatchOperation.DELETE -> R.string.file_batch_delete_title
        }
        val message = when (pending.operation) {
            BatchOperation.COPY -> R.string.file_batch_copy_message
            BatchOperation.MOVE -> R.string.file_batch_move_message
            BatchOperation.DELETE -> R.string.file_batch_delete_message
        }
        AlertDialog(
            onDismissRequest = { pendingBatchOperation = null },
            title = { Text(context.getString(title)) },
            text = { Text(context.getString(message, pending.selection.files.size)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingBatchOperation = null
                    perform(onSuccess = ::clearSelection) {
                        when (pending.operation) {
                            BatchOperation.COPY -> fileOperations.copyAll(
                                pending.selection.files,
                                requireNotNull(pending.destination),
                            )
                            BatchOperation.MOVE -> fileOperations.moveAll(
                                pending.selection.files,
                                requireNotNull(pending.destination),
                            )
                            BatchOperation.DELETE -> fileOperations.deleteAll(pending.selection.files)
                        }
                    }
                }) {
                    Text(
                        context.getString(
                            when (pending.operation) {
                                BatchOperation.COPY -> R.string.file_action_copy
                                BatchOperation.MOVE -> R.string.file_action_move
                                BatchOperation.DELETE -> R.string.file_action_delete
                            },
                        ),
                        color = if (pending.operation == BatchOperation.DELETE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { pendingBatchOperation = null }) { Text(context.getString(android.R.string.cancel)) } },
        )
    }
    renameTarget?.let { selected ->
        NameDialog(
            title = context.getString(R.string.file_action_rename),
            initial = selected.file.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                perform { fileOperations.rename(selected.file, name) }
            },
        )
    }
    createFolderPane?.let { pane ->
        NameDialog(
            title = context.getString(R.string.file_create_folder),
            initial = "",
            onDismiss = { createFolderPane = null },
            onConfirm = { name ->
                createFolderPane = null
                perform { fileOperations.createDirectory(File(if (pane == 0) leftPath else rightPath), name) }
            },
        )
    }
    createFilePane?.let { pane ->
        NameDialog(
            title = context.getString(R.string.file_create_file),
            initial = "",
            onDismiss = { createFilePane = null },
            onConfirm = { name ->
                createFilePane = null
                perform { fileOperations.createFile(File(if (pane == 0) leftPath else rightPath), name) }
            },
        )
    }
    propertiesTarget?.let { file ->
        FilePropertiesDialog(file = file, fileOperations = fileOperations, onDismiss = { propertiesTarget = null })
    }
    openWithTarget?.let { file ->
        OpenWithDialog(
            file = file,
            onDismiss = { openWithTarget = null },
            onOpen = { mode ->
                openWithTarget = null
                onOpenFileAs(file, mode)
            },
        )
    }

    Box(
        Modifier
            .fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize()) {
            FileManagerHeader(
                activePane = activePane,
                activePath = activePath,
                summary = activeSummary,
                menuOpen = menuOpen,
                searchOpen = searchOpen,
                searchQuery = searchQuery,
                searchScope = searchScope,
                searchResultCount = if (searchQuery.isNotBlank()) searchResults.size else null,
                sortMode = activeSortMode,
                sortDescending = activeSortDescending,
                privilegedAccessLabel = fileOperations.accessLabel(),
                onMenuOpen = { menuOpen = it },
                onSearchToggle = {
                    searchOpen = !searchOpen
                    if (!searchOpen) searchQuery = ""
                    menuOpen = false
                },
                onSearchQuery = { searchQuery = it },
                onSearchScope = { searchScopeName = it.name },
                onSortMode = { mode ->
                    if (activePane == 0) leftSortName = mode.name else rightSortName = mode.name
                    menuOpen = false
                },
                onToggleSortDirection = {
                    if (activePane == 0) leftSortDescending = !leftSortDescending
                    else rightSortDescending = !rightSortDescending
                    menuOpen = false
                },
                onPrivilegedAccess = {
                    menuOpen = false
                    privilegedDialogOpen = true
                },
                sharedStorageGranted = sharedStorageGranted,
                onOpenProotInstances = {
                    menuOpen = false
                    instancesWarningPane = activePane
                },
                onOpenSharedStorage = {
                    menuOpen = false
                    if (sharedStorageGranted) navigate(activePane, File(root), File(root)) else onRequestSharedStorageAccess()
                },
            )
            HorizontalDivider()
            Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clipToBounds()
                    .then(if (activePane == 0) Modifier.predictiveBackTransform(predictiveBackState) else Modifier),
            ) {
                AnimatedContent(
                    targetState = leftPath,
                    transitionSpec = {
                        if (skipNextLeftTransition) EnterTransition.None togetherWith ExitTransition.None
                        else fileNavigationTransition(initialState, targetState)
                    },
                    label = "left-directory-transition",
                    modifier = Modifier.fillMaxSize(),
                ) { animatedPath ->
                    FilePane(
                        fileOperations = fileOperations,
                        directory = File(animatedPath),
                        refreshToken = leftRefresh,
                        active = activePane == 0,
                        selectedPaths = leftSelection,
                        scrollPositions = leftScrollPositions,
                        modifier = Modifier.fillMaxSize(),
                        onActivate = { activePane = 0 },
                        onNavigate = { navigate(0, it) },
                        onOpenFile = { activePane = 0; onOpenFile(it) },
                        onLongPress = { activePane = 0; action = PaneAction(0, it) },
                        onToggleSelection = { toggleSelection(0, it) },
                        onCreateFolder = { activePane = 0; createFolderPane = 0 },
                        onCreateFile = { activePane = 0; createFilePane = 0 },
                        onRefresh = { activePane = 0; leftRefresh++ },
                        sortMode = FileSortMode.valueOf(leftSortName),
                        sortDescending = leftSortDescending,
                        searchResults = if (activePane == 0 && searchOpen && searchQuery.isNotBlank()) searchResults else null,
                        searchLoading = activePane == 0 && searchLoading,
                        searchError = if (activePane == 0) searchError else "",
                        searchRoot = File(leftPath),
                        onSummary = { leftSummary = it },
                        navigationRoot = File(leftNavigationRootPath),
                    )
                }
            }
            Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clipToBounds()
                    .then(if (activePane == 1) Modifier.predictiveBackTransform(predictiveBackState) else Modifier),
            ) {
                AnimatedContent(
                    targetState = rightPath,
                    transitionSpec = {
                        if (skipNextRightTransition) EnterTransition.None togetherWith ExitTransition.None
                        else fileNavigationTransition(initialState, targetState)
                    },
                    label = "right-directory-transition",
                    modifier = Modifier.fillMaxSize(),
                ) { animatedPath ->
                    FilePane(
                        fileOperations = fileOperations,
                        directory = File(animatedPath),
                        refreshToken = rightRefresh,
                        active = activePane == 1,
                        selectedPaths = rightSelection,
                        scrollPositions = rightScrollPositions,
                        modifier = Modifier.fillMaxSize(),
                        onActivate = { activePane = 1 },
                        onNavigate = { navigate(1, it) },
                        onOpenFile = { activePane = 1; onOpenFile(it) },
                        onLongPress = { activePane = 1; action = PaneAction(1, it) },
                        onToggleSelection = { toggleSelection(1, it) },
                        onCreateFolder = { activePane = 1; createFolderPane = 1 },
                        onCreateFile = { activePane = 1; createFilePane = 1 },
                        onRefresh = { activePane = 1; rightRefresh++ },
                        sortMode = FileSortMode.valueOf(rightSortName),
                        sortDescending = rightSortDescending,
                        searchResults = if (activePane == 1 && searchOpen && searchQuery.isNotBlank()) searchResults else null,
                        searchLoading = activePane == 1 && searchLoading,
                        searchError = if (activePane == 1) searchError else "",
                        searchRoot = File(rightPath),
                        onSummary = { rightSummary = it },
                        navigationRoot = File(rightNavigationRootPath),
                    )
                }
            }
        }
        }
        val selectedCount = leftSelection.size + rightSelection.size
        AnimatedVisibility(
            visible = selectedCount > 0,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(tonalElevation = 8.dp, shadowElevation = 10.dp) {
                Row(
                    Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Text(
                        context.getString(R.string.file_selected_count, selectedCount),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = {
                        val selected = activeBatchSelection()
                        val destination = File(if (selected.pane == 0) rightPath else leftPath)
                        pendingBatchOperation = PendingBatchOperation(BatchOperation.COPY, selected, destination)
                    }) { Icon(Icons.Default.ContentCopy, contentDescription = context.getString(R.string.file_batch_copy)) }
                    IconButton(onClick = {
                        val selected = activeBatchSelection()
                        val destination = File(if (selected.pane == 0) rightPath else leftPath)
                        pendingBatchOperation = PendingBatchOperation(BatchOperation.MOVE, selected, destination)
                    }) { Icon(Icons.Default.DriveFileMove, contentDescription = context.getString(R.string.file_batch_move)) }
                    IconButton(onClick = {
                        pendingBatchOperation = PendingBatchOperation(BatchOperation.DELETE, activeBatchSelection())
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = context.getString(R.string.file_batch_delete), tint = MaterialTheme.colorScheme.error)
                    }
                    IconButton(onClick = ::clearSelection) { Icon(Icons.Default.Close, contentDescription = context.getString(R.string.file_clear_selection)) }
                }
            }
        }
    }
}

private enum class FileSearchScope { ALL, FILES, FOLDERS }

private enum class FileSortMode { NAME, MODIFIED, SIZE, TYPE }

private data class PaneSummary(
    val folderCount: Int = 0,
    val fileCount: Int = 0,
    val loading: Boolean = true,
)

@Composable
private fun FileManagerHeader(
    activePane: Int,
    activePath: String,
    summary: PaneSummary,
    menuOpen: Boolean,
    searchOpen: Boolean,
    searchQuery: String,
    searchScope: FileSearchScope,
    searchResultCount: Int?,
    sortMode: FileSortMode,
    sortDescending: Boolean,
    privilegedAccessLabel: String,
    onMenuOpen: (Boolean) -> Unit,
    onSearchToggle: () -> Unit,
    onSearchQuery: (String) -> Unit,
    onSearchScope: (FileSearchScope) -> Unit,
    onSortMode: (FileSortMode) -> Unit,
    onToggleSortDirection: () -> Unit,
    onPrivilegedAccess: () -> Unit,
    sharedStorageGranted: Boolean,
    onOpenProotInstances: () -> Unit,
    onOpenSharedStorage: () -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    context.getString(if (activePane == 0) R.string.file_manager_left_pane else R.string.file_manager_right_pane),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    activePath,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        searchResultCount != null -> context.getString(R.string.file_manager_search_results, searchResultCount)
                        summary.loading -> context.getString(R.string.file_loading)
                        else -> context.getString(
                            R.string.file_manager_item_summary,
                            summary.folderCount,
                            summary.fileCount,
                        )
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Box {
                IconButton(onClick = { onMenuOpen(true) }) {
                    Icon(Icons.Default.MoreVert, contentDescription = context.getString(R.string.file_manager_menu))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpen(false) }) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(context.getString(R.string.file_privileged_access))
                                if (privilegedAccessLabel.isNotBlank()) {
                                    Text(
                                        privilegedAccessLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        },
                        leadingIcon = { Icon(Icons.Default.Security, contentDescription = null) },
                        onClick = onPrivilegedAccess,
                    )
                    DropdownMenuItem(
                        text = { Text(context.getString(R.string.file_open_proot_instances)) },
                        leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                        onClick = onOpenProotInstances,
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                context.getString(
                                    if (sharedStorageGranted) R.string.file_open_shared_storage else R.string.file_permission_grant,
                                ),
                            )
                        },
                        leadingIcon = { Icon(if (sharedStorageGranted) Icons.Default.FolderOpen else Icons.Default.Security, contentDescription = null) },
                        onClick = onOpenSharedStorage,
                    )
                    DropdownMenuItem(
                        text = { Text(context.getString(R.string.file_manager_search)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        onClick = onSearchToggle,
                    )
                    HorizontalDivider()
                    Text(
                        context.getString(R.string.file_manager_sort_by),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    FileSortMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(context.getString(mode.labelResource())) },
                            leadingIcon = {
                                if (mode == sortMode) Icon(Icons.Default.CheckCircle, contentDescription = null)
                            },
                            onClick = { onSortMode(mode) },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(context.getString(if (sortDescending) R.string.file_manager_sort_descending else R.string.file_manager_sort_ascending))
                        },
                        leadingIcon = { Icon(Icons.Default.UnfoldMore, contentDescription = null) },
                        onClick = onToggleSortDirection,
                    )
                }
            }
        }
        AnimatedVisibility(visible = searchOpen) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQuery,
                    placeholder = { Text(context.getString(R.string.file_manager_search_hint)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { if (searchQuery.isEmpty()) onSearchToggle() else onSearchQuery("") }) {
                            Icon(Icons.Default.Close, contentDescription = context.getString(R.string.file_clear_selection))
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    FileSearchScope.entries.forEach { scope ->
                        TextButton(onClick = { onSearchScope(scope) }) {
                            Text(
                                context.getString(scope.labelResource()),
                                color = if (scope == searchScope) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (scope == searchScope) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun FileSearchScope.labelResource(): Int = when (this) {
    FileSearchScope.ALL -> R.string.file_manager_search_all
    FileSearchScope.FILES -> R.string.file_manager_search_files
    FileSearchScope.FOLDERS -> R.string.file_manager_search_folders
}

private fun FileSortMode.labelResource(): Int = when (this) {
    FileSortMode.NAME -> R.string.file_manager_sort_name
    FileSortMode.MODIFIED -> R.string.file_manager_sort_modified
    FileSortMode.SIZE -> R.string.file_manager_sort_size
    FileSortMode.TYPE -> R.string.file_manager_sort_type
}

private fun sortFileEntries(
    entries: List<LocalFileEntry>,
    mode: FileSortMode,
    descending: Boolean,
): List<LocalFileEntry> {
    return entries.sortedWith { first, second ->
        if (first.directory != second.directory) {
            if (first.directory) -1 else 1
        } else {
            val comparison = when (mode) {
                FileSortMode.NAME -> first.name.compareTo(second.name, ignoreCase = true)
                FileSortMode.MODIFIED -> first.modifiedAt.compareTo(second.modifiedAt)
                FileSortMode.SIZE -> first.size.compareTo(second.size)
                FileSortMode.TYPE -> first.file.extension.compareTo(second.file.extension, ignoreCase = true)
            }.let { if (it == 0) first.name.compareTo(second.name, ignoreCase = true) else it }
            if (descending) -comparison else comparison
        }
    }
}

@Composable
private fun FilePane(
    fileOperations: PrivilegedFileOperations,
    directory: File,
    refreshToken: Int,
    active: Boolean,
    selectedPaths: Set<String>,
    scrollPositions: MutableMap<String, PaneScrollPosition>,
    modifier: Modifier,
    onActivate: () -> Unit,
    onNavigate: (File) -> Unit,
    onOpenFile: (File) -> Unit,
    onLongPress: (File) -> Unit,
    onToggleSelection: (File) -> Unit,
    onCreateFolder: () -> Unit,
    onCreateFile: () -> Unit,
    onRefresh: () -> Unit,
    sortMode: FileSortMode,
    sortDescending: Boolean,
    searchResults: List<LocalFileEntry>?,
    searchLoading: Boolean,
    searchError: String,
    searchRoot: File,
    onSummary: (PaneSummary) -> Unit,
    navigationRoot: File,
) {
    val context = LocalContext.current
    val directoryPath = directory.absolutePath
    val initialScrollPosition = remember(directoryPath) {
        scrollPositions[directoryPath] ?: PaneScrollPosition(0, 0)
    }
    val listState = remember(directoryPath) {
        LazyListState(
            firstVisibleItemIndex = initialScrollPosition.index,
            firstVisibleItemScrollOffset = initialScrollPosition.offset,
        )
    }
    var entries by remember(directory.absolutePath) { mutableStateOf<List<LocalFileEntry>>(emptyList()) }
    var loading by remember(directory.absolutePath) { mutableStateOf(true) }
    var error by remember(directory.absolutePath) { mutableStateOf("") }
    var createMenuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(directory.absolutePath, refreshToken) {
        loading = true
        val result = withContext(Dispatchers.IO) { fileOperations.list(directory) }
        result.onSuccess { entries = it; error = "" }.onFailure { error = it.message.orEmpty() }
        loading = false
    }
    LaunchedEffect(entries, loading) {
        onSummary(
            PaneSummary(
                folderCount = entries.count { it.directory },
                fileCount = entries.count { !it.directory },
                loading = loading,
            ),
        )
    }
    LaunchedEffect(directoryPath, listState) {
        snapshotFlow { PaneScrollPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            .collect { scrollPositions[directoryPath] = it }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) onActivate()
        }
    }
    val displayedEntries = remember(entries, searchResults, sortMode, sortDescending) {
        sortFileEntries(searchResults ?: entries, sortMode, sortDescending)
    }
    val displayedLoading = if (searchResults != null) searchLoading else loading
    val displayedError = if (searchResults != null) searchError else error
    Column(
        modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 2.dp,
                color = if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
            ),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                displayedLoading -> Text(context.getString(R.string.file_loading), modifier = Modifier.align(Alignment.Center))
                displayedError.isNotBlank() -> Text(displayedError, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                displayedEntries.isEmpty() -> Text(
                    context.getString(if (searchResults != null) R.string.file_manager_search_empty else R.string.file_empty_folder),
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(displayedEntries, key = { it.file.absolutePath }) { entry ->
                        FileRow(
                            entry = entry,
                            selected = entry.file.absolutePath in selectedPaths,
                            parentPath = if (searchResults != null) {
                                entry.file.parentFile?.relativeToOrNull(searchRoot)?.path?.ifBlank { File.separator }
                            } else null,
                            onClick = {
                                onActivate()
                                if (selectedPaths.isNotEmpty()) {
                                    onToggleSelection(entry.file)
                                } else if (entry.directory) {
                                    onNavigate(entry.file)
                                } else {
                                    onOpenFile(entry.file)
                                }
                            },
                            onLongClick = {
                                onActivate()
                                if (selectedPaths.isNotEmpty()) onToggleSelection(entry.file) else onLongPress(entry.file)
                            },
                            onSwipe = {
                                onActivate()
                                onToggleSelection(entry.file)
                            },
                        )
                    }
                }
            }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                enabled = !isFileManagerNavigationRoot(directory, navigationRoot),
                onClick = {
                    onActivate()
                    directory.parentFile?.let(onNavigate)
                },
            ) { Icon(Icons.Default.ArrowUpward, contentDescription = context.getString(R.string.file_parent_folder)) }
            IconButton(
                onClick = {
                    onActivate()
                    fileOperations.invalidate(directory)
                    onRefresh()
                },
            ) {
                Icon(Icons.Default.Refresh, contentDescription = context.getString(R.string.file_refresh))
            }
            Box {
                IconButton(onClick = { onActivate(); createMenuOpen = true }) {
                    Icon(Icons.Default.Add, contentDescription = context.getString(R.string.file_create))
                }
                DropdownMenu(expanded = createMenuOpen, onDismissRequest = { createMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(context.getString(R.string.file_create_folder)) },
                        leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                        onClick = { createMenuOpen = false; onCreateFolder() },
                    )
                    DropdownMenuItem(
                        text = { Text(context.getString(R.string.file_create_file)) },
                        leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                        onClick = { createMenuOpen = false; onCreateFile() },
                    )
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    entry: LocalFileEntry,
    selected: Boolean,
    parentPath: String? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipe: () -> Unit,
) {
    var horizontalDrag by remember(entry.file.absolutePath) { mutableFloatStateOf(0f) }
    var dragging by remember(entry.file.absolutePath) { mutableStateOf(false) }
    val displayedOffset by animateFloatAsState(
        targetValue = if (dragging) horizontalDrag else 0f,
        animationSpec = if (dragging) snap() else spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "file-row-swipe-offset",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                if (abs(displayedOffset) > 1f) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                else Color.Transparent,
            ),
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .align(if (displayedOffset >= 0f) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 16.dp)
                .graphicsLayer { alpha = (abs(displayedOffset) / 80f).coerceIn(0f, 1f) },
        )
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = displayedOffset }
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                .pointerInput(entry.file.absolutePath) {
                    val maximumDrag = 88.dp.toPx()
                    val selectionThreshold = 48.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = {
                            horizontalDrag = 0f
                            dragging = true
                        },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            horizontalDrag = (horizontalDrag + amount).coerceIn(-maximumDrag, maximumDrag)
                        },
                        onDragEnd = {
                            if (abs(horizontalDrag) >= selectionThreshold) onSwipe()
                            horizontalDrag = 0f
                            dragging = false
                        },
                        onDragCancel = {
                            horizontalDrag = 0f
                            dragging = false
                        },
                    )
                }
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 8.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(42.dp),
            ) {
                FileVisual(entry)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(
                    parentPath ?: if (entry.directory) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(entry.modifiedAt)
                    else formatBytes(entry.size),
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun FileVisual(entry: LocalFileEntry) {
    if (entry.directory) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(25.dp))
        }
        return
    }
    val extension = entry.file.extension.lowercase()
    val mediaKind = mediaPreviewKind(entry.file)
    when {
        mediaKind == MediaPreviewKind.IMAGE -> ImageThumbnail(entry.file)
        mediaKind == MediaPreviewKind.VIDEO -> FileCategoryIcon(Icons.Default.Movie, MaterialTheme.colorScheme.primary)
        mediaKind == MediaPreviewKind.AUDIO -> FileCategoryIcon(Icons.Default.MusicNote, Color(0xFF7E57C2))
        isAndroidPackageFile(entry.file) -> FileCategoryIcon(Icons.Default.Android, Color(0xFF3DDC84))
        extension in archiveExtensions -> FileCategoryIcon(Icons.Default.UnfoldMore, Color(0xFFFF8F00))
        extension in codeLanguageMarks -> CodeLanguageBadge(extension)
        extension == "pdf" -> FormatBadge("PDF", Color(0xFFD32F2F))
        extension in wordExtensions -> FormatBadge("DOC", Color(0xFF1976D2))
        extension in spreadsheetExtensions -> FormatBadge("XLS", Color(0xFF2E7D32))
        extension in presentationExtensions -> FormatBadge("PPT", Color(0xFFEF6C00))
        else -> FileCategoryIcon(Icons.Default.Description, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ImageThumbnail(file: File) {
    val bitmap by produceState<Bitmap?>(initialValue = null, file.absolutePath, file.lastModified()) {
        value = withContext(Dispatchers.IO) { loadImageThumbnail(file) }
    }
    if (bitmap != null) {
        Image(
            bitmap = requireNotNull(bitmap).asImageBitmap(),
            contentDescription = file.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        FileCategoryIcon(Icons.Default.ImageIcon, MaterialTheme.colorScheme.primary)
    }
}

private fun loadImageThumbnail(file: File): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 160) sample *= 2
    BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        },
    )
}.getOrNull()

@Composable
private fun FileCategoryIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun CodeLanguageBadge(extension: String) {
    val (mark, color) = codeLanguageMarks.getValue(extension)
    FormatBadge(mark, color)
}

@Composable
private fun FormatBadge(mark: String, color: Color) {
    Box(Modifier.fillMaxSize().background(color), contentAlignment = Alignment.Center) {
        Text(
            mark,
            color = Color.White,
            fontWeight = FontWeight.ExtraBold,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

private val codeLanguageMarks = mapOf(
    "kt" to ("KT" to Color(0xFF7F52FF)),
    "kts" to ("KT" to Color(0xFF7F52FF)),
    "java" to ("JAVA" to Color(0xFFE76F00)),
    "py" to ("PY" to Color(0xFF3776AB)),
    "js" to ("JS" to Color(0xFFF0DB4F)),
    "jsx" to ("JSX" to Color(0xFF087EA4)),
    "ts" to ("TS" to Color(0xFF3178C6)),
    "tsx" to ("TSX" to Color(0xFF3178C6)),
    "html" to ("<>" to Color(0xFFE34F26)),
    "htm" to ("<>" to Color(0xFFE34F26)),
    "css" to ("CSS" to Color(0xFF1572B6)),
    "scss" to ("SASS" to Color(0xFFCC6699)),
    "c" to ("C" to Color(0xFF5C6BC0)),
    "h" to ("C" to Color(0xFF5C6BC0)),
    "cpp" to ("C++" to Color(0xFF00599C)),
    "hpp" to ("C++" to Color(0xFF00599C)),
    "cs" to ("C#" to Color(0xFF512BD4)),
    "go" to ("GO" to Color(0xFF00ADD8)),
    "rs" to ("RS" to Color(0xFF6B4F3A)),
    "php" to ("PHP" to Color(0xFF777BB4)),
    "rb" to ("RB" to Color(0xFFCC342D)),
    "swift" to ("SW" to Color(0xFFF05138)),
    "dart" to ("DART" to Color(0xFF0175C2)),
    "vue" to ("VUE" to Color(0xFF42B883)),
    "sh" to (">_" to Color(0xFF455A64)),
    "bash" to (">_" to Color(0xFF455A64)),
    "json" to ("{}" to Color(0xFF546E7A)),
    "xml" to ("XML" to Color(0xFF8E24AA)),
    "sql" to ("SQL" to Color(0xFF336791)),
    "lua" to ("LUA" to Color(0xFF000080)),
)

private val archiveExtensions = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "apk", "jar", "war")
private val wordExtensions = setOf("doc", "docx", "odt", "rtf")
private val spreadsheetExtensions = setOf("xls", "xlsx", "ods", "csv", "tsv")
private val presentationExtensions = setOf("ppt", "pptx", "odp")

@Composable
private fun FileActionDialog(
    target: File,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onProperties: () -> Unit,
    onOpenWith: () -> Unit,
    onPreviewHtml: () -> Unit,
    onUnzip: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(target.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                DialogAction(Icons.Default.ContentCopy, context.getString(R.string.file_action_copy_other), onCopy)
                DialogAction(Icons.Default.DriveFileMove, context.getString(R.string.file_action_move_other), onMove)
                DialogAction(Icons.Default.Edit, context.getString(R.string.file_action_rename), onRename)
                DialogAction(Icons.Default.Info, context.getString(R.string.file_action_properties), onProperties)
                if (target.isFile) {
                    DialogAction(Icons.Default.OpenWith, context.getString(R.string.file_action_open_with), onOpenWith)
                }
                if (target.isFile && target.extension.lowercase() in setOf("html", "htm")) {
                    DialogAction(Icons.Default.OpenInBrowser, context.getString(R.string.file_action_preview_html), onPreviewHtml)
                }
                if (target.isFile && target.extension.equals("zip", true)) {
                    DialogAction(Icons.Default.UnfoldMore, context.getString(R.string.file_action_unzip), onUnzip)
                }
                DialogAction(Icons.Default.Delete, context.getString(R.string.file_action_delete), onDelete, destructive = true)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(context.getString(android.R.string.cancel)) } },
    )
}

internal enum class FileOpenMode { TEXT, IMAGE, VIDEO, AUDIO }

@Composable
private fun OpenWithDialog(
    file: File,
    onDismiss: () -> Unit,
    onOpen: (FileOpenMode) -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.file_open_with_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    file.name,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                DialogAction(
                    Icons.Default.Description,
                    context.getString(R.string.file_open_as_text),
                    onClick = { onOpen(FileOpenMode.TEXT) },
                )
                DialogAction(
                    Icons.Default.ImageIcon,
                    context.getString(R.string.file_open_as_image),
                    onClick = { onOpen(FileOpenMode.IMAGE) },
                )
                DialogAction(
                    Icons.Default.Movie,
                    context.getString(R.string.file_open_as_video),
                    onClick = { onOpen(FileOpenMode.VIDEO) },
                )
                DialogAction(
                    Icons.Default.MusicNote,
                    context.getString(R.string.file_open_as_audio),
                    onClick = { onOpen(FileOpenMode.AUDIO) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(context.getString(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun DialogAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, destructive: Boolean = false) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(label, modifier = Modifier.weight(1f), color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val context = LocalContext.current
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(context.getString(R.string.file_name_label)) },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name) }) { Text(context.getString(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(context.getString(android.R.string.cancel)) } },
    )
}

@Composable
private fun FilePropertiesDialog(
    file: File,
    fileOperations: PrivilegedFileOperations,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var totalSize by remember(file.absolutePath, file.lastModified()) {
        mutableStateOf<Long?>(if (file.isFile) file.length() else null)
    }
    LaunchedEffect(file.absolutePath, file.lastModified()) {
        if (file.isDirectory) {
            totalSize = withContext(Dispatchers.IO) {
                fileOperations.totalSize(file).getOrElse { file.length() }
            }
        }
    }
    fun copyPath() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(file.name, file.absolutePath))
        Toast.makeText(context, context.getString(R.string.file_property_path_copied), Toast.LENGTH_SHORT).show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.file_properties_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .combinedClickable(onClick = ::copyPath, onLongClick = ::copyPath)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        context.getString(R.string.file_property_path, file.absolutePath),
                        modifier = Modifier.weight(1f),
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.ContentCopy, contentDescription = context.getString(R.string.file_property_copy_path))
                }
                Text(context.getString(R.string.file_property_type, context.getString(if (file.isDirectory) R.string.file_type_folder else R.string.file_type_file)))
                Text(
                    context.getString(
                        R.string.file_property_size,
                        totalSize?.let(::formatBytes) ?: context.getString(R.string.file_property_calculating_size),
                    ),
                )
                Text(context.getString(R.string.file_property_modified, DateFormat.getDateTimeInstance().format(file.lastModified())))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(context.getString(android.R.string.ok)) } },
    )
}

@Composable
private fun FileEditorScreen(
    file: File,
    loaded: TextFileContent,
    controller: ChatController,
    settings: AppSettings,
    termuxExecutor: TermuxExecutor,
    fileOperations: PrivilegedFileOperations,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val handle = remember(file.absolutePath) { EditorHandle() }
    var original by remember(file.absolutePath) { mutableStateOf(loaded.text) }
    var wordWrap by rememberSaveable(file.absolutePath) { mutableStateOf(true) }
    var readOnly by remember(file.absolutePath) { mutableStateOf(loaded.hasUtf8Errors) }
    var showEncodingWarning by remember(file.absolutePath) { mutableStateOf(loaded.hasUtf8Errors) }
    var showUnsaved by remember { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var replaceText by rememberSaveable { mutableStateOf("") }
    var regexSearch by rememberSaveable { mutableStateOf(false) }
    var caseInsensitiveSearch by rememberSaveable { mutableStateOf(true) }
    var wholeWordSearch by rememberSaveable { mutableStateOf(false) }
    var nextAfterReplace by rememberSaveable { mutableStateOf(true) }
    var searchResultCount by remember { mutableIntStateOf(0) }
    var searchMessage by remember { mutableStateOf("") }
    var jumpDialog by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var aiOpen by rememberSaveable { mutableStateOf(false) }
    var aiHistoryOpen by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var toolNotice by remember(file.absolutePath) { mutableStateOf<ChatRecord?>(null) }
    var observedToolMessageId by remember(file.absolutePath) {
        mutableStateOf(controller.messages.value.lastOrNull { it.role == "tool" }?.id ?: 0L)
    }
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val activeEditorActivity = controller.editorFileActivity.value
    val workspaceRevision = controller.settingsRevision.intValue
    val workspaceConversationId = controller.activeConversationId.value
    val workspaceDisplayPath = remember(workspaceRevision, workspaceConversationId) {
        controller.workspaceDisplayPath()
    }
    val workspaceLauncher = com.yukisoffd.lyracode.workspace.rememberWorkspacePicker { uri ->
        uri?.let {
            runCatching { controller.persistWorkspaceForActiveSession(it) }
                .onFailure { error -> showError(context, error) }
        }
    }
    LaunchedEffect(
        searchOpen,
        searchText,
        regexSearch,
        caseInsensitiveSearch,
        wholeWordSearch,
    ) {
        if (!searchOpen) return@LaunchedEffect
        handle.search(
            query = searchText,
            regularExpression = regexSearch,
            caseInsensitive = caseInsensitiveSearch,
            wholeWord = wholeWordSearch,
        ).onSuccess {
            searchMessage = ""
        }.onFailure { error ->
            searchResultCount = 0
            searchMessage = error.message.orEmpty()
            return@LaunchedEffect
        }
        while (true) {
            searchResultCount = handle.matchedPositionCount()
            delay(140L)
        }
    }

    val pendingEditorMutation = controller.editorFileMutations.firstOrNull {
        filesReferToSamePath(File(it.path), file)
    }
    LaunchedEffect(pendingEditorMutation?.id) {
        val mutation = pendingEditorMutation ?: return@LaunchedEffect
        aiOpen = false
        if (searchOpen) {
            searchOpen = false
            searchResultCount = 0
            handle.search(
                query = "",
                regularExpression = false,
                caseInsensitive = false,
                wholeWord = false,
            )
        }
        if (mutation.committed) {
            if (handle.text() == mutation.content) {
                original = mutation.content
                status = context.getString(R.string.file_editor_ai_saved)
            }
            controller.consumeEditorFileMutation(mutation.id)
            return@LaunchedEffect
        }
        status = context.getString(R.string.file_editor_ai_editing)
        val expectedText = mutation.beforeContent.orEmpty()
        runCatching { handle.applyAgentTextChange(expectedText, mutation.content) }
            .onSuccess {
                status = context.getString(R.string.file_editor_ai_saving)
                controller.consumeEditorFileMutation(mutation.id, AgentFileEditResult.Applied)
            }
            .onFailure { error ->
                showError(context, error)
                controller.consumeEditorFileMutation(
                    mutation.id,
                    AgentFileEditResult.failed(error.message.orEmpty()),
                )
            }
    }
    val latestToolRecord = controller.messages.value.lastOrNull { it.role == "tool" }
    LaunchedEffect(latestToolRecord?.id) {
        val record = latestToolRecord ?: return@LaunchedEffect
        if (record.id == observedToolMessageId) return@LaunchedEffect
        observedToolMessageId = record.id
        toolNotice = record
        delay(6_000L)
        if (toolNotice?.id == record.id) toolNotice = null
    }

    fun requestClose() {
        if (handle.text() != original) showUnsaved = true else onClose()
    }

    fun save(close: Boolean = false) {
        if (readOnly) return
        val text = handle.text()
        scope.launch {
            val result = withContext(Dispatchers.IO) { fileOperations.saveUtf8WithBackup(file, text) }
            result.onSuccess { backup ->
                original = text
                status = context.getString(if (backup != null) R.string.file_editor_saved_backup else R.string.file_editor_saved)
                if (close) onClose()
            }.onFailure { showError(context, it) }
        }
    }

    fun navigateBackFromEditor() {
        when {
            aiOpen -> aiOpen = false
            searchOpen -> {
                searchOpen = false
                handle.search(
                    query = "",
                    regularExpression = false,
                    caseInsensitive = false,
                    wholeWord = false,
                )
            }
            else -> requestClose()
        }
    }
    val predictiveBackState = rememberPredictiveBackGestureState(
        enabled = settings.predictiveBackEnabled,
        onBack = ::navigateBackFromEditor,
    )
    BackHandler(enabled = !settings.predictiveBackEnabled) { navigateBackFromEditor() }
    if (showUnsaved) {
        AlertDialog(
            onDismissRequest = { showUnsaved = false },
            title = { Text(context.getString(R.string.file_editor_unsaved_title)) },
            text = { Text(context.getString(R.string.file_editor_unsaved_message)) },
            confirmButton = {
                TextButton(onClick = { showUnsaved = false; save(close = true) }) {
                    Text(context.getString(R.string.file_editor_save))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showUnsaved = false }) { Text(context.getString(android.R.string.cancel)) }
                    TextButton(onClick = { showUnsaved = false; onClose() }) { Text(context.getString(R.string.file_editor_discard)) }
                }
            },
        )
    }
    if (showEncodingWarning) {
        AlertDialog(
            onDismissRequest = { showEncodingWarning = false },
            title = { Text(context.getString(R.string.file_editor_encoding_title)) },
            text = { Text(context.getString(R.string.file_editor_encoding_message)) },
            confirmButton = {
                TextButton(onClick = { readOnly = false; showEncodingWarning = false }) {
                    Text(context.getString(R.string.file_editor_edit_anyway))
                }
            },
            dismissButton = { TextButton(onClick = { showEncodingWarning = false }) { Text(context.getString(android.R.string.cancel)) } },
        )
    }
    if (jumpDialog) {
        var line by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { jumpDialog = false },
            title = { Text(context.getString(R.string.file_editor_jump_line)) },
            text = { OutlinedTextField(value = line, onValueChange = { line = it.filter(Char::isDigit) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { line.toIntOrNull()?.let(handle::jumpToLine); jumpDialog = false }) { Text(context.getString(android.R.string.ok)) } },
            dismissButton = { TextButton(onClick = { jumpDialog = false }) { Text(context.getString(android.R.string.cancel)) } },
        )
    }
    if (!aiOpen) {
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
    }

    Box(
        Modifier
            .fillMaxSize()
            .predictiveBackTransform(predictiveBackState)
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(file.absolutePath, aiOpen) {
                if (aiOpen) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val toolbarHeight = 48.dp.toPx()
                    val startZoneWidth = minOf(size.width * 0.4f, maxOf(128.dp.toPx(), size.width * 0.32f))
                    val canStart = down.position.x <= startZoneWidth &&
                        down.position.y >= toolbarHeight &&
                        down.position.y <= size.height * 0.72f
                    if (!canStart) return@awaitEachGesture

                    val startedAt = down.uptimeMillis
                    val start = down.position
                    val triggerDistance = 56.dp.toPx()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val elapsed = change.uptimeMillis - startedAt
                        val horizontal = change.position.x - start.x
                        val vertical = change.position.y - start.y

                        // Only a prompt, clearly horizontal swipe may open AI. Once the
                        // gesture becomes a long press, leave it entirely to the editor.
                        if (elapsed > 260L || horizontal < -triggerDistance / 2f || abs(vertical) > triggerDistance) break
                        if (horizontal >= triggerDistance && horizontal > abs(vertical) * 1.35f) {
                            event.changes.forEach { it.consume() }
                            aiOpen = true
                            break
                        }
                    }
                }
            },
    ) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = ::requestClose) { Icon(Icons.Default.ArrowBack, contentDescription = null) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = handle::undo) { Icon(Icons.Default.Undo, contentDescription = null) }
                IconButton(onClick = handle::redo) { Icon(Icons.Default.Redo, contentDescription = null) }
                IconButton(enabled = !readOnly, onClick = { save() }) { Icon(Icons.Default.Save, contentDescription = context.getString(R.string.file_editor_save)) }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = null) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.file_editor_search)) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            onClick = { menuOpen = false; searchOpen = true },
                        )
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.file_editor_jump_line)) },
                            leadingIcon = { Icon(Icons.Default.NavigateNext, contentDescription = null) },
                            onClick = { menuOpen = false; jumpDialog = true },
                        )
                        DropdownMenuItem(
                            text = { Text(context.getString(if (wordWrap) R.string.file_editor_single_line else R.string.file_editor_word_wrap)) },
                            leadingIcon = { Checkbox(checked = wordWrap, onCheckedChange = null) },
                            onClick = { wordWrap = !wordWrap; menuOpen = false },
                        )
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.file_editor_ai)) },
                            leadingIcon = { Icon(Icons.Default.SmartToy, contentDescription = null) },
                            onClick = { menuOpen = false; aiOpen = true },
                        )
                    }
                }
            }
            if (searchOpen) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 2.dp,
                ) {
                    Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            context.getString(R.string.file_editor_search_results, searchResultCount),
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        SearchOptionButton(
                            label = "NF",
                            selected = nextAfterReplace,
                            onClick = { nextAfterReplace = !nextAfterReplace },
                        )
                        SearchOptionButton(
                            label = "Cc",
                            selected = caseInsensitiveSearch,
                            onClick = { caseInsensitiveSearch = !caseInsensitiveSearch },
                        )
                        SearchOptionButton(
                            label = "W",
                            selected = wholeWordSearch,
                            onClick = {
                                wholeWordSearch = !wholeWordSearch
                                if (wholeWordSearch) regexSearch = false
                            },
                        )
                        SearchOptionButton(
                            label = ".*",
                            selected = regexSearch,
                            onClick = {
                                regexSearch = !regexSearch
                                if (regexSearch) wholeWordSearch = false
                            },
                        )
                    }
                    HorizontalDivider()
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedTextField(
                            value = searchText,
                            onValueChange = { searchText = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(context.getString(R.string.file_editor_search_query)) },
                        )
                        OutlinedTextField(
                            value = replaceText,
                            onValueChange = { replaceText = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !readOnly,
                            label = { Text(context.getString(R.string.file_editor_replace_hint)) },
                        )
                    }
                    if (searchMessage.isNotBlank()) {
                        Text(
                            searchMessage,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().height(48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { handle.previousMatch() },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                        ) {
                            Text(
                                context.getString(R.string.file_search_previous),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        TextButton(
                            onClick = { handle.nextMatch() },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                        ) {
                            Text(
                                context.getString(R.string.file_search_next),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        TextButton(
                            enabled = !readOnly && searchText.isNotBlank(),
                            onClick = {
                                handle.replaceCurrentMatch(replaceText, nextAfterReplace)
                                    .onSuccess { searchMessage = context.getString(R.string.file_editor_replace_done) }
                                    .onFailure { error -> searchMessage = error.message.orEmpty() }
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                        ) {
                            Text(
                                context.getString(R.string.file_editor_replace),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        TextButton(
                            enabled = !readOnly && searchText.isNotBlank(),
                            onClick = {
                                handle.replaceAllMatches(replaceText) {
                                    searchMessage = context.getString(R.string.file_editor_replace_all_done)
                                }
                                    .onSuccess { searchMessage = context.getString(R.string.file_editor_replacing_all) }
                                    .onFailure { error -> searchMessage = error.message.orEmpty() }
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                        ) {
                            Text(
                                context.getString(R.string.file_editor_replace_all),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        IconButton(
                            onClick = {
                                searchOpen = false
                                searchMessage = ""
                                searchResultCount = 0
                                handle.search(
                                    query = "",
                                    regularExpression = false,
                                    caseInsensitive = false,
                                    wholeWord = false,
                                )
                            },
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null)
                        }
                    }
                    }
                }
            }
            if (status.isNotBlank()) {
                Text(status, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
            }
            if (!aiOpen && controller.isActiveConversationRunning()) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.SmartToy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            activeEditorActivity?.let {
                                context.getString(
                                    if (it.operation == "read") {
                                        R.string.file_editor_ai_reading_file
                                    } else {
                                        R.string.file_editor_ai_editing_file
                                    },
                                    File(it.path).name,
                                )
                            } ?: controller.status.value.ifBlank { context.getString(R.string.file_editor_ai_working) },
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        TextButton(onClick = { aiOpen = true }) { Text(context.getString(R.string.file_editor_view_ai)) }
                    }
                }
            }
            if (!aiOpen) {
                toolNotice?.let { record ->
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 10.dp, top = 7.dp, bottom = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    context.getString(R.string.file_editor_tool_output, record.toolName.ifBlank { context.getString(R.string.file_editor_ai) }),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(record.content.take(800), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { aiOpen = true; toolNotice = null }) { Text(context.getString(R.string.file_editor_view_ai)) }
                            IconButton(onClick = { toolNotice = null }) { Icon(Icons.Default.Close, contentDescription = null) }
                        }
                    }
                }
            }
            SoraCodeEditor(
                file = file,
                initialText = loaded.text,
                wordWrap = wordWrap,
                readOnly = readOnly,
                darkTheme = darkTheme,
                handle = handle,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }

        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(top = 48.dp)
                .width(128.dp)
                .fillMaxHeight(0.72f)
                .systemGestureExclusion(),
        )

        AnimatedVisibility(
            visible = aiOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)).combinedClickable(onClick = { aiOpen = false }, onLongClick = {}))
                AnimatedVisibility(
                    visible = aiOpen,
                    enter = slideInHorizontally { -it },
                    exit = slideOutHorizontally { -it },
                    modifier = Modifier.fillMaxHeight().fillMaxWidth(0.9f).align(Alignment.CenterStart),
                ) {
                    Surface(tonalElevation = 8.dp, shadowElevation = 10.dp) {
                        Column(Modifier.fillMaxSize()) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(onClick = { aiHistoryOpen = !aiHistoryOpen }) {
                                    Icon(
                                        Icons.Default.History,
                                        contentDescription = context.getString(R.string.label_history_sessions),
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (aiHistoryOpen) context.getString(R.string.label_history_sessions) else context.getString(R.string.file_editor_ai),
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        activeEditorActivity?.let {
                                            context.getString(
                                                if (it.operation == "read") {
                                                    R.string.file_editor_ai_reading_file
                                                } else {
                                                    R.string.file_editor_ai_editing_file
                                                },
                                                File(it.path).name,
                                            )
                                        } ?: workspaceDisplayPath?.let {
                                            context.getString(R.string.file_editor_ai_workspace, it)
                                        } ?: context.getString(R.string.file_editor_ai_no_workspace),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(onClick = { workspaceLauncher() }) {
                                    Icon(Icons.Default.Add, contentDescription = context.getString(R.string.file_editor_add_workspace))
                                }
                                IconButton(onClick = { aiOpen = false }) { Icon(Icons.Default.Close, contentDescription = null) }
                            }
                            HorizontalDivider()
                            Box(Modifier.weight(1f)) {
                                if (aiHistoryOpen) {
                                    EditorConversationHistory(
                                        controller = controller,
                                        onSelected = { aiHistoryOpen = false },
                                    )
                                } else {
                                    ChatScreen(controller, settings, termuxExecutor)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class PaneAction(val pane: Int, val file: File)

private data class BatchSelection(val pane: Int, val files: List<File>)

private enum class BatchOperation { COPY, MOVE, DELETE }

private data class PendingBatchOperation(
    val operation: BatchOperation,
    val selection: BatchSelection,
    val destination: File? = null,
)

@Composable
private fun EditorConversationHistory(
    controller: ChatController,
    onSelected: () -> Unit,
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val conversations = controller.conversations.toList()
    val filtered = remember(conversations, query) {
        val clean = query.trim()
        if (clean.isBlank()) conversations else conversations.filter {
            it.title.contains(clean, ignoreCase = true) ||
                it.model.contains(clean, ignoreCase = true)
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(context.getString(R.string.search_history_placeholder)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
            )
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = {
                controller.requestNewConversation()
                onSelected()
            }) {
                Text(context.getString(R.string.label_new_conversation), maxLines = 1)
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(filtered, key = { it.id }) { conversation ->
                val selected = controller.activeConversationId.value == conversation.id
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                controller.selectConversation(conversation.id)
                                onSelected()
                            },
                            onLongClick = {},
                        ),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Text(
                            conversation.title,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                if (conversation.model.isNotBlank()) append(conversation.model)
                                if (conversation.updatedAt > 0L) {
                                    if (isNotEmpty()) append(" · ")
                                    append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(conversation.updatedAt))
                                }
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private data class PaneScrollPosition(val index: Int, val offset: Int)

@Composable
private fun SearchOptionButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.width(42.dp).height(44.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(
            label,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

private fun fileNavigationTransition(initialPath: String, targetPath: String): androidx.compose.animation.ContentTransform {
    val forward = pathDepth(targetPath) > pathDepth(initialPath)
    return (slideInHorizontally(tween(220)) { width -> if (forward) width / 4 else -width / 4 } + fadeIn(tween(180))) togetherWith
        (slideOutHorizontally(tween(220)) { width -> if (forward) -width / 4 else width / 4 } + fadeOut(tween(160)))
}

private fun pathDepth(path: String): Int = path.replace('\\', '/').trim('/').count { it == '/' }

internal fun isFileManagerNavigationRoot(file: File, navigationRoot: File): Boolean =
    filesReferToSamePath(file, navigationRoot)

private fun filesReferToSamePath(first: File, second: File): Boolean = runCatching {
    first.canonicalFile == second.canonicalFile
}.getOrDefault(first.absolutePath == second.absolutePath)

private const val FILE_SHIZUKU_PERMISSION_REQUEST_CODE = 2401

private fun hasFileManagerPermission(context: Context): Boolean = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}.getOrDefault(false)

private fun openExternalFile(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val extension = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { showError(context, it) }
}

private fun previewHtmlInBrowser(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "text/html")
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.onFailure { showError(context, it) }
}

private fun showError(context: Context, error: Throwable) {
    Toast.makeText(context, error.message.orEmpty().ifBlank { context.getString(R.string.file_open_failed) }, Toast.LENGTH_LONG).show()
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return "%.1f %s".format(value, units[unit])
}
