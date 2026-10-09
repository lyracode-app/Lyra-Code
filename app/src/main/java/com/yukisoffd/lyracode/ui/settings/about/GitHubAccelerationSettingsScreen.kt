package com.yukisoffd.lyracode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
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
    val focusManager = LocalFocusManager.current
    var config by remember { mutableStateOf(store.load()) }
    var editing by remember { mutableStateOf<GitHubAccelerator?>(null) }
    var testUrl by remember { mutableStateOf(initialTestUrl.takeIf(::isGitHubDownloadUrl) ?: GITHUB_CONNECTIVITY_TEST_URL) }
    var results by remember { mutableStateOf(emptyMap<String, GitHubConnectionResult>()) }
    var testing by remember { mutableStateOf(emptySet<String>()) }
    var editingTestUrl by remember { mutableStateOf(false) }
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
        item { KimiSectionLabel(uiText(R.string.github_acceleration_automatic_section)) }
        item {
            KimiCardBox {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GitHubSettingsIcon(Icons.Default.Sync)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(uiText(R.string.github_acceleration_enable), style = MaterialTheme.typography.titleSmall)
                        Text(uiText(R.string.github_acceleration_description), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = config.enabled,
                        onCheckedChange = { save(config.copy(enabled = it)) },
                        modifier = Modifier.semantics { contentDescription = uiText(R.string.github_acceleration_enable) },
                    )
                }
            }
        }
        item { KimiSectionLabel(uiText(R.string.github_acceleration_test_section)) }
        item {
            KimiCardBox {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GitHubSettingsIcon(Icons.Default.Link)
                    Text(uiText(R.string.github_acceleration_test_url), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = {
                        focusManager.clearFocus()
                        editingTestUrl = !editingTestUrl
                    }, enabled = testing.isEmpty()) {
                        Icon(Icons.Default.Edit, contentDescription = uiText(R.string.github_acceleration_edit_test_url))
                    }
                }
                if (editingTestUrl) {
                    OutlinedTextField(
                        value = testUrl,
                        onValueChange = { testUrl = it; results = emptyMap() },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(uiText(R.string.github_acceleration_test_url)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        enabled = testing.isEmpty(),
                        isError = !validTestUrl,
                    )
                } else {
                    SelectionContainer {
                        Text(testUrl, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                GitHubDownloadTestActions(validTestUrl, "direct" in testing) { focusManager.clearFocus(); test("direct") }
                GitHubConnectionStatus(results["direct"], "direct" in testing)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(uiText(R.string.github_acceleration_routes_section), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { focusManager.clearFocus(); editing = GitHubAccelerator(name = "", prefix = "") }, enabled = testing.isEmpty(), modifier = Modifier.semantics { contentDescription = uiText(R.string.github_acceleration_add) }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(uiText(R.string.action_add), Modifier.padding(start = 4.dp))
                }
            }
        }
        item { GitHubRouteLabel(uiText(R.string.github_acceleration_default_section)) }
        item {
            GitHubRouteCard(
                name = uiText(R.string.github_acceleration_direct),
                address = "github.com",
                enabled = true,
                result = results["direct"],
                testing = "direct" in testing,
                canTest = validTestUrl,
                onTest = { focusManager.clearFocus(); test("direct") },
            )
        }
        item { GitHubRouteLabel(uiText(R.string.github_acceleration_routes_section)) }
        items(config.links, key = { it.id }) { link ->
            GitHubRouteCard(
                name = link.name,
                address = link.prefix.removePrefix("https://").trimEnd('/'),
                enabled = link.enabled,
                result = results[link.id],
                testing = link.id in testing,
                canTest = validTestUrl,
                onTest = { focusManager.clearFocus(); test(link.id, link.prefix) },
                moreActions = {
                    GitHubRouteMenu(
                        link = link,
                        enabled = testing.isEmpty(),
                        onToggle = { save(config.copy(links = config.links.map { if (it.id == link.id) it.copy(enabled = !it.enabled) else it })) },
                        onEdit = { editing = link },
                        onDelete = { save(config.copy(links = config.links.filterNot { it.id == link.id })) },
                    )
                },
            )
        }
        if (config.links.isEmpty()) {
            item { KimiCardBox { Text(uiText(R.string.github_acceleration_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) } }
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
private fun GitHubDownloadTestActions(validUrl: Boolean, testing: Boolean, onTest: () -> Unit) {
    val hint: @Composable (Modifier) -> Unit = { modifier ->
        Text(
            uiText(if (validUrl) R.string.github_acceleration_test_hint else R.string.github_acceleration_invalid_test_url),
            modifier = modifier,
            color = if (validUrl) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    val button: @Composable () -> Unit = {
        Button(onClick = onTest, enabled = validUrl && !testing, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
            Text(uiText(R.string.action_test_connection))
        }
    }
    BoxWithConstraints {
        if (maxWidth >= 280.dp && LocalDensity.current.fontScale <= 1.1f) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                hint(Modifier.weight(1f))
                button()
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                hint(Modifier)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { button() }
            }
        }
    }
}

@Composable
private fun GitHubSettingsIcon(icon: ImageVector) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun GitHubRouteLabel(text: String) {
    Text(text, Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun GitHubRouteCard(
    name: String,
    address: String,
    enabled: Boolean,
    result: GitHubConnectionResult?,
    testing: Boolean,
    canTest: Boolean,
    onTest: () -> Unit,
    moreActions: (@Composable () -> Unit)? = null,
) {
    KimiCardBox {
        BoxWithConstraints {
            val inlineActions = maxWidth >= 280.dp && LocalDensity.current.fontScale <= 1.1f
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        .semantics { stateDescription = uiText(if (enabled) R.string.skill_status_enabled else R.string.skill_status_disabled) })
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(address, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!enabled) Text(uiText(R.string.skill_status_disabled), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                    if (inlineActions) GitHubRouteTestButton(name, canTest && !testing, onTest)
                    moreActions?.invoke()
                }
                if (!inlineActions) GitHubRouteTestButton(name, canTest && !testing, onTest, Modifier.align(Alignment.End))
            }
        }
        GitHubConnectionStatus(result, testing)
    }
}

@Composable
private fun GitHubRouteTestButton(name: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics { contentDescription = "${uiText(R.string.action_test_connection)} · $name" },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) { Text(uiText(R.string.action_test_connection)) }
}

@Composable
private fun GitHubRouteMenu(link: GitHubAccelerator, enabled: Boolean, onToggle: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(Icons.Default.MoreVert, contentDescription = "${uiText(R.string.action_more)} · ${link.name}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(uiText(if (link.enabled) R.string.action_disable else R.string.action_enable)) }, onClick = { expanded = false; onToggle() })
            DropdownMenuItem(text = { Text(uiText(R.string.github_acceleration_edit)) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { expanded = false; onEdit() })
            DropdownMenuItem(text = { Text(uiText(R.string.action_delete)) }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { expanded = false; onDelete() })
        }
    }
}

@Composable
private fun GitHubConnectionStatus(result: GitHubConnectionResult?, testing: Boolean) {
    when {
        testing -> Text(uiText(R.string.github_acceleration_testing), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
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
