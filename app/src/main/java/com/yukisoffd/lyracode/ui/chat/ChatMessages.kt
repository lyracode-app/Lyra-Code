package com.yukisoffd.lyracode

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.yukisoffd.lyracode.ai.ChatRecord
import com.yukisoffd.lyracode.ai.MEDIA_MESSAGE_ROLE
import com.yukisoffd.lyracode.ai.TodoItem
import com.yukisoffd.lyracode.data.AppSettings
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.min
import kotlin.math.max


internal fun isInternalProcessMessage(message: ChatRecord): Boolean {
    return message.role == "tool" || (message.role == "assistant" && message.content.isBlank())
}

internal data class ChatRenderItem(
    val key: String,
    val message: ChatRecord? = null,
    val process: List<ChatRecord> = emptyList(),
    val processStartedAt: Long? = null,
    val processFinishedAt: Long? = null,
)

private fun sourceMessageId(id: Long): Long = if (id < 0L) -id else id

internal fun chatRenderItems(
    messages: List<ChatRecord>,
    isStreaming: Boolean = false,
    collapseStreamingProse: Boolean = false,
): List<ChatRenderItem> {
    val result = mutableListOf<ChatRenderItem>()
    val assistantTurn = mutableListOf<ChatRecord>()

    fun flushAssistantTurn(streamTurn: Boolean) {
        if (assistantTurn.isEmpty()) return
        val turn = assistantTurn.toList()
        assistantTurn.clear()
        if (streamTurn && collapseStreamingProse) {
            result += ChatRenderItem(key = "process-${turn.first().id}", process = turn,
                processStartedAt = turn.minOfOrNull { it.createdAt }, processFinishedAt = turn.maxOfOrNull { it.createdAt })
            return
        }
        if (streamTurn) {
            val pendingProcess = mutableListOf<ChatRecord>()

            fun flushPendingProcess() {
                if (pendingProcess.isEmpty()) return
                val process = pendingProcess.toList()
                pendingProcess.clear()
                val firstSourceId = sourceMessageId(process.first().id)
                result += ChatRenderItem(
                    key = "process-$firstSourceId",
                    process = process,
                    processStartedAt = process.minOfOrNull { it.createdAt },
                    processFinishedAt = process.maxOfOrNull { it.createdAt },
                )
            }

            turn.forEach { message ->
                if (message.role == "assistant") {
                    if (message.thinking.isNotBlank() || message.content.isBlank()) {
                        val processMessage = if (message.content.isNotBlank()) {
                            message.copy(id = -message.id, content = "")
                        } else {
                            message
                        }
                        pendingProcess += processMessage
                    }
                    if (message.content.isNotBlank()) {
                        // Intermediate prose is a visual boundary: all following
                        // thinking/tool activity starts a new process card.
                        flushPendingProcess()
                        result += ChatRenderItem(
                            key = "message-${message.id}",
                            message = message.copy(thinking = ""),
                        )
                    }
                } else {
                    pendingProcess += message
                }
            }
            flushPendingProcess()
            return
        }
        val finalAnswerIndex = turn.indexOfLast {
            it.role == "assistant" && it.content.isNotBlank()
        }
        val process = buildList {
            turn.forEachIndexed { index, message ->
                if (index == finalAnswerIndex) {
                    if (message.thinking.isNotBlank()) {
                        add(message.copy(id = -message.id, content = ""))
                    }
                } else {
                    add(message)
                }
            }
        }
        if (process.isNotEmpty()) {
            result += ChatRenderItem(
                key = "process-${turn.first().id}",
                process = process,
                processStartedAt = turn.minOfOrNull { it.createdAt },
                processFinishedAt = turn.maxOfOrNull { it.createdAt },
            )
        }
        if (finalAnswerIndex >= 0) {
            val finalAnswer = turn[finalAnswerIndex].copy(thinking = "")
            result += ChatRenderItem("message-${finalAnswer.id}", message = finalAnswer)
        }
    }

    messages.forEach { message ->
        if (message.role == "user" || message.role == MEDIA_MESSAGE_ROLE) {
            // Completed turns must keep the same item structure while a later
            // turn is streaming. Re-expanding all history invalidates the
            // LazyColumn's measured item indices and can move its viewport.
            flushAssistantTurn(streamTurn = false)
            result += ChatRenderItem("message-${message.id}", message = message)
        } else {
            assistantTurn += message
        }
    }
    flushAssistantTurn(streamTurn = isStreaming)
    return result
}

@Composable
internal fun AgentProcessSummary(
    messages: List<ChatRecord>,
    selectionResetKey: Int,
    active: Boolean = false,
    streamingAnimationMode: String = AppSettings.STREAMING_ANIMATION_TYPEWRITER,
    startedAtOverride: Long? = null,
    finishedAtOverride: Long? = null,
    inlineToolDetails: Boolean = false,
) {
    // Synthetic thinking-only records use a negative ID. Normalize it so the
    // expanded state survives both streaming splits and the final merge.
    val processKey = sourceMessageId(messages.firstOrNull()?.id ?: 0L)
    var expanded by rememberSaveable(processKey) { mutableStateOf(false) }
    val toolCount = messages.count { it.role == "tool" }
    val thinkingCount = messages.count { it.thinking.isNotBlank() || it.role == "assistant" }
    val fallbackNow = remember(processKey) { System.currentTimeMillis() }
    var wasActive by rememberSaveable(processKey) { mutableStateOf(false) }
    var completedAt by rememberSaveable(processKey) { mutableStateOf<Long?>(null) }
    LaunchedEffect(active, processKey) {
        if (active) {
            wasActive = true
            completedAt = null
        } else if (wasActive && completedAt == null) {
            completedAt = System.currentTimeMillis()
        }
    }
    val startedAt = startedAtOverride ?: messages.minOfOrNull { it.createdAt } ?: fallbackNow
    val finishedAt = completedAt ?: finishedAtOverride ?: messages.maxOfOrNull { it.createdAt } ?: fallbackNow
    val collapsedText = if (expanded) {
        uiText(R.string.process_record_expanded)
    } else {
        uiText(R.string.ui_process_log_collapsed_thinking_1_s_tools_2_s, thinkingCount, toolCount)
    }
    Card(
        Modifier
            .fillMaxWidth()
            .smoothStreamingHeight(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProcessDurationHeader(
                startedAt = startedAt,
                finishedAt = finishedAt,
                active = active,
            )
            CollapsedStatusLine(
                text = collapsedText,
                expanded = expanded,
                onClick = { expanded = !expanded },
            )
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    messages.forEachIndexed { index, message ->
                        key(message.id) {
                            MessageCard(
                                message = message,
                                selectionResetKey = selectionResetKey,
                                inProcessRecord = true,
                                inlineToolDetails = inlineToolDetails,
                                streamingAnimationMode = streamingAnimationMode,
                                isStreaming = active &&
                                    index == messages.lastIndex &&
                                    message.role == "assistant",
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProcessDurationHeader(
    startedAt: Long,
    finishedAt: Long?,
    active: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (active) uiText(R.string.label_task_processing) else uiText(R.string.label_task_duration),
            color = KimiMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        ProcessDurationText(
            startedAt = startedAt,
            finishedAt = finishedAt,
            active = active,
        )
    }
}

@Composable
internal fun ProcessDurationText(
    startedAt: Long,
    finishedAt: Long?,
    active: Boolean,
) {
    var now by remember(startedAt, finishedAt, active) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt, finishedAt, active) {
        if (active) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1000L)
            }
        }
    }
    val endAt = if (active) now else (finishedAt ?: now)
    Text(
        text = formatProcessDuration((endAt - startedAt).coerceAtLeast(0L)),
        color = KimiMuted,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
internal fun ToolApprovalDialog(
    pending: PendingToolApproval,
    onApprove: (rememberConversation: Boolean) -> Unit,
    onReject: (feedback: String) -> Unit,
) {
    var feedback by rememberSaveable(pending.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(uiText(R.string.title_confirm_tool_call)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pending.request.summary, style = MaterialTheme.typography.titleSmall)
                Text(pending.request.risk, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                SelectionContainer {
                    Text(
                        pending.request.arguments,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
                OutlinedTextField(
                    value = feedback,
                    onValueChange = { feedback = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    label = { Text(uiText(R.string.label_reject_feedback)) },
                )
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (pending.request.toolName != "send_email") {
                    TextButton(
                        onClick = { onApprove(true) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(uiText(R.string.action_session_no_confirm))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { onReject(feedback) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(uiText(R.string.action_reject))
                    }
                    Button(
                        onClick = { onApprove(false) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(uiText(R.string.action_approve))
                    }
                }
            }
        },
    )
}

@Composable
internal fun UserQuestionDialog(
    pending: PendingUserQuestion,
    onActivity: () -> Unit,
    onSubmit: (selectedOptions: List<String>, freeText: String) -> Unit,
) {
    var selectedOptions by remember(pending.id) { mutableStateOf(emptyList<String>()) }
    var freeText by rememberSaveable(pending.id) { mutableStateOf("") }
    var confirming by rememberSaveable(pending.id) { mutableStateOf(false) }
    val canSubmit = selectedOptions.isNotEmpty() || freeText.isNotBlank()
    AlertDialog(
        onDismissRequest = { onActivity() },
        title = { Text(pending.request.title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .pointerInput(pending.id) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.any { it.pressed }) onActivity()
                            }
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(pending.request.question, style = MaterialTheme.typography.bodyLarge)
                if (pending.request.options.isNotEmpty()) {
                    Text(
                        stringResource(R.string.ask_user_multi_select_hint),
                        color = KimiMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    pending.request.options.forEach { option ->
                        val selected = option in selectedOptions
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f),
                                )
                                .clickable {
                                    onActivity()
                                    selectedOptions = if (selected) {
                                        selectedOptions - option
                                    } else {
                                        selectedOptions + option
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = selected, onCheckedChange = null)
                            Spacer(Modifier.width(6.dp))
                            Text(option, modifier = Modifier.weight(1f))
                        }
                    }
                }
                OutlinedTextField(
                    value = freeText,
                    onValueChange = {
                        onActivity()
                        freeText = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 6,
                    label = { Text(stringResource(R.string.ask_user_free_text_label)) },
                    supportingText = { Text(stringResource(R.string.ask_user_free_text_support)) },
                )
                Text(
                    stringResource(R.string.ask_user_idle_timeout_hint),
                    color = KimiMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = canSubmit,
                onClick = {
                    onActivity()
                    confirming = true
                },
            ) {
                Text(stringResource(R.string.ask_user_submit))
            }
        },
    )
    if (confirming) {
        AlertDialog(
            onDismissRequest = {
                onActivity()
                confirming = false
            },
            title = { Text(stringResource(R.string.ask_user_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ask_user_confirm_body))
                    if (selectedOptions.isNotEmpty()) {
                        Text(
                            stringResource(
                                R.string.ask_user_confirm_selected,
                                selectedOptions.joinToString("、"),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (freeText.isNotBlank()) {
                        Text(
                            stringResource(R.string.ask_user_confirm_extra, freeText.trim()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        onActivity()
                        confirming = false
                    },
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onActivity()
                        onSubmit(selectedOptions, freeText)
                    },
                ) {
                    Text(stringResource(R.string.ask_user_confirm_submit))
                }
            },
        )
    }
}

@Composable
internal fun TodoProgressPanel(settings: AppSettings, conversationId: Long, items: List<TodoItem>) {
    if (items.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(true) }
    val signature = remember(items) { items.joinToString("|") { "${it.id}:${it.status}:${it.text}:${it.note}" } }
    var hiddenSignature by remember(conversationId) { mutableStateOf(settings.hiddenTodoSignature(conversationId)) }
    var dragX by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val animatedDragX by animateFloatAsState(targetValue = dragX, label = "todo-panel-drag")
    val panelDragX = if (isDragging) dragX else animatedDragX
    val completed = items.count { it.status == "completed" }
    AnimatedVisibility(visible = hiddenSignature != signature, enter = fadeIn(), exit = fadeOut()) {
        Card(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = panelDragX.coerceAtMost(0f)
                    alpha = 1f - ((-translationX) / 420f).coerceIn(0f, 0.45f)
                }
                .pointerInput(signature) {
                    detectDragGestures(
                        onDragStart = { isDragging = true },
                        onDragEnd = {
                            isDragging = false
                            if (dragX < -120f) {
                                hiddenSignature = signature
                                settings.setHiddenTodoSignature(conversationId, signature)
                            }
                            dragX = 0f
                        },
                        onDragCancel = {
                            isDragging = false
                            dragX = 0f
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        dragX = (dragX + dragAmount.x).coerceIn(-420f, 0f)
                    }
                },
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("TODO $completed/${items.size}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (expanded) uiText(R.string.cd_expand) else uiText(R.string.cd_expand_alt),
                        )
                    }
                }
                AnimatedVisibility(expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        items.forEach { item ->
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(todoStatusMark(item.status), color = todoStatusColor(item.status), style = MaterialTheme.typography.bodyMedium)
                                Column(Modifier.weight(1f)) {
                                    Text(item.text, style = MaterialTheme.typography.bodyMedium)
                                    if (item.note.isNotBlank()) {
                                        Text(item.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun todoStatusMark(status: String): String = when (status) {
    "running" -> "..."
    "completed" -> "✓"
    "blocked" -> "!"
    else -> "○"
}

@Composable
internal fun todoStatusColor(status: String): Color = when (status) {
    "running" -> MaterialTheme.colorScheme.primary
    "completed" -> Color(0xFF188038)
    "blocked" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

internal data class ConversationFileChange(
    val messageId: Long,
    val index: Int,
    val change: FileChangeView,
) {
    val key: String = "$messageId:$index:${change.path}"
}

@Composable
internal fun ConversationChangesPanel(settings: AppSettings, conversationId: Long, messages: List<ChatRecord>) {
    val events = remember(messages) {
        messages.flatMap { message ->
            parseFileChanges(message.content).mapIndexed { index, change ->
                ConversationFileChange(message.id, index, change)
            }
        }.takeLast(20)
      }
      if (events.isEmpty()) return
      val signature = remember(events) { events.joinToString("|") { it.key } }
      var hiddenSignature by remember(conversationId) { mutableStateOf(settings.hiddenFileChangesSignature(conversationId)) }
      var dragX by remember { mutableStateOf(0f) }
      var isDragging by remember { mutableStateOf(false) }
      val animatedDragX by animateFloatAsState(targetValue = dragX, label = "changes-panel-drag")
      val panelDragX = if (isDragging) dragX else animatedDragX

      var expanded by rememberSaveable { mutableStateOf(true) }
    var openedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val totalAdded = events.sumOf { it.change.added }
    val totalRemoved = events.sumOf { it.change.removed }

      AnimatedVisibility(visible = hiddenSignature != signature, enter = fadeIn(), exit = fadeOut()) {
      Card(
          Modifier
              .fillMaxWidth()
              .graphicsLayer {
                  translationX = panelDragX.coerceAtMost(0f)
                  alpha = 1f - ((-translationX) / 420f).coerceIn(0f, 0.45f)
              }
              .pointerInput(signature) {
                  detectDragGestures(
                      onDragStart = { isDragging = true },
                      onDragEnd = {
                          isDragging = false
                          if (dragX < -120f) {
                              hiddenSignature = signature
                              settings.setHiddenFileChangesSignature(conversationId, signature)
                          }
                          dragX = 0f
                      },
                      onDragCancel = {
                          isDragging = false
                          dragX = 0f
                      },
                  ) { change, dragAmount ->
                      change.consume()
                      dragX = (dragX + dragAmount.x).coerceIn(-420f, 0f)
                  }
              },
      ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${uiText(R.string.ui_file_changes)} ${events.size}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text("+$totalAdded", color = Color(0xFF188038), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(8.dp))
                Text("-$totalRemoved", color = Color(0xFFD93025), style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) uiText(R.string.cd_expand) else uiText(R.string.cd_expand_alt),
                    )
                }
            }
            AnimatedVisibility(expanded) {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(events.asReversed(), key = { it.key }) { event ->
                        val change = event.change
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    openedKey = if (openedKey == event.key) null else event.key
                                }
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(fileNameForDisplay(change.path), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                    Text(change.path, color = KimiMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                                }
                                Text("+${change.added}", color = Color(0xFF188038), style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.width(8.dp))
                                Text("-${change.removed}", color = Color(0xFFD93025), style = MaterialTheme.typography.labelMedium)
                            }
                            Text(
                                if (openedKey == event.key) uiText(R.string.cd_expand_collapse) else uiText(R.string.cd_expand_collapse_alt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            AnimatedVisibility(openedKey == event.key) {
                                FileChangeDetail(change)
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
internal fun ModelToolbar(controller: ChatController) {
    var profileExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    val profiles = controller.profiles.toList()
    val profile = profiles.firstOrNull { it.id == controller.activeProfileId.value } ?: profiles.firstOrNull()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(0.9f)) {
            OutlinedButton(onClick = { profileExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(profile?.name ?: uiText(R.string.label_platform), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = profileExpanded, onDismissRequest = { profileExpanded = false }) {
                profiles.forEach {
                    DropdownMenuItem(text = { Text(it.name) }, onClick = {
                        profileExpanded = false
                        controller.selectProfile(it.id)
                    })
                }
            }
        }
        Box(Modifier.weight(1.1f)) {
            OutlinedButton(onClick = { modelExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(controller.activeModel.value.ifBlank { uiText(R.string.label_model) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                profile?.enabledModels.orEmpty().forEach { model ->
                    DropdownMenuItem(text = { Text(model) }, onClick = {
                        modelExpanded = false
                        controller.selectModel(model)
                    })
                }
            }
        }
    }
}


internal fun formatTokensPerSecond(value: Double): String {
    if (value <= 0.0 || value.isNaN() || value.isInfinite()) return ""
    val text = if (value >= 10.0) "%.0f".format(Locale.US, value) else "%.1f".format(Locale.US, value)
    return "$text tok/s"
}
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun MessageCard(
    message: ChatRecord,
    selectionResetKey: Int = 0,
    inProcessRecord: Boolean = false,
    streamingAnimationMode: String = AppSettings.STREAMING_ANIMATION_TYPEWRITER,
    isStreaming: Boolean = false,
    inlineToolDetails: Boolean = false,
    onEditAndRegenerate: ((Long, String) -> Unit)? = null,
    onCreateBranch: ((Long) -> Unit)? = null,
) {
    val visibleContent = displayMessageContent(message)
    val mediaPreviews = remember(message.content) { uploadedMediaPreviews(message.content) }
    val filePreviews = remember(message.content) { uploadedFilePreviews(message.content) }
    val workspaceReferences = remember(message.content) { workspaceReferencePreviews(message.content) }
    val container = when (message.role) {
        "user" -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        "tool" -> MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        else -> Color.Transparent
    }
    val contentColor = when (message.role) {
        "user" -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val clipboard = LocalClipboardManager.current
    var showThinking by rememberSaveable(message.id) { mutableStateOf(false) }
    var showToolResult by rememberSaveable(message.id) { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var localSelectionResetKey by rememberSaveable(message.id) { mutableStateOf(0) }
    var editDialogOpen by rememberSaveable(message.id) { mutableStateOf(false) }
    var editText by rememberSaveable(message.id) { mutableStateOf(message.content) }
    val isUser = message.role == "user"
    val isMedia = message.role == MEDIA_MESSAGE_ROLE
    val shouldRenderBubble = !isUser ||
        visibleContent.isNotBlank() ||
        message.thinking.isNotBlank()
    if (editDialogOpen) {
        AlertDialog(
            onDismissRequest = { editDialogOpen = false },
            title = { Text(uiText(R.string.title_edit_regenerate)) },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    minLines = 5,
                    maxLines = 12,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        editDialogOpen = false
                        onEditAndRegenerate?.invoke(message.id, editText)
                    },
                ) {
                    Text(uiText(R.string.status_regenerate))
                }
            },
            dismissButton = {
                TextButton(onClick = { editDialogOpen = false }) {
                    Text(uiText(R.string.action_cancel))
                }
            },
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isUser && workspaceReferences.isNotEmpty()) {
                WorkspaceReferenceCardColumn(workspaceReferences)
            }
            if (isUser && mediaPreviews.isNotEmpty()) {
                UploadedMediaGrid(mediaPreviews)
            }
            if (isUser && filePreviews.isNotEmpty()) {
                UploadedFileCardColumn(filePreviews)
            }
            if (shouldRenderBubble) {
                Box {
                    val cardModifier = if (isUser) {
                        Modifier
                            .widthIn(max = 320.dp)
                            .combinedClickable(
                                onClick = { localSelectionResetKey++ },
                                onLongClick = {
                                    editText = message.content
                                    menuExpanded = true
                                },
                            )
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .clickable { localSelectionResetKey++ }
                    }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = container),
                        shape = if (isUser) RoundedCornerShape(22.dp) else RoundedCornerShape(18.dp),
                        border = if (message.role == "assistant" || isMedia) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
                        modifier = cardModifier,
                    ) {
                        val compactToolResult = inProcessRecord && message.role == "tool"
                        Column(
                            Modifier.padding(
                                horizontal = when {
                                    isUser -> 16.dp
                                    compactToolResult -> 2.dp
                                    else -> 6.dp
                                },
                                vertical = when {
                                    isUser -> 9.dp
                                    compactToolResult -> 2.dp
                                    else -> 6.dp
                                },
                            ),
                            verticalArrangement = Arrangement.spacedBy(
                                when {
                                    compactToolResult -> 2.dp
                                    isUser || message.role == "assistant" -> 4.dp
                                    else -> 8.dp
                                },
                            ),
                        ) {
                            if (!isUser && !isMedia && message.role != "assistant" && !compactToolResult) {
                                Text(uiText(R.string.stats_tool_results), color = KimiMuted, style = MaterialTheme.typography.labelMedium)
                            }
                            if (message.thinking.isNotBlank()) {
                                CollapsedStatusLine(
                                    text = if (showThinking) uiText(R.string.thinking_details_expanded) else if (message.content.isBlank()) "thinking..." else uiText(R.string.label_thinking_done),
                                    expanded = showThinking,
                                    onClick = { showThinking = !showThinking },
                                )
                                AnimatedVisibility(showThinking) {
                                    key(selectionResetKey) {
                                        SelectionContainer {
                                            StreamingThinkingContent(
                                                content = message.thinking,
                                                isStreaming = isStreaming,
                                                mode = streamingAnimationMode,
                                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                                color = contentColor,
                                                smoothHeight = !inProcessRecord,
                                            )
                                        }
                                    }
                                }
                            }
                            if (message.role == "tool") {
                                ToolResultContent(
                                    content = message.content,
                                    toolName = message.toolName,
                                    toolInput = message.toolInput,
                                    expanded = showToolResult,
                                    onToggle = { showToolResult = !showToolResult },
                                    compact = compactToolResult,
                                    inlineDetails = inlineToolDetails,
                                )
                            } else {
                                if (visibleContent.isNotBlank()) {
                                    key(selectionResetKey) {
                                        if (isUser) {
                                            Text(visibleContent, color = contentColor, style = MaterialTheme.typography.bodyLarge)
                                        } else {
                                            key(localSelectionResetKey) {
                                                SelectionContainer {
                                                    StreamingAssistantContent(visibleContent, isStreaming, streamingAnimationMode)
                                                }
                                            }
                                        }
                                    }
                                    if (message.role == "assistant" && !inProcessRecord) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            formatTokensPerSecond(message.tokensPerSecond)
                                                .takeIf { it.isNotBlank() }
                                                ?.let { speed ->
                                                    Text(
                                                        speed,
                                                        color = KimiMuted,
                                                        style = MaterialTheme.typography.labelSmall,
                                                    )
                                                }
                                            message.deepSeekCacheHitRate?.let { rate ->
                                                Text(
                                                    uiText(R.string.deepseek_cache_hit_rate, rate.coerceIn(0.0, 100.0)),
                                                    color = KimiMuted,
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            }
                                            Spacer(Modifier.weight(1f))
                                            if (!isStreaming) {
                                                Box {
                                                    IconButton(
                                                        onClick = { menuExpanded = true },
                                                        modifier = Modifier.size(32.dp),
                                                    ) {
                                                        Icon(
                                                            Icons.Default.MoreVert,
                                                            contentDescription = uiText(R.string.action_more),
                                                            tint = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(18.dp),
                                                        )
                                                    }
                                                    MessageActionsDropdown(
                                                        expanded = menuExpanded,
                                                        onDismiss = { menuExpanded = false },
                                                        onCopy = {
                                                            clipboard.setText(AnnotatedString(message.content))
                                                            menuExpanded = false
                                                        },
                                                        onCreateBranch = onCreateBranch?.let { createBranch ->
                                                            {
                                                                menuExpanded = false
                                                                createBranch(message.id)
                                                            }
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else if (message.role == "assistant" && !inProcessRecord) {
                                    Text(uiText(R.string.ui_composing_response), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    if (isUser && !inProcessRecord) {
                        MessageActionsDropdown(
                            expanded = menuExpanded,
                            onDismiss = { menuExpanded = false },
                            onCopy = {
                                clipboard.setText(AnnotatedString(message.content))
                                menuExpanded = false
                            },
                            onCreateBranch = onCreateBranch?.let { createBranch ->
                                {
                                    menuExpanded = false
                                    createBranch(message.id)
                                }
                            },
                            onEditAndRegenerate = onEditAndRegenerate?.let {
                                {
                                    editText = message.content
                                    menuExpanded = false
                                    editDialogOpen = true
                                }
                            },
                            onRegenerate = onEditAndRegenerate?.let { regenerate ->
                                {
                                    menuExpanded = false
                                    regenerate(message.id, message.content)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageActionsDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onCreateBranch: (() -> Unit)? = null,
    onEditAndRegenerate: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(uiText(R.string.file_action_copy)) },
            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
            onClick = onCopy,
        )
        onCreateBranch?.let { createBranch ->
            DropdownMenuItem(
                text = { Text(uiText(R.string.action_create_branch)) },
                leadingIcon = { Icon(Icons.Default.CallSplit, contentDescription = null) },
                onClick = createBranch,
            )
        }
        onEditAndRegenerate?.let { editAndRegenerate ->
            DropdownMenuItem(
                text = { Text(uiText(R.string.action_edit_regenerate)) },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = editAndRegenerate,
            )
        }
        onRegenerate?.let { regenerate ->
            DropdownMenuItem(
                text = { Text(uiText(R.string.status_regenerate)) },
                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                onClick = regenerate,
            )
        }
    }
}

@Composable
internal fun StreamingAssistantContent(
    content: String,
    isStreaming: Boolean,
    mode: String,
    style: TextStyle = LocalTextStyle.current,
    textColor: Color = Color.Unspecified,
    smoothHeight: Boolean = true,
) {
    val frame = rememberStreamingTextFrame(content, isStreaming, mode)
    RichMarkdownContent(
        frame.content,
        modifier = if (smoothHeight) Modifier.smoothStreamingHeight() else Modifier,
        style = style.copy(color = textColor),
        streamingFade = frame.fade,
    )
}

private data class StreamingTextFrame(
    val content: String,
    val fade: StreamingTextFade?,
)

@Composable
private fun rememberStreamingTextFrame(
    content: String,
    isStreaming: Boolean,
    mode: String,
): StreamingTextFrame {
    val normalizedMode = AppSettings.normalizeStreamingAnimationMode(mode)
    val latestContent by rememberUpdatedState(content)
    val fadeMode = normalizedMode == AppSettings.STREAMING_ANIMATION_FADE
    var renderedContent by remember {
        mutableStateOf(if (isStreaming && !fadeMode) "" else content)
    }
    val opaquePosition = remember {
        Animatable(if (isStreaming && fadeMode) 0f else content.length.toFloat())
    }

    LaunchedEffect(content, isStreaming, normalizedMode) {
        if (!fadeMode) return@LaunchedEffect

        val targetPosition = content.length.toFloat()
        val contentWasReplaced = !content.startsWith(renderedContent)
        renderedContent = content
        when {
            contentWasReplaced -> opaquePosition.snapTo((targetPosition - 72f).coerceAtLeast(0f))
            opaquePosition.value > targetPosition -> opaquePosition.snapTo(targetPosition)
        }
        // Render every received character immediately; only the newest tail fades in.
        opaquePosition.animateTo(
            targetValue = targetPosition,
            animationSpec = tween(durationMillis = 500),
        )
    }

    LaunchedEffect(isStreaming, normalizedMode) {
        if (fadeMode) return@LaunchedEffect
        if (!isStreaming) {
            renderedContent = latestContent
            return@LaunchedEffect
        }

        while (true) {
            val target = latestContent
            if (!target.startsWith(renderedContent)) {
                renderedContent = ""
            }
            val pending = target.length - renderedContent.length
            if (pending > 0) {
                renderedContent = target.take(renderedContent.length + 1)
            }
            delay(12L)
        }
    }
    val fade = if (
        fadeMode && opaquePosition.value < renderedContent.length
    ) {
        StreamingTextFade(renderedContent.length, opaquePosition.value)
    } else {
        null
    }
    return StreamingTextFrame(renderedContent, fade)
}

@Composable
private fun StreamingThinkingContent(
    content: String,
    isStreaming: Boolean,
    mode: String,
    style: TextStyle,
    color: Color,
    smoothHeight: Boolean,
) {
    val frame = rememberStreamingTextFrame(content, isStreaming, mode)
    val annotated = remember(frame.content, frame.fade, color) {
        buildAnnotatedString {
            appendStreamingFadedText(
                value = frame.content,
                sourceStart = 0,
                streamingFade = frame.fade,
                baseColor = color,
            )
        }
    }
    Text(
        text = annotated,
        modifier = if (smoothHeight) Modifier.smoothStreamingHeight() else Modifier,
        style = style.copy(color = color),
    )
}

private fun Modifier.smoothStreamingHeight(): Modifier = animateContentSize(
    animationSpec = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    ),
    alignment = Alignment.TopStart,
)
internal fun formatProcessDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> uiText(R.string.label_time_hours, hours, minutes, seconds)
        minutes > 0L -> uiText(R.string.label_time_minutes, minutes, seconds)
        else -> uiText(R.string.label_time_seconds, seconds)
    }
}

@Composable
internal fun CollapsedStatusLine(
    text: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(KimiPillShape)
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, modifier = Modifier.weight(1f), color = KimiMuted, style = MaterialTheme.typography.labelMedium)
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) uiText(R.string.cd_expand) else uiText(R.string.cd_expand_alt),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
internal fun ContinueInterruptedRow(onContinue: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)))
        KimiChip(uiText(R.string.action_continue_chat), onClick = onContinue)
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)))
    }
}

