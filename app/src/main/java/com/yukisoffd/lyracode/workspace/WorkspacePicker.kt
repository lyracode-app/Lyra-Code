package com.yukisoffd.lyracode.workspace

import com.yukisoffd.lyracode.R
import com.yukisoffd.lyracode.uiText
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yukisoffd.lyracode.debian.ProotLinuxManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared storage uses SAF; Linux directories are browsed by the owning app itself. */
@Composable
internal fun rememberWorkspacePicker(onDismiss: () -> Unit = {}, onSelected: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    val selected by rememberUpdatedState(onSelected)
    val dismiss by rememberUpdatedState(onDismiss)
    var stage by rememberSaveable { mutableIntStateOf(0) }
    var linuxId by rememberSaveable { mutableStateOf("") }
    var path by rememberSaveable { mutableStateOf("/") }
    fun select(uri: Uri) {
        runCatching { selected(uri) }.onSuccess { stage = 0 }.onFailure {
            Toast.makeText(context, it.message.orEmpty(), Toast.LENGTH_LONG).show()
        }
    }
    val sharedPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        if (it != null) select(it) else dismiss()
    }
    if (stage > 0) {
        val manager = remember(context) { ProotLinuxManager.getInstance(context) }
        val state by manager.state.collectAsState()
        var directories by remember { mutableStateOf<List<String>>(emptyList()) }
        var error by remember { mutableStateOf<String?>(null) }
        var loading by remember { mutableStateOf(false) }
        LaunchedEffect(stage, linuxId, path, state.instances) {
            if (stage != 3) return@LaunchedEffect
            loading = true
            error = null
            directories = emptyList()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = ProotWorkspace.directory(context, ProotWorkspace.uri(linuxId, path))
                    val children = dir.listFiles() ?: error(uiText(R.string.workspace_error_read_directory))
                    children.filter { child ->
                        runCatching {
                            ProotWorkspace.directory(context, ProotWorkspace.uri(linuxId, path.trimEnd('/') + "/" + child.name))
                        }.isSuccess
                    }.map { it.name }.sortedBy { it.lowercase() }
                }
            }
            result.onSuccess { directories = it }.onFailure { error = it.message }
            loading = false
        }
        AlertDialog(
            onDismissRequest = { stage = 0; dismiss() },
            title = { Text(when (stage) { 1 -> uiText(R.string.workspace_picker_location); 2 -> uiText(R.string.workspace_picker_linux); else -> uiText(R.string.workspace_picker_directory) }) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    when (stage) {
                        1 -> {
                            TextButton(onClick = { stage = 0; sharedPicker.launch(null) }) { Text(uiText(R.string.workspace_picker_shared)) }
                            TextButton(onClick = { stage = 2 }) { Text(uiText(R.string.workspace_picker_proot)) }
                        }
                        2 -> {
                            if (state.instances.isEmpty()) Text(uiText(R.string.workspace_picker_no_linux))
                            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                                items(state.instances, key = { it.id }) { instance ->
                                    Column(Modifier.fillMaxWidth().clickable {
                                        linuxId = instance.id; path = "/"; stage = 3
                                    }.padding(vertical = 12.dp)) {
                                        Text(instance.name)
                                        Text(uiText(R.string.workspace_picker_linux_id, instance.id), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        3 -> {
                            Text("$linuxId:$path", style = MaterialTheme.typography.bodySmall)
                            if (path != "/") TextButton(onClick = {
                                path = path.trimEnd('/').substringBeforeLast('/').ifBlank { "/" }
                            }) { Text(uiText(R.string.workspace_picker_parent)) }
                            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (!loading && error == null && directories.isEmpty()) Text(uiText(R.string.workspace_picker_empty))
                            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                                items(directories) { name ->
                                    Text(name, Modifier.fillMaxWidth().clickable {
                                        path = path.trimEnd('/') + "/" + name
                                    }.padding(vertical = 12.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (stage == 3) TextButton(enabled = !loading && error == null, onClick = {
                    select(ProotWorkspace.uri(linuxId, path))
                }) { Text(uiText(R.string.workspace_picker_select)) }
            },
            dismissButton = {
                Row {
                    if (stage > 1) TextButton(onClick = { stage-- }) { Text(uiText(R.string.cd_back)) }
                    TextButton(onClick = { stage = 0; dismiss() }) { Text(uiText(R.string.action_cancel)) }
                }
            },
        )
    }
    return { stage = 1 }
}
