package com.yukisoffd.lyracode

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.yukisoffd.lyracode.data.AppUpdateInfo
import com.yukisoffd.lyracode.data.UpdateDownloadProgress
import com.yukisoffd.lyracode.data.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import kotlin.math.min
import kotlin.math.max



internal data class LicenseNotice(
    val name: String,
    val license: String,
    val note: String,
    val licenseText: String,
)

private enum class UpdateManifestEasterEggPrompt {
    FIRST_WARNING,
    CHOICE,
}

@Composable
internal fun OpenSourceLicensesScreen() {
    val context = LocalContext.current
    var selectedNotice by remember { mutableStateOf<LicenseNotice?>(null) }
    val gpl3Text = remember(context) {
        runCatching {
            context.assets.open("org/scilab/forge/jlatexmath/licences/GPL-3.0.txt")
                .bufferedReader()
                .use { it.readText() }
        }.getOrElse { "GNU GPL version 3: https://www.gnu.org/licenses/gpl-3.0.txt" }
    }
    val notices = remember(context) {
        listOf(
            LicenseNotice("AndroidX Core KTX", "Apache License 2.0", uiText(R.string.ui_android_kotlin_extensions_and_compatibility_layer), LicenseTexts.APACHE_2_0),
            LicenseNotice("AndroidX Activity Compose", "Apache License 2.0", uiText(R.string.ui_compose_activity_integration), LicenseTexts.APACHE_2_0),
            LicenseNotice("Jetpack Compose UI", "Apache License 2.0", uiText(R.string.ui_declarative_ui_framework), LicenseTexts.APACHE_2_0),
            LicenseNotice("Jetpack Compose Material 3", "Apache License 2.0", uiText(R.string.ui_material_design_3_components), LicenseTexts.APACHE_2_0),
            LicenseNotice("Jetpack Compose Material Icons Extended", "Apache License 2.0", uiText(R.string.ui_ui_icon_library), LicenseTexts.APACHE_2_0),
            LicenseNotice("AndroidX DocumentFile", "Apache License 2.0", uiText(R.string.ui_saf_workspace_file_access), LicenseTexts.APACHE_2_0),
            LicenseNotice("AndroidX Security Crypto", "Apache License 2.0", uiText(R.string.ui_local_encrypted_storage_for_sensitive_configuration), LicenseTexts.APACHE_2_0),
            LicenseNotice("Kotlinx Coroutines", "Apache License 2.0", uiText(R.string.ui_async_tasks_and_streaming_requests), LicenseTexts.APACHE_2_0),
            LicenseNotice("OkHttp", "Apache License 2.0", uiText(R.string.ui_http_sse_compatible_reading_and_mcp_streamable_http_communication), LicenseTexts.APACHE_2_0),
            LicenseNotice(
                "Android Mail / Jakarta Mail for Android 1.6.7",
                "Eclipse Public License 2.0",
                uiText(R.string.ui_provides_imap_message_reading_folder_and_draft_management_smtp),
                LicenseTexts.EPL_2_0,
            ),
            LicenseNotice("JetBrains Markdown / RikkaHub Markdown fork", "Apache License 2.0", uiText(R.string.ui_markdown_gfm_ast_parsing_with_tables_lists_and_math), LicenseTexts.APACHE_2_0),
            LicenseNotice("Mermaid 11.17.2", "MIT", uiText(R.string.tool_create_chart_desc),
                context.assets.open("charts/MERMAID-LICENSE").bufferedReader().use { it.readText() }),
            LicenseNotice("Apache ECharts 6.1.0", "Apache License 2.0 / BSD", uiText(R.string.tool_create_chart_desc),
                listOf("ECHARTS-LICENSE", "ECHARTS-NOTICE", "ECHARTS-D3-LICENSE").joinToString("\n\n") { name ->
                    context.assets.open("charts/$name").bufferedReader().use { it.readText() }
                }),
            LicenseNotice(
                "Sora Editor / language-textmate",
                "GNU LGPL 2.1 or later",
                context.getString(R.string.license_sora_editor_note),
                LicenseTexts.LGPL_2_1,
            ),
            LicenseNotice(
                "Sora Editor demo TextMate grammars and themes",
                "Individual upstream licenses",
                context.getString(R.string.license_textmate_assets_note),
                LicenseTexts.TEXTMATE_ASSET_NOTICES,
            ),
            LicenseNotice("Android Gradle Plugin", "Apache License 2.0", uiText(R.string.ui_android_build_toolchain), LicenseTexts.APACHE_2_0),
            LicenseNotice("Kotlin", "Apache License 2.0", uiText(R.string.ui_main_development_language_and_compiler), LicenseTexts.APACHE_2_0),
            LicenseNotice("JSch / mwiede fork", "BSD 3-Clause License", uiText(R.string.ui_ssh_connections_and_remote_command_execution), LicenseTexts.BSD_3_CLAUSE),
            LicenseNotice(
                "PRoot for Android / Soffd fork",
                "GPL-2.0-or-later; GPLv3 selected",
                context.getString(R.string.license_proot_note),
                LicenseTexts.PROOT_GPL_NOTICE + "\n\n" + gpl3Text,
            ),
            LicenseNotice("JLatexMath Android / Soffd fork", "GPL v2 or later with linking exception; GPL v3 selected", uiText(R.string.ui_local_latex_math_rendering_source_is_kept_under_third), LicenseTexts.JLATEXMATH_GPL_WITH_EXCEPTION),
            LicenseNotice("JLatexMath core fonts", "OFL / Knuth / permissive terms / Public Domain", uiText(R.string.ui_math_rendering_fonts_full_font_licenses_are_distributed_under), LicenseTexts.JLATEXMATH_FONT_LICENSES),
            LicenseNotice("JSON-java / org.json", "JSON License", uiText(R.string.ui_json_parsing_and_serialization), LicenseTexts.JSON_LICENSE),
            LicenseNotice("JUnit", "Eclipse Public License 1.0", uiText(R.string.ui_unit_test_framework_used_only_for_test_builds), LicenseTexts.EPL_1_0),
            LicenseNotice("Simple Icons", "CC0 1.0 Universal", uiText(R.string.ui_repository_svg_icons_on_the_about_page), LicenseTexts.CC0_1_0),
        )
    }
    selectedNotice?.let { notice ->
        Dialog(
            onDismissRequest = { selectedNotice = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 34.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(notice.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(notice.license, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        }
                        IconButton(onClick = { selectedNotice = null }) {
                            Icon(Icons.Default.Close, contentDescription = uiText(R.string.cd_close))
                        }
                    }
                    KimiDivider()
                    SelectionContainer {
                        Text(
                            notice.licenseText,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 18.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            KimiCardBox {
                Text(uiText(R.string.title_licenses), style = MaterialTheme.typography.titleMedium)
                Text(
                    uiText(R.string.licenses_desc),
                    color = KimiMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(notices) { notice ->
            KimiCardBox(
                modifier = Modifier.clickable { selectedNotice = notice },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(notice.name, style = MaterialTheme.typography.titleSmall)
                        Text(notice.license, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(notice.note, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = KimiMuted)
                }
            }
        }
    }
}

@Composable
internal fun AboutSoftwareScreen(
    updateAvailable: Boolean,
    onUpdateAvailabilityChange: (Boolean) -> Unit,
    onOpenDeviceInfo: () -> Unit,
    onOpenServiceAgreements: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val updateManager = remember(context) { UpdateManager(context) }
    val packageInfo = remember(context.packageName) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        }.getOrNull()
    }
    val versionName = packageInfo?.versionName.orEmpty().ifBlank { uiText(R.string.device_battery_unknown) }
    val versionCode = packageInfo?.let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode.toString() else {
            @Suppress("DEPRECATION")
            it.versionCode.toString()
        }
    } ?: uiText(R.string.device_battery_unknown)
    var notice by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var downloadProgress by remember { mutableStateOf<UpdateDownloadProgress?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var pendingApk by remember { mutableStateOf(updateManager.pendingDownloadedApk()) }
    var updatePromptDisabled by remember { mutableStateOf(updateManager.updatePromptDisabled()) }
    val easterEggSession = remember(context) { UpdateManifestEasterEggRuntime.takeSession(context) }
    var easterEggPrompt by remember { mutableStateOf<UpdateManifestEasterEggPrompt?>(null) }
    var easterEggVisible by remember { mutableStateOf(false) }
    var easterEggCompleted by remember { mutableStateOf(UpdateManifestEasterEggRuntime.isCompleted(context)) }
    var showEasterEggResetConfirmation by remember { mutableStateOf(false) }
    var easterEggRevision by remember { mutableIntStateOf(0) }

    fun registerUpdateManifestTap() {
        if (easterEggCompleted || easterEggVisible) return
        val now = SystemClock.elapsedRealtime()
        when (easterEggSession.click(now)) {
            UpdateManifestEasterEggEvent.SHOW_FIRST_WARNING -> {
                easterEggPrompt = UpdateManifestEasterEggPrompt.FIRST_WARNING
            }
            UpdateManifestEasterEggEvent.SHOW_CHOICE -> {
                easterEggPrompt = UpdateManifestEasterEggPrompt.CHOICE
            }
            UpdateManifestEasterEggEvent.CRASH_ONCE -> {
                UpdateManifestEasterEggRuntime.prepareIntentionalCrash(context)
                Handler(Looper.getMainLooper()).post { throw IntentionalUpdateManifestCrashException() }
            }
            UpdateManifestEasterEggEvent.OPEN_RECOVERY -> {
                easterEggVisible = true
            }
            UpdateManifestEasterEggEvent.NONE -> Unit
        }
        easterEggRevision++
    }

    fun resumeEasterEggAfterPrompt() {
        easterEggPrompt = null
        easterEggSession.resumeAfterPrompt(SystemClock.elapsedRealtime())
        easterEggRevision++
    }

    LaunchedEffect(easterEggRevision, easterEggPrompt, easterEggVisible) {
        if (easterEggPrompt != null || easterEggVisible) return@LaunchedEffect
        val remaining = easterEggSession.remainingTimeoutMs(SystemClock.elapsedRealtime())
            ?: return@LaunchedEffect
        delay(remaining.coerceAtLeast(1L))
        if (easterEggSession.expire(SystemClock.elapsedRealtime())) easterEggRevision++
    }
    DisposableEffect(easterEggSession) {
        onDispose { easterEggSession.reset() }
    }
    val installPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val apk = updateManager.pendingDownloadedApk()
        pendingApk = apk
        if (apk != null && !updateManager.needsInstallPermission()) {
            runCatching { context.startActivity(updateManager.installIntent(apk)) }
                .onFailure { notice = it.message.orEmpty().ifBlank { uiText(R.string.notice_cannot_open_installer) } }
        } else if (apk != null) {
            notice = uiText(R.string.ui_permission_is_not_complete_you_can_tap_continue_installation)
        }
    }

    fun openInstaller(apk: File) {
        if (updateManager.needsInstallPermission()) {
            notice = uiText(R.string.notice_grant_install_permission)
            installPermissionLauncher.launch(updateManager.installPermissionIntent())
        } else {
            runCatching { context.startActivity(updateManager.installIntent(apk)) }
                .onFailure { notice = it.message.orEmpty().ifBlank { uiText(R.string.notice_cannot_open_installer) } }
        }
    }

    fun checkUpdate() {
        if (checking) return
        checking = true
        notice = uiText(R.string.action_checking_update)
        scope.launch {
            val result = withContext(Dispatchers.IO) { updateManager.checkForUpdate() }
            checking = false
            result.fold(
                onSuccess = { info ->
                    if (info == null) {
                        updateManager.clearLatestAvailableUpdate()
                        onUpdateAvailabilityChange(false)
                        notice = uiText(R.string.ui_you_are_on_the_latest_version)
                    } else {
                        updateManager.saveLatestAvailableUpdate(info)
                        onUpdateAvailabilityChange(true)
                        notice = ""
                        updateInfo = info
                    }
                },
                onFailure = { notice = it.message.orEmpty().ifBlank { uiText(R.string.ui_update_check_failed) } },
            )
        }
    }

    updateInfo?.let { info ->
        UpdateDialog(
            info = info,
            progress = downloadProgress,
            downloading = downloading,
            onDismiss = {
                if (!downloading) {
                    updateInfo = null
                    downloadProgress = null
                }
            },
            onOpenWeb = {
                val target = info.webUrl.ifBlank { info.apkUrl }
                if (target.isNotBlank()) runCatching { uriHandler.openUri(target) }
            },
            onDownload = {
                if (downloading) return@UpdateDialog
                downloading = true
                downloadProgress = UpdateDownloadProgress(status = uiText(R.string.notice_preparing_download))
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        updateManager.downloadApk(info) { progress -> downloadProgress = progress }
                    }
                    downloading = false
                    result.fold(
                        onSuccess = { apk ->
                            pendingApk = apk
                            notice = uiText(R.string.notice_download_complete_prepare_install)
                            openInstaller(apk)
                        },
                        onFailure = {
                            val message = it.message.orEmpty().ifBlank { uiText(R.string.notice_download_failed) }
                            downloadProgress = UpdateDownloadProgress(status = message)
                            notice = message
                        },
                    )
                }
            },
        )
    }

    when (easterEggPrompt) {
        UpdateManifestEasterEggPrompt.FIRST_WARNING -> AlertDialog(
            onDismissRequest = {},
            text = { Text(uiText(R.string.easter_egg_first_warning)) },
            confirmButton = {
                TextButton(onClick = ::resumeEasterEggAfterPrompt) {
                    Text(uiText(R.string.easter_egg_first_warning_confirm))
                }
            },
        )
        UpdateManifestEasterEggPrompt.CHOICE -> AlertDialog(
            onDismissRequest = {},
            text = { Text(uiText(R.string.easter_egg_choice_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        easterEggPrompt = null
                        easterEggSession.chooseDefiant(SystemClock.elapsedRealtime())
                        easterEggRevision++
                    },
                ) { Text(uiText(R.string.easter_egg_choice_defiant)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        easterEggPrompt = null
                        UpdateManifestEasterEggRuntime.stopForCurrentProcess()
                        easterEggSession.chooseStop()
                        easterEggRevision++
                    },
                ) { Text(uiText(R.string.easter_egg_choice_stop)) }
            },
        )
        null -> Unit
    }
    if (showEasterEggResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showEasterEggResetConfirmation = false },
            title = { Text(uiText(R.string.easter_egg_reset_title)) },
            text = { Text(uiText(R.string.easter_egg_reset_description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        UpdateManifestEasterEggRuntime.clear(context)
                        easterEggSession.reset()
                        easterEggCompleted = false
                        showEasterEggResetConfirmation = false
                        easterEggRevision++
                    },
                ) { Text(uiText(R.string.action_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showEasterEggResetConfirmation = false }) {
                    Text(uiText(R.string.action_cancel))
                }
            },
        )
    }
    if (easterEggVisible) {
        UpdateManifestRecoveryEasterEgg(
            onExit = {
                UpdateManifestEasterEggRuntime.markCompleted(context)
                easterEggSession.chooseStop()
                easterEggCompleted = true
                easterEggVisible = false
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AboutLogoHeader()
            KimiCardBox {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        uiText(R.string.about_description),
                        color = KimiMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            KimiSectionLabel(context.getString(R.string.section_version_update))
            KimiCardBox {
                AboutVersionRow(
                    versionText = context.getString(R.string.label_version, versionName, versionCode),
                    value = if (checking) context.getString(R.string.action_checking_update) else if (updateAvailable) context.getString(R.string.notice_new_version_found) else context.getString(R.string.action_check_update),
                    updateAvailable = updateAvailable,
                    onClick = ::checkUpdate,
                )
                pendingApk?.let { apk ->
                    KimiDivider()
                    KimiMenuRow(
                        Icons.Default.InstallMobile,
                        updateManager.pendingDownloadedApkLabel(),
                        uiText(R.string.label_downloaded_size, formatBytes(apk.length())),
                        onClick = { openInstaller(apk) },
                    )
                }
                KimiDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AboutIconBadge(Icons.Default.NotificationsOff)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(uiText(R.string.label_no_update_prompt), style = MaterialTheme.typography.titleSmall)
                        Text(uiText(R.string.no_update_prompt_desc), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = updatePromptDisabled,
                        onCheckedChange = {
                            updatePromptDisabled = it
                            updateManager.setUpdatePromptDisabled(it)
                        },
                    )
                }
                KimiDivider()
                KimiMenuRow(Icons.Default.Apps, uiText(R.string.label_app_id), context.packageName)
            }
            KimiSectionLabel(uiText(R.string.section_repos))
            KimiCardBox {
                SocialLinkRow(
                    logo = { AboutIconBadge(Icons.Default.Public) },
                    title = uiText(R.string.label_website),
                    value = "lyracode.app",
                    onClick = { uriHandler.openUri("https://lyracode.app") },
                )
                KimiDivider()
                SocialLinkRow(
                    logo = { AboutIconBadge(R.drawable.ic_simple_github) },
                    title = "GitHub",
                    value = "lyracode-app/Lyra-Code",
                    onClick = { uriHandler.openUri("https://github.com/lyracode-app/Lyra-Code") },
                )
                KimiDivider()
                SocialLinkRow(
                    logo = { AboutIconBadge(R.drawable.ic_simple_gitee) },
                    title = "Gitee",
                    value = "yukisoffd/lyra-code",
                    onClick = { uriHandler.openUri("https://gitee.com/yukisoffd/lyra-code") },
                )
            }
            KimiSectionLabel(context.getString(R.string.compliance_service_agreements))
            KimiCardBox {
                KimiMenuRow(
                    Icons.Default.Policy,
                    context.getString(R.string.compliance_service_agreements),
                    context.getString(R.string.compliance_service_agreements_desc),
                    onClick = onOpenServiceAgreements,
                )
            }
            KimiSectionLabel(uiText(R.string.section_build_info))
            KimiCardBox {
                KimiMenuRow(Icons.Default.PhoneAndroid, uiText(R.string.title_device_info), "${Build.MANUFACTURER} ${Build.MODEL}", onClick = onOpenDeviceInfo)
                KimiDivider()
                KimiMenuRowWithLongClick(
                    Icons.Default.CloudDownload,
                    uiText(R.string.menu_update_manifest),
                    updateManager.manifestUrl().ifBlank { uiText(R.string.label_not_configured_or_na) },
                    onClick = ::registerUpdateManifestTap,
                    onLongClick = {
                        if (easterEggCompleted) showEasterEggResetConfirmation = true
                    },
                )
            }
        }
        TransientNotice(
            message = notice,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp),
            onDismiss = { notice = "" },
        )
    }
}

@Composable
internal fun DeviceInfoScreen() {
    val context = LocalContext.current
    val snapshot = remember { DeviceInfoCollector.collect(context) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            KimiCardBox {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(uiText(R.string.title_device_info), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        uiText(R.string.device_info_desc),
                        color = KimiMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        snapshot.sections.forEach { section ->
            item { KimiSectionLabel(section.title) }
            item {
                KimiCardBox {
                    SelectionContainer {
                        Column {
                            section.items.forEachIndexed { index, item ->
                                DeviceInfoRow(item)
                                if (index != section.items.lastIndex) KimiDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DeviceInfoRow(item: DeviceInfoItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            item.label,
            modifier = Modifier.widthIn(min = 88.dp, max = 112.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            item.value,
            modifier = Modifier.weight(1f),
            color = KimiMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun AboutVersionRow(
    versionText: String,
    value: String,
    updateAvailable: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AboutIconBadge(Icons.Default.SystemUpdate)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(versionText, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (updateAvailable) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error),
                    )
                }
            }
            Text(
                value,
                color = if (updateAvailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SocialLinkRow(
    logo: @Composable () -> Unit,
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        logo()
        Spacer(Modifier.width(14.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                value,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun AboutIconBadge(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(23.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
internal fun AboutIconBadge(
    iconRes: Int,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(23.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
internal fun AboutLogoHeader() {
    val context = LocalContext.current
    val backgroundArgb = MaterialTheme.colorScheme.background.toArgb()
    val isDark = remember(backgroundArgb) {
        val red = (backgroundArgb shr 16) and 0xFF
        val green = (backgroundArgb shr 8) and 0xFF
        val blue = backgroundArgb and 0xFF
        (0.299 * red + 0.587 * green + 0.114 * blue) < 128.0
    }
    val logoAsset = if (isDark) "img/logo-white.png" else "img/logo-black.png"
    val logoBitmap = remember(logoAsset) {
        runCatching {
            context.assets.open(logoAsset).use(BitmapFactory::decodeStream)
        }.getOrNull()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(220.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (logoBitmap != null) {
                Image(
                    bitmap = logoBitmap.asImageBitmap(),
                    contentDescription = "Lyra Code Logo",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(22.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            "Lyra Code",
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}

@Composable
internal fun UpdateDialog(
    info: AppUpdateInfo,
    progress: UpdateDownloadProgress?,
    downloading: Boolean,
    onDismiss: () -> Unit,
    onOpenWeb: () -> Unit,
    onDownload: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
        title = { Text(uiText(R.string.title_new_version, info.versionName.ifBlank { info.versionCode.toString() })) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (info.mandatory) {
                    Text(uiText(R.string.notice_mandatory_update), color = MaterialTheme.colorScheme.error)
                }
                RichMarkdownContent(
                    markdown = info.releaseNotes,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (info.apkSha256.isNotBlank()) {
                    Text(
                        "SHA-256：${info.apkSha256}",
                        color = KimiMuted,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
                progress?.let {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(
                            progress = { it.percent },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val totalText = if (it.totalBytes > 0) " / ${formatBytes(it.totalBytes)}" else ""
                        Text(
                            "${it.status} ${formatBytes(it.downloadedBytes)}$totalText",
                            color = KimiMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDownload, enabled = !downloading && info.apkUrl.isNotBlank()) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (downloading) uiText(R.string.action_downloading) else uiText(R.string.action_download_in_app))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (info.webUrl.isNotBlank() || info.apkUrl.isNotBlank()) {
                    TextButton(onClick = onOpenWeb) { Text(uiText(R.string.action_download_web)) }
                }
                TextButton(onClick = onDismiss, enabled = !downloading) { Text(uiText(R.string.action_later)) }
            }
        },
    )
}

