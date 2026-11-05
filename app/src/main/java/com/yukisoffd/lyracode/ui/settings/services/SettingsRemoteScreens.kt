package com.yukisoffd.lyracode

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.BackupManager
import com.yukisoffd.lyracode.data.BackupOptions
import com.yukisoffd.lyracode.data.SshServerConfig
import com.yukisoffd.lyracode.data.FileTransferServerConfig
import com.yukisoffd.lyracode.data.WebDavServerConfig
import com.yukisoffd.lyracode.filetransfer.FileTransferClient
import com.yukisoffd.lyracode.ssh.SshExecutor
import com.yukisoffd.lyracode.webdav.TransferProgress
import com.yukisoffd.lyracode.webdav.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.Locale
import kotlin.math.max



@Composable
internal fun WebDavSettings(settings: AppSettings, webDavClient: WebDavClient, externalRevision: Int = 0) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    val servers = remember(revision, externalRevision) { settings.webDavServers() }
    var editing by remember { mutableStateOf<WebDavServerConfig?>(null) }
    var deleteTarget by remember { mutableStateOf<WebDavServerConfig?>(null) }
    var status by remember { mutableStateOf("") }

    fun saveServers(updated: List<WebDavServerConfig>) {
        settings.saveWebDavServers(updated)
        revision++
    }

    editing?.let { server ->
        WebDavServerDialog(
            initial = server,
            onDismiss = { editing = null },
            onSave = { saved ->
                val updated = servers.toMutableList()
                val index = updated.indexOfFirst { it.id == saved.id }
                if (index >= 0) updated[index] = saved else updated += saved
                saveServers(updated)
                editing = null
                status = uiText(R.string.notice_webdav_saved)
            },
        )
    }
    deleteTarget?.let { server ->
        ConfirmDeleteDialog(
            title = uiText(R.string.title_delete_webdav),
            message = uiText(R.string.confirm_delete_webdav),
            targetName = server.name.ifBlank { server.url },
            onDismiss = { deleteTarget = null },
            onConfirm = {
                saveServers(servers.filterNot { it.id == server.id })
                status = uiText(R.string.notice_deleted_service, server.name)
            },
        )
    }

    KimiCardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("WebDAV", style = MaterialTheme.typography.titleMedium)
                Text(uiText(R.string.webdav_desc), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { editing = defaultWebDavServer() }, shape = KimiPillShape) { Text(uiText(R.string.action_add)) }
        }
        if (status.isNotBlank()) Text(status, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
    }

    if (servers.isEmpty()) {
        KimiCardBox {
            Text(uiText(R.string.notice_no_webdav), style = MaterialTheme.typography.titleSmall)
            Text(uiText(R.string.webdav_empty_hint), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        }
    }

    servers.forEach { server ->
        KimiCardBox {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(server.name, style = MaterialTheme.typography.titleMedium)
                    Text(if (server.hideAddressInDrawer) uiText(R.string.label_address_hidden) else server.url, color = KimiMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    Text(server.username.ifBlank { uiText(R.string.label_anonymous) } + " · " + server.initialPath.ifBlank { "/" }, color = KimiMuted, style = MaterialTheme.typography.labelMedium)
                    if (server.url.startsWith("http://", ignoreCase = true)) {
                        Text(uiText(R.string.notice_http_warning_webdav), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Switch(
                    checked = server.enabled,
                    onCheckedChange = { enabled ->
                        saveServers(servers.map { if (it.id == server.id) it.copy(enabled = enabled) else it })
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        status = uiText(R.string.server_testing_name, server.name)
                        scope.launch {
                            status = withContext(Dispatchers.IO) {
                                webDavClient.test(server).fold(
                                    onSuccess = { uiText(R.string.webdav_test_success_count, it.size) },
                                    onFailure = { uiText(R.string.webdav_test_failed, it.message) },
                                )
                            }
                        }
                    },
                    shape = KimiPillShape,
                ) { Text(uiText(R.string.action_test_connection)) }
                IconButton(onClick = { editing = server }) {
                    Icon(Icons.Default.Edit, contentDescription = uiText(R.string.cd_edit_webdav))
                }
                IconButton(onClick = { deleteTarget = server }) {
                    Icon(Icons.Default.Delete, contentDescription = uiText(R.string.cd_delete_webdav))
                }
            }
        }
    }
}

@Composable
internal fun WebDavServerDialog(
    initial: WebDavServerConfig,
    onDismiss: () -> Unit,
    onSave: (WebDavServerConfig) -> Unit,
) {
    var name by rememberSaveable(initial.id) { mutableStateOf(initial.name) }
    var url by rememberSaveable(initial.id) { mutableStateOf(initial.url) }
    var username by rememberSaveable(initial.id) { mutableStateOf(initial.username) }
    var password by rememberSaveable(initial.id) { mutableStateOf(initial.password) }
    var userAgent by rememberSaveable(initial.id) { mutableStateOf(initial.userAgent) }
    var initialPath by rememberSaveable(initial.id) { mutableStateOf(initial.initialPath) }
    var note by rememberSaveable(initial.id) { mutableStateOf(initial.note) }
    var trustAll by rememberSaveable(initial.id) { mutableStateOf(initial.trustAllCertificates) }
    var multiThread by rememberSaveable(initial.id) { mutableStateOf(initial.multiThread) }
    var hideAddress by rememberSaveable(initial.id) { mutableStateOf(initial.hideAddressInDrawer) }
    var enabled by rememberSaveable(initial.id) { mutableStateOf(initial.enabled) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("WebDAV") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_webdav_service_name)) }, singleLine = true)
                OutlinedTextField(value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(), label = { Text("URL") }, singleLine = true)
                if (url.startsWith("http://", ignoreCase = true)) {
                    Text(uiText(R.string.notice_http_insecure), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(value = username, onValueChange = { username = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_username_optional)) }, singleLine = true)
                OutlinedTextField(value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_password_optional)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                OutlinedTextField(value = userAgent, onValueChange = { userAgent = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_custom_ua)) }, singleLine = true)
                OutlinedTextField(value = initialPath, onValueChange = { initialPath = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_initial_path)) }, singleLine = true)
                OutlinedTextField(value = note, onValueChange = { note = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_note)) }, minLines = 2)
                WebDavSwitchRow(uiText(R.string.switch_trust_all_certs), uiText(R.string.switch_trust_all_certs_desc), trustAll) { trustAll = it }
                WebDavSwitchRow(uiText(R.string.switch_multi_thread), uiText(R.string.switch_multi_thread_desc), multiThread) { multiThread = it }
                WebDavSwitchRow(uiText(R.string.switch_hide_ft_address), uiText(R.string.switch_hide_address_desc), hideAddress) { hideAddress = it }
                WebDavSwitchRow(uiText(R.string.switch_enable_ft_server), uiText(R.string.enable_connection_desc), enabled) { enabled = it }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        WebDavServerConfig(
                            id = initial.id.ifBlank { AppSettings.newId() },
                            name = name.ifBlank { "WebDAV" },
                            url = url.trim(),
                            username = username.trim(),
                            password = password,
                            userAgent = userAgent.trim(),
                            initialPath = initialPath.ifBlank { "/" },
                            note = note,
                            trustAllCertificates = trustAll,
                            multiThread = multiThread,
                            hideAddressInDrawer = hideAddress,
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
internal fun WebDavSwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

internal fun defaultWebDavServer(): WebDavServerConfig = WebDavServerConfig(
    id = AppSettings.newId(),
    name = "WebDAV",
    url = "",
    username = "",
    password = "",
    userAgent = "",
    initialPath = "/",
    note = "",
    trustAllCertificates = false,
    multiThread = true,
    hideAddressInDrawer = false,
    enabled = true,
)

@Composable
internal fun FileTransferSettings(settings: AppSettings, fileTransferClient: FileTransferClient, externalRevision: Int = 0) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    val servers = remember(revision, externalRevision) { settings.fileTransferServers() }
    var editing by remember { mutableStateOf<FileTransferServerConfig?>(null) }
    var deleteTarget by remember { mutableStateOf<FileTransferServerConfig?>(null) }
    var status by remember { mutableStateOf("") }

    fun saveServers(updated: List<FileTransferServerConfig>) {
        settings.saveFileTransferServers(updated)
        revision++
    }

    editing?.let { server ->
        FileTransferServerDialog(
            initial = server,
            onDismiss = { editing = null },
            onSave = { saved ->
                val updated = servers.toMutableList()
                val index = updated.indexOfFirst { it.id == saved.id }
                if (index >= 0) updated[index] = saved else updated += saved
                saveServers(updated)
                editing = null
                status = uiText(R.string.notice_file_transfer_saved)
            },
        )
    }
    deleteTarget?.let { server ->
        ConfirmDeleteDialog(
            title = uiText(R.string.title_delete_file_transfer),
            message = uiText(R.string.confirm_delete_file_transfer, server.protocol.uppercase(Locale.US)),
            targetName = server.name.ifBlank { server.host },
            onDismiss = { deleteTarget = null },
            onConfirm = {
                saveServers(servers.filterNot { it.id == server.id })
                status = uiText(R.string.notice_deleted_service, server.name)
            },
        )
    }

    KimiCardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("FTP / FTPS / SFTP", style = MaterialTheme.typography.titleMedium)
                Text(uiText(R.string.file_transfer_desc), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { editing = defaultFileTransferServer(AppSettings.FILE_TRANSFER_SFTP) }, shape = KimiPillShape) { Text(uiText(R.string.action_add)) }
        }
        if (status.isNotBlank()) Text(status, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
    }

    if (servers.isEmpty()) {
        KimiCardBox {
            Text(uiText(R.string.notice_no_file_transfer), style = MaterialTheme.typography.titleSmall)
            Text(uiText(R.string.file_transfer_empty_hint), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        }
    }

    servers.forEach { server ->
        KimiCardBox {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(server.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (server.hideAddressInDrawer) uiText(R.string.label_address_hidden) else "${server.protocol.uppercase(Locale.US)}://${server.host}:${server.port}",
                        color = KimiMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val auth = if (server.protocol == AppSettings.FILE_TRANSFER_SFTP && server.usePrivateKey) uiText(R.string.label_key_login) else server.username.ifBlank { uiText(R.string.label_anonymous) }
                    Text("$auth · ${server.initialPath.ifBlank { "/" }}", color = KimiMuted, style = MaterialTheme.typography.labelMedium)
                    if (server.protocol == AppSettings.FILE_TRANSFER_FTP) {
                        Text(uiText(R.string.notice_ftp_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Switch(
                    checked = server.enabled,
                    onCheckedChange = { enabled ->
                        saveServers(servers.map { if (it.id == server.id) it.copy(enabled = enabled) else it })
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        status = uiText(R.string.server_testing_name, server.name)
                        scope.launch {
                            status = withContext(Dispatchers.IO) {
                                fileTransferClient.test(server).fold(
                                    onSuccess = { uiText(R.string.file_transfer_test_success_count, server.protocol.uppercase(Locale.US), it.size) },
                                    onFailure = { uiText(R.string.file_transfer_test_failed, server.protocol.uppercase(Locale.US), it.message) },
                                )
                            }
                        }
                    },
                    shape = KimiPillShape,
                ) { Text(uiText(R.string.action_test_connection)) }
                IconButton(onClick = { editing = server }) {
                    Icon(Icons.Default.Edit, contentDescription = uiText(R.string.cd_edit_file_transfer))
                }
                IconButton(onClick = { deleteTarget = server }) {
                    Icon(Icons.Default.Delete, contentDescription = uiText(R.string.cd_delete_file_transfer))
                }
            }
        }
    }
}

@Composable
internal fun FileTransferServerDialog(
    initial: FileTransferServerConfig,
    onDismiss: () -> Unit,
    onSave: (FileTransferServerConfig) -> Unit,
) {
    var protocol by rememberSaveable(initial.id) { mutableStateOf(AppSettings.normalizeFileTransferProtocol(initial.protocol)) }
    var name by rememberSaveable(initial.id) { mutableStateOf(initial.name) }
    var host by rememberSaveable(initial.id) { mutableStateOf(initial.host) }
    var portText by rememberSaveable(initial.id) { mutableStateOf(initial.port.toString()) }
    var username by rememberSaveable(initial.id) { mutableStateOf(initial.username) }
    var password by rememberSaveable(initial.id) { mutableStateOf(initial.password) }
    var usePrivateKey by rememberSaveable(initial.id) { mutableStateOf(initial.usePrivateKey) }
    var privateKey by rememberSaveable(initial.id) { mutableStateOf(initial.privateKey) }
    var passphrase by rememberSaveable(initial.id) { mutableStateOf(initial.passphrase) }
    var initialPath by rememberSaveable(initial.id) { mutableStateOf(initial.initialPath) }
    var note by rememberSaveable(initial.id) { mutableStateOf(initial.note) }
    var encoding by rememberSaveable(initial.id) { mutableStateOf(initial.encoding) }
    var passiveMode by rememberSaveable(initial.id) { mutableStateOf(initial.passiveMode) }
    var explicitFtps by rememberSaveable(initial.id) { mutableStateOf(initial.explicitFtps) }
    var multiThread by rememberSaveable(initial.id) { mutableStateOf(initial.multiThread) }
    var syncPermissions by rememberSaveable(initial.id) { mutableStateOf(initial.syncPermissions) }
    var hideAddress by rememberSaveable(initial.id) { mutableStateOf(initial.hideAddressInDrawer) }
    var enabled by rememberSaveable(initial.id) { mutableStateOf(initial.enabled) }
    var protocolMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(uiText(R.string.detail_file_transfer)) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box {
                    OutlinedButton(onClick = { protocolMenu = true }, shape = KimiPillShape) {
                        Text(protocol.uppercase(Locale.US))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = protocolMenu, onDismissRequest = { protocolMenu = false }) {
                        listOf(AppSettings.FILE_TRANSFER_SFTP, AppSettings.FILE_TRANSFER_FTP, AppSettings.FILE_TRANSFER_FTPS).forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.uppercase(Locale.US)) },
                                onClick = {
                                    protocol = item
                                    portText = AppSettings.defaultFileTransferPort(item).toString()
                                    if (item == AppSettings.FILE_TRANSFER_SFTP && username == "anonymous") username = ""
                                    if (item != AppSettings.FILE_TRANSFER_SFTP && username.isBlank()) username = "anonymous"
                                    protocolMenu = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_webdav_service_name)) }, singleLine = true)
                OutlinedTextField(value = host, onValueChange = { host = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_host)) }, singleLine = true)
                OutlinedTextField(value = portText, onValueChange = { portText = it.filter(Char::isDigit).take(5) }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_port)) }, singleLine = true)
                OutlinedTextField(value = username, onValueChange = { username = it }, modifier = Modifier.fillMaxWidth(), label = { Text(if (protocol == AppSettings.FILE_TRANSFER_SFTP) uiText(R.string.label_username) else uiText(R.string.label_username_optional)) }, singleLine = true)
                if (protocol == AppSettings.FILE_TRANSFER_SFTP) {
                    WebDavSwitchRow(uiText(R.string.switch_use_key_login), uiText(R.string.switch_use_key_login_desc), usePrivateKey) { usePrivateKey = it }
                }
                if (protocol == AppSettings.FILE_TRANSFER_SFTP && usePrivateKey) {
                    OutlinedTextField(value = privateKey, onValueChange = { privateKey = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_private_key)) }, minLines = 4)
                    OutlinedTextField(value = passphrase, onValueChange = { passphrase = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_passphrase_optional)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                } else {
                    OutlinedTextField(value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_password_optional)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                }
                if (protocol == AppSettings.FILE_TRANSFER_FTP) {
                    Text(uiText(R.string.ftp_warning_detail), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(value = initialPath, onValueChange = { initialPath = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_initial_path)) }, singleLine = true)
                OutlinedTextField(value = note, onValueChange = { note = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_note)) }, minLines = 2)
                OutlinedTextField(value = encoding, onValueChange = { encoding = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_encoding)) }, singleLine = true)
                if (protocol != AppSettings.FILE_TRANSFER_SFTP) {
                    WebDavSwitchRow(uiText(R.string.switch_passive_mode), uiText(R.string.switch_passive_mode_desc), passiveMode) { passiveMode = it }
                }
                if (protocol == AppSettings.FILE_TRANSFER_FTPS) {
                    WebDavSwitchRow(uiText(R.string.switch_explicit_ftps), uiText(R.string.switch_explicit_ftps_desc), explicitFtps) { explicitFtps = it }
                }
                WebDavSwitchRow(uiText(R.string.switch_multi_thread), uiText(R.string.switch_multi_thread_desc), multiThread) { multiThread = it }
                WebDavSwitchRow(uiText(R.string.switch_sync_permissions), uiText(R.string.switch_sync_permissions_desc), syncPermissions) { syncPermissions = it }
                WebDavSwitchRow(uiText(R.string.switch_hide_ft_address), uiText(R.string.switch_hide_ft_address_desc), hideAddress) { hideAddress = it }
                WebDavSwitchRow(uiText(R.string.switch_enable_ft_server), uiText(R.string.enable_connection_desc), enabled) { enabled = it }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val normalizedProtocol = AppSettings.normalizeFileTransferProtocol(protocol)
                    onSave(
                        FileTransferServerConfig(
                            id = initial.id.ifBlank { AppSettings.newId() },
                            name = name.ifBlank { normalizedProtocol.uppercase(Locale.US) },
                            protocol = normalizedProtocol,
                            host = host.trim(),
                            port = portText.toIntOrNull()?.coerceIn(1, 65535) ?: AppSettings.defaultFileTransferPort(normalizedProtocol),
                            username = username.trim().ifBlank { if (normalizedProtocol == AppSettings.FILE_TRANSFER_SFTP) "" else "anonymous" },
                            password = password,
                            usePrivateKey = normalizedProtocol == AppSettings.FILE_TRANSFER_SFTP && usePrivateKey,
                            privateKey = privateKey,
                            passphrase = passphrase,
                            initialPath = initialPath.ifBlank { "/" },
                            note = note,
                            encoding = encoding.ifBlank { "UTF-8" },
                            passiveMode = passiveMode,
                            explicitFtps = explicitFtps,
                            multiThread = multiThread,
                            syncPermissions = syncPermissions,
                            hideAddressInDrawer = hideAddress,
                            enabled = enabled,
                        ),
                    )
                },
            ) { Text(uiText(R.string.file_editor_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiText(R.string.action_cancel)) } },
    )
}

internal fun defaultFileTransferServer(protocol: String): FileTransferServerConfig {
    val normalized = AppSettings.normalizeFileTransferProtocol(protocol)
    return FileTransferServerConfig(
        id = AppSettings.newId(),
        name = normalized.uppercase(Locale.US),
        protocol = normalized,
        host = "",
        port = AppSettings.defaultFileTransferPort(normalized),
        username = if (normalized == AppSettings.FILE_TRANSFER_SFTP) "" else "anonymous",
        password = "",
        usePrivateKey = false,
        privateKey = "",
        passphrase = "",
        initialPath = "/",
        note = "",
        encoding = "UTF-8",
        passiveMode = true,
        explicitFtps = true,
        multiThread = true,
        syncPermissions = false,
        hideAddressInDrawer = false,
        enabled = true,
    )
}

@Composable
internal fun BackupSettings(
    settings: AppSettings,
    webDavClient: WebDavClient,
    backupManager: BackupManager,
    status: String,
    onStatusChange: (String) -> Unit,
    onImportBackup: (String) -> Unit,
    onConfigChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val webDavServers = settings.webDavServers().filter { it.enabled }
    var includeProfile by rememberSaveable { mutableStateOf(true) }
    var includeConversations by rememberSaveable { mutableStateOf(true) }

    var includeModelProfiles by rememberSaveable { mutableStateOf(true) }
    var includeMcp by rememberSaveable { mutableStateOf(true) }
    var includeSsh by rememberSaveable { mutableStateOf(true) }
    var includeEmail by rememberSaveable { mutableStateOf(true) }
    var includePrompts by rememberSaveable { mutableStateOf(true) }
    var includeMemories by rememberSaveable { mutableStateOf(true) }
    var includeSkills by rememberSaveable { mutableStateOf(true) }
    var includeWebDav by rememberSaveable { mutableStateOf(true) }
    var includeFileTransfer by rememberSaveable { mutableStateOf(true) }
    var includeSecrets by rememberSaveable { mutableStateOf(false) }
    var selectedServerId by rememberSaveable { mutableStateOf(webDavServers.firstOrNull()?.id.orEmpty()) }
    var remotePath by rememberSaveable { mutableStateOf(DEFAULT_WEBDAV_BACKUP_PATH) }
    var transferStatus by remember { mutableStateOf("") }
    val selectedServer = webDavServers.firstOrNull { it.id == selectedServerId } ?: webDavServers.firstOrNull()

    fun options() = BackupOptions(
        includeProfile = includeProfile,
        includeConversations = includeConversations,

        includeModelProfiles = includeModelProfiles,
        includeMcp = includeMcp,
        includeSsh = includeSsh,
        includeEmail = includeEmail,
        includePrompts = includePrompts,
        includeMemories = includeMemories,
        includeSkills = includeSkills,
        includeWebDav = includeWebDav,
        includeFileTransfer = includeFileTransfer,
        includeSecrets = includeSecrets,
    )

    KimiCardBox {
        Text(uiText(R.string.title_export_content), style = MaterialTheme.typography.titleMedium)
        Text(uiText(R.string.backup_desc), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        BackupIncludeRow(uiText(R.string.backup_include_profile), includeProfile) { includeProfile = it }
        BackupIncludeRow(uiText(R.string.backup_include_conversations), includeConversations) { includeConversations = it }

        BackupIncludeRow(uiText(R.string.backup_include_model_profiles), includeModelProfiles) { includeModelProfiles = it }
        BackupIncludeRow(uiText(R.string.backup_include_mcp), includeMcp) { includeMcp = it }
        BackupIncludeRow(uiText(R.string.backup_include_ssh), includeSsh) { includeSsh = it }
        BackupIncludeRow(uiText(R.string.ui_email_server_configuration), includeEmail) { includeEmail = it }
        BackupIncludeRow(uiText(R.string.title_system_prompt), includePrompts) { includePrompts = it }
        BackupIncludeRow(uiText(R.string.backup_include_memories), includeMemories) { includeMemories = it }
        BackupIncludeRow("Skills", includeSkills) { includeSkills = it }
        BackupIncludeRow(uiText(R.string.backup_include_webdav), includeWebDav) { includeWebDav = it }
        BackupIncludeRow(uiText(R.string.backup_include_file_transfer), includeFileTransfer) { includeFileTransfer = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(uiText(R.string.title_include_secrets), style = MaterialTheme.typography.titleSmall)
                Text(uiText(R.string.backup_secrets_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = includeSecrets, onCheckedChange = { includeSecrets = it })
        }
        Button(
            onClick = {
                scope.launch {
                    onStatusChange(uiText(R.string.ui_exporting_to_download_lyracode))
                    onStatusChange(withContext(Dispatchers.IO) {
                        runCatching { backupManager.exportToDownloads(options()) }
                            .fold({ it }, { uiText(R.string.error_export_failed, it.message) })
                    })
                }
            },
            shape = KimiPillShape,
        ) { Text(uiText(R.string.action_export_local)) }
    }

    KimiCardBox {
        Text(uiText(R.string.title_import_backup), style = MaterialTheme.typography.titleMedium)
        Text(uiText(R.string.import_desc), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onImportBackup("supplement") }, shape = KimiPillShape) { Text(uiText(R.string.action_supplement_import)) }
            OutlinedButton(onClick = { onImportBackup("replace") }, shape = KimiPillShape) { Text(uiText(R.string.action_replace_import)) }
        }
        if (status.isNotBlank()) Text(status, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
    }

    KimiCardBox {
        Text(uiText(R.string.title_webdav_backup), style = MaterialTheme.typography.titleMedium)
        if (webDavServers.isEmpty()) {
            Text(uiText(R.string.notice_no_webdav_for_backup), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        } else {
            WebDavServerPicker(webDavServers, selectedServerId) { selectedServerId = it }
            OutlinedTextField(value = remotePath, onValueChange = { remotePath = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_remote_backup_path)) }, singleLine = true)
            if (transferStatus.isNotBlank()) Text(transferStatus, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val server = selectedServer ?: return@OutlinedButton
                        scope.launch {
                            onStatusChange(uiText(R.string.ui_exporting_and_uploading_to_webdav))
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val bytes = backupManager.exportZip(options())
                                    val targetPath = remotePath.ifBlank { DEFAULT_WEBDAV_BACKUP_PATH }
                                    webDavClient.upload(server, targetPath, bytes) { progress ->
                                        scope.launch { transferStatus = formatTransferProgress(progress) }
                                    }
                                    uiText(R.string.notice_uploaded_to, server.name, targetPath)
                                }.fold({ it }, { uiText(R.string.error_upload_failed, it.message) })
                            }
                            transferStatus = ""
                            onStatusChange(result)
                        }
                    },
                    shape = KimiPillShape,
                ) { Text(uiText(R.string.action_upload_backup)) }
                OutlinedButton(
                    onClick = {
                        val server = selectedServer ?: return@OutlinedButton
                        scope.launch {
                            onStatusChange(uiText(R.string.ui_downloading_from_webdav_and_importing_in_supplement_mode))
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val requested = remotePath.trim().ifBlank { DEFAULT_WEBDAV_BACKUP_PATH }
                                    var usedPath = requested
                                    val bytes = runCatching {
                                        webDavClient.download(server, requested) { progress ->
                                            scope.launch { transferStatus = formatTransferProgress(progress) }
                                        }
                                    }.getOrElse {
                                        usedPath = resolveLatestWebDavBackupPath(webDavClient, server, requested)
                                        webDavClient.download(server, usedPath) { progress ->
                                            scope.launch { transferStatus = formatTransferProgress(progress) }
                                        }
                                    }
                                    uiText(R.string.ui_from) + usedPath + uiText(R.string.ui_supplemental_import) + backupManager.importZip(bytes, "supplement")
                                }.fold({ uiText(R.string.notice_import_complete, it) }, { uiText(R.string.notice_import_failed, it.message) })
                            }
                            transferStatus = ""
                            onStatusChange(result)
                            onConfigChanged()
                        }
                    },
                    shape = KimiPillShape,
                ) { Text(uiText(R.string.action_import_from_cloud)) }
            }
        }
    }
}

private const val DEFAULT_WEBDAV_BACKUP_PATH = "/LyraCode/lyra_backup_latest.zip"

private fun resolveLatestWebDavBackupPath(client: WebDavClient, server: WebDavServerConfig, rawPath: String): String {
    val requested = rawPath.trim().ifBlank { DEFAULT_WEBDAV_BACKUP_PATH }
    val directory = requested.substringBeforeLast('/', "/").ifBlank { "/" }.let { if (it.endsWith("/")) it else "$it/" }
    val candidates = client.list(server, directory, depth = 1)
        .filter {
            val name = it.path.substringAfterLast('/')
            name.endsWith(".zip", ignoreCase = true) && name.contains("lyra_backup", ignoreCase = true)
        }
        .sortedWith(compareByDescending<com.yukisoffd.lyracode.webdav.WebDavFile> { it.modified }.thenByDescending { it.path.substringAfterLast('/') })
    return candidates.firstOrNull()?.path ?: requested
}

@Composable
internal fun BackupIncludeRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun WebDavServerPicker(servers: List<WebDavServerConfig>, selectedId: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = servers.firstOrNull { it.id == selectedId } ?: servers.firstOrNull()
    Box {
        OutlinedButton(onClick = { expanded = true }, shape = KimiPillShape) {
            Text(selected?.name ?: uiText(R.string.ui_choose_webdav))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            servers.forEach { server ->
                DropdownMenuItem(
                    text = { Text(server.name) },
                    onClick = {
                        onSelect(server.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

internal fun formatTransferProgress(progress: TransferProgress): String {
    val total = if (progress.totalBytes > 0) formatBytes(progress.totalBytes) else uiText(R.string.label_unknown_size)
    val percent = if (progress.totalBytes > 0) " · ${(progress.doneBytes * 100 / progress.totalBytes).coerceIn(0, 100)}%" else ""
    return "${progress.title}: ${formatBytes(progress.doneBytes)} / $total$percent · ${formatBytes(progress.bytesPerSecond)}/s"
}

@Composable
internal fun SshSettings(settings: AppSettings, sshExecutor: SshExecutor, externalRevision: Int = 0) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    val servers = remember(revision, externalRevision) { settings.sshServers() }
    var editing by remember { mutableStateOf<SshServerConfig?>(null) }
    var deleteTarget by remember { mutableStateOf<SshServerConfig?>(null) }
    var status by remember { mutableStateOf("") }

    fun saveServers(updated: List<SshServerConfig>) {
        settings.saveSshServers(updated)
        revision++
    }

    editing?.let { server ->
        SshServerDialog(
            initial = server,
            onDismiss = { editing = null },
            onSave = { saved ->
                val updated = servers.toMutableList()
                val index = updated.indexOfFirst { it.id == saved.id }
                if (index >= 0) updated[index] = saved else updated += saved
                saveServers(updated)
                editing = null
                status = uiText(R.string.notice_ssh_saved)
            },
        )
    }
    deleteTarget?.let { server ->
        ConfirmDeleteDialog(
            title = uiText(R.string.title_delete_ssh),
            message = uiText(R.string.confirm_delete_ssh),
            targetName = "${server.name} · ${server.stableId}",
            onDismiss = { deleteTarget = null },
            onConfirm = {
                saveServers(servers.filterNot { it.id == server.id })
                status = uiText(R.string.notice_deleted_service, server.name)
            },
        )
    }

    KimiCardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(uiText(R.string.detail_ssh), style = MaterialTheme.typography.titleMedium)
                Text(uiText(R.string.ssh_desc), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { editing = defaultSshServer() }, shape = KimiPillShape) { Text(uiText(R.string.action_add)) }
        }
        if (status.isNotBlank()) Text(status, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
    }

    if (servers.isEmpty()) {
        KimiCardBox {
            Text(uiText(R.string.notice_no_ssh), style = MaterialTheme.typography.titleSmall)
            Text(uiText(R.string.ssh_empty_hint), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        }
    }

    servers.forEach { server ->
        KimiCardBox {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(server.name, style = MaterialTheme.typography.titleMedium)
                    Text(server.stableId, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
                    Text("${server.username} · ${sshAuthLabel(server.authType)} · ${server.timeoutSeconds}s", color = KimiMuted, style = MaterialTheme.typography.labelMedium)
                }
                Switch(
                    checked = server.enabled,
                    onCheckedChange = { enabled ->
                        saveServers(servers.map { if (it.id == server.id) it.copy(enabled = enabled) else it })
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        status = uiText(R.string.server_testing_name, server.name)
                        scope.launch {
                            val result = sshExecutor.execute(
                                server = server,
                                command = "printf 'lyra_ssh_ok\\n' && uname -a 2>/dev/null || ver",
                                cwd = "",
                                inputLines = emptyList(),
                                timeoutSeconds = 15,
                            )
                            status = if (result.ok) uiText(R.string.ssh_test_success_id, server.stableId) else result.message.take(200)
                        }
                    },
                    shape = KimiPillShape,
                ) { Text(uiText(R.string.action_test_connection)) }
                IconButton(onClick = { editing = server }) {
                    Icon(Icons.Default.Edit, contentDescription = uiText(R.string.cd_edit_ssh))
                }
                IconButton(onClick = { deleteTarget = server }) {
                    Icon(Icons.Default.Delete, contentDescription = uiText(R.string.cd_delete_ssh))
                }
            }
        }
    }

    KimiCardBox {
        Text(uiText(R.string.title_ssh_usage), style = MaterialTheme.typography.titleSmall)
        Text(
            uiText(R.string.ssh_usage_desc),
            color = KimiMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun SshServerDialog(
    initial: SshServerConfig,
    onDismiss: () -> Unit,
    onSave: (SshServerConfig) -> Unit,
) {
    var name by rememberSaveable(initial.id) { mutableStateOf(initial.name) }
    var host by rememberSaveable(initial.id) { mutableStateOf(initial.host) }
    var port by rememberSaveable(initial.id) { mutableStateOf(initial.port.toString()) }
    var username by rememberSaveable(initial.id) { mutableStateOf(initial.username) }
    var authType by rememberSaveable(initial.id) { mutableStateOf(initial.authType.ifBlank { AppSettings.SSH_AUTH_PASSWORD }) }
    var password by rememberSaveable(initial.id) { mutableStateOf(initial.password) }
    var privateKey by rememberSaveable(initial.id) { mutableStateOf(initial.privateKey) }
    var passphrase by rememberSaveable(initial.id) { mutableStateOf(initial.passphrase) }
    var timeout by rememberSaveable(initial.id) { mutableStateOf(initial.timeoutSeconds.toString()) }
    var enabled by rememberSaveable(initial.id) { mutableStateOf(initial.enabled) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(uiText(R.string.detail_ssh)) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_display_name)) }, singleLine = true)
                OutlinedTextField(value = host, onValueChange = { host = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_host_ip)) }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, modifier = Modifier.weight(1f), label = { Text(uiText(R.string.label_port)) }, singleLine = true)
                    OutlinedTextField(value = username, onValueChange = { username = it }, modifier = Modifier.weight(2f), label = { Text(uiText(R.string.label_username)) }, singleLine = true)
                }
                Text(uiText(R.string.label_auth_method), style = MaterialTheme.typography.labelMedium)
                MaterialButtonGroup(
                    options = listOf(AppSettings.SSH_AUTH_PASSWORD to uiText(R.string.auth_password), AppSettings.SSH_AUTH_KEY to uiText(R.string.auth_private_key)),
                    selected = authType,
                    onSelect = { authType = it },
                )
                if (authType == AppSettings.SSH_AUTH_PASSWORD) {
                    OutlinedTextField(value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.auth_password)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                } else {
                    OutlinedTextField(value = privateKey, onValueChange = { privateKey = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_private_key)) }, minLines = 5, maxLines = 10)
                    OutlinedTextField(value = passphrase, onValueChange = { passphrase = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_passphrase_optional_with_parens)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                }
                OutlinedTextField(value = timeout, onValueChange = { timeout = it.filter(Char::isDigit).take(3) }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_default_timeout)) }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(uiText(R.string.title_enable_connection), style = MaterialTheme.typography.titleSmall)
                        Text(uiText(R.string.enable_connection_desc), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Text(uiText(R.string.ui_stable_identifier_will_use) + host.ifBlank { "host" } + ":" + port.ifBlank { "22" } + uiText(R.string.ui_the_ai_uses_this_identifier_when_calling_ssh_tools), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        SshServerConfig(
                            id = initial.id.ifBlank { AppSettings.newId() },
                            name = name.ifBlank { host.ifBlank { "SSH Server" } },
                            host = host.trim(),
                            port = port.toIntOrNull()?.coerceIn(1, 65535) ?: 22,
                            username = username.trim(),
                            authType = authType,
                            password = password,
                            privateKey = privateKey,
                            passphrase = passphrase,
                            timeoutSeconds = timeout.toIntOrNull()?.coerceIn(5, 600) ?: 60,
                            enabled = enabled,
                        ),
                    )
                },
            ) { Text(uiText(R.string.file_editor_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiText(R.string.action_cancel)) } },
    )
}

internal fun defaultSshServer(): SshServerConfig = SshServerConfig(
    id = AppSettings.newId(),
    name = "SSH Server",
    host = "",
    port = 22,
    username = "",
    authType = AppSettings.SSH_AUTH_PASSWORD,
    password = "",
    privateKey = "",
    passphrase = "",
    timeoutSeconds = 60,
    enabled = true,
)

internal fun sshAuthLabel(authType: String): String = when (authType) {
    AppSettings.SSH_AUTH_KEY -> uiText(R.string.auth_private_key)
    else -> uiText(R.string.auth_password)
}

