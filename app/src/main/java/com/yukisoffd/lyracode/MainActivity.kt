package com.yukisoffd.lyracode

import android.Manifest
import android.app.Activity
import android.app.WallpaperManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.os.StrictMode
import android.provider.Settings
import android.provider.MediaStore
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.MediaController
import android.widget.VideoView
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.core.content.edit
import com.yukisoffd.lyracode.ai.ChatRecord
import com.yukisoffd.lyracode.ai.AiResponseCache
import com.yukisoffd.lyracode.ai.OpenAiAgent
import com.yukisoffd.lyracode.ai.TodoItem
import com.yukisoffd.lyracode.ai.WebViewWebAgent
import com.yukisoffd.lyracode.data.ApiProfile
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.AuditEntry
import com.yukisoffd.lyracode.data.AuditLogStore
import com.yukisoffd.lyracode.data.BackupManager
import com.yukisoffd.lyracode.data.BackupOptions
import com.yukisoffd.lyracode.data.Conversation
import com.yukisoffd.lyracode.data.ConversationStore
import com.yukisoffd.lyracode.data.McpServerConfig
import com.yukisoffd.lyracode.data.McpToolDefinition
import com.yukisoffd.lyracode.data.SkillPack
import com.yukisoffd.lyracode.data.SshServerConfig
import com.yukisoffd.lyracode.data.WebDavServerConfig
import com.yukisoffd.lyracode.filetransfer.FileTransferClient
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
import com.yukisoffd.lyracode.webdav.TransferProgress
import com.yukisoffd.lyracode.webdav.WebDavClient
import com.yukisoffd.lyracode.workspace.GlobalFileManager
import com.yukisoffd.lyracode.workspace.NativeFileManager
import com.yukisoffd.lyracode.workspace.UploadedFile
import com.yukisoffd.lyracode.workspace.UploadedFileManager
import com.yukisoffd.lyracode.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.max
import kotlin.math.abs
import android.graphics.Canvas as AndroidCanvas

class MainActivity : ComponentActivity() {
    private var controller: ChatController? = null
    private var miniServerManager: MiniServerManager? = null
    private var localMcpServerManager: LocalMcpServerManager? = null
    private var sshTerminalSessionManager: SshTerminalSessionManager? = null
    private var localProotTerminalSessionManager: LocalProotTerminalSessionManager? = null
    private val localNetworkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) localMcpServerManager?.syncWithSettings()
    }

    override fun attachBaseContext(newBase: Context) {
        val languageMode = AppSettings(newBase).languageMode
        super.attachBaseContext(newBase.localizedContext(languageMode))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UpdateManifestEasterEggRuntime.initialize(this)
        if (savedInstanceState == null) UpdateManifestEasterEggRuntime.beginFreshAppTask()
        enableAndroid17CompatibilityDiagnostics()
        val settings = AppSettings(this)
        val compatibilityPreferences = getSharedPreferences(COMPATIBILITY_PREFERENCES, MODE_PRIVATE)
        AppStrings.initialize(this)
        if (savedInstanceState == null) settings.clearChatInputDrafts()
        val auditLogStore = AuditLogStore(this)
        val conversationStore = ConversationStore(this)
        val workspaceManager = WorkspaceManager(this, settings)
        val nativeFileManager = NativeFileManager(this, workspaceManager)
        val globalFileManager = GlobalFileManager()
        val downloadTaskManager = DownloadTaskManager.getInstance(
            this,
            settings,
            nativeFileManager,
            globalFileManager,
        )
        val scheduledTaskManager = ScheduledTaskManager.getInstance(this)
        val termuxExecutor = TermuxExecutor(this, auditLogStore)
        val uploadedFileManager = UploadedFileManager(this)
        val webAgent = WebViewWebAgent(this, settings)
        val responseCache = AiResponseCache(cacheDir)
        val mcpClientManager = McpClientManager(this, settings)
        val sshExecutor = SshExecutor(settings)
        val sshTerminalSessionManager = SshTerminalSessionManager(sshExecutor)
        this.sshTerminalSessionManager = sshTerminalSessionManager
        val localProotTerminalSessionManager = LocalProotTerminalSessionManager(this) {
            workspaceManager.termuxRootPath()
        }
        this.localProotTerminalSessionManager = localProotTerminalSessionManager
        val systemCommandExecutor = SystemCommandExecutor(this, settings)
        val webDavClient = WebDavClient()
        val fileTransferClient = FileTransferClient(this)
        val backupManager = BackupManager(this, settings, conversationStore)
        val miniServerManager = MiniServerManager(this, settings, workspaceManager)
        this.miniServerManager = miniServerManager
        val localMcpServerManager = LocalMcpServerManager(settings)
        this.localMcpServerManager = localMcpServerManager
        val agent = OpenAiAgent(this, settings, conversationStore, nativeFileManager, globalFileManager, termuxExecutor, workspaceManager, webAgent, mcpClientManager, sshExecutor, systemCommandExecutor, webDavClient, fileTransferClient, backupManager, miniServerManager, downloadTaskManager, scheduledTaskManager, responseCache)
        localMcpServerManager.attachAgent(agent)
        if (Android17Compatibility.hasLocalNetworkAccess(this)) {
            localMcpServerManager.syncWithSettings()
        }
        val chatController = ChatController(this, settings, conversationStore, uploadedFileManager, workspaceManager, agent)
        controller = chatController

        setContent {
            var themeMode by remember { mutableStateOf(settings.themeMode) }
            var dynamicColorEnabled by remember { mutableStateOf(settings.dynamicColorEnabled) }
            var predictiveBackEnabled by remember { mutableStateOf(settings.predictiveBackEnabled) }
            var languageMode by remember { mutableStateOf(settings.languageMode) }
            var refreshRateMode by remember { mutableStateOf(settings.refreshRateMode) }
            var fontScaleMode by remember { mutableStateOf(settings.fontScaleMode) }
            var customFontScale by remember { mutableStateOf(settings.customFontScale) }
            var wallpaperColorRevision by remember { mutableIntStateOf(0) }
            var showLocalNetworkPermissionRationale by rememberSaveable {
                mutableStateOf(
                    Android17Compatibility.requiresLocalNetworkPermission() &&
                        !Android17Compatibility.hasLocalNetworkAccess(this@MainActivity) &&
                        !compatibilityPreferences.getBoolean(LOCAL_NETWORK_RATIONALE_SEEN, false),
                )
            }
            val systemDark = isSystemInDarkTheme()
            val systemFontScale = LocalDensity.current.fontScale
            val settingsRevision = chatController.settingsRevision.intValue
            val customThemeColorEnabled = settings.customThemeColorEnabled
            val customThemeIsDark = remember(settingsRevision, customThemeColorEnabled, settings.customThemeColor) {
                if (!customThemeColorEnabled) false else runCatching {
                    val color = android.graphics.Color.parseColor(settings.customThemeColor)
                    val luminance = (android.graphics.Color.red(color) * 299 + android.graphics.Color.green(color) * 587 + android.graphics.Color.blue(color) * 114) / 1000
                    luminance < 128
                }.getOrDefault(false)
            }
            LaunchedEffect(refreshRateMode) {
                applyPreferredRefreshRate(refreshRateMode)
            }
            DisposableEffect(dynamicColorEnabled) {
                if (dynamicColorEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val wallpaperManager = WallpaperManager.getInstance(this@MainActivity)
                    val listener = WallpaperManager.OnColorsChangedListener { _, _ -> wallpaperColorRevision++ }
                    wallpaperManager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
                    onDispose { wallpaperManager.removeOnColorsChangedListener(listener) }
                } else {
                    onDispose { }
                }
            }
            val effectiveFontScale = when (fontScaleMode) {
                AppSettings.FONT_SCALE_SMALL -> 0.9f
                AppSettings.FONT_SCALE_NORMAL -> 1.0f
                AppSettings.FONT_SCALE_LARGE -> 1.12f
                AppSettings.FONT_SCALE_EXTRA_LARGE -> 1.25f
                AppSettings.FONT_SCALE_CUSTOM -> customFontScale
                else -> systemFontScale
            }.coerceIn(AppSettings.MIN_FONT_SCALE, AppSettings.MAX_FONT_SCALE)
            val darkMode = if (customThemeColorEnabled) {
                customThemeIsDark
            } else when (themeMode) {
                AppSettings.THEME_LIGHT -> false
                AppSettings.THEME_DARK -> true
                else -> systemDark
            }
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides this@MainActivity,
                LocalOnBackPressedDispatcherOwner provides this@MainActivity,
            ) {
                LyraCodeTheme(
                    darkMode = darkMode,
                    dynamicColor = dynamicColorEnabled,
                    fontScale = effectiveFontScale,
                    settings = settings,
                    settingsRevision = settingsRevision,
                    dynamicColorRevision = wallpaperColorRevision,
                ) {
                    LyraCodeApp(
                        settings = settings,
                        auditLogStore = auditLogStore,
                        workspaceManager = workspaceManager,
                        termuxExecutor = termuxExecutor,
                        mcpClientManager = mcpClientManager,
                        sshExecutor = sshExecutor,
                        sshTerminalSessionManager = sshTerminalSessionManager,
                        localProotTerminalSessionManager = localProotTerminalSessionManager,
                        systemCommandExecutor = systemCommandExecutor,
                        webDavClient = webDavClient,
                        fileTransferClient = fileTransferClient,
                        backupManager = backupManager,
                        miniServerManager = miniServerManager,
                        localMcpServerManager = localMcpServerManager,
                        downloadTaskManager = downloadTaskManager,
                        scheduledTaskManager = scheduledTaskManager,
                        controller = chatController,
                        themeMode = themeMode,
                        onThemeModeChange = {
                            themeMode = it
                            settings.themeMode = it
                            settings.darkMode = it == AppSettings.THEME_DARK
                        },
                        dynamicColorEnabled = dynamicColorEnabled,
                        onDynamicColorChange = {
                            dynamicColorEnabled = it
                            settings.dynamicColorEnabled = it
                        },
                        predictiveBackEnabled = predictiveBackEnabled,
                        onPredictiveBackChange = {
                            predictiveBackEnabled = it
                            settings.predictiveBackEnabled = it
                        },
                        languageMode = languageMode,
                        onLanguageModeChange = {
                            val normalized = AppSettings.normalizeLanguageMode(it)
                            if (normalized != languageMode) {
                                languageMode = normalized
                                settings.languageMode = normalized
                                recreate()
                            }
                        },
                        refreshRateMode = refreshRateMode,
                        onRefreshRateModeChange = {
                            refreshRateMode = it
                            settings.refreshRateMode = it
                        },
                        fontScaleMode = fontScaleMode,
                        customFontScale = customFontScale,
                        onFontScaleModeChange = {
                            fontScaleMode = it
                            settings.fontScaleMode = it
                        },
                        onCustomFontScaleChange = {
                            customFontScale = it
                            settings.customFontScale = it
                        },
                    )
                    if (showLocalNetworkPermissionRationale) {
                        AlertDialog(
                            onDismissRequest = {
                                showLocalNetworkPermissionRationale = false
                                compatibilityPreferences.edit {
                                    putBoolean(LOCAL_NETWORK_RATIONALE_SEEN, true)
                                }
                            },
                            title = { Text(uiText(R.string.local_network_permission_title)) },
                            text = { Text(uiText(R.string.local_network_permission_rationale)) },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showLocalNetworkPermissionRationale = false
                                        compatibilityPreferences.edit {
                                            putBoolean(LOCAL_NETWORK_RATIONALE_SEEN, true)
                                        }
                                        requestLocalNetworkPermission()
                                    },
                                ) { Text(uiText(R.string.action_enable)) }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = {
                                        showLocalNetworkPermissionRationale = false
                                        compatibilityPreferences.edit {
                                            putBoolean(LOCAL_NETWORK_RATIONALE_SEEN, true)
                                        }
                                    },
                                ) { Text(uiText(R.string.action_later)) }
                            },
                        )
                    }
                }
            }
        }
    }

    internal fun requestLocalNetworkPermission() {
        if (Android17Compatibility.requiresLocalNetworkPermission() &&
            !Android17Compatibility.hasLocalNetworkAccess(this)
        ) {
            localNetworkPermissionLauncher.launch(
                Android17Compatibility.ACCESS_LOCAL_NETWORK_PERMISSION,
            )
        }
    }

    private fun enableAndroid17CompatibilityDiagnostics() {
        if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= Android17Compatibility.API_LEVEL) {
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder(StrictMode.getVmPolicy())
                    .detectImplicitUriPermissionGrant()
                    .penaltyLog()
                    .build(),
            )
        }
    }

    override fun onDestroy() {
        controller?.close()
        miniServerManager?.close()
        localMcpServerManager?.close()
        sshTerminalSessionManager?.close()
        localProotTerminalSessionManager?.close()
        if (isFinishing) {
            AppSettings(this).clearChatInputDrafts()
        }
        super.onDestroy()
    }

    private fun applyPreferredRefreshRate(mode: String) {
        val preferredRate = when (mode) {
            AppSettings.REFRESH_RATE_30 -> 30f
            AppSettings.REFRESH_RATE_60 -> 60f
            AppSettings.REFRESH_RATE_90 -> 90f
            AppSettings.REFRESH_RATE_120 -> 120f
            else -> 0f
        }
        val attrs = window.attributes
        if (attrs.preferredRefreshRate != preferredRate) {
            attrs.preferredRefreshRate = preferredRate
            window.attributes = attrs
        }
    }

    companion object {
        internal const val TERMUX_RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
        private const val COMPATIBILITY_PREFERENCES = "android_compatibility"
        private const val LOCAL_NETWORK_RATIONALE_SEEN = "android_17_local_network_rationale_seen"
    }
}

internal fun Context.localizedContext(languageMode: String): Context {
    val locale = when (AppSettings.normalizeLanguageMode(languageMode)) {
        AppSettings.LANGUAGE_ZH_CN -> Locale.SIMPLIFIED_CHINESE
        AppSettings.LANGUAGE_ZH_TW -> Locale.TRADITIONAL_CHINESE
        AppSettings.LANGUAGE_EN -> Locale.ENGLISH
        else -> null
    } ?: return this
    val configuration = Configuration(resources.configuration)
    configuration.setLocales(LocaleList(locale))
    return createConfigurationContext(configuration)
}






