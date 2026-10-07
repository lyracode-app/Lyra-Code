package com.yukisoffd.lyracode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yukisoffd.lyracode.data.GITHUB_CONNECTIVITY_TEST_URL
import com.yukisoffd.lyracode.data.GitHubAccelerationConfig
import com.yukisoffd.lyracode.data.GitHubAccelerationSettings
import com.yukisoffd.lyracode.data.GitHubAccelerator
import com.yukisoffd.lyracode.data.GitHubConnectionResult
import com.yukisoffd.lyracode.data.isGitHubDownloadUrl
import com.yukisoffd.lyracode.data.normalizeGitHubAcceleratorPrefix
import com.yukisoffd.lyracode.data.probeGitHubConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun GitHubAccelerationSettingsScreen(
    initialTestUrl: String = GITHUB_CONNECTIVITY_TEST_URL,
    testConnection: (String) -> GitHubConnectionResult = { probeGitHubConnection(it) },
) {
    val context = LocalContext.current
    val store = remember(context) { GitHubAccelerationSettings(context) }
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(store.load()) }
    var editing by remember { mutableStateOf<GitHubAccelerator?>(null) }
    var testUrl by remember { mutableStateOf(initialTestUrl.takeIf(::isGitHubDownloadUrl) ?: GITHUB_CONNECTIVITY_TEST_URL) }
    var results by remember { mutableStateOf(emptyMap<String, GitHubConnectionResult>()) }
    var testing by remember { mutableStateOf(emptySet<String>()) }
    val validTestUrl = isGitHubDownloadUrl(testUrl.trim())

    fun save(next: GitHubAccelerationConfig) {
        store.save(next)
        config = next
        results = emptyMap()
    }

    fun test(id: String, prefix: String = "") {
        if (!validTestUrl || id in testing) return
        val url = prefix + testUrl.trim()
        testing = testing + id
        results = results - id
        scope.launch {
            val result = withContext(Dispatchers.IO) { testConnection(url) }
            results = results + (id to result)
            testing = testing - id
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            KimiCardBox {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(uiText(R.string.github_acceleration_enable), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Switch(
                        checked = config.enabled,
                        onCheckedChange = { save(config.copy(enabled = it)) },
                        modifier = Modifier.semantics { contentDescription = uiText(R.string.github_acceleration_enable) },
                    )
                }
                Text(uiText(R.string.github_acceleration_description), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            OutlinedTextField(
                value = testUrl,
                onValueChange = { testUrl = it; results = emptyMap() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(uiText(R.string.github_acceleration_test_url)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                enabled = testing.isEmpty(),
                isError = !validTestUrl,
                supportingText = { Text(uiText(if (validTestUrl) R.string.github_acceleration_test_hint else R.string.github_acceleration_invalid_test_url)) },
            )
        }
        item {
            KimiCardBox {
                Text(uiText(R.string.github_acceleration_direct), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { test("direct") }, enabled = validTestUrl && "direct" !in testing) {
                    Text(uiText(R.string.action_test_connection))
                }
                GitHubConnectionStatus(results["direct"], "direct" in testing)
            }
        }
        items(config.links, key = { it.id }) { link ->
            KimiCardBox {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(link.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Switch(
                        checked = link.enabled,
                        onCheckedChange = { enabled ->
                            save(config.copy(links = config.links.map { if (it.id == link.id) it.copy(enabled = enabled) else it }))
                        },
                        modifier = Modifier.semantics { contentDescription = link.name },
                    )
                }
                SelectionContainer { Text(link.prefix, color = KimiMuted, style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { test(link.id, link.prefix) }, enabled = validTestUrl && link.id !in testing, modifier = Modifier.weight(1f)) {
                        Text(uiText(R.string.action_test_connection))
                    }
                    IconButton(onClick = { editing = link }, enabled = testing.isEmpty()) {
                        Icon(Icons.Default.Edit, contentDescription = uiText(R.string.github_acceleration_edit))
                    }
                    IconButton(onClick = { save(config.copy(links = config.links.filterNot { it.id == link.id })) }, enabled = testing.isEmpty()) {
                        Icon(Icons.Default.Delete, contentDescription = uiText(R.string.action_delete))
                    }
                }
                GitHubConnectionStatus(results[link.id], link.id in testing)
            }
        }
        item {
            if (config.links.isEmpty()) Text(uiText(R.string.github_acceleration_empty), color = KimiMuted)
            Button(onClick = { editing = GitHubAccelerator(name = "", prefix = "") }, modifier = Modifier.fillMaxWidth()) {
                Text(uiText(R.string.github_acceleration_add))
            }
        }
    }

    editing?.let { link ->
        GitHubAcceleratorEditor(
            link = link,
            others = config.links.filterNot { it.id == link.id },
            onDismiss = { editing = null },
            onSave = { changed ->
                val exists = config.links.any { it.id == changed.id }
                save(config.copy(links = if (exists) config.links.map { if (it.id == changed.id) changed else it } else config.links + changed))
                editing = null
            },
        )
    }
}

@Composable
private fun GitHubConnectionStatus(result: GitHubConnectionResult?, testing: Boolean) {
    when {
        testing -> Text(uiText(R.string.github_acceleration_testing), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        result != null -> Text(
            if (result.successful) uiText(R.string.github_acceleration_test_success, result.elapsedMs, result.httpStatus ?: 200)
            else uiText(R.string.github_acceleration_test_failed, result.elapsedMs, result.error),
            color = if (result.successful) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun GitHubAcceleratorEditor(
    link: GitHubAccelerator,
    others: List<GitHubAccelerator>,
    onDismiss: () -> Unit,
    onSave: (GitHubAccelerator) -> Unit,
) {
    var name by remember(link.id) { mutableStateOf(link.name) }
    var prefix by remember(link.id) { mutableStateOf(link.prefix) }
    val normalized = normalizeGitHubAcceleratorPrefix(prefix)
    val duplicate = normalized != null && others.any { normalizeGitHubAcceleratorPrefix(it.prefix) == normalized }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(uiText(R.string.github_acceleration_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(uiText(R.string.github_acceleration_name)) }, singleLine = true)
                OutlinedTextField(
                    value = prefix, onValueChange = { prefix = it },
                    label = { Text(uiText(R.string.github_acceleration_prefix)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    isError = prefix.isNotBlank() && (normalized == null || duplicate),
                    supportingText = { Text(uiText(when {
                        duplicate -> R.string.github_acceleration_duplicate
                        prefix.isNotBlank() && normalized == null -> R.string.github_acceleration_invalid_prefix
                        else -> R.string.github_acceleration_prefix_hint
                    })) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(link.copy(name = name.trim(), prefix = normalized!!)) }, enabled = name.isNotBlank() && normalized != null && !duplicate) {
                Text(uiText(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiText(R.string.action_cancel)) } },
    )
}
