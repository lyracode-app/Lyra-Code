package com.yukisoffd.lyracode

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.BackupManager
import com.yukisoffd.lyracode.data.MediaGenerationKind
import com.yukisoffd.lyracode.data.SkillPack
import com.yukisoffd.lyracode.filetransfer.FileTransferClient
import com.yukisoffd.lyracode.interaction.ui.DeviceInteractionSettings
import com.yukisoffd.lyracode.interaction.ui.ManualControlDebugScreen
import com.yukisoffd.lyracode.mcp.LocalMcpServerManager
import com.yukisoffd.lyracode.mcp.McpClientManager
import com.yukisoffd.lyracode.server.MiniServerManager
import com.yukisoffd.lyracode.ssh.SshExecutor
import com.yukisoffd.lyracode.system.SystemCommandExecutor
import com.yukisoffd.lyracode.termux.TermuxExecutor
import com.yukisoffd.lyracode.webdav.WebDavClient
import com.yukisoffd.lyracode.workspace.WorkspaceManager


private data class SettingsMenuEntry(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val target: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    settings: AppSettings,
    controller: ChatController,
    workspaceManager: WorkspaceManager,
    termuxExecutor: TermuxExecutor,
    mcpClientManager: McpClientManager,
    sshExecutor: SshExecutor,
    systemCommandExecutor: SystemCommandExecutor,
    webDavClient: WebDavClient,
    fileTransferClient: FileTransferClient,
    backupManager: BackupManager,
    miniServerManager: MiniServerManager,
    localMcpServerManager: LocalMcpServerManager,
    skills: List<SkillPack>,
    skillStatus: String,
    backupStatus: String,
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
    onImportSkillFile: () -> Unit,
    onImportSkillRepository: (String) -> Unit,
    onImportSkillMarkdown: (String) -> Unit,
    onImportBackup: (String) -> Unit,
    onBackupStatusChange: (String) -> Unit,
    updateAvailable: Boolean,
    onUpdateAvailabilityChange: (Boolean) -> Unit,
    settingsBackRequest: Int,
    onDetailTitleChange: (String?) -> Unit,
    onOpenDrawer: () -> Unit,
    onToggleSkill: (String, Boolean) -> Unit,
    onDeleteSkill: (String) -> Unit,
) {
    var showUnsupportedDevice by rememberSaveable { mutableStateOf(false) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var modelNestedPageActive by rememberSaveable { mutableStateOf(false) }
    var modelNestedTitle by rememberSaveable { mutableStateOf("") }
    var modelBackRequest by rememberSaveable { mutableIntStateOf(0) }
    var settingsQuery by rememberSaveable { mutableStateOf("") }
    var skipNextTransition by remember { mutableStateOf(false) }
    val settingsListScroll = rememberScrollState()
    val context = LocalContext.current
    if (showUnsupportedDevice) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showUnsupportedDevice = false },
            title = { Text(context.getString(R.string.detail_device_interaction)) },
            text = { Text(context.getString(R.string.device_interaction_upgrade_hint)) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { showUnsupportedDevice = false }) {
                Text(context.getString(android.R.string.ok))
            } },
        )
    }
    fun previousDetail(current: String?): String? = when (current) {
        "device" -> "about"
        CompliancePageIds.INDEX -> "about"
        CompliancePageIds.USER_AGREEMENT,
        CompliancePageIds.PRIVACY_POLICY,
        CompliancePageIds.PERSONAL_INFO,
        CompliancePageIds.THIRD_PARTY,
        CompliancePageIds.APP_PERMISSIONS -> CompliancePageIds.INDEX
        "custom_theme_color" -> "theme_mode"
        "device_interaction_control" -> "device_interaction"
        "font_library" -> "font"
        "topic_summary_model_topic",
        "topic_summary_model_compression",
        "topic_summary_model_vision",
        "topic_summary_model_ocr",
        "topic_summary_model_media_image",
        "topic_summary_model_media_video",
        "topic_summary_model_media_music",
        "topic_summary_model_media_audio" -> "topic_summary_model"
        "theme_mode", "font", "refresh_rate", "chat_background", "streaming_output", "app_icon" -> "theme"
        "mini_server_logs" -> "mini_server"
        else -> null
    }
    fun navigateBackFromDetail() {
        if (detail == "model" && modelNestedPageActive) {
            modelBackRequest++
            return
        }
        detail = previousDetail(detail)
    }
    val predictiveBackState = rememberPredictiveBackGestureState(
        enabled = predictiveBackEnabled && detail != null,
        onBack = {
            skipNextTransition = true
            navigateBackFromDetail()
        },
    )
    BackHandler(enabled = !predictiveBackEnabled && detail != null) { navigateBackFromDetail() }
    LaunchedEffect(detail, context) {
        if (detail != "model") modelNestedPageActive = false
        onDetailTitleChange(detail?.let { settingsDetailTitle(context, it) })
        skipNextTransition = false
    }
    LaunchedEffect(settingsBackRequest) {
        if (settingsBackRequest > 0 && detail != null) navigateBackFromDetail()
    }
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        typography = MaterialTheme.typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(14.dp),
            small = RoundedCornerShape(18.dp),
            medium = RoundedCornerShape(22.dp),
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(32.dp),
        ),
    ) {
        val renderPage: @Composable (String?) -> Unit = { target ->
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    if (target != "model") TopAppBar(
                        expandedHeight = 56.dp,
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.onBackground,
                            navigationIconContentColor = MaterialTheme.colorScheme.primary,
                        ),
                        navigationIcon = {
                            IconButton(
                                onClick = if (target == null) onOpenDrawer else ::navigateBackFromDetail,
                                modifier = Modifier.size(56.dp),
                            ) {
                                Icon(
                                    if (target == null) Icons.Default.Menu else Icons.Default.ArrowBack,
                                    contentDescription = context.getString(if (target == null) R.string.cd_menu else R.string.cd_back),
                                )
                            }
                        },
                        title = {
                            Text(
                                when {
                                    target == "model" && modelNestedPageActive && modelNestedTitle.isNotBlank() -> modelNestedTitle
                                    target != null -> settingsDetailTitle(context, target)
                                    else -> context.getString(R.string.title_settings)
                                },
                                style = MaterialTheme.typography.titleLarge,
                            )
                        },
                    )
                },
            ) { pagePadding ->
                Box(
                    Modifier
                        .padding(pagePadding)
                        .fillMaxSize(),
                ) pageContent@{
            if (target in setOf("device_interaction", "device_interaction_control") && !com.yukisoffd.lyracode.interaction.DeviceInteractionAvailability.isSupported()) {
                Text(context.getString(R.string.device_interaction_status_unsupported), Modifier.padding(18.dp))
                return@pageContent
            }
            if (target != null) {
                SettingsDetailPage(
                    inset = target != "model",
                    scroll = target !in setOf("model", "prompts", "memories", "licenses", "about", "device", "font", "font_library"),
                ) {
                    when (target) {
                    "profile" -> ProfileSettingsSummary(settings)
                    "model" -> ModelServiceSettings(
                        settings = settings,
                        controller = controller,
                        predictiveBackEnabled = predictiveBackEnabled,
                        externalBackRequest = modelBackRequest,
                        onBack = ::navigateBackFromDetail,
                        onNestedPageChanged = { active, title ->
                            modelNestedPageActive = active
                            modelNestedTitle = title
                            onDetailTitleChange(
                                if (active) title else settingsDetailTitle(context, "model"),
                            )
                        },
                    )
                    "topic_summary_model" -> TopicSummaryModelSettings(
                        onOpenTopic = { detail = "topic_summary_model_topic" },
                        onOpenCompression = { detail = "topic_summary_model_compression" },
                        onOpenVision = { detail = "topic_summary_model_vision" },
                        onOpenOcr = { detail = "topic_summary_model_ocr" },
                        onOpenMedia = { kind -> detail = "topic_summary_model_media_${kind.value}" },
                    )
                    "topic_summary_model_topic" -> TopicSummaryModelEditor(settings, controller)
                    "topic_summary_model_compression" -> HistoryCompressionModelEditor(settings, controller)
                    "topic_summary_model_vision" -> VisionUnderstandingModelEditor(settings, controller)
                    "topic_summary_model_ocr" -> OcrModelEditor(settings, controller)
                    "topic_summary_model_media_image" -> MediaGenerationModelEditor(settings, controller, MediaGenerationKind.IMAGE)
                    "topic_summary_model_media_video" -> MediaGenerationModelEditor(settings, controller, MediaGenerationKind.VIDEO)
                    "topic_summary_model_media_music" -> MediaGenerationModelEditor(settings, controller, MediaGenerationKind.MUSIC)
                    "topic_summary_model_media_audio" -> MediaGenerationModelEditor(settings, controller, MediaGenerationKind.AUDIO)
                    "sub_agents" -> SubAgentSettings(settings, controller)

                    "theme" -> ThemeSettings(
                        settings = settings,
                        themeMode = themeMode,
                        dynamicColorEnabled = dynamicColorEnabled,
                        onDynamicColorChange = onDynamicColorChange,
                        predictiveBackEnabled = predictiveBackEnabled,
                        onPredictiveBackChange = onPredictiveBackChange,
                        refreshRateMode = refreshRateMode,
                        onRefreshRateModeChange = onRefreshRateModeChange,
                        fontScaleMode = fontScaleMode,
                        customFontScale = customFontScale,
                        onOpenThemeModeSettings = { detail = "theme_mode" },
                        onOpenFontSettings = { detail = "font" },
                        onOpenRefreshRateSettings = { detail = "refresh_rate" },
                        onOpenChatBackgroundSettings = { detail = "chat_background" },
                        onOpenAppIconSettings = { detail = "app_icon" },
                        onOpenStreamingOutputSettings = { detail = "streaming_output" },
                    )
                    "theme_mode" -> ThemeModeSettings(
                        settings = settings,
                        controller = controller,
                        themeMode = themeMode,
                        onOpenCustomThemeColor = { detail = "custom_theme_color" },
                        onThemeModeChange = onThemeModeChange,
                    )
                    "custom_theme_color" -> CustomThemeColorSettings(settings, controller)
                    "language" -> LanguageSettings(
                        languageMode = languageMode,
                        onLanguageModeChange = onLanguageModeChange,
                    )
                    "refresh_rate" -> RefreshRateSettings(
                        refreshRateMode = refreshRateMode,
                        onRefreshRateModeChange = onRefreshRateModeChange,
                    )
                    "chat_background" -> ChatBackgroundSettings(settings)
                    "app_icon" -> AppIconSettings(settings)
                    "streaming_output" -> StreamingOutputSettings(settings, controller)
                    "font" -> FontSizeSettings(
                        settings = settings,
                        controller = controller,
                        fontScaleMode = fontScaleMode,
                        customFontScale = customFontScale,
                        onFontScaleModeChange = onFontScaleModeChange,
                        onCustomFontScaleChange = onCustomFontScaleChange,
                        onOpenFontLibrary = { detail = "font_library" },
                    )
                    "font_library" -> FontLibrarySettings(settings, controller)
                    "permissions" -> PermissionSettings(termuxExecutor)
                    "system_permissions" -> SystemPermissionSettings(settings, systemCommandExecutor)
                    "device_interaction" -> DeviceInteractionSettings(
                        settings = settings,
                        onOpenManualControl = { detail = "device_interaction_control" },
                    )
                    "device_interaction_control" -> ManualControlDebugScreen(settings)
                    "tools" -> AgentToolSettings(settings, termuxExecutor, controller.settingsRevision.intValue)
                    "termux" -> TermuxSettings(settings, termuxExecutor, workspaceManager)
                    "debian" -> ProotLinuxSettings()
                    "mcp" -> McpSettings(settings, mcpClientManager, controller.settingsRevision.intValue)
                    "local_mcp" -> LocalMcpServerSettings(settings, localMcpServerManager, controller.settingsRevision.intValue)
                    "ssh" -> SshSettings(settings, sshExecutor, controller.settingsRevision.intValue)
                    "email" -> EmailSettings(settings, controller.settingsRevision.intValue)
                    "webdav" -> WebDavSettings(settings, webDavClient, controller.settingsRevision.intValue)
                    "file_transfer" -> FileTransferSettings(settings, fileTransferClient, controller.settingsRevision.intValue)
                    "mini_server" -> MiniServerSettings(
                        settings,
                        miniServerManager,
                        controller.settingsRevision.intValue,
                        onOpenLogs = { detail = "mini_server_logs" },
                    )
                    "mini_server_logs" -> MiniServerLogSettings(miniServerManager)
                    "web_search" -> WebSearchSettings(
                        settings = settings,
                        externalRevision = controller.settingsRevision.intValue,
                        onChanged = { controller.settingsRevision.intValue++ },
                    )
                    "backup" -> BackupSettings(
                        settings = settings,
                        webDavClient = webDavClient,
                        backupManager = backupManager,
                        status = backupStatus,
                        onStatusChange = onBackupStatusChange,
                        onImportBackup = onImportBackup,
                        onConfigChanged = { controller.settingsRevision.intValue++ },
                    )
                    "storage" -> StorageCacheSettings()
                    "prompts" -> PromptSettingsScreen(settings)
                    "memories" -> MemorySettingsScreen(settings)
                    "skills" -> SkillsScreen(
                        skills = skills,
                        status = skillStatus,
                        onImportSkillFile = onImportSkillFile,
                        onImportSkillRepository = onImportSkillRepository,
                        onImportSkillMarkdown = onImportSkillMarkdown,
                        onToggleSkill = onToggleSkill,
                        onDeleteSkill = onDeleteSkill,
                    )
                    "licenses" -> OpenSourceLicensesScreen()
                    "about" -> AboutSoftwareScreen(
                        updateAvailable = updateAvailable,
                        onUpdateAvailabilityChange = onUpdateAvailabilityChange,
                        onOpenDeviceInfo = { detail = "device" },
                        onOpenServiceAgreements = { detail = CompliancePageIds.INDEX },
                    )
                    "device" -> DeviceInfoScreen()
                    CompliancePageIds.INDEX -> ServiceAgreementScreen(onOpenDocument = { detail = it })
                    CompliancePageIds.USER_AGREEMENT,
                    CompliancePageIds.PRIVACY_POLICY,
                    CompliancePageIds.PERSONAL_INFO,
                    CompliancePageIds.THIRD_PARTY,
                    CompliancePageIds.APP_PERMISSIONS -> ComplianceDocumentScreen(target)
                    else -> Text(context.getString(R.string.settings_not_available), color = KimiMuted)
                    }
                }
                return@pageContent
            }

        val modelEntries = listOf(
            SettingsMenuEntry(Icons.Default.AccountCircle, context.getString(R.string.menu_profile), context.getString(R.string.menu_profile_desc), "profile"),
            SettingsMenuEntry(Icons.Default.SmartToy, context.getString(R.string.menu_model_service), context.getString(R.string.menu_model_service_desc), "model"),
            SettingsMenuEntry(Icons.Default.Summarize, context.getString(R.string.menu_topic_summary_model), context.getString(R.string.menu_topic_summary_model_desc), "topic_summary_model"),
            SettingsMenuEntry(Icons.Default.AccountTree, context.getString(R.string.menu_sub_agents), context.getString(R.string.menu_sub_agents_desc), "sub_agents"),
            SettingsMenuEntry(Icons.Default.TravelExplore, context.getString(R.string.menu_web_search), context.getString(R.string.menu_web_search_desc), "web_search"),
            SettingsMenuEntry(Icons.Default.Terminal, context.getString(R.string.menu_termux), context.getString(R.string.menu_termux_desc), "termux"),
            SettingsMenuEntry(Icons.Default.Computer, context.getString(R.string.menu_debian), context.getString(R.string.menu_debian_desc), "debian"),
            SettingsMenuEntry(ImageVector.vectorResource(R.drawable.ic_mcp), context.getString(R.string.menu_mcp_server), context.getString(R.string.menu_mcp_server_desc), "mcp"),
            SettingsMenuEntry(Icons.Default.Hub, context.getString(R.string.menu_local_mcp), context.getString(R.string.menu_local_mcp_desc), "local_mcp"),
            SettingsMenuEntry(Icons.Default.Key, context.getString(R.string.menu_ssh), context.getString(R.string.menu_ssh_desc), "ssh"),
            SettingsMenuEntry(Icons.Default.Email, context.getString(R.string.menu_email), context.getString(R.string.menu_email_desc), "email"),
            SettingsMenuEntry(Icons.Default.CloudSync, context.getString(R.string.menu_webdav), context.getString(R.string.menu_webdav_desc), "webdav"),
            SettingsMenuEntry(Icons.Default.SyncAlt, context.getString(R.string.menu_file_transfer), context.getString(R.string.menu_file_transfer_desc), "file_transfer"),
            SettingsMenuEntry(Icons.Default.Lan, context.getString(R.string.menu_mini_server), context.getString(R.string.menu_mini_server_desc), "mini_server"),
        )
        val personalizationEntries = listOf(
            SettingsMenuEntry(
                Icons.Default.Palette,
                context.getString(R.string.menu_theme),
                context.getString(
                    R.string.menu_theme_current,
                    if (settings.customThemeColorEnabled) uiText(R.string.custom_theme_color_value, settings.customThemeColor) else themeName(themeMode),
                    refreshRateName(refreshRateMode),
                    fontScaleName(fontScaleMode, customFontScale),
                ),
                "theme",
            ),
            SettingsMenuEntry(
                Icons.Default.Language,
                context.getString(R.string.detail_language),
                languageName(languageMode),
                "language",
            ),
            SettingsMenuEntry(Icons.Default.EditNote, context.getString(R.string.menu_system_prompt), context.getString(R.string.menu_system_prompt_desc), "prompts"),
            SettingsMenuEntry(Icons.Default.Psychology, context.getString(R.string.menu_memory), context.getString(R.string.menu_memory_desc, settings.memories().count { it.enabled }), "memories"),
            SettingsMenuEntry(Icons.Default.School, context.getString(R.string.menu_skills), context.getString(R.string.menu_skills_desc, skills.size), "skills"),
        )
        val generalEntries = listOf(
            SettingsMenuEntry(Icons.Default.Construction, context.getString(R.string.menu_agent_tools), context.getString(R.string.menu_agent_tools_desc), "tools"),
            SettingsMenuEntry(Icons.Default.Storage, context.getString(R.string.menu_storage), context.getString(R.string.menu_storage_desc), "storage"),
            SettingsMenuEntry(Icons.Default.Backup, context.getString(R.string.menu_backup), context.getString(R.string.menu_backup_desc), "backup"),
            SettingsMenuEntry(Icons.Default.AdminPanelSettings, context.getString(R.string.menu_system_permissions), context.getString(R.string.menu_system_permissions_desc), "system_permissions"),
            SettingsMenuEntry(Icons.Default.AccessibilityNew, context.getString(R.string.menu_device_interaction), context.getString(R.string.menu_device_interaction_desc), "device_interaction"),
            SettingsMenuEntry(Icons.Default.Security, context.getString(R.string.menu_app_permissions), context.getString(R.string.menu_app_permissions_desc), "permissions"),
            SettingsMenuEntry(Icons.Default.Description, context.getString(R.string.menu_licenses), context.getString(R.string.menu_licenses_desc), "licenses"),
            SettingsMenuEntry(Icons.Default.Info, context.getString(R.string.menu_about), context.getString(R.string.menu_about_desc), "about"),
        )
        val normalizedQuery = settingsQuery.trim()
        fun filtered(entries: List<SettingsMenuEntry>): List<SettingsMenuEntry> {
            if (normalizedQuery.isBlank()) return entries
            return entries.filter {
                it.title.contains(normalizedQuery, ignoreCase = true) ||
                    it.description.contains(normalizedQuery, ignoreCase = true)
            }
        }
        val visibleGroups = listOf(
            context.getString(R.string.section_model_service) to filtered(modelEntries),
            context.getString(R.string.section_personalization) to filtered(personalizationEntries),
            context.getString(R.string.section_general) to filtered(generalEntries),
        )

            Column(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(settingsListScroll)
                    .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
            CapsuleTextField(
                value = settingsQuery,
                onValueChange = { settingsQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = context.getString(R.string.settings_search_hint),
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(21.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            visibleGroups.forEach { (label, entries) ->
                if (entries.isNotEmpty()) {
                    KimiSectionLabel(label)
                    KimiCardBox {
                        entries.forEachIndexed { index, entry ->
                            KimiMenuRow(entry.icon, entry.title, entry.description,
                                enabled = entry.target != "device_interaction" || com.yukisoffd.lyracode.interaction.DeviceInteractionAvailability.isSupported(),
                                onDisabledClick = { showUnsupportedDevice = true }) {
                                detail = entry.target
                            }
                            if (index != entries.lastIndex) KimiDivider()
                        }
                    }
                }
            }
            if (visibleGroups.all { it.second.isEmpty() }) {
                Text(
                    context.getString(R.string.settings_search_no_results),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            }
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            if (predictiveBackEnabled && detail != null && predictiveBackState.isInProgress) {
                Box(Modifier.fillMaxSize()) {
                    renderPage(previousDetail(detail))
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
                targetState = detail,
                modifier = Modifier
                    .fillMaxSize()
                    .predictiveBackTransform(predictiveBackState),
                transitionSpec = {
                    if (skipNextTransition) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        val forward = when {
                            initialState == "device" && targetState == "about" -> false
                            initialState == "about" && targetState == "device" -> true
                            initialState == CompliancePageIds.INDEX && targetState == "about" -> false
                            initialState == "about" && targetState == CompliancePageIds.INDEX -> true
                            isComplianceDocument(initialState) && targetState == CompliancePageIds.INDEX -> false
                            initialState == CompliancePageIds.INDEX && isComplianceDocument(targetState) -> true
                            initialState == "custom_theme_color" && targetState == "theme_mode" -> false
                            initialState == "font_library" && targetState == "font" -> false
                            initialState == "device_interaction_control" && targetState == "device_interaction" -> false
                            initialState == "theme_mode" && targetState == "custom_theme_color" -> true
                            initialState == "font" && targetState == "font_library" -> true
                            initialState == "device_interaction" && targetState == "device_interaction_control" -> true
                            initialState in ADDITIONAL_MODEL_DETAIL_IDS && targetState == "topic_summary_model" -> false
                            initialState == "topic_summary_model" && targetState in ADDITIONAL_MODEL_DETAIL_IDS -> true
                            initialState in setOf("theme_mode", "font", "refresh_rate", "chat_background", "streaming_output", "app_icon") && targetState == "theme" -> false
                            initialState == "theme" && targetState in setOf("theme_mode", "font", "refresh_rate", "chat_background", "streaming_output", "app_icon") -> true
                            initialState == "mini_server_logs" && targetState == "mini_server" -> false
                            initialState == "mini_server" && targetState == "mini_server_logs" -> true
                            targetState == null -> false
                            else -> true
                        }
                        slideInHorizontally(animationSpec = tween(260)) { fullWidth -> if (forward) fullWidth else -fullWidth / 3 } togetherWith
                            slideOutHorizontally(animationSpec = tween(260)) { fullWidth -> if (forward) -fullWidth / 3 else fullWidth }
                    }
                },
                label = "settings-detail-transition",
            ) { target ->
                renderPage(target)
            }
        }
    }
}

@Composable
internal fun SettingsDetailPage(
    inset: Boolean = true,
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
            .padding(if (inset) 18.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val bodyModifier = if (scroll) {
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        } else {
            Modifier
                .weight(1f)
                .fillMaxWidth()
        }
        Column(bodyModifier, verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

internal fun settingsDetailTitle(context: Context, detail: String): String = when (detail) {
    "profile" -> context.getString(R.string.detail_profile)
    "model" -> context.getString(R.string.detail_model)
    "topic_summary_model" -> context.getString(R.string.detail_topic_summary_model)
    "topic_summary_model_topic" -> uiText(R.string.ui_topic_summary_model)
    "topic_summary_model_compression" -> uiText(R.string.label_history_compression_model)
    "topic_summary_model_vision" -> uiText(R.string.label_vision_understanding_model)
    "topic_summary_model_ocr" -> uiText(R.string.label_ocr_model)
    "topic_summary_model_media_image" -> uiText(R.string.ui_image_generation_model)
    "topic_summary_model_media_video" -> uiText(R.string.ui_video_generation_model)
    "topic_summary_model_media_music" -> uiText(R.string.ui_music_generation_model)
    "topic_summary_model_media_audio" -> uiText(R.string.ui_audio_generation_model)
    "sub_agents" -> context.getString(R.string.detail_sub_agents)
    "web_search" -> context.getString(R.string.detail_web_search)
    "workspace" -> context.getString(R.string.detail_workspace)
    "theme" -> context.getString(R.string.detail_theme)
    "custom_theme_color" -> uiText(R.string.ui_set_custom_theme_color)
    "theme_mode" -> context.getString(R.string.detail_theme_mode)
    "language" -> context.getString(R.string.detail_language)
    "font" -> uiText(R.string.ui_fonts_and_size)
    "font_library" -> uiText(R.string.ui_font_library)
    "refresh_rate" -> context.getString(R.string.detail_refresh_rate)
    "chat_background" -> context.getString(R.string.detail_chat_background)
    "app_icon" -> context.getString(R.string.app_icon_title)
    "streaming_output" -> context.getString(R.string.detail_streaming_output)
    "permissions" -> context.getString(R.string.detail_permissions)
    "system_permissions" -> context.getString(R.string.detail_system_permissions)
    "device_interaction" -> context.getString(R.string.detail_device_interaction)
    "device_interaction_control" -> context.getString(R.string.detail_manual_control)
    "tools" -> context.getString(R.string.detail_tools)
    "storage" -> context.getString(R.string.detail_storage)
    "termux" -> context.getString(R.string.detail_termux)
    "debian" -> context.getString(R.string.menu_debian)
    "mcp" -> context.getString(R.string.detail_mcp)
    "local_mcp" -> context.getString(R.string.detail_local_mcp)
    "ssh" -> context.getString(R.string.detail_ssh)
    "email" -> context.getString(R.string.detail_email)
    "webdav" -> context.getString(R.string.detail_webdav)
    "file_transfer" -> context.getString(R.string.detail_file_transfer)
    "mini_server" -> context.getString(R.string.detail_mini_server)
    "mini_server_logs" -> context.getString(R.string.detail_mini_server_logs)
    "backup" -> context.getString(R.string.detail_backup)
    "prompts" -> context.getString(R.string.detail_prompts)
    "memories" -> context.getString(R.string.detail_memories)
    "skills" -> context.getString(R.string.detail_skills)
    "licenses" -> context.getString(R.string.detail_licenses)
    "about" -> context.getString(R.string.detail_about)
    "device" -> context.getString(R.string.detail_device)
    CompliancePageIds.INDEX -> context.getString(R.string.compliance_service_agreements)
    CompliancePageIds.USER_AGREEMENT -> context.getString(R.string.compliance_user_agreement)
    CompliancePageIds.PRIVACY_POLICY -> context.getString(R.string.compliance_privacy_policy)
    CompliancePageIds.PERSONAL_INFO -> context.getString(R.string.compliance_personal_info_list)
    CompliancePageIds.THIRD_PARTY -> context.getString(R.string.compliance_third_party_list)
    CompliancePageIds.APP_PERMISSIONS -> context.getString(R.string.compliance_app_permissions)
    else -> context.getString(R.string.detail_default)
}

private val ADDITIONAL_MODEL_DETAIL_IDS = setOf(
    "topic_summary_model_topic",
    "topic_summary_model_compression",
    "topic_summary_model_vision",
    "topic_summary_model_ocr",
    "topic_summary_model_media_image",
    "topic_summary_model_media_video",
    "topic_summary_model_media_music",
    "topic_summary_model_media_audio",
)

private fun isComplianceDocument(detail: String?): Boolean = detail != null && detail in CompliancePageIds.documents

@Composable
internal fun WorkspaceSettings(
    workspaceDisplayName: String,
    workspaceManager: WorkspaceManager,
    onPickWorkspace: () -> Unit,
) {
    KimiCardBox {
        KimiMenuRow(Icons.Default.Folder, uiText(R.string.menu_current_directory), workspaceDisplayName, onClick = onPickWorkspace)
        KimiDivider()
        KimiMenuRow(Icons.Default.Terminal, uiText(R.string.menu_termux_path), workspaceManager.termuxRootPath() ?: uiText(R.string.termux_path_primary))
        Text(uiText(R.string.workspace_hint), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
    }
}

