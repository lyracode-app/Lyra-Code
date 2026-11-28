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
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import com.yukisoffd.lyracode.data.AppUpdateInfo
import com.yukisoffd.lyracode.data.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import kotlin.math.min
import kotlin.math.max



private enum class UpdateManifestEasterEggPrompt {
    FIRST_WARNING,
    CHOICE,
}

@Composable
internal fun AboutSoftwareScreen(
    updateAvailable: Boolean,
    onUpdateAvailabilityChange: (Boolean) -> Unit,
    onOpenDeviceInfo: () -> Unit,
    onOpenServiceAgreements: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenGitHubAcceleration: (String) -> Unit,
    onOpenUpdateManagement: () -> Unit,
    onOpenSponsorDevelopment: () -> Unit = {},
    onOpenDeveloperWall: () -> Unit = {},
    updateInfoState: MutableState<AppUpdateInfo?> = remember { mutableStateOf(null) },
    showUpdateDialog: Boolean = true,
    scrollState: ScrollState = rememberScrollState(),
    updateDownload: UpdateDownload? = null,
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
    var updateInfo by updateInfoState
    val download = updateDownload ?: rememberUpdateDownload(updateManager)
    LaunchedEffect(download, showUpdateDialog) {
        if (showUpdateDialog) download.refreshPending()
    }
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
    fun checkUpdate() {
        if (checking || download.busy) return
        checking = true
        notice = uiText(R.string.action_checking_update)
        scope.launch {
            val result = withContext(Dispatchers.IO) { updateManager.checkForUpdate() }
            download.refreshPending()
            checking = false
            result.fold(
                onSuccess = { info ->
                    if (info == null) {
                        onUpdateAvailabilityChange(false)
                        notice = uiText(R.string.ui_you_are_on_the_latest_version)
                    } else {
                        onUpdateAvailabilityChange(true)
                        notice = ""
                        download.clearStatus()
                        updateInfo = info
                    }
                },
                onFailure = { notice = it.message.orEmpty().ifBlank { uiText(R.string.ui_update_check_failed) } },
            )
        }
    }

    updateInfo?.takeIf { showUpdateDialog }?.let { info ->
        UpdateDialog(
            info = info,
            download = download,
            onOpenGitHubAcceleration = onOpenGitHubAcceleration,
            onDismiss = {
                updateInfo = null
                download.clearStatus()
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
                .verticalScroll(scrollState),
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
                KimiDivider()
                KimiMenuRow(
                    Icons.Default.SystemUpdate,
                    uiText(R.string.update_management_title),
                    onClick = onOpenUpdateManagement,
                )
                val pending = download.pending
                if (pending != null) {
                    KimiDivider()
                    KimiMenuRow(
                        Icons.Default.InstallMobile,
                        uiText(R.string.update_install_now),
                        pending.versionName.ifBlank { pending.versionCode.toString() },
                        onClick = { download.install(pending.file) },
                    )
                }
                if (updateInfo == null && (pending != null || download.message.isNotBlank())) {
                    UpdateDownloadStatus(pending, null, download.message)
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
                    logo = { AboutIconBadge(Icons.AutoMirrored.Filled.MenuBook) },
                    title = uiText(R.string.label_documentation),
                    value = "docs.lyracode.app",
                    onClick = { uriHandler.openUri("https://docs.lyracode.app/") },
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
            KimiSectionLabel(context.getString(R.string.sponsor_development_title))
            KimiCardBox {
                KimiMenuRow(
                    Icons.Default.Favorite,
                    context.getString(R.string.sponsor_development_title),
                    context.getString(R.string.sponsor_development_desc),
                    onClick = onOpenSponsorDevelopment,
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
                KimiDivider()
                KimiMenuRow(
                    Icons.Default.Description,
                    context.getString(R.string.menu_licenses),
                    context.getString(R.string.menu_licenses_desc),
                    onClick = onOpenLicenses,
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
            DeveloperWallEntry(onClick = onOpenDeveloperWall)
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
    val logoAsset = if (isDark) "img/logo-white.webp" else "img/logo-black.webp"
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

