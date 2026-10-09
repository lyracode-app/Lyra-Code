package com.yukisoffd.lyracode

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.LocalMcpServerConfig
import com.yukisoffd.lyracode.data.McpServerConfig
import com.yukisoffd.lyracode.data.McpToolDefinition
import com.yukisoffd.lyracode.mcp.LocalMcpServerManager
import com.yukisoffd.lyracode.mcp.McpClientManager
import com.yukisoffd.lyracode.mcp.McpJsonConfig
import com.yukisoffd.lyracode.mcp.McpProtocol
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject



@Composable
internal fun McpSettings(
    settings: AppSettings,
    mcpClientManager: McpClientManager,
    externalRevision: Int = 0,
    onOpenTools: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    val servers = remember(revision, externalRevision) { settings.mcpServers() }
    var editing by remember { mutableStateOf<McpServerConfig?>(null) }
    var deleteTarget by remember { mutableStateOf<McpServerConfig?>(null) }
    var status by remember { mutableStateOf("") }

    editing?.let { server ->
        McpServerDialog(
            initial = server,
            onDismiss = { editing = null },
            onSave = {
                settings.upsertMcpServer(it)
                mcpClientManager.invalidate(it.id)
                editing = null
                status = uiText(R.string.notice_mcp_saved)
                revision++
            },
        )
    }
    deleteTarget?.let { server ->
        ConfirmDeleteDialog(
            title = uiText(R.string.title_delete_mcp),
            message = uiText(R.string.confirm_delete_mcp),
            targetName = server.name.ifBlank { server.url },
            onDismiss = { deleteTarget = null },
            onConfirm = {
                settings.deleteMcpServer(server.id)
                mcpClientManager.invalidate(server.id)
                status = uiText(R.string.notice_deleted_service, server.name)
                revision++
            },
        )
    }

    KimiCardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(uiText(R.string.detail_mcp), style = MaterialTheme.typography.titleMedium)
                Text(uiText(R.string.mcp_desc), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = {
                editing = defaultMcpServer()
            }, shape = KimiPillShape) { Text(uiText(R.string.action_add)) }
        }
        if (status.isNotBlank()) {
            Text(status, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (servers.isEmpty()) {
        KimiCardBox {
            Text(uiText(R.string.notice_no_mcp), style = MaterialTheme.typography.titleSmall)
            Text(uiText(R.string.mcp_empty_hint), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
    servers.forEach { server ->
        KimiCardBox {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(server.name, style = MaterialTheme.typography.titleMedium)
                    Text(server.url, color = KimiMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    Text(context.getString(R.string.label_mcp_tools_count, transportLabel(server.transport), server.timeoutSeconds, server.tools.size), color = KimiMuted, style = MaterialTheme.typography.labelMedium)
                    if (server.url.startsWith("http://", ignoreCase = true)) {
                        Text(uiText(R.string.notice_http_mcp_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Switch(
                    checked = server.enabled,
                    onCheckedChange = {
                        settings.setMcpServerEnabled(server.id, it)
                        if (!it) mcpClientManager.invalidate(server.id)
                        revision++
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        status = uiText(R.string.server_testing_name, server.name)
                        scope.launch {
                            mcpClientManager.testAndRefreshTools(server).fold(
                                onSuccess = {
                                    status = context.getString(R.string.mcp_connected, server.name, it.size)
                                    revision++
                                },
                                onFailure = { status = uiText(R.string.mcp_connection_failed_detail, it.message) },
                            )
                        }
                    },
                    shape = KimiPillShape,
                ) { Text(uiText(R.string.action_test_and_fetch)) }
                IconButton(onClick = { editing = server }) {
                    Icon(Icons.Default.Edit, contentDescription = uiText(R.string.cd_edit_mcp))
                }
                IconButton(onClick = { deleteTarget = server }) {
                    Icon(Icons.Default.Delete, contentDescription = uiText(R.string.cd_delete_mcp))
                }
            }
            if (server.tools.isNotEmpty()) {
                KimiDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { onOpenTools(server.id) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Default.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(context.getString(R.string.label_fetched_tools, server.tools.size), style = MaterialTheme.typography.titleSmall)
                        Text(
                            uiText(R.string.mcp_tools_open_hint),
                            color = KimiMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = KimiMuted,
                    )
                }
            }
        }
    }
}

@Composable
internal fun LocalMcpServerSettings(
    settings: AppSettings,
    localMcpServerManager: LocalMcpServerManager,
    externalRevision: Int = 0,
) {
    var revision by remember { mutableIntStateOf(0) }
    val localConfig = remember(revision, externalRevision) { settings.localMcpServerConfig() }
    val localStatus = remember(revision, externalRevision) { localMcpServerManager.status() }
    var editing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    var exportProtocolVersion by rememberSaveable { mutableStateOf(McpProtocol.LEGACY) }

    LaunchedEffect(Unit) {
        localMcpServerManager.syncWithSettings()
        revision++
    }
    val externalConnectionJson = remember(localConfig, localStatus.url, localStatus.lanUrls, exportProtocolVersion) {
        buildLocalMcpExternalConnectionJson(localConfig, localStatus.url, localStatus.lanUrls, exportProtocolVersion)
    }

    if (editing) {
        LocalMcpServerDialog(
            initial = localConfig,
            onDismiss = { editing = false },
            onSave = { config ->
                settings.saveLocalMcpServerConfig(config)
                if (config.enabled) {
                    localMcpServerManager.start(config)
                } else {
                    localMcpServerManager.stop()
                }
                editing = false
                status = uiText(R.string.notice_local_mcp_saved)
                revision++
            },
        )
    }

    KimiCardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Hub, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(uiText(R.string.title_local_mcp_as_server), style = MaterialTheme.typography.titleMedium)
                Text(
                    uiText(R.string.local_mcp_desc),
                    color = KimiMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = localConfig.enabled && localStatus.running,
                onCheckedChange = { enabled ->
                    val updated = localConfig.copy(enabled = enabled)
                    if (enabled) {
                        localMcpServerManager.start(updated)
                    } else {
                        settings.saveLocalMcpServerConfig(updated)
                        localMcpServerManager.stop()
                    }
                    revision++
                },
            )
        }
        KimiDivider()
        Text(
            stringResource(
                    R.string.label_server_status,
                    if (localStatus.running) uiText(R.string.label_server_running) else uiText(R.string.notice_server_stopped),
                    localStatus.message,
                ),
            color = KimiMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.label_local_address_info, localStatus.url),
            color = KimiMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        if (localStatus.lanUrls.isNotEmpty()) {
            Text(
                uiText(R.string.ui_lan_address) + localStatus.lanUrls.joinToString("  "),
                color = KimiMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            if (localConfig.authKey.isBlank()) uiText(R.string.local_mcp_auth_not_set) else uiText(R.string.local_mcp_auth_enabled),
            color = if (localConfig.authKey.isBlank()) MaterialTheme.colorScheme.error else KimiMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        if (status.isNotBlank()) {
            Text(status, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { editing = true }, shape = KimiPillShape) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(uiText(R.string.action_configure))
            }
            OutlinedButton(
                onClick = {
                    if (localStatus.running) {
                        localMcpServerManager.stop()
                    }
                    localMcpServerManager.start(localConfig.copy(enabled = true))
                    status = uiText(R.string.notice_local_mcp_restarted)
                    revision++
                },
                shape = KimiPillShape,
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(uiText(R.string.action_restart))
            }
        }
    }

    KimiCardBox {
        Text(uiText(R.string.title_external_usage), style = MaterialTheme.typography.titleMedium)
        Text(
            uiText(R.string.external_usage_desc),
            color = KimiMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(uiText(R.string.ui_external_connection_raw_json), style = MaterialTheme.typography.titleSmall)
        Text(uiText(R.string.mcp_local_protocol_hint), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MaterialChoiceButton(McpProtocol.LEGACY, exportProtocolVersion == McpProtocol.LEGACY) { exportProtocolVersion = McpProtocol.LEGACY }
            MaterialChoiceButton(uiText(R.string.mcp_protocol_stateless), exportProtocolVersion == McpProtocol.MODERN) { exportProtocolVersion = McpProtocol.MODERN }
        }
        CommandCopyCard(
            command = externalConnectionJson,
            buttonText = uiText(R.string.ui_copy_external_connection_json),
            onCopy = { clipboard.setText(AnnotatedString(externalConnectionJson)) },
        )
        Text(
            uiText(R.string.ui_the_copied_config_only_includes_mcp_protocol_version_and),
            color = KimiMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

internal fun buildLocalMcpExternalConnectionJson(
    config: LocalMcpServerConfig,
    url: String,
    lanUrls: List<String>,
    protocolVersion: String = McpProtocol.LEGACY,
): String {
    val key = config.authKey.trim()
    val headers = JSONObject()
        .put("Mcp-Protocol-Version", protocolVersion)
    if (key.isNotBlank()) {
        headers.put("Authorization", if (key.startsWith("Bearer ", ignoreCase = true)) key else "Bearer $key")
    }
    val server = JSONObject()
        .put("type", "streamableHttp")
        .put("transport", "streamable_http")
        .put("name", "Lyra Code")
        .put("url", url)
        .put("baseUrl", url)
        .put("headers", headers)
    val root = JSONObject()
        .put("protocolVersion", protocolVersion)
        .put("mcpServers", JSONObject().put("lyra_code", server))
        .put(
            "direct",
            JSONObject()
                .put("method", "POST")
                .put("url", url)
                .put("headers", headers),
        )
    if (lanUrls.isNotEmpty()) root.put("alternativeUrls", JSONArray(lanUrls))
    return root.toString(2)
}

@Composable
internal fun McpToolsPage(settings: AppSettings, serverId: String, externalRevision: Int, onOpenTool: (String) -> Unit) {
    val server = remember(serverId, externalRevision) { settings.mcpServers().firstOrNull { it.id == serverId } }
    if (server == null || server.tools.isEmpty()) {
        KimiCardBox { Text(uiText(R.string.mcp_tools_unavailable)) }
        return
    }
    KimiCardBox {
        Text(server.name, style = MaterialTheme.typography.titleMedium)
        Text(uiText(R.string.mcp_tools_open_hint), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
    }
    server.tools.forEach { tool ->
        KimiCardBox {
            Row(Modifier.fillMaxWidth().clickable { onOpenTool(tool.name) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tool.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Icon(Icons.Default.ChevronRight, contentDescription = uiText(R.string.mcp_tool_details), tint = KimiMuted)
            }
        }
    }
}

@Composable
internal fun McpToolDetailPage(settings: AppSettings, serverId: String, toolName: String, externalRevision: Int) {
    val tool = remember(serverId, toolName, externalRevision) {
        settings.mcpServers().firstOrNull { it.id == serverId }?.tools?.firstOrNull { it.name == toolName }
    }
    if (tool == null) {
        KimiCardBox { Text(uiText(R.string.mcp_tools_unavailable)) }
        return
    }
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            KimiCardBox {
                Text(tool.name, style = MaterialTheme.typography.titleMedium)
                Text(tool.description.ifBlank { uiText(R.string.label_no_description) }, style = MaterialTheme.typography.bodyMedium)
            }
            KimiCardBox {
                Text(uiText(R.string.mcp_tool_parameters), style = MaterialTheme.typography.titleMedium)
                Text(uiText(R.string.mcp_tool_parameters_hint), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
                Text(
                    runCatching { JSONObject(tool.inputSchema).toString(2) }.getOrDefault(tool.inputSchema),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}
@Composable
internal fun LocalMcpServerDialog(
    initial: LocalMcpServerConfig,
    onDismiss: () -> Unit,
    onSave: (LocalMcpServerConfig) -> Unit,
) {
    var host by rememberSaveable { mutableStateOf(initial.host.ifBlank { AppSettings.DEFAULT_LOCAL_MCP_SERVER_HOST }) }
    var port by rememberSaveable { mutableStateOf(initial.port.toString()) }
    var authKey by rememberSaveable { mutableStateOf(initial.authKey) }
    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(uiText(R.string.ui_local_mcp_server)) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    uiText(R.string.local_mcp_dialog_hint),
                    color = KimiMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(uiText(R.string.label_listen_host)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(uiText(R.string.label_port)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = authKey,
                    onValueChange = { authKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(uiText(R.string.label_auth_key_optional)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                if (authKey.isBlank()) {
                    Text(uiText(R.string.notice_auth_key_risk), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(uiText(R.string.label_save_and_enable), modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        LocalMcpServerConfig(
                            host = host.trim().ifBlank { AppSettings.DEFAULT_LOCAL_MCP_SERVER_HOST },
                            port = port.toIntOrNull()?.coerceIn(1, 65535) ?: AppSettings.DEFAULT_LOCAL_MCP_SERVER_PORT,
                            authKey = authKey.trim(),
                            enabled = enabled,
                        ),
                    )
                },
            ) { Text(uiText(R.string.file_editor_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiText(R.string.action_cancel)) } },
    )
}

@Composable
internal fun McpServerDialog(
    initial: McpServerConfig,
    onDismiss: () -> Unit,
    onSave: (McpServerConfig) -> Unit,
) {
    var rawJson by rememberSaveable(initial.id) { mutableStateOf(runCatching { McpJsonConfig.draft(initial) }.getOrDefault(initial.rawJson)) }
    val parsed = remember(rawJson) { runCatching { McpJsonConfig(rawJson).also { it.url; it.headers; it.transport; it.protocolVersion } }.getOrNull() }
    var timeout by rememberSaveable(initial.id) { mutableStateOf(initial.timeoutSeconds.toString()) }
    var enabled by rememberSaveable(initial.id) { mutableStateOf(initial.enabled) }
    var editingJson by rememberSaveable(initial.id) { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    fun patch(field: String, value: String) {
        runCatching { McpJsonConfig(rawJson).patch(field, value) }.fold(
            onSuccess = { rawJson = it; error = "" }, onFailure = { error = it.message.orEmpty() },
        )
    }
    if (editingJson) {
        McpRawJsonEditor(rawJson, onDismiss = { editingJson = false }, onApply = { rawJson = it; error = ""; editingJson = false })
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(uiText(R.string.detail_mcp)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(parsed?.name.orEmpty(), { patch("name", it) }, Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_webdav_service_name)) }, singleLine = true)
                OutlinedTextField(runCatching { parsed?.url.orEmpty() }.getOrDefault(""), { patch("url", it) }, Modifier.fillMaxWidth(), label = { Text("URL") }, singleLine = true)
                OutlinedTextField(parsed?.authKey.orEmpty(), { patch("authKey", it) }, Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_auth_key_optional)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                Text(uiText(R.string.mcp_auth_verbatim_hint), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MaterialChoiceButton("Streamable HTTP", parsed?.transport == AppSettings.MCP_TRANSPORT_STREAMABLE_HTTP) { patch("transport", AppSettings.MCP_TRANSPORT_STREAMABLE_HTTP) }
                    MaterialChoiceButton("SSE", parsed?.transport == AppSettings.MCP_TRANSPORT_SSE) { patch("transport", AppSettings.MCP_TRANSPORT_SSE) }
                }
                Text(uiText(R.string.mcp_protocol_version), style = MaterialTheme.typography.titleSmall)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val version = runCatching { parsed?.protocolVersion.orEmpty() }.getOrDefault("")
                    MaterialChoiceButton(uiText(R.string.mcp_protocol_auto), version.isBlank()) { patch("protocolVersion", "") }
                    MaterialChoiceButton(McpProtocol.LEGACY, version == McpProtocol.LEGACY) { patch("protocolVersion", McpProtocol.LEGACY) }
                    MaterialChoiceButton(uiText(R.string.mcp_protocol_stateless), version == McpProtocol.MODERN) { patch("protocolVersion", McpProtocol.MODERN) }
                }
                OutlinedTextField(timeout, { timeout = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_timeout_seconds)) }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(uiText(R.string.label_enabled), Modifier.weight(1f))
                    Switch(enabled, { enabled = it })
                }
                OutlinedButton(onClick = { editingJson = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Code, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(uiText(R.string.mcp_edit_raw_json))
                }
                Text(rawJson, maxLines = 5, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                runCatching {
                    val json = McpJsonConfig(rawJson)
                    json.validateEndpoint()
                    json.project(initial).copy(rawJson = rawJson, enabled = enabled, timeoutSeconds = timeout.toIntOrNull()?.coerceIn(5, 300) ?: 30,
                        tools = if (rawJson == initial.rawJson) initial.tools else emptyList())
                }.fold(onSuccess = onSave, onFailure = { error = it.message.orEmpty() })
            }) { Text(uiText(R.string.file_editor_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiText(R.string.action_cancel)) } },
    )
}

@Composable
internal fun McpRawJsonEditor(initialJson: String, onDismiss: () -> Unit, onApply: (String) -> Unit) {
    var json by rememberSaveable { mutableStateOf(initialJson) }
    var error by remember { mutableStateOf("") }
    // Compose 1.7's non-default dialog measures against screenHeight even when the IME shrinks
    // its window. Keep platform measurement and explicitly make this editor window full screen.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val window = (view.parent as DialogWindowProvider).window
        DisposableEffect(window) {
            val previousMode = window.attributes.softInputMode
            window.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT)
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            onDispose { window.setSoftInputMode(previousMode) }
        }
        // Native visible-frame/compat insets also cover keyboards that Compose's dialog insets miss.
        val keyboardOffset = with(LocalDensity.current) { rememberKeyboardAvoidanceOffsetPx().toDp() }
        Scaffold(
            Modifier.fillMaxSize().systemBarsPadding().padding(bottom = keyboardOffset),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, contentDescription = uiText(R.string.cd_back)) }
                    Text(uiText(R.string.label_raw_json), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = {
                        runCatching { McpJsonConfig(json).validateEndpoint() }.fold(
                            onSuccess = { onApply(json) }, onFailure = { error = it.message.orEmpty() },
                        )
                    }) { Text(uiText(R.string.file_editor_save)) }
                }
                Text(uiText(R.string.mcp_raw_json_hint), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = json, onValueChange = { json = it; error = "" },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    label = { Text(uiText(R.string.label_raw_json)) },
                    isError = error.isNotBlank(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                )
            }
        }
    }
}

internal fun defaultMcpServer(): McpServerConfig = McpServerConfig(
    id = AppSettings.newId(), name = "MCP Server", url = "", authKey = "",
    transport = AppSettings.MCP_TRANSPORT_STREAMABLE_HTTP, timeoutSeconds = 30,
    enabled = true, rawJson = "{}", tools = emptyList(),
)
internal fun transportLabel(transport: String): String = when (transport) {
    AppSettings.MCP_TRANSPORT_SSE -> "SSE"
    else -> "Streamable HTTP"
}

