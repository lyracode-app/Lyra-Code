package com.yukisoffd.lyracode

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.yukisoffd.lyracode.data.AppUpdateInfo
import com.yukisoffd.lyracode.data.DownloadedUpdate
import com.yukisoffd.lyracode.data.GitHubDownloadTarget
import com.yukisoffd.lyracode.data.UpdateDownloadProgress
import com.yukisoffd.lyracode.data.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun rememberUpdateDownload(manager: UpdateManager): UpdateDownload {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val awaitingPermission = rememberSaveable { mutableStateOf<String?>(null) }
    val download = remember(context, manager, scope) { UpdateDownload(context, manager, scope, awaitingPermission) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        download.resumeInstall()
    }
    download.requestPermission = { permission.launch(manager.installPermissionIntent()) }
    return download
}

internal class UpdateDownload(
    private val context: Context,
    private val manager: UpdateManager,
    private val scope: CoroutineScope,
    private val permissionPath: MutableState<String?>,
) {
    var pending by mutableStateOf<DownloadedUpdate?>(null)
        private set
    var pendingLoaded by mutableStateOf(false)
        private set
    var downloading by mutableStateOf(false)
        private set
    var installing by mutableStateOf(false)
        private set
    var progress by mutableStateOf<UpdateDownloadProgress?>(null)
        private set
    var message by mutableStateOf("")
        private set
    var requestPermission: () -> Unit = {}
    private var awaitingPermission by permissionPath

    val busy: Boolean get() = downloading || installing || awaitingPermission != null

    suspend fun refreshPending() {
        withContext(Dispatchers.IO) { manager.pendingDownload() }.also { pending = it }
        pendingLoaded = true
    }

    fun clearStatus() {
        progress = null
        message = ""
    }

    fun routes(info: AppUpdateInfo): List<List<GitHubDownloadTarget>> = manager.downloadRoutes(info)

    fun download(info: AppUpdateInfo, route: List<GitHubDownloadTarget>) {
        if (busy) return
        downloading = true
        message = ""
        progress = UpdateDownloadProgress(status = uiText(R.string.notice_preparing_download))
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val job = currentCoroutineContext()
                    manager.downloadApk(info, route) {
                        job.ensureActive()
                        progress = it
                    }
                }
                refreshPending()
                result.onFailure { message = it.message.orEmpty().ifBlank { uiText(R.string.notice_download_failed) } }
            } finally {
                downloading = false
                progress = null
            }
        }
    }

    fun install(file: File) {
        if (!busy) verifyAndInstall(file)
    }

    fun resumeInstall() {
        val path = awaitingPermission ?: return
        awaitingPermission = null
        if (manager.needsInstallPermission()) {
            message = uiText(R.string.notice_auth_pending_install_later)
        } else {
            verifyAndInstall(File(path))
        }
    }

    private fun verifyAndInstall(file: File) {
        installing = true
        message = uiText(R.string.update_apk_verifying)
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { manager.verifyPendingApk(file) }
                refreshPending()
                result.fold(
                    onSuccess = { apk ->
                        message = ""
                        runCatching {
                            if (manager.needsInstallPermission()) {
                                awaitingPermission = apk.absolutePath
                                requestPermission()
                            } else {
                                context.startActivity(manager.installIntent(apk))
                            }
                        }.onFailure {
                            awaitingPermission = null
                            message = it.message.orEmpty().ifBlank { uiText(R.string.notice_cannot_open_installer) }
                        }
                    },
                    onFailure = { message = it.message.orEmpty().ifBlank { uiText(R.string.update_apk_invalid) } },
                )
            } finally {
                installing = false
            }
        }
    }
}

@Composable
internal fun UpdateDialog(
    info: AppUpdateInfo,
    download: UpdateDownload,
    onDismiss: () -> Unit,
    onOpenGitHubAcceleration: (String) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val notesScroll = rememberScrollState()
    var sourceMenu by remember(info) { mutableStateOf(false) }
    val saved = download.pending?.takeIf { it.matches(info) }
    val webUrl = info.webUrl.ifBlank { info.apkUrl }
    LaunchedEffect(info, download) { download.refreshPending() }
    AlertDialog(
        onDismissRequest = { if (!download.busy) onDismiss() },
        icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
        title = { Text(uiText(R.string.title_new_version, info.versionName.ifBlank { info.versionCode.toString() })) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(notesScroll),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(onClick = { onOpenGitHubAcceleration(info.apkUrl) }, enabled = !download.busy) {
                        Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(uiText(R.string.github_acceleration_title))
                    }
                    if (info.mandatory) {
                        Text(uiText(R.string.notice_mandatory_update), color = MaterialTheme.colorScheme.error)
                    }
                    RichMarkdownContent(markdown = info.releaseNotes, style = MaterialTheme.typography.bodyMedium)
                    if (info.apkSha256.isNotBlank()) {
                        Text(
                            "SHA-256: ${info.apkSha256}",
                            color = KimiMuted,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
                if (saved != null || download.progress != null || download.message.isNotBlank()) {
                    UpdateDownloadStatus(saved, download.progress, download.message)
                }
            }
        },
        confirmButton = {
            Box {
                Button(
                    onClick = {
                        if (saved != null) download.install(saved.file)
                        else {
                            val routes = download.routes(info)
                            if (routes.size == 1) download.download(info, routes.single())
                            else sourceMenu = true
                        }
                    },
                    enabled = download.pendingLoaded && !download.busy &&
                        (saved != null || info.apkUrl.isNotBlank()),
                ) {
                    Icon(
                        if (saved == null) Icons.Default.Download else Icons.Default.InstallMobile,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(uiText(when {
                        download.downloading -> R.string.action_downloading
                        saved != null -> R.string.update_install_now
                        else -> R.string.action_download_in_app
                    }))
                }
                if (sourceMenu) {
                    DropdownMenu(expanded = true, onDismissRequest = { sourceMenu = false }) {
                        download.routes(info).forEach { route ->
                            val name = when {
                                route.size > 1 -> uiText(R.string.github_acceleration_enable)
                                route.single().acceleratorName != null -> uiText(
                                    R.string.update_download_accelerated, route.single().acceleratorName.orEmpty(),
                                )
                                else -> uiText(R.string.update_download_original)
                            }
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    sourceMenu = false
                                    download.download(info, route)
                                },
                            )
                        }
                    }
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (webUrl.isNotBlank()) {
                    TextButton(onClick = { runCatching { uriHandler.openUri(webUrl) } }) {
                        Text(uiText(R.string.action_download_web))
                    }
                }
                TextButton(onClick = onDismiss, enabled = !download.busy) { Text(uiText(R.string.action_later)) }
            }
        },
    )
}

@Composable
internal fun UpdateDownloadStatus(saved: DownloadedUpdate?, progress: UpdateDownloadProgress?, message: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (saved != null) {
            SelectionContainer {
                Text(
                    uiText(R.string.update_apk_saved_directory, saved.file.parent.orEmpty()),
                    color = KimiMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else if (progress != null) {
            if (progress.totalBytes > 0) {
                LinearProgressIndicator(progress = { progress.percent }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            val total = if (progress.totalBytes > 0) " / ${formatBytes(progress.totalBytes)}" else ""
            Text(
                "${progress.status} ${formatBytes(progress.downloadedBytes)}$total",
                color = KimiMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (message.isNotBlank()) {
            Text(message, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
