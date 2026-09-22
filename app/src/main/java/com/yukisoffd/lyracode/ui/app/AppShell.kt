package com.yukisoffd.lyracode

import android.content.Context
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.AuditLogStore
import com.yukisoffd.lyracode.data.BackupManager
import com.yukisoffd.lyracode.data.SkillPack
import com.yukisoffd.lyracode.data.AppUpdateInfo
import com.yukisoffd.lyracode.data.UpdateDownloadProgress
import com.yukisoffd.lyracode.data.UpdateManager
import com.yukisoffd.lyracode.filetransfer.FileTransferClient
import com.yukisoffd.lyracode.filemanager.FileManagerScreen
import com.yukisoffd.lyracode.mcp.LocalMcpServerManager
import com.yukisoffd.lyracode.mcp.McpClientManager
import com.yukisoffd.lyracode.server.MiniServerManager
import com.yukisoffd.lyracode.ssh.SshExecutor
import com.yukisoffd.lyracode.ssh.SshTerminalSessionManager
import com.yukisoffd.lyracode.ssh.LocalProotTerminalSessionManager
import com.yukisoffd.lyracode.system.SystemCommandExecutor
import com.yukisoffd.lyracode.tasks.DownloadTaskManager
import com.yukisoffd.lyracode.tasks.ScheduledTaskManager
import com.yukisoffd.lyracode.termux.TermuxExecutor
import com.yukisoffd.lyracode.webdav.WebDavClient
import com.yukisoffd.lyracode.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL


private const val PAGE_CHAT = 0
private const val PAGE_FILES = 1
private const val PAGE_TERMINAL = 2
private const val PAGE_LOG = 3
private const val PAGE_STATS = 4
private const val PAGE_TASKS = 5
private const val PAGE_ARCHIVE = 6
private const val PAGE_SETTINGS = 7

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun LyraCodeApp(
    settings: AppSettings,
    auditLogStore: AuditLogStore,
    workspaceManager: WorkspaceManager,
    termuxExecutor: TermuxExecutor,
    mcpClientManager: McpClientManager,
    sshExecutor: SshExecutor,
    sshTerminalSessionManager: SshTerminalSessionManager,
    localProotTerminalSessionManager: LocalProotTerminalSessionManager,
    systemCommandExecutor: SystemCommandExecutor,
    webDavClient: WebDavClient,
    fileTransferClient: FileTransferClient,
    backupManager: BackupManager,
    miniServerManager: MiniServerManager,
    localMcpServerManager: LocalMcpServerManager,
    downloadTaskManager: DownloadTaskManager,
    scheduledTaskManager: ScheduledTaskManager,
    controller: ChatController,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    dynamicColorEnabled: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    predictiveBackEnabled: Boolean,
    onPredictiveBackChange: (Boolean) -> Unit,
    languageMode: String,
    onLanguageModeChange: (String) -> Unit,
    refreshRateMode: String,
    onRefreshRateModeChange: (String) -> Unit,
    fontScaleMode: String,
    customFontScale: Float,
    onFontScaleModeChange: (String) -> Unit,
    onCustomFontScaleChange: (Float) -> Unit,
) {
    val context = LocalContext.current
    val pages = listOf(
        context.getString(R.string.nav_tab_ai_chat),
        context.getString(R.string.nav_tab_files),
        context.getString(R.string.nav_tab_terminal),
        context.getString(R.string.nav_tab_log),
        context.getString(R.string.nav_tab_statistics),
        context.getString(R.string.nav_tab_tasks),
        context.getString(R.string.nav_tab_archive),
        context.getString(R.string.nav_tab_settings),
    )
    var selectedPage by rememberSaveable { mutableIntStateOf(PAGE_CHAT) }
    val safeSelectedPage = selectedPage.coerceIn(0, pages.lastIndex)
    val controllerStatus = controller.status.value
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val keyboardAvoidanceOffsetPx = rememberAnimatedKeyboardAvoidanceOffsetPx()
    val drawerVisible =
        drawerState.currentValue != DrawerValue.Closed ||
            drawerState.targetValue != DrawerValue.Closed
    val drawerKeyboardOffsetPx = if (drawerVisible) keyboardAvoidanceOffsetPx else 0
    val chatKeyboardOffsetPx = if (drawerVisible) 0 else keyboardAvoidanceOffsetPx
    val drawerKeyboardOffsetDp = with(LocalDensity.current) { drawerKeyboardOffsetPx.toDp() }
    val focusManager = LocalFocusManager.current
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(drawerVisible) {
        focusManager.clearFocus(force = true)
        softwareKeyboardController?.hide()
    }
    val activeConversationId = controller.activeConversationId.value
    val workspaceName = remember(activeConversationId, controller.settingsRevision.intValue) { controller.workspaceDisplayName() }
    val workspaceLabel = if (workspaceName == "未选择工作目录") {
        context.getString(R.string.label_no_workspace)
    } else {
        workspaceName
    }
    val workspacePath = remember(activeConversationId, controller.settingsRevision.intValue) {
        controller.workspaceDisplayPath()
    }.orEmpty().ifBlank { workspaceLabel }
    val activeConversation = controller.conversations.firstOrNull { it.id == activeConversationId }
    val conversationTitle = activeConversation?.title.orEmpty().ifBlank { context.getString(R.string.title_new_chat) }
    val activeProfile = controller.profiles.firstOrNull { it.id == controller.activeProfileId.value }
    val activeModelName = controller.activeModel.value.ifBlank {
        activeProfile?.selectedModel.orEmpty().ifBlank { context.getString(R.string.label_model) }
    }
    var nickname by remember { mutableStateOf(settings.userNickname) }
    var avatarPath by remember { mutableStateOf(settings.userAvatarPath) }
    var skillsRevision by remember { mutableIntStateOf(0) }
    var skillStatus by remember { mutableStateOf("") }
    var backupStatus by remember { mutableStateOf("") }
    var appNotice by remember { mutableStateOf("") }
    var backupImportMode by remember { mutableStateOf("supplement") }
    val updateManager = remember(context) { UpdateManager(context) }
    val uriHandler = LocalUriHandler.current
    var aboutUpdateAvailable by remember { mutableStateOf(updateManager.hasAvailableUpdate()) }
    var startupUpdateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var startupUpdateProgress by remember { mutableStateOf<UpdateDownloadProgress?>(null) }
    var startupUpdateDownloading by remember { mutableStateOf(false) }
    var startupPendingApk by remember { mutableStateOf(updateManager.pendingDownloadedApk()) }
    val appSettingsRevision = controller.settingsRevision.intValue
    val skills = remember(skillsRevision, appSettingsRevision) { settings.installedSkills() }
    var settingsDetailTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsBackRequest by remember { mutableIntStateOf(0) }
    var conversationDetailsExpanded by rememberSaveable { mutableStateOf(false) }
    var showStatsAboutDialog by rememberSaveable { mutableStateOf(false) }
    var skipNextPageTransition by remember { mutableStateOf(false) }
    fun requestNewConversation() {
        if (controller.requestNewConversation()) {
            selectedPage = PAGE_CHAT
        } else {
            appNotice = context.getString(R.string.notice_already_in_new_chat)
        }
    }

    fun updateBackupStatus(message: String) {
        backupStatus = message
        if (message.isNotBlank()) appNotice = message
    }
    val startupInstallPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val apk = updateManager.pendingDownloadedApk()
        startupPendingApk = apk
        if (apk != null && !updateManager.needsInstallPermission()) {
            runCatching { context.startActivity(updateManager.installIntent(apk)) }
                .onFailure { appNotice = it.message.orEmpty().ifBlank { context.getString(R.string.notice_cannot_open_installer) } }
        } else if (apk != null) {
            appNotice = context.getString(R.string.notice_auth_pending_install_later)
        }
    }
    fun openStartupInstaller(apk: File) {
        if (updateManager.needsInstallPermission()) {
            appNotice = context.getString(R.string.notice_grant_install_permission)
            startupInstallPermissionLauncher.launch(updateManager.installPermissionIntent())
        } else {
            runCatching { context.startActivity(updateManager.installIntent(apk)) }
                .onFailure { appNotice = it.message.orEmpty().ifBlank { context.getString(R.string.notice_cannot_open_installer) } }
        }
    }
    LaunchedEffect(Unit) {
        val info = withContext(Dispatchers.IO) {
            updateManager.checkDailyForUpdateIfNeeded().getOrNull() ?: updateManager.latestAvailableUpdate()
        }
        aboutUpdateAvailable = updateManager.hasAvailableUpdate()
        if (info != null && updateManager.shouldShowDailyUpdatePrompt()) {
            updateManager.markDailyUpdatePromptShown()
            startupUpdateInfo = info
        }
    }
    LaunchedEffect(safeSelectedPage) {
        if (safeSelectedPage != PAGE_SETTINGS) settingsDetailTitle = null
        skipNextPageTransition = false
    }
    LaunchedEffect(safeSelectedPage, activeConversationId) {
        if (safeSelectedPage != PAGE_CHAT || conversationDetailsExpanded) {
            conversationDetailsExpanded = false
        }
    }
    val pagePredictiveBackState = rememberPredictiveBackGestureState(
        enabled = predictiveBackEnabled &&
            safeSelectedPage != PAGE_CHAT &&
            !(safeSelectedPage == PAGE_SETTINGS && settingsDetailTitle != null) &&
            !drawerState.isOpen,
    ) {
        skipNextPageTransition = true
        selectedPage = PAGE_CHAT
    }
    BackHandler(enabled = !predictiveBackEnabled && safeSelectedPage == PAGE_SETTINGS && settingsDetailTitle != null && !drawerState.isOpen) {
        settingsBackRequest++
    }
    BackHandler(enabled = !predictiveBackEnabled && safeSelectedPage != PAGE_CHAT && safeSelectedPage != PAGE_FILES && !(safeSelectedPage == PAGE_SETTINGS && settingsDetailTitle != null) && !drawerState.isOpen) {
        selectedPage = PAGE_CHAT
    }
    BackHandler(enabled = drawerState.isOpen) {
        if (safeSelectedPage != PAGE_FILES) selectedPage = PAGE_CHAT
        scope.launch { drawerState.close() }
    }
    BackHandler(enabled = conversationDetailsExpanded && safeSelectedPage == PAGE_CHAT && !drawerState.isOpen) {
        conversationDetailsExpanded = false
    }
    LaunchedEffect(safeSelectedPage, drawerState.currentValue) {
        if (safeSelectedPage == PAGE_FILES && drawerState.isOpen) {
            drawerState.snapTo(DrawerValue.Closed)
        }
    }
    val treeLauncher = com.yukisoffd.lyracode.workspace.rememberWorkspacePicker { uri: Uri? ->
        if (uri != null) {
            val selectedName = controller.persistWorkspaceForActiveSession(uri)
            appNotice = context.getString(R.string.notice_workspace_selected_current_chat, selectedName)
        }
    }
    val projectTreeLauncher = com.yukisoffd.lyracode.workspace.rememberWorkspacePicker { uri: Uri? ->
        if (uri != null) {
            controller.createProject(uri)?.let { project ->
                selectedPage = PAGE_CHAT
                appNotice = context.getString(R.string.notice_project_created, project.name)
                scope.launch { drawerState.close() }
            }
        }
    }
    fun updateSkillImportStatus(result: Result<SkillPack>) {
        result.fold(
            onSuccess = {
                skillStatus = context.getString(R.string.notice_skill_imported, it.name)
                skillsRevision++
            },
            onFailure = { skillStatus = it.message.orEmpty().ifBlank { context.getString(R.string.notice_skill_import_failed) } },
        )
    }
    val skillFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            updateSkillImportStatus(settings.importSkillFile(uri))
        }
    }
    val backupZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                backupStatus = context.getString(R.string.notice_importing_backup)
                appNotice = context.getString(R.string.notice_importing_backup)
                backupStatus = withContext(Dispatchers.IO) {
                    runCatching { backupManager.importFromUri(uri, backupImportMode) }
                        .fold({ context.getString(R.string.notice_import_complete, it) }, { context.getString(R.string.notice_import_failed, it.message.orEmpty()) })
                }
                appNotice = backupStatus
                controller.reloadConversations()
                skillsRevision++
                controller.settingsRevision.intValue++
            }
        }
    }

    startupUpdateInfo?.let { info ->
        UpdateDialog(
            info = info,
            progress = startupUpdateProgress,
            downloading = startupUpdateDownloading,
            onDismiss = {
                if (!startupUpdateDownloading) {
                    startupUpdateInfo = null
                    startupUpdateProgress = null
                }
            },
            onOpenWeb = {
                val target = info.webUrl.ifBlank { info.apkUrl }
                if (target.isNotBlank()) runCatching { uriHandler.openUri(target) }
            },
            onDownload = {
                if (startupUpdateDownloading) return@UpdateDialog
                startupUpdateDownloading = true
                startupUpdateProgress = UpdateDownloadProgress(status = context.getString(R.string.notice_preparing_download))
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        updateManager.downloadApk(info) { progress -> startupUpdateProgress = progress }
                    }
                    startupUpdateDownloading = false
                    result.fold(
                        onSuccess = { apk ->
                            startupPendingApk = apk
                            aboutUpdateAvailable = true
                            appNotice = context.getString(R.string.notice_download_complete_prepare_install)
                            openStartupInstaller(apk)
                        },
                        onFailure = {
                            val message = it.message.orEmpty().ifBlank { context.getString(R.string.notice_download_failed) }
                            startupUpdateProgress = UpdateDownloadProgress(status = message)
                            appNotice = message
                        },
                    )
                }
            },
        )
    }

    val renderAppTopBar: @Composable (Int) -> Unit = { page ->
        if (page != PAGE_FILES && page != PAGE_SETTINGS) TopAppBar(
            expandedHeight = 56.dp,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.primary,
                actionIconContentColor = MaterialTheme.colorScheme.primary,
            ),
            navigationIcon = {
                IconButton(
                    onClick = { scope.launch { drawerState.open() } },
                    modifier = Modifier.width(64.dp),
                ) {
                    Icon(Icons.Default.Menu, contentDescription = context.getString(R.string.cd_menu))
                }
            },
            title = {
                if (page == PAGE_CHAT) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { conversationDetailsExpanded = !conversationDetailsExpanded },
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            conversationTitle,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "$activeModelName / $workspaceLabel",
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(
                                if (conversationDetailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (conversationDetailsExpanded) {
                                    uiText(R.string.collapse_conversation_details)
                                } else {
                                    uiText(R.string.expand_conversation_details)
                                },
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Text(
                        pages[page],
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            actions = {
                if (page == PAGE_CHAT) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { treeLauncher() }) {
                            PlusBadgeIcon(
                                baseIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                            )
                        }
                        IconButton(onClick = { requestNewConversation() }) {
                            PlusBadgeIcon(
                                baseIcon = { Icon(Icons.Default.ChatBubble, contentDescription = null) },
                            )
                        }
                    }
                } else if (page == PAGE_STATS) {
                    IconButton(onClick = { showStatsAboutDialog = true }) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = context.getString(R.string.stats_about_title),
                        )
                    }
                }
            },
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = safeSelectedPage != PAGE_FILES,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.fillMaxWidth(0.86f),
                drawerContainerColor = MaterialTheme.colorScheme.background,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = drawerKeyboardOffsetDp),
                ) {
                    KimiDrawerContent(
                        settings = settings,
                        pages = pages,
                        selectedPage = safeSelectedPage,
                        languageMode = languageMode,
                        controller = controller,
                        nickname = nickname,
                        avatarPath = avatarPath,
                        keyboardAvoidanceOffsetPx = drawerKeyboardOffsetPx,
                        onProfileChanged = { newNickname, newAvatarPath ->
                            nickname = newNickname
                            avatarPath = newAvatarPath
                        },
                        onSelectPage = { index ->
                            if (index == PAGE_FILES) {
                                scope.launch {
                                    drawerState.close()
                                    selectedPage = index
                                }
                            } else {
                                selectedPage = index
                                scope.launch { drawerState.close() }
                            }
                        },
                        onNewConversation = {
                            requestNewConversation()
                            scope.launch { drawerState.close() }
                        },
                        onCreateProject = {
                            projectTreeLauncher()
                        },
                        onNewProjectConversation = { projectId ->
                            if (controller.startProjectConversation(projectId)) {
                                selectedPage = PAGE_CHAT
                                scope.launch { drawerState.close() }
                            } else {
                                appNotice = context.getString(R.string.notice_already_in_new_project_chat)
                            }
                        },
                        onSelectConversation = { id ->
                            controller.selectConversation(id)
                            selectedPage = PAGE_CHAT
                            scope.launch { drawerState.close() }
                        },
                    )
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            if (pagePredictiveBackState.isInProgress) {
                Box(Modifier.fillMaxSize()) {
                    Scaffold(
                        containerColor = MaterialTheme.colorScheme.background,
                        topBar = { renderAppTopBar(PAGE_CHAT) },
                    ) { targetPadding ->
                        Box(Modifier.padding(targetPadding)) {
                            ChatScreen(
                                controller = controller,
                                settings = settings,
                                termuxExecutor = termuxExecutor,
                                keyboardLiftPx = chatKeyboardOffsetPx,
                            )
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Color.Black.copy(
                                    alpha = 0.22f * (1f - pagePredictiveBackState.progress),
                                ),
                            ),
                    )
                }
            }
        Scaffold(
            modifier = Modifier.predictiveBackTransform(pagePredictiveBackState),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { renderAppTopBar(safeSelectedPage) },
        ) { padding ->
            Box(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                AnimatedContent(
                    targetState = safeSelectedPage,
                    transitionSpec = {
                        if (skipNextPageTransition) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else {
                            val forward = targetState > initialState
                            slideInHorizontally(animationSpec = tween(260)) { fullWidth -> if (forward) fullWidth else -fullWidth } togetherWith
                                slideOutHorizontally(animationSpec = tween(260)) { fullWidth -> if (forward) -fullWidth else fullWidth }
                        }
                    },
                    label = "page-transition",
                ) { page ->
                    when (page) {
                        PAGE_CHAT -> ChatScreen(
                            controller = controller,
                            settings = settings,
                            termuxExecutor = termuxExecutor,
                            keyboardLiftPx = chatKeyboardOffsetPx,
                        )
                        PAGE_FILES -> FileManagerScreen(
                            controller = controller,
                            settings = settings,
                            termuxExecutor = termuxExecutor,
                            onExit = { selectedPage = PAGE_CHAT },
                        )
                        PAGE_TERMINAL -> TerminalScreen(
                            settings = settings,
                            sessionManager = sshTerminalSessionManager,
                            localSessionManager = localProotTerminalSessionManager,
                        )
                        PAGE_LOG -> LogScreen(auditLogStore)
                        PAGE_STATS -> UsageStatsScreen(
                            controller = controller,
                            showAboutDialog = showStatsAboutDialog,
                            onDismissAboutDialog = { showStatsAboutDialog = false },
                        )
                        PAGE_TASKS -> TaskScreen(settings, downloadTaskManager, scheduledTaskManager)
                        PAGE_ARCHIVE -> ArchivedConversationsScreen(controller)
                        PAGE_SETTINGS -> SettingsScreen(
                            settings = settings,
                            controller = controller,
                            workspaceManager = workspaceManager,
                            termuxExecutor = termuxExecutor,
                            mcpClientManager = mcpClientManager,
                            sshExecutor = sshExecutor,
                            systemCommandExecutor = systemCommandExecutor,
                            webDavClient = webDavClient,
                            fileTransferClient = fileTransferClient,
                            backupManager = backupManager,
                            miniServerManager = miniServerManager,
                            localMcpServerManager = localMcpServerManager,
                            skills = skills,
                            skillStatus = skillStatus,
                            backupStatus = backupStatus,
                            themeMode = themeMode,
                            onThemeModeChange = onThemeModeChange,
                            dynamicColorEnabled = dynamicColorEnabled,
                            onDynamicColorChange = onDynamicColorChange,
                            predictiveBackEnabled = predictiveBackEnabled,
                            onPredictiveBackChange = onPredictiveBackChange,
                            languageMode = languageMode,
                            onLanguageModeChange = onLanguageModeChange,
                            refreshRateMode = refreshRateMode,
                            onRefreshRateModeChange = onRefreshRateModeChange,
                            fontScaleMode = fontScaleMode,
                            customFontScale = customFontScale,
                            onFontScaleModeChange = onFontScaleModeChange,
                            onCustomFontScaleChange = onCustomFontScaleChange,
                            onImportSkillFile = { skillFileLauncher.launch("*/*") },
                            onImportSkillRepository = { url ->
                                skillStatus = context.getString(R.string.skill_downloading)
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { settings.importSkillRepository(url) }
                                    updateSkillImportStatus(result)
                                }
                            },
                            onImportSkillMarkdown = { text ->
                                updateSkillImportStatus(settings.importSkillMarkdown("manual_SKILL.md", text))
                            },
                            onImportBackup = { mode ->
                                backupImportMode = mode
                                backupZipLauncher.launch("application/zip")
                            },
                            onBackupStatusChange = ::updateBackupStatus,
                            updateAvailable = aboutUpdateAvailable,
                            onUpdateAvailabilityChange = { aboutUpdateAvailable = it },
                            settingsBackRequest = settingsBackRequest,
                            onDetailTitleChange = { settingsDetailTitle = it },
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onToggleSkill = { id, enabled ->
                                settings.setSkillEnabled(id, enabled)
                                skillsRevision++
                            },
                            onDeleteSkill = { id ->
                                settings.deleteSkill(id)
                                skillsRevision++
                            },
                        )
                    }
                }
                AnimatedVisibility(
                    visible = conversationDetailsExpanded && safeSelectedPage == PAGE_CHAT,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = 600.dp)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .zIndex(2f),
                    enter = expandVertically(
                        animationSpec = tween(240),
                        expandFrom = Alignment.Top,
                    ) + fadeIn(animationSpec = tween(180)),
                    exit = shrinkVertically(
                        animationSpec = tween(200),
                        shrinkTowards = Alignment.Top,
                    ) + fadeOut(animationSpec = tween(140)),
                ) {
                    KimiCardBox(
                        modifier = Modifier
                            .shadow(12.dp, KimiCardShape)
                            .background(MaterialTheme.colorScheme.surface, KimiCardShape),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                uiText(R.string.conversation_details),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            IconButton(
                                onClick = { conversationDetailsExpanded = false },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Default.ExpandLess,
                                    contentDescription = uiText(R.string.collapse_conversation_details),
                                )
                            }
                        }
                        ConversationDetailRow(uiText(R.string.conversation_title_label), conversationTitle)
                        ConversationDetailRow(
                            uiText(R.string.detail_model),
                            activeProfile?.name.orEmpty().ifBlank { uiText(R.string.label_not_selected) },
                        )
                        ConversationDetailRow(uiText(R.string.current_model_label), activeModelName)
                        ConversationDetailRow(uiText(R.string.current_workspace_directory_label), workspacePath)
                        ConversationDetailRow(
                            uiText(R.string.conversation_id_label),
                            activeConversation?.id?.toString() ?: uiText(R.string.conversation_id_pending),
                        )
                    }
                }
                TransientNotice(
                    message = appNotice,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    onDismiss = { appNotice = "" },
                )
            }
        }
        }
    }
}

@Composable
private fun ConversationDetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

