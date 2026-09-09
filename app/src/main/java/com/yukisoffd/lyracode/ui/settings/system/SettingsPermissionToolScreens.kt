package com.yukisoffd.lyracode

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.OpenableColumns
import android.webkit.WebView
import android.content.pm.PackageManager
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.system.SystemCommandExecutor
import com.yukisoffd.lyracode.termux.TermuxExecutor
import com.yukisoffd.lyracode.workspace.WorkspaceManager
import com.yukisoffd.lyracode.debian.ProotLinuxManager
import com.yukisoffd.lyracode.debian.ProotOperationPhase
import kotlinx.coroutines.launch
import java.net.URL
import rikka.shizuku.Shizuku

internal fun requestTermuxRunCommandPermission(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    if (context.checkSelfPermission(MainActivity.TERMUX_RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED) return
    (context as? Activity)?.requestPermissions(arrayOf(MainActivity.TERMUX_RUN_COMMAND_PERMISSION), 1001)
}



@Composable
internal fun SystemPermissionSettings(
    settings: AppSettings,
    executor: SystemCommandExecutor,
) {
    val scope = rememberCoroutineScope()
    var rootEnabled by remember { mutableStateOf(settings.requestRootAccess) }
    var shellEnabled by remember { mutableStateOf(settings.requestShellAccess) }
    var suCommand by remember { mutableStateOf(settings.customSuCommand) }
    var revision by remember { mutableIntStateOf(0) }
    var rootStatus by remember { mutableStateOf(uiText(R.string.status_not_detected)) }
    val shizukuRunning = remember(revision) { executor.isShizukuRunning() }
    val shellGranted = remember(revision) { executor.hasShellPermission() }
    val permissionListener = remember {
        Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) revision++
        }
    }
    val binderReceivedListener = remember {
        Shizuku.OnBinderReceivedListener { revision++ }
    }
    val binderDeadListener = remember {
        Shizuku.OnBinderDeadListener { revision++ }
    }
    DisposableEffect(Unit) {
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        onDispose {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        }
    }
    KimiCardBox {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(uiText(R.string.title_root_permission), style = MaterialTheme.typography.titleSmall)
                Text(
                    uiText(R.string.root_permission_desc),
                    color = KimiMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = rootEnabled,
                onCheckedChange = {
                    rootEnabled = it
                    settings.requestRootAccess = it
                },
            )
        }
        KimiDivider()
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(uiText(R.string.title_shell_permission), style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        shellGranted -> uiText(R.string.shell_granted)
                        shizukuRunning -> uiText(R.string.shell_shizuku_running)
                        else -> uiText(R.string.shell_need_shizuku)
                    },
                    color = if (shellGranted) MaterialTheme.colorScheme.primary else KimiMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = shellEnabled,
                onCheckedChange = { enabled ->
                    shellEnabled = enabled
                    settings.requestShellAccess = enabled
                    if (enabled && shizukuRunning && !shellGranted) {
                        Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                    }
                },
            )
        }
    }
    KimiCardBox {
        OutlinedTextField(
            value = suCommand,
            onValueChange = {
                suCommand = it
                settings.customSuCommand = it
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(uiText(R.string.label_custom_su_command)) },
            supportingText = {
                Text(uiText(R.string.su_command_hint))
            },
            singleLine = true,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    if (shizukuRunning && !shellGranted) {
                        Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                    } else {
                        revision++
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(if (shellGranted) uiText(R.string.action_shell_authorized) else uiText(R.string.action_authorize_shell))
            }
            OutlinedButton(
                onClick = {
                    rootStatus = uiText(R.string.ui_checking)
                    scope.launch {
                        val result = executor.probeRoot()
                        rootStatus = if (result.ok && result.stdout.trim().lineSequence().lastOrNull() == "0") {
                            uiText(R.string.root_available)
                        } else {
                            uiText(R.string.root_not_available, result.stderr.ifBlank { result.message }.take(120))
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(uiText(R.string.action_detect_root))
            }
        }
        Text(rootStatus, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        KimiDivider()
        SettingsExternalLinkRow(
            icon = Icons.Default.Link,
            title = "Shizuku GitHub",
            subtitle = "RikkaApps/Shizuku",
            url = "https://github.com/RikkaApps/Shizuku",
        )
    }
    Text(
        uiText(R.string.system_permissions_hint),
        color = KimiMuted,
        style = MaterialTheme.typography.bodySmall,
    )
}

private const val SHIZUKU_PERMISSION_REQUEST_CODE = 2300

@Composable
internal fun PermissionSettings(termuxExecutor: TermuxExecutor) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissions = remember(context, termuxExecutor, revision) {
        appPermissionRows(context, termuxExecutor)
    }
    KimiCardBox {
        permissions.forEachIndexed { index, row ->
            val displayStatus = if (row.title == uiText(R.string.permission_read_app_list)) {
                row.status
            } else if (row.granted) {
                uiText(R.string.permission_status_granted)
            } else {
                row.status
            }
            KimiMenuRow(row.icon, row.title, displayStatus) {
                if (row.target == "overlay") {
                    com.yukisoffd.lyracode.interaction.overlay.OverlayPermission.openSettings(context)
                } else if (row.target == "accessibility") {
                    com.yukisoffd.lyracode.interaction.ui.openAccessibilitySettings(context)
                } else if (row.title == uiText(R.string.permission_termux)) {
                    requestTermuxRunCommandPermission(context)
                    revision++
                } else if (row.title == uiText(R.string.permission_local_network)) {
                    context.findMainActivity()?.requestLocalNetworkPermission() ?: openAppSettings(context)
                } else {
                    openAppSettings(context)
                }
            }
            if (index != permissions.lastIndex) KimiDivider()
        }
    }
    Text(uiText(R.string.permission_hint), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun AgentToolSettings(settings: AppSettings, termuxExecutor: TermuxExecutor, externalRevision: Int = 0) {
    val context = LocalContext.current
    val prootRuntime = remember(context) { com.yukisoffd.lyracode.debian.ProotLinuxManager.getInstance(context) }
    val prootState by prootRuntime.state.collectAsState()
    var disabled by remember(externalRevision) { mutableStateOf(settings.disabledTools()) }
    var query by rememberSaveable { mutableStateOf("") }
    var showReachabilityPage by rememberSaveable { mutableStateOf(false) }
    val localTools = agentToolCatalog().filter {
        it.name != "proot_command" || prootState.instances.any { instance -> instance.enabled }
    }
    val mcpTools = remember(disabled, externalRevision) { settings.enabledMcpTools() }
    val sshToolsEnabled = remember(disabled, externalRevision) { settings.sshServers().any { it.enabled } }
    val termuxGranted = termuxExecutor.hasRunCommandPermission()
    fun matches(text: String): Boolean = query.isBlank() || text.contains(query.trim(), ignoreCase = true)
    val filteredLocalTools = remember(localTools, query) {
        localTools.filter { matches("${it.title}\n${it.name}\n${it.description}") }
    }
    val filteredMcpTools = remember(mcpTools, query) {
        mcpTools.filter { (server, tool) ->
            matches("MCP ${server.name} ${tool.name} ${tool.description} ${settings.mcpToolFunctionName(server, tool)}")
        }
    }
    KimiCardBox {
        Text(uiText(R.string.title_search_tools), style = MaterialTheme.typography.titleSmall)
        CapsuleTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = uiText(R.string.search_tools_placeholder),
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) },
        )
        Text(
            stringResource(
                    R.string.label_tools_match,
                    filteredLocalTools.size + filteredMcpTools.size,
                    localTools.size + mcpTools.size,
                ),
            color = KimiMuted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
    KimiCardBox {
        if (filteredLocalTools.isEmpty() && filteredMcpTools.isEmpty()) {
            Text(uiText(R.string.notice_no_matching_tools), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        }
        filteredLocalTools.forEachIndexed { index, tool ->
            val lockedByPermission = tool.name == "run_command" && !termuxGranted
            val protectedTool = tool.name == "manage_app_config"
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(tool.title, style = MaterialTheme.typography.titleSmall)
                    Text(tool.name, color = KimiMuted, style = MaterialTheme.typography.labelSmall)
                    Text(tool.description, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
                    if (lockedByPermission) {
                        Text(uiText(R.string.notice_termux_permission_required), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                    if (tool.name == "ssh_exec" && !sshToolsEnabled) {
                        Text(uiText(R.string.notice_ssh_not_configured), color = KimiMuted, style = MaterialTheme.typography.labelSmall)
                    }
                    if (protectedTool) {
                        Text(uiText(R.string.notice_protected_tool), color = KimiMuted, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Switch(
                    checked = protectedTool || (!lockedByPermission && tool.name !in disabled),
                    enabled = !lockedByPermission && !protectedTool,
                    onCheckedChange = { enabled ->
                        settings.setToolEnabled(tool.name, enabled)
                        disabled = settings.disabledTools()
                    },
                )
            }
            if (index != filteredLocalTools.lastIndex || filteredMcpTools.isNotEmpty()) KimiDivider()
        }
        filteredMcpTools.forEachIndexed { index, (server, tool) ->
            val functionName = settings.mcpToolFunctionName(server, tool)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("MCP · ${server.name} / ${tool.name}", style = MaterialTheme.typography.titleSmall)
                    Text(functionName, color = KimiMuted, style = MaterialTheme.typography.labelSmall)
                    Text(tool.description.ifBlank { uiText(R.string.label_remote_mcp_tool) }, color = KimiMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Switch(
                    checked = functionName !in disabled,
                    onCheckedChange = { enabled ->
                        settings.setToolEnabled(functionName, enabled)
                        disabled = settings.disabledTools()
                    },
                )
            }
            if (index != filteredMcpTools.lastIndex) KimiDivider()
        }
    }
}

@Composable
internal fun TermuxSettings(settings: AppSettings, termuxExecutor: TermuxExecutor, workspaceManager: WorkspaceManager) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    val permissionGranted = remember(revision) { termuxExecutor.hasRunCommandPermission() }
    KimiCardBox {
        KimiMenuRow(Icons.Default.Terminal, "Termux", if (termuxExecutor.isTermuxInstalled()) uiText(R.string.label_termux_installed) else uiText(R.string.label_termux_not_installed))
        KimiDivider()
        KimiMenuRow(Icons.Default.Extension, "Termux:API", if (termuxExecutor.isTermuxApiInstalled()) uiText(R.string.label_available) else uiText(R.string.label_termux_not_installed))
        KimiDivider()
        KimiMenuRow(Icons.Default.CheckCircle, uiText(R.string.ui_run_command_permission), if (permissionGranted) uiText(R.string.label_granted) else uiText(R.string.label_click_to_grant)) {
            requestTermuxRunCommandPermission(context)
            revision++
        }
        KimiDivider()
        KimiMenuRow(Icons.Default.Folder, uiText(R.string.menu_termux_path), workspaceManager.termuxRootPath() ?: uiText(R.string.termux_path_primary))
    }
    TermuxSetupGuide()
}

@Composable
internal fun ProotLinuxSettings() {
    val context = LocalContext.current
    val runtime = remember(context) { ProotLinuxManager.getInstance(context) }
    val state by runtime.state.collectAsState()
    val scope = rememberCoroutineScope()
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var importName by remember { mutableStateOf("") }
    var deleteId by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingImportUri = uri
            importName = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()?.substringBeforeLast('.')?.substringBeforeLast('.')?.ifBlank { null } ?: "Imported Linux"
        }
    }

    KimiCardBox {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(uiText(R.string.proot_linux_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    if (runtime.isSupported()) uiText(R.string.proot_linux_count, state.instances.size) else uiText(R.string.debian_runtime_unsupported),
                    color = KimiMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (state.phase in setOf(ProotOperationPhase.DOWNLOADING, ProotOperationPhase.IMPORTING)) {
            LinearProgressIndicator(
                progress = { state.progressPercent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                state.message.ifBlank { uiText(R.string.proot_linux_working) },
                color = KimiMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.phase == ProotOperationPhase.ERROR) {
            Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { scope.launch { runCatching { runtime.downloadDebian() } } },
                enabled = runtime.isSupported() && state.phase in setOf(ProotOperationPhase.IDLE, ProotOperationPhase.ERROR) && state.instances.none { it.id == "debian" },
                modifier = Modifier.weight(1f),
            ) { Text(uiText(R.string.debian_runtime_install_action)) }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/gzip", "application/x-gzip", "application/x-tar", "application/octet-stream")) },
                enabled = runtime.isSupported() && state.phase in setOf(ProotOperationPhase.IDLE, ProotOperationPhase.ERROR),
                modifier = Modifier.weight(1f),
            ) { Text(uiText(R.string.proot_linux_import_action)) }
        }
    }
    KimiCardBox {
        Text(uiText(R.string.proot_linux_supported_files), style = MaterialTheme.typography.titleSmall)
        Text(uiText(R.string.proot_linux_supported_files_desc), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        Text(uiText(R.string.proot_linux_iso_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        KimiDivider()
        Text(uiText(R.string.proot_linux_download_sources), style = MaterialTheme.typography.titleSmall)
        SettingsExternalLinkRow(
            icon = Icons.Default.CloudDownload,
            title = "Alpine Linux",
            subtitle = uiText(R.string.proot_linux_alpine_source_hint),
            url = "https://www.alpinelinux.org/downloads/",
        )
        KimiDivider()
        SettingsExternalLinkRow(
            icon = Icons.Default.CloudDownload,
            title = "Ubuntu Base",
            subtitle = uiText(R.string.proot_linux_ubuntu_source_hint),
            url = "https://cdimage.ubuntu.com/ubuntu-base/releases/",
        )
        KimiDivider()
        SettingsExternalLinkRow(
            icon = Icons.Default.CloudDownload,
            title = "Arch Linux ARM",
            subtitle = uiText(R.string.proot_linux_arch_source_hint),
            url = "https://archlinuxarm.org/about/downloads",
        )
        KimiDivider()
        SettingsExternalLinkRow(
            icon = Icons.Default.Info,
            title = "Termux PRoot Distro",
            subtitle = uiText(R.string.proot_linux_proot_distro_hint),
            url = "https://github.com/termux/proot-distro",
        )
    }
    if (state.instances.isNotEmpty()) {
        KimiCardBox {
            state.instances.forEachIndexed { index, linux ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(linux.name, style = MaterialTheme.typography.titleSmall)
                        Text("Linux ID: ${linux.id}", color = KimiMuted, style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(checked = linux.enabled, onCheckedChange = { runtime.setEnabled(linux.id, it) })
                    IconButton(onClick = { deleteId = linux.id }) {
                        Icon(Icons.Default.Delete, contentDescription = uiText(R.string.proot_linux_delete_action), tint = MaterialTheme.colorScheme.error)
                    }
                }
                if (index != state.instances.lastIndex) KimiDivider()
            }
        }
    }
    Text(
        uiText(R.string.proot_linux_preservation_hint),
        color = KimiMuted,
        style = MaterialTheme.typography.bodySmall,
    )

    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text(uiText(R.string.proot_linux_import_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(uiText(R.string.proot_linux_import_hint))
                    OutlinedTextField(
                        value = importName,
                        onValueChange = { importName = it },
                        label = { Text(uiText(R.string.proot_linux_name)) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                OutlinedButton(
                    onClick = {
                        pendingImportUri = null
                        scope.launch { runCatching { runtime.importRootfs(uri, importName) } }
                    },
                    enabled = importName.isNotBlank(),
                ) { Text(uiText(R.string.proot_linux_import_action)) }
            },
            dismissButton = { OutlinedButton(onClick = { pendingImportUri = null }) { Text(uiText(R.string.action_cancel)) } },
        )
    }
    deleteId?.let { id ->
        val linux = state.instances.firstOrNull { it.id == id }
        if (linux != null) {
            AlertDialog(
                onDismissRequest = { deleteId = null },
                title = { Text(uiText(R.string.proot_linux_delete_title, linux.name)) },
                text = { Text(uiText(R.string.proot_linux_delete_warning)) },
                confirmButton = {
                    OutlinedButton(
                        onClick = {
                            deleteId = null
                            scope.launch { runCatching { runtime.delete(id) } }
                        },
                    ) { Text(uiText(R.string.proot_linux_delete_action), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { OutlinedButton(onClick = { deleteId = null }) { Text(uiText(R.string.action_cancel)) } },
            )
        }
    }
}

internal data class PermissionRow(
    val icon: ImageVector,
    val title: String,
    val granted: Boolean,
    val status: String,
    val target: String? = null,
)

internal fun appPermissionRows(context: Context, termuxExecutor: TermuxExecutor): List<PermissionRow> {
    fun granted(permission: String): Boolean = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    val mediaGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        granted(Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        true
    }
    val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        granted(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        true
    }
    val locationGranted = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    return buildList {
        add(PermissionRow(Icons.Default.Layers, context.getString(R.string.device_interaction_overlay_title),
            com.yukisoffd.lyracode.interaction.overlay.OverlayPermission.isGranted(context), context.getString(R.string.permission_status_not_allowed), "overlay"))
        add(PermissionRow(Icons.Default.AccessibilityNew, context.getString(R.string.device_interaction_accessibility_title),
            com.yukisoffd.lyracode.interaction.service.AccessibilityConnection.isEnabledInSystem(context), context.getString(R.string.permission_status_not_allowed), "accessibility"))

        add(PermissionRow(Icons.Default.PhotoLibrary, uiText(R.string.permission_media), mediaGranted, uiText(R.string.permission_status_not_allowed)))
        add(PermissionRow(Icons.Default.LocationOn, uiText(R.string.permission_location), locationGranted, uiText(R.string.permission_status_not_allowed)))
        if (Android17Compatibility.requiresLocalNetworkPermission()) {
            add(
                PermissionRow(
                    Icons.Default.Wifi,
                    uiText(R.string.permission_local_network),
                    Android17Compatibility.hasLocalNetworkAccess(context),
                    uiText(R.string.label_click_to_grant),
                ),
            )
        }
        add(PermissionRow(Icons.Default.PhotoCamera, uiText(R.string.compliance_permission_camera_title), granted(Manifest.permission.CAMERA), uiText(R.string.permission_status_not_allowed)))
        add(PermissionRow(Icons.Default.Notifications, uiText(R.string.compliance_permission_notifications_title), notificationGranted, uiText(R.string.permission_status_not_allowed)))
        add(PermissionRow(Icons.Default.Apps, uiText(R.string.permission_read_app_list), true, uiText(R.string.permission_status_declared)))
        add(PermissionRow(Icons.Default.Terminal, uiText(R.string.permission_termux), termuxExecutor.hasRunCommandPermission(), uiText(R.string.label_click_to_grant)))
    }
}

private tailrec fun Context.findMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.findMainActivity()
    else -> null
}

internal fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

internal data class AgentToolInfo(
    val name: String,
    val title: String,
    val description: String,
)

internal fun agentToolCatalog(): List<AgentToolInfo> = listOf(
    AgentToolInfo("ask_user", uiText(R.string.ui_ask_the_user), uiText(R.string.ui_pause_and_ask_the_user_when_a_complex_task)),
    AgentToolInfo("list_directory", uiText(R.string.tool_list_directory), uiText(R.string.tool_list_directory_desc)),
    AgentToolInfo("read_file", uiText(R.string.tool_read_file), uiText(R.string.tool_read_file_desc)),
    AgentToolInfo("read_file_lines", uiText(R.string.ui_read_file_lines), uiText(R.string.ui_read_a_selected_line_range_from_a_large_file)),
    AgentToolInfo("write_file", uiText(R.string.tool_write_file), uiText(R.string.ui_create_or_fully_overwrite_text_files_in_the_workspace)),
    AgentToolInfo("edit_file", uiText(R.string.ui_precise_file_edit), uiText(R.string.ui_modify_a_workspace_text_file_by_unique_source_text)),
    AgentToolInfo("append_file", uiText(R.string.tool_append_file), uiText(R.string.tool_append_file_desc)),
    AgentToolInfo("create_folder", uiText(R.string.tool_create_folder), uiText(R.string.tool_create_folder_desc)),
    AgentToolInfo("delete_file_or_folder", uiText(R.string.tool_delete_file), uiText(R.string.tool_delete_file_desc)),
    AgentToolInfo("rename_move", uiText(R.string.tool_rename_move), uiText(R.string.tool_rename_move_desc)),
    AgentToolInfo("global_list_directory", uiText(R.string.tool_global_list_directory), uiText(R.string.tool_global_list_directory_desc)),
    AgentToolInfo("global_read_file", uiText(R.string.tool_global_read_file), uiText(R.string.tool_global_read_file_desc)),
    AgentToolInfo("global_read_file_lines", uiText(R.string.ui_global_read_file_lines), uiText(R.string.ui_read_a_selected_line_range_from_a_large_shared)),
    AgentToolInfo("global_write_file", uiText(R.string.tool_global_write_file), uiText(R.string.ui_create_or_fully_overwrite_a_shared_storage_file_outside)),
    AgentToolInfo("global_edit_file", uiText(R.string.ui_global_precise_file_edit), uiText(R.string.ui_modify_a_shared_storage_file_by_unique_source_text)),
    AgentToolInfo("global_append_file", uiText(R.string.tool_global_append_file), uiText(R.string.tool_global_append_file_desc)),
    AgentToolInfo("global_create_folder", uiText(R.string.tool_global_create_folder), uiText(R.string.tool_global_create_folder_desc)),
    AgentToolInfo("global_delete_file_or_folder", uiText(R.string.tool_global_delete), uiText(R.string.tool_global_delete_desc)),
    AgentToolInfo("global_rename_move", uiText(R.string.tool_global_rename), uiText(R.string.tool_global_rename_desc)),
    AgentToolInfo("download_file", uiText(R.string.tool_download_file), uiText(R.string.tool_download_file_desc)),
    AgentToolInfo("manage_scheduled_tasks", uiText(R.string.tool_manage_tasks), uiText(R.string.tool_manage_tasks_desc)),
    AgentToolInfo("get_mini_server_status", uiText(R.string.tool_mini_server_status), uiText(R.string.tool_mini_server_status_desc)),
    AgentToolInfo("read_mini_server_logs", uiText(R.string.tool_mini_server_logs), uiText(R.string.tool_mini_server_logs_desc)),
    AgentToolInfo("manage_mini_server", uiText(R.string.tool_manage_mini_server), uiText(R.string.tool_manage_mini_server_desc)),
    AgentToolInfo("search_conversation_history", uiText(R.string.tool_search_conversation), uiText(R.string.tool_search_conversation_desc)),
    AgentToolInfo("read_conversation_history", uiText(R.string.tool_read_conversation), uiText(R.string.tool_read_conversation_desc)),
    AgentToolInfo("read_memories", uiText(R.string.ui_read_memories), uiText(R.string.ui_read_cross_chat_memories_and_their_ids_for_review)),
    AgentToolInfo("save_memory", uiText(R.string.ui_save_memory), uiText(R.string.ui_save_lasting_user_preferences_work_styles_or_communication_habits)),
    AgentToolInfo("update_memory", uiText(R.string.ui_update_memory), uiText(R.string.ui_edit_correct_or_disable_an_existing_personalized_memory)),
    AgentToolInfo("delete_memory", uiText(R.string.ui_delete_memory), uiText(R.string.ui_delete_a_memory_that_no_longer_applies_or_that)),
    AgentToolInfo("search_files", uiText(R.string.tool_search_files), uiText(R.string.tool_search_files_desc)),
    AgentToolInfo("global_search_files", uiText(R.string.tool_global_search_files), uiText(R.string.tool_global_search_files_desc)),
    AgentToolInfo("get_file_info", uiText(R.string.tool_get_file_info), uiText(R.string.tool_get_file_info_desc)),
    AgentToolInfo("list_skill_files", uiText(R.string.tool_list_skill_files), uiText(R.string.tool_list_skill_files_desc)),
    AgentToolInfo("read_skill_file", uiText(R.string.tool_read_skill_file), uiText(R.string.tool_read_skill_file_desc)),
    AgentToolInfo("run_command", uiText(R.string.tool_run_command), uiText(R.string.tool_run_command_desc)),
    AgentToolInfo("web_search", uiText(R.string.tool_web_search), uiText(R.string.tool_web_search_desc)),
    AgentToolInfo("read_web_page", uiText(R.string.tool_read_web_page), uiText(R.string.tool_read_web_page_desc)),
    AgentToolInfo("mark_web_sources", uiText(R.string.tool_mark_web_sources), uiText(R.string.tool_mark_web_sources_desc)),
    AgentToolInfo("manage_app_config", uiText(R.string.tool_manage_app_config), uiText(R.string.ui_add_edit_enable_disable_or_delete_mcp_ssh_email)),
    AgentToolInfo("get_current_time", uiText(R.string.tool_get_time), uiText(R.string.tool_get_time_desc)),
    AgentToolInfo("get_current_location", uiText(R.string.tool_get_location), uiText(R.string.tool_get_location_desc)),
    AgentToolInfo("get_device_hardware_info", uiText(R.string.tool_get_hardware), uiText(R.string.tool_get_hardware_desc)),
    AgentToolInfo("list_installed_apps", uiText(R.string.tool_list_apps), uiText(R.string.tool_list_apps_desc)),
    AgentToolInfo("execute_shell_command", uiText(R.string.tool_shell_command), uiText(R.string.tool_shell_command_desc)),
    AgentToolInfo("execute_root_command", uiText(R.string.tool_root_command), uiText(R.string.tool_root_command_desc)),
    AgentToolInfo("list_ssh_servers", uiText(R.string.tool_list_ssh), uiText(R.string.tool_list_ssh_desc)),
    AgentToolInfo("ssh_exec", uiText(R.string.tool_ssh_exec), uiText(R.string.tool_ssh_exec_desc)),
    AgentToolInfo("list_email_accounts", uiText(R.string.ui_list_email_accounts), uiText(R.string.ui_view_configured_and_enabled_imap_smtp_email_accounts_without)),
    AgentToolInfo("list_email_folders", uiText(R.string.ui_list_email_folders), uiText(R.string.ui_list_imap_folders_and_identify_the_drafts_folder_without)),
    AgentToolInfo("list_emails", uiText(R.string.ui_list_emails), uiText(R.string.ui_list_email_metadata_and_unread_read_answered_and_draft)),
    AgentToolInfo("read_email", uiText(R.string.ui_read_email_body), uiText(R.string.ui_read_a_bounded_email_body_without_changing_its_state)),
    AgentToolInfo("set_email_flags", uiText(R.string.ui_change_email_status), uiText(R.string.ui_change_an_email_s_read_unread_or_flagged_state)),
    AgentToolInfo("download_email_attachment", uiText(R.string.ui_quarantine_email_attachment), uiText(R.string.ui_download_an_attachment_to_temporary_quarantine_without_exposing_it)),
    AgentToolInfo("record_email_attachment_scan", uiText(R.string.ui_record_attachment_scan_result), uiText(R.string.ui_record_the_attachment_scan_result_explicitly_provided_by_the)),
    AgentToolInfo("save_email_draft", uiText(R.string.ui_save_email_draft), uiText(R.string.ui_build_a_txt_html_email_and_append_it_to)),
    AgentToolInfo("send_email", uiText(R.string.ui_send_email), uiText(R.string.ui_send_or_reply_in_thread_through_smtp_every_send)),
    AgentToolInfo("list_webdav_servers", uiText(R.string.tool_list_webdav), uiText(R.string.tool_list_webdav_desc)),
    AgentToolInfo("webdav_list", uiText(R.string.tool_webdav_list), uiText(R.string.tool_webdav_list_desc)),
    AgentToolInfo("webdav_search", uiText(R.string.tool_webdav_search), uiText(R.string.tool_webdav_search_desc)),
    AgentToolInfo("webdav_download_to_workspace", uiText(R.string.tool_webdav_download), uiText(R.string.tool_webdav_download_desc)),
    AgentToolInfo("webdav_upload_from_workspace", uiText(R.string.tool_webdav_upload), uiText(R.string.tool_webdav_upload_desc)),
    AgentToolInfo("list_file_transfer_servers", uiText(R.string.tool_list_file_transfer), uiText(R.string.tool_list_file_transfer_desc)),
    AgentToolInfo("file_transfer_list", uiText(R.string.tool_file_transfer_list), uiText(R.string.tool_file_transfer_list_desc)),
    AgentToolInfo("file_transfer_search", uiText(R.string.tool_file_transfer_search), uiText(R.string.tool_file_transfer_search_desc)),
    AgentToolInfo("file_transfer_download_to_workspace", uiText(R.string.tool_file_transfer_download), uiText(R.string.tool_file_transfer_download_desc)),
    AgentToolInfo("file_transfer_upload_from_workspace", uiText(R.string.tool_file_transfer_upload), uiText(R.string.tool_file_transfer_upload_desc)),
    AgentToolInfo("export_backup", uiText(R.string.tool_export_backup), uiText(R.string.tool_export_backup_desc)),
    AgentToolInfo("import_backup", uiText(R.string.title_import_backup), uiText(R.string.tool_import_backup_desc)),
    AgentToolInfo("set_todo_list", uiText(R.string.tool_set_todo), uiText(R.string.tool_set_todo_desc)),
    AgentToolInfo("update_todo_item", uiText(R.string.tool_update_todo), uiText(R.string.tool_update_todo_desc)),
) + prootAgentToolCatalog()

@Composable
internal fun TermuxSetupGuide() {
    val clipboard = LocalClipboardManager.current
    val setupCommand = remember {
        "mkdir -p ~/.termux && (grep -qxF 'allow-external-apps=true' ~/.termux/termux.properties || echo 'allow-external-apps=true' >> ~/.termux/termux.properties) && termux-reload-settings"
    }
    val testCommand = remember { "python --version && pwd" }
    KimiCardBox {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(uiText(R.string.title_termux_config), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(
                uiText(R.string.termux_config_desc),
                color = KimiMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            TermuxGuideStep("1", uiText(R.string.termux_step1))
            SettingsExternalLinkRow(
                icon = Icons.Default.Terminal,
                title = "Termux GitHub",
                subtitle = "termux/termux-app",
                url = "https://github.com/termux/termux-app",
            )
            TermuxGuideStep("2", uiText(R.string.termux_step2))
            CommandCopyCard(
                command = setupCommand,
                buttonText = uiText(R.string.action_copy_config_command),
                onCopy = { clipboard.setText(AnnotatedString(setupCommand)) },
            )
            TermuxGuideStep("3", uiText(R.string.termux_step3))
            TermuxGuideStep("4", uiText(R.string.termux_step4))
            TermuxGuideStep("5", uiText(R.string.termux_step5))
            CommandCopyCard(
                command = testCommand,
                buttonText = uiText(R.string.action_copy_test_command),
                onCopy = { clipboard.setText(AnnotatedString(testCommand)) },
            )
        }
    }
}

@Composable
internal fun SettingsExternalLinkRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    url: String,
) {
    val uriHandler = LocalUriHandler.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { uriHandler.openUri(url) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = KimiMuted)
    }
}

@Composable
internal fun TermuxGuideStep(index: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(index, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        }
        Text(text, modifier = Modifier.weight(1f), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun CommandCopyCard(command: String, buttonText: String, onCopy: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SelectionContainer {
            Text(
                command,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
        OutlinedButton(onClick = onCopy, shape = KimiPillShape) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(buttonText)
        }
    }
}

