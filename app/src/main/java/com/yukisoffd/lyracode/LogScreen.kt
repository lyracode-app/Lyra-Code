package com.yukisoffd.lyracode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yukisoffd.lyracode.data.AuditEntry
import com.yukisoffd.lyracode.data.AuditLogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LogScreen(auditLogStore: AuditLogStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var logs by remember { mutableStateOf(emptyList<AuditEntry>()) }
    var kinds by remember { mutableStateOf(emptyList<String>()) }
    var kind by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var limit by remember { mutableIntStateOf(100) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedLog by remember { mutableStateOf<AuditEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(kind, query) { limit = 100 }
    LaunchedEffect(refresh, kind, query, limit) {
        loading = true
        delay(200)
        try {
            val result = withContext(Dispatchers.IO) { auditLogStore.recent(limit, kind, query) to auditLogStore.kinds() }
            logs = result.first
            kinds = result.second
            error = null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message
        } finally { loading = false }
    }
    selectedLog?.let { entry ->
        LogDetailDialog(entry, auditLogStore, { selectedLog = null }, { deleteTarget = entry.id })
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(context.getString(if (target == 0L) R.string.cd_clear_log else R.string.action_delete)) },
            text = { Text(context.getString(if (target == 0L) R.string.log_confirm_clear else R.string.log_confirm_delete)) },
            confirmButton = { TextButton(onClick = {
                deleteTarget = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { if (target == 0L) auditLogStore.clear() else auditLogStore.delete(target) }
                        if (target == 0L || selectedLog?.id == target) selectedLog = null
                        refresh++
                    } catch (e: Exception) { error = e.message }
                }
            }) { Text(context.getString(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(context.getString(R.string.action_cancel)) } },
        )
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(context.getString(R.string.title_audit_log), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { refresh++ }) { Icon(Icons.Default.Refresh, context.getString(R.string.cd_refresh_log)) }
            IconButton(onClick = { deleteTarget = 0L }) { Icon(Icons.Default.DeleteSweep, context.getString(R.string.cd_clear_log)) }
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(context.getString(R.string.log_search)) }, leadingIcon = { Icon(Icons.Default.Search, null) })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(kind == null, { kind = null }, { Text(context.getString(R.string.log_filter_all)) })
            (listOf("command", "proot", "model_http") + kinds).distinct().forEach { value ->
                val label = when (value) {
                    "command" -> "Termux"
                    "proot" -> "PRoot"
                    "model_http" -> "HTTP"
                    else -> value
                }
                FilterChip(kind == value, { kind = value }, { Text(label) })
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (logs.isEmpty() && !loading) Text(context.getString(R.string.notice_no_log))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(logs, key = { it.id }) { entry -> LogCard(entry) { selectedLog = entry } }
            if (logs.size >= limit) item { TextButton(onClick = { limit += 100 }) { Text(context.getString(R.string.log_load_more)) } }
        }
    }
}

@Composable
internal fun LogCard(entry: AuditEntry, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${entry.kind} · ${formatTime(entry.createdAt)}", style = MaterialTheme.typography.labelMedium)
            Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(entry.detail, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
