package com.yukisoffd.lyracode

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yukisoffd.lyracode.ai.ModelRequestPreviews
import com.yukisoffd.lyracode.data.ApiProfile
import com.yukisoffd.lyracode.data.ModelRequestCustomization
import org.json.JSONObject
import org.json.JSONTokener

private data class RequestField(val original: String?, val name: String, val value: String, val changed: Boolean = false, val id: String = java.util.UUID.randomUUID().toString(), val removing: Boolean = false)

/** Edits deltas rather than freezing the live history/tool fields shown in the preview. */
@Composable
internal fun ModelRequestSettings(profile: ApiProfile, onSave: (String, ModelRequestCustomization?) -> Unit) {
    val models = profile.enabledModels.distinct()
    if (models.isEmpty()) { Text(uiText(R.string.request_no_models)); return }
    var selected by remember(profile.id) { mutableStateOf(profile.selectedModel.takeIf { it in models } ?: models.first()) }
    var menu by remember { mutableStateOf(false) }
    var status by remember(selected) { mutableStateOf("") }
    var dirty by remember(selected) { mutableStateOf(false) }
    var saved by remember(profile.id, selected) { mutableStateOf(profile.modelRequestOverrides[selected] ?: ModelRequestCustomization()) }
    var revision by remember(selected) { mutableIntStateOf(0) }
    val preview = remember(profile.id, selected, revision) { ModelRequestPreviews.get(profile, selected) }
    val headers = remember(selected, revision) {
        val combined = preview.headers.toMutableMap()
        saved.removedHeaders.forEach { name -> combined.keys.filter { it.equals(name, true) }.forEach(combined::remove) }
        saved.headers.forEach { (name, value) ->
            combined.keys.filter { it.equals(name, true) }.forEach(combined::remove)
            combined[name] = value
        }
        mutableStateListOf<RequestField>().apply { combined.forEach { (name, value) -> add(RequestField(name, name, value, saved.headers.keys.any { it.equals(name, true) })) } }
    }
    val body = remember(selected, revision) {
        val combined = JSONObject(preview.body)
        saved.removedBodyKeys.forEach(combined::remove)
        val custom = JSONObject(saved.body)
        custom.keys().forEach { combined.put(it, custom.get(it)) }
        mutableStateListOf<RequestField>().apply {
            combined.keys().forEach { name ->
                val value = combined.get(name)
                val text = when (value) { is String -> JSONObject.quote(value); is JSONObject -> value.toString(2); is org.json.JSONArray -> value.toString(2); else -> value.toString() }
                add(RequestField(name, name, text, custom.has(name)))
            }
        }
    }
    var removedHeaders by remember(selected, revision) { mutableStateOf(saved.removedHeaders) }
    var removedBody by remember(selected, revision) { mutableStateOf(saved.removedBodyKeys) }
    var replay by remember(selected, revision) { mutableStateOf(saved.replayThinking) }
    fun save(): Boolean = try {
        val activeHeaders = headers.filterNot { it.removing }
        val activeBody = body.filterNot { it.removing }
        require(activeHeaders.all { it.name.isNotBlank() } && activeHeaders.map { it.name.trim().lowercase() }.distinct().size == activeHeaders.size)
        require(activeBody.all { it.name.isNotBlank() } && activeBody.map { it.name.trim() }.distinct().size == activeBody.size)
        val headerValues = activeHeaders.filter { it.changed }.associate { field ->
            okhttp3.Headers.Builder().add(field.name.trim(), field.value).build()
            field.name.trim() to field.value
        }
        val bodyValues = JSONObject()
        activeBody.filter { it.changed }.forEach { field ->
            require(field.value.isNotBlank())
            val parser = JSONTokener(field.value)
            val value = parser.nextValue()
            require(parser.nextClean() == '\u0000')
            bodyValues.put(field.name.trim(), value)
        }
        val deletedHeaders = removedHeaders + activeHeaders.filter { it.original != null && !it.original.equals(it.name.trim(), true) }.mapNotNull { it.original }
        val deletedBody = removedBody + activeBody.filter { it.original != null && it.original != it.name.trim() }.mapNotNull { it.original }
        val result = ModelRequestCustomization(headerValues, deletedHeaders, bodyValues.toString(), deletedBody, replay)
        onSave(selected, result)
        saved = result; dirty = false; status = uiText(R.string.request_saved)
        true
    } catch (_: Exception) { status = uiText(R.string.request_invalid); false }
    Column(Modifier.fillMaxSize()) {
        Box {
            OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) { Text("${uiText(R.string.request_model)} · $selected") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                models.forEach { model -> DropdownMenuItem(text = { Text(model) }, onClick = {
                    menu = false
                    if (dirty) status = uiText(R.string.request_unsaved) else selected = model
                }) }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(uiText(R.string.request_preview_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(uiText(R.string.request_replay_thinking), Modifier.weight(1f))
                Switch(checked = replay, onCheckedChange = { replay = it; dirty = true })
            }
            TextButton(onClick = {
                onSave(selected, null); saved = ModelRequestCustomization(); revision++; dirty = false; status = uiText(R.string.request_saved)
            }) { Text(uiText(R.string.request_restore)) }
            Text("Headers", style = MaterialTheme.typography.titleMedium)
            Text(uiText(R.string.request_header_hint), style = MaterialTheme.typography.bodySmall)
            headers.toList().forEach { field ->
                key(field.id) {
                    RequestFieldEditor(field, json = false, onChange = { updated ->
                        val index = headers.indexOfFirst { it.id == field.id }
                        if (index >= 0) { headers[index] = updated; dirty = true }
                    }, onDelete = {
                        removedHeaders = removedHeaders + listOfNotNull(field.original ?: field.name.takeIf(String::isNotBlank))
                        val index = headers.indexOfFirst { it.id == field.id }
                        if (index >= 0) { headers[index] = field.copy(removing = true); dirty = true }
                    }, onRemoved = { headers.removeAll { it.id == field.id } })
                }
            }
            OutlinedButton(onClick = { headers.add(RequestField(null, "", "", true)); dirty = true }) { Text(uiText(R.string.request_add_header)) }
            Text("Body", style = MaterialTheme.typography.titleMedium)
            body.toList().forEach { field ->
                key(field.id) {
                    RequestFieldEditor(field, json = true, onChange = { updated ->
                        val index = body.indexOfFirst { it.id == field.id }
                        if (index >= 0) { body[index] = updated; dirty = true }
                    }, onDelete = {
                        removedBody = removedBody + listOfNotNull(field.original ?: field.name.takeIf(String::isNotBlank))
                        val index = body.indexOfFirst { it.id == field.id }
                        if (index >= 0) { body[index] = field.copy(removing = true); dirty = true }
                    }, onRemoved = { body.removeAll { it.id == field.id } })
                }
            }
            OutlinedButton(onClick = { body.add(RequestField(null, "", "null", true)); dirty = true }) { Text(uiText(R.string.request_add_body)) }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text(uiText(R.string.request_save)) }
    }
}

@Composable
private fun RequestFieldEditor(field: RequestField, json: Boolean, onChange: (RequestField) -> Unit, onDelete: () -> Unit, onRemoved: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    val visibility = remember { MutableTransitionState(true) }
    visibility.targetState = !field.removing
    LaunchedEffect(visibility.isIdle, visibility.currentState) {
        if (visibility.isIdle && !visibility.currentState) onRemoved()
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
        title = { Text(uiText(R.string.request_delete_title)) },
        text = { Text(uiText(R.string.request_delete_message, if (json) "Body" else "Header", field.name.ifBlank { "—" })) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(uiText(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(uiText(R.string.action_cancel)) } },
    )
    AnimatedVisibility(visibleState = visibility, enter = EnterTransition.None,
        exit = fadeOut(tween(180)) + shrinkVertically(tween(260))) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = field.name, onValueChange = { onChange(field.copy(name = it, changed = true)) },
                    label = { Text(uiText(R.string.request_field_name)) }, singleLine = true, modifier = Modifier.weight(1f))
                IconButton(onClick = { confirmDelete = true }, enabled = !field.removing) {
                    Icon(Icons.Outlined.DeleteOutline,
                        contentDescription = uiText(R.string.request_delete_field, field.name.ifBlank { "—" }),
                        tint = MaterialTheme.colorScheme.error)
                }
            }
            OutlinedTextField(value = field.value, onValueChange = { onChange(field.copy(value = it, changed = true)) },
                label = { Text(uiText(if (json) R.string.request_json_value else R.string.request_field_value)) },
                minLines = 1, maxLines = 8, modifier = Modifier.fillMaxWidth())
        }
    }
}
}
