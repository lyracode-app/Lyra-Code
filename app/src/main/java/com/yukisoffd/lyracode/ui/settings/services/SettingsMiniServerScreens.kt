package com.yukisoffd.lyracode

import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.MiniServerConfig
import com.yukisoffd.lyracode.server.MiniServerManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.net.URL
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.max



@Composable
internal fun MiniServerSettings(
    settings: AppSettings,
    miniServerManager: MiniServerManager,
    externalRevision: Int = 0,
    onOpenLogs: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    val savedConfig = remember(revision, externalRevision) { settings.miniServerConfig() }
    var protocol by remember(savedConfig) { mutableStateOf(savedConfig.protocol) }
    var host by remember(savedConfig) { mutableStateOf(savedConfig.host) }
    var portText by remember(savedConfig) { mutableStateOf(savedConfig.port.toString()) }
    var username by remember(savedConfig) { mutableStateOf(savedConfig.username.ifBlank { AppSettings.DEFAULT_MINI_SERVER_USERNAME }) }
    var password by remember(savedConfig) { mutableStateOf(savedConfig.password) }
    var customDomainsText by remember(savedConfig) { mutableStateOf(savedConfig.customDomains.joinToString("\n")) }
    var forceHttps by remember(savedConfig) { mutableStateOf(savedConfig.forceHttps) }
    var tlsKeyStoreBase64 by remember(savedConfig) { mutableStateOf(savedConfig.tlsKeyStoreBase64) }
    var tlsKeyStorePassword by remember(savedConfig) { mutableStateOf(savedConfig.tlsKeyStorePassword) }
    var tlsCertificateChain by remember(savedConfig) { mutableStateOf(savedConfig.tlsCertificateChain) }
    var tlsPrivateKey by remember(savedConfig) { mutableStateOf(savedConfig.tlsPrivateKey) }
    var spaFallback by remember(savedConfig) { mutableStateOf(savedConfig.spaFallback) }
    var directoryListing by remember(savedConfig) { mutableStateOf(savedConfig.directoryListing) }
    var mdnsEnabled by remember(savedConfig) { mutableStateOf(savedConfig.mdnsEnabled) }
    var mdnsName by remember(savedConfig) { mutableStateOf(savedConfig.mdnsName) }
    var statusText by remember { mutableStateOf("") }
    var statusRevision by remember { mutableIntStateOf(0) }
    val keyStoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tlsKeyStoreBase64 = Base64.encodeToString(input.readBytes(), Base64.NO_WRAP)
                } ?: error(uiText(R.string.error_read_keystore_failed))
            }.fold(
                { statusText = uiText(R.string.notice_keystore_read) },
                { statusText = uiText(R.string.notice_keystore_read_failed, it.message) },
            )
        }
    }
    val certChainLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tlsCertificateChain = input.bufferedReader(Charsets.UTF_8).readText()
                } ?: error(uiText(R.string.error_read_cert_chain_failed))
            }.fold(
                { statusText = uiText(R.string.notice_cert_chain_read) },
                { statusText = uiText(R.string.notice_cert_chain_read_failed, it.message) },
            )
        }
    }
    val privateKeyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tlsPrivateKey = input.bufferedReader(Charsets.UTF_8).readText()
                } ?: error(uiText(R.string.error_read_private_key_failed))
            }.fold(
                { statusText = uiText(R.string.notice_private_key_read) },
                { statusText = uiText(R.string.notice_private_key_read_failed, it.message) },
            )
        }
    }
    val status = remember(statusRevision, revision, externalRevision) { miniServerManager.status() }
    val lanUrls = remember(statusRevision, revision, externalRevision) {
        miniServerManager.statusJson().optJSONArray("lanUrls")?.let { array ->
            buildList {
                for (index in 0 until array.length()) add(array.optString(index))
            }
        }.orEmpty()
    }
    val customUrls = remember(statusRevision, revision, externalRevision) {
        miniServerManager.statusJson().optJSONArray("customUrls")?.let { array ->
            buildList {
                for (index in 0 until array.length()) add(array.optString(index))
            }
        }.orEmpty()
    }

    fun currentConfig(enabled: Boolean = status.running): MiniServerConfig {
        return MiniServerConfig(
            protocol = if (protocol == AppSettings.MINI_SERVER_PROTOCOL_HTTPS) AppSettings.MINI_SERVER_PROTOCOL_HTTPS else AppSettings.MINI_SERVER_PROTOCOL_HTTP,
            host = host.trim().ifBlank { AppSettings.DEFAULT_MINI_SERVER_HOST },
            port = portText.toIntOrNull()?.coerceIn(1, 65535) ?: AppSettings.DEFAULT_MINI_SERVER_PORT,
            username = username.trim().ifBlank { AppSettings.DEFAULT_MINI_SERVER_USERNAME },
            password = password,
            customDomains = customDomainsText.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.distinct().toList(),
            forceHttps = forceHttps,
            tlsKeyStoreBase64 = tlsKeyStoreBase64,
            tlsKeyStorePassword = tlsKeyStorePassword,
            tlsCertificateChain = tlsCertificateChain,
            tlsPrivateKey = tlsPrivateKey,
            spaFallback = spaFallback,
            directoryListing = directoryListing,
            mdnsEnabled = mdnsEnabled,
            mdnsName = mdnsName.ifBlank { AppSettings.DEFAULT_MINI_SERVER_MDNS_NAME },
            enabled = enabled,
        )
    }

    fun refresh(message: String) {
        statusText = message
        statusRevision++
        revision++
    }

    KimiCardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(uiText(R.string.title_workspace_mini_server), style = MaterialTheme.typography.titleMedium)
                Text(uiText(R.string.mini_server_desc), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
            }
            Text(if (status.running) uiText(R.string.label_server_running) else uiText(R.string.notice_server_stopped), color = if (status.running) MaterialTheme.colorScheme.primary else KimiMuted)
        }
        KimiDivider()
        Text(
            stringResource(R.string.label_local_address, status.url),
            color = KimiMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        if (lanUrls.isNotEmpty()) {
            Text(uiText(R.string.ui_lan_address) + lanUrls.joinToString("  "), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        }
        if (customUrls.isNotEmpty()) {
            Text(uiText(R.string.ui_bound_domains) + customUrls.joinToString("  "), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        }
        if (status.message.isNotBlank()) {
            Text(status.message, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        }
        if (statusText.isNotBlank()) {
            Text(statusText, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(
            onClick = onOpenLogs,
            shape = KimiPillShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Article, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(uiText(R.string.action_view_logs))
        }
    }

    KimiCardBox {
        Text(uiText(R.string.title_listen_config), style = MaterialTheme.typography.titleMedium)
        KimiDivider()
        MaterialButtonGroup(
            options = listOf(AppSettings.MINI_SERVER_PROTOCOL_HTTP to "HTTP", AppSettings.MINI_SERVER_PROTOCOL_HTTPS to "HTTPS"),
            selected = protocol,
            onSelect = { protocol = it },
        )
        if (protocol == AppSettings.MINI_SERVER_PROTOCOL_HTTPS) {
            Text(uiText(R.string.notice_https_self_signed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(value = host, onValueChange = { host = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_listen_host)) }, singleLine = true)
        Text(uiText(R.string.listen_host_hint), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = portText, onValueChange = { portText = it.filter(Char::isDigit).take(5) }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_port)) }, singleLine = true)
        OutlinedTextField(value = username, onValueChange = { username = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_access_username)) }, singleLine = true)
        OutlinedTextField(value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_access_password_optional)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
        OutlinedTextField(
            value = customDomainsText,
            onValueChange = { customDomainsText = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            label = { Text(uiText(R.string.label_bind_domains)) },
            placeholder = { Text("docs.example.com\nhttps://preview.example.com") },
        )
        WebDavSwitchRow(uiText(R.string.switch_force_https), uiText(R.string.switch_force_https_desc), forceHttps) { forceHttps = it }
        if (host.trim() == "0.0.0.0" || password.isBlank()) {
            Text(uiText(R.string.notice_server_security), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }

    KimiCardBox {
        Text(uiText(R.string.title_https_cert), style = MaterialTheme.typography.titleMedium)
        KimiDivider()
        Text(uiText(R.string.https_cert_desc), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { keyStoreLauncher.launch("*/*") },
                shape = KimiPillShape,
                modifier = Modifier.weight(1f),
            ) { Text(if (tlsKeyStoreBase64.isBlank()) uiText(R.string.action_upload_keystore) else uiText(R.string.action_replace_keystore)) }
            OutlinedButton(
                onClick = { tlsKeyStoreBase64 = "" },
                shape = KimiPillShape,
                enabled = tlsKeyStoreBase64.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { Text(uiText(R.string.action_clear_keystore)) }
        }
        OutlinedTextField(
            value = tlsKeyStorePassword,
            onValueChange = { tlsKeyStorePassword = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(uiText(R.string.label_keystore_password)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { certChainLauncher.launch("*/*") }, shape = KimiPillShape, modifier = Modifier.weight(1f)) { Text(uiText(R.string.action_upload_cert_chain)) }
            OutlinedButton(onClick = { privateKeyLauncher.launch("*/*") }, shape = KimiPillShape, modifier = Modifier.weight(1f)) { Text(uiText(R.string.action_upload_private_key)) }
        }
        OutlinedTextField(
            value = tlsCertificateChain,
            onValueChange = { tlsCertificateChain = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            label = { Text(uiText(R.string.label_cert_chain_pem)) },
            placeholder = { Text("-----BEGIN CERTIFICATE-----") },
        )
        OutlinedTextField(
            value = tlsPrivateKey,
            onValueChange = { tlsPrivateKey = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            label = { Text(uiText(R.string.label_private_key_pem)) },
            placeholder = { Text("-----BEGIN PRIVATE KEY-----") },
            visualTransformation = PasswordVisualTransformation(),
        )
    }

    KimiCardBox {
        Text(uiText(R.string.title_site_behavior), style = MaterialTheme.typography.titleMedium)
        KimiDivider()
        WebDavSwitchRow(uiText(R.string.switch_spa_fallback), uiText(R.string.switch_spa_fallback_desc), spaFallback) { spaFallback = it }
        WebDavSwitchRow(uiText(R.string.switch_directory_listing), uiText(R.string.switch_directory_listing_desc), directoryListing) { directoryListing = it }
        WebDavSwitchRow(uiText(R.string.switch_mdns), uiText(R.string.switch_mdns_desc), mdnsEnabled) { mdnsEnabled = it }
        if (mdnsEnabled) {
            OutlinedTextField(value = mdnsName, onValueChange = { mdnsName = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.label_mdns_name)) }, singleLine = true)
        }
    }

    KimiCardBox {
        Text(uiText(R.string.title_operations), style = MaterialTheme.typography.titleMedium)
        KimiDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    settings.saveMiniServerConfig(currentConfig())
                    refresh(uiText(R.string.notice_mini_server_config_saved))
                },
                shape = KimiPillShape,
                modifier = Modifier.weight(1f),
            ) { Text(uiText(R.string.file_editor_save)) }
            Button(
                onClick = {
                    statusText = uiText(R.string.ui_starting)
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { miniServerManager.start(currentConfig(enabled = true)) }
                                .fold({ uiText(R.string.notice_server_started, it.url) }, { uiText(R.string.notice_server_start_failed, it.message) })
                        }
                        refresh(result)
                    }
                },
                shape = KimiPillShape,
                modifier = Modifier.weight(1f),
            ) { Text(if (status.running) uiText(R.string.action_restart) else uiText(R.string.action_start)) }
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { miniServerManager.stop() }
                            .fold({ uiText(R.string.notice_server_stopped) }, { uiText(R.string.notice_server_stop_failed, it.message) })
                    }
                    refresh(result)
                }
            },
            shape = KimiPillShape,
            enabled = status.running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(uiText(R.string.action_stop_service)) }
    }
}

@Composable
internal fun MiniServerLogSettings(miniServerManager: MiniServerManager) {
    var revision by remember { mutableIntStateOf(0) }
    var levelFilter by rememberSaveable { mutableStateOf("") }
    val payload = remember(revision, levelFilter) { miniServerManager.logsJson(200, levelFilter) }
    val logs = remember(payload) {
        payload.optJSONArray("logs")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { add(it) }
                }
            }
        }.orEmpty()
    }
    val levels = listOf("" to "ALL", "info" to "INFO", "warn" to "WARN", "error" to "ERROR")
    val terminalScroll = rememberScrollState()
    val terminalHorizontalScroll = rememberScrollState()
    val filterScroll = rememberScrollState()

    LaunchedEffect(levelFilter) {
        while (true) {
            delay(1_000)
            revision++
        }
    }

    LaunchedEffect(logs.size, levelFilter) {
        terminalScroll.animateScrollTo(terminalScroll.maxValue)
    }

    KimiCardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(uiText(R.string.title_terminal_log), style = MaterialTheme.typography.titleMedium)
                Text(
                    uiText(R.string.terminal_log_desc),
                    color = KimiMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(
                onClick = {
                    miniServerManager.clearLogs()
                    revision++
                },
            ) {
                Icon(Icons.Default.DeleteSweep, contentDescription = uiText(R.string.cd_clear_logs), tint = MaterialTheme.colorScheme.primary)
            }
        }
        KimiDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(filterScroll),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${if (payload.optBoolean("running")) "RUNNING" else "STOPPED"} · ${payload.optString("workspace")} · ${payload.optInt("count")} lines",
                color = KimiMuted,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            levels.forEach { (value, label) ->
                TextButton(
                    onClick = { levelFilter = value },
                    shape = KimiPillShape,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (levelFilter == value) "[$label]" else label,
                        maxLines = 1,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 420.dp, max = 620.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF101114))
                .padding(12.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .horizontalScroll(terminalHorizontalScroll)
                    .verticalScroll(terminalScroll),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (logs.isEmpty()) {
                    Text(
                        uiText(R.string.mini_server_empty_log_placeholder),
                        color = Color(0xFF8BE9FD),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium,
                    )
                } else {
                    logs.forEach { log ->
                        MiniServerTerminalLine(log)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniServerTerminalLine(log: JSONObject) {
    val level = log.optString("level", "info")
    val color = when (level.lowercase(Locale.US)) {
        "error" -> Color(0xFFFF6B6B)
        "warn" -> Color(0xFFFFC857)
        else -> Color(0xFF7BD88F)
    }
    val time = remember(log.optLong("timestamp")) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.optLong("timestamp")))
    }
    val status = log.optInt("status").takeIf { it > 0 }?.toString().orEmpty()
    val method = log.optString("method")
    val path = log.optString("path")
    val durationMs = log.optLong("durationMs")
    val message = log.optString("message")
    Row(verticalAlignment = Alignment.Top) {
        Text(
            "$time ",
            color = Color(0xFF8D99AE),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
        Text(
            level.uppercase(Locale.US).padEnd(5),
            color = color,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            buildString {
                if (method.isNotBlank()) append(method).append(' ')
                if (status.isNotBlank()) append(status).append(' ')
                append(path.ifBlank { "-" })
                append(" (").append(durationMs).append("ms)")
                if (message.isNotBlank()) append(" - ").append(message)
            },
            color = Color(0xFFE8EAED),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}
