package com.yukisoffd.lyracode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.ChatProject
import com.yukisoffd.lyracode.data.Conversation
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun KimiDrawerContent(
    settings: AppSettings,
    pages: List<String>,
    selectedPage: Int,
    languageMode: String,
    controller: ChatController,
    nickname: String,
    avatarPath: String?,
    keyboardAvoidanceOffsetPx: Int,
    onProfileChanged: (String, String?) -> Unit,
    onOpenAccount: (String) -> Unit,
    onOpenChatGptAccount: () -> Unit,
    onSelectPage: (Int) -> Unit,
    onNewConversation: () -> Unit,
    onCreateProject: () -> Unit,
    onNewProjectConversation: (Long) -> Unit,
    onSelectConversation: (Long) -> Unit,
) {
    val context = LocalContext.current
    val conversationSnapshot = controller.conversations.toList()
    val projectSnapshot = controller.projects.toList()
    var historyQuery by rememberSaveable { mutableStateOf("") }
    var historyMode by rememberSaveable { mutableStateOf("sessions") }
    var functionsExpanded by rememberSaveable { mutableStateOf(true) }
    var historyExpanded by rememberSaveable { mutableStateOf(true) }
    val historyContentProgress = remember { Animatable(1f) }
    var lastAnimatedHistoryMode by remember { mutableStateOf(historyMode) }
    LaunchedEffect(historyMode) {
        if (historyMode != lastAnimatedHistoryMode) {
            lastAnimatedHistoryMode = historyMode
            historyContentProgress.snapTo(0f)
            historyContentProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 260),
            )
        }
    }
    val historyContentShiftPx = with(LocalDensity.current) { 14.dp.toPx() }
    val historyContentModifier = Modifier.graphicsLayer {
        alpha = historyContentProgress.value
        translationX = (1f - historyContentProgress.value) *
            if (historyMode == "projects") historyContentShiftPx else -historyContentShiftPx
    }
    val searchBringIntoViewRequester = remember { BringIntoViewRequester() }
    var searchFocused by remember { mutableStateOf(false) }
    val composeImeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(searchFocused, keyboardAvoidanceOffsetPx, composeImeBottom) {
        if (searchFocused) {
            delay(80)
            searchBringIntoViewRequester.bringIntoView()
        }
    }
    var selectedHistoryIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var collapsedProjectIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var actionConversation by remember { mutableStateOf<Conversation?>(null) }
    var actionProject by remember { mutableStateOf<ChatProject?>(null) }
    val sessionConversations = remember(conversationSnapshot) {
        conversationSnapshot.filter { it.projectId <= 0L }
    }
    val filteredConversations = remember(sessionConversations, historyQuery) {
        val query = historyQuery.trim()
        if (query.isBlank()) {
            sessionConversations
        } else {
            sessionConversations.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.model.contains(query, ignoreCase = true) ||
                    it.status.contains(query, ignoreCase = true)
            }
        }
    }
    val historyLanguageKey = listOf(
        languageMode,
        context.getString(R.string.label_today),
        context.getString(R.string.date_format_year_month),
    ).joinToString("|")
    val groupedConversations = remember(filteredConversations, historyLanguageKey) {
        groupConversationsByTime(filteredConversations, context, languageMode)
    }
    val projectGroups = remember(projectSnapshot, conversationSnapshot, historyQuery) {
        val query = historyQuery.trim()
        projectSnapshot.mapNotNull { project ->
            val projectConversations = conversationSnapshot.filter { it.projectId == project.id }
            val projectMatches = query.isBlank() ||
                project.name.contains(query, ignoreCase = true) ||
                project.workspaceUri.contains(query, ignoreCase = true)
            val matchingConversations = if (query.isBlank() || projectMatches) {
                projectConversations
            } else {
                projectConversations.filter {
                    it.title.contains(query, ignoreCase = true) ||
                        it.model.contains(query, ignoreCase = true) ||
                        it.status.contains(query, ignoreCase = true)
                }
            }
            if (projectMatches || matchingConversations.isNotEmpty()) project to matchingConversations else null
        }
    }
    var editingProfile by rememberSaveable { mutableStateOf(false) }
    actionConversation?.let { conversation ->
        HistoryConversationActionsDialog(
            conversation = conversation,
            onDismiss = { actionConversation = null },
            onRename = { title ->
                controller.renameConversation(conversation.id, title)
                actionConversation = null
            },
            onPin = {
                controller.setConversationPinned(conversation.id, conversation.pinnedAt <= 0L)
                actionConversation = null
            },
            onArchive = {
                controller.archiveConversation(conversation.id)
                selectedHistoryIds = selectedHistoryIds - conversation.id
                actionConversation = null
            },
            onDelete = {
                controller.deleteConversation(conversation.id)
                selectedHistoryIds = selectedHistoryIds - conversation.id
                actionConversation = null
            },
            onMultiSelect = {
                selectedHistoryIds = selectedHistoryIds + conversation.id
                actionConversation = null
            },
            showMultiSelect = conversation.projectId <= 0L,
        )
    }
    actionProject?.let { project ->
        ProjectActionsDialog(
            project = project,
            onDismiss = { actionProject = null },
            onRename = { name ->
                controller.renameProject(project.id, name)
                actionProject = null
            },
            onPin = {
                controller.setProjectPinned(project.id, project.pinnedAt <= 0L)
                actionProject = null
            },
            onArchive = {
                controller.archiveProject(project.id)
                actionProject = null
            },
            onDelete = {
                controller.deleteProject(project.id)
                actionProject = null
            },
        )
    }
    if (editingProfile) {
        ProfileEditDialog(
            settings = settings,
            onServicesChanged = controller::reloadProfiles,
            onOpenAccount = { notice -> editingProfile = false; onOpenAccount(notice) },
            onOpenChatGptAccount = { editingProfile = false; onOpenChatGptAccount() },
            nickname = nickname,
            avatarPath = avatarPath,
            onDismiss = { editingProfile = false },
            onSaved = { newNickname, newAvatarPath ->
                onProfileChanged(newNickname, newAvatarPath)
                editingProfile = false
            },
        )
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
        item {
            KimiCardBox {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { editingProfile = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UserAvatar(avatarPath = avatarPath, fallback = nickname.take(1).ifBlank { "L" }, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(nickname, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                DrawerSectionHeader(
                    title = context.getString(R.string.label_functions),
                    expanded = functionsExpanded,
                    onToggle = { functionsExpanded = !functionsExpanded },
                )
                if (functionsExpanded) {
                    pages.indices.forEach { index ->
                        KimiFunctionRow(
                            icon = functionPageIcon(index),
                            title = pages[index],
                            selected = selectedPage == index,
                            onClick = { onSelectPage(index) },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    KimiDivider()
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DrawerSectionHeader(
                    title = context.getString(R.string.label_history_sessions),
                    expanded = historyExpanded,
                    onToggle = { historyExpanded = !historyExpanded },
                )
                if (historyExpanded) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clip(KimiPillShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                                shape = KimiPillShape,
                            )
                            .padding(3.dp),
                    ) {
                        HistoryModeButton(
                            selected = historyMode == "sessions",
                            icon = Icons.Default.ChatBubbleOutline,
                            label = context.getString(R.string.history_mode_sessions),
                            onClick = {
                                historyMode = "sessions"
                                selectedHistoryIds = emptySet()
                            },
                            modifier = Modifier.weight(1f),
                        )
                        HistoryModeButton(
                            selected = historyMode == "projects",
                            icon = Icons.Default.FolderOpen,
                            label = context.getString(R.string.history_mode_projects),
                            onClick = {
                                historyMode = "projects"
                                selectedHistoryIds = emptySet()
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val projectMode = historyMode == "projects"
                        val projectButtonSlotWidth by animateDpAsState(
                            targetValue = if (projectMode) 56.dp else 0.dp,
                            animationSpec = tween(durationMillis = 260),
                            label = "project-button-slot-width",
                        )
                        val searchWidth = (maxWidth - projectButtonSlotWidth).coerceAtLeast(0.dp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CapsuleTextField(
                                value = historyQuery,
                                onValueChange = { historyQuery = it },
                                modifier = Modifier
                                    .width(searchWidth)
                                    .bringIntoViewRequester(searchBringIntoViewRequester)
                                    .onFocusChanged { searchFocused = it.isFocused },
                                placeholder = context.getString(
                                    if (projectMode) R.string.search_projects_placeholder
                                    else R.string.search_history_placeholder,
                                ),
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(19.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                            Box(
                                modifier = Modifier
                                    .width(projectButtonSlotWidth)
                                    .height(48.dp),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = projectMode,
                                    enter = fadeIn(tween(180)) + expandHorizontally(
                                        animationSpec = tween(260),
                                        expandFrom = Alignment.End,
                                    ),
                                    exit = fadeOut(tween(120)) + shrinkHorizontally(
                                        animationSpec = tween(220),
                                        shrinkTowards = Alignment.End,
                                    ),
                                ) {
                                    NewProjectButton(onClick = onCreateProject)
                                }
                            }
                        }
                    }
                    if (historyMode == "sessions" && selectedHistoryIds.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(context.getString(R.string.label_selected_count, selectedHistoryIds.size), modifier = Modifier.weight(1f), color = KimiMuted)
                            KimiChip(context.getString(R.string.action_pin), onClick = {
                                controller.setConversationsPinned(selectedHistoryIds, true)
                                selectedHistoryIds = emptySet()
                            })
                            KimiChip(context.getString(R.string.action_delete), onClick = {
                                controller.deleteConversations(selectedHistoryIds)
                                selectedHistoryIds = emptySet()
                            })
                            IconButton(onClick = { selectedHistoryIds = emptySet() }) {
                                Icon(Icons.Default.Close, contentDescription = context.getString(R.string.action_cancel_select))
                            }
                        }
                    }
                    if (historyMode == "sessions" && filteredConversations.isEmpty()) {
                        Text(
                            context.getString(R.string.notice_no_sessions),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (historyMode == "projects" && projectGroups.isEmpty()) {
                        Text(
                            context.getString(R.string.notice_no_projects),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (historyExpanded && historyMode == "sessions") {
            groupedConversations.forEach { (label, conversations) ->
                item(key = "history-group-$label") {
                    Text(
                        label,
                        color = KimiMuted,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = historyContentModifier
                            .padding(horizontal = 8.dp)
                            .padding(top = 2.dp),
                    )
                }
                items(conversations, key = { "session-${it.id}" }) { conversation ->
                    KimiConversationRow(
                        conversation = conversation,
                        selected = controller.activeConversationId.value == conversation.id,
                        multiSelected = conversation.id in selectedHistoryIds,
                        selectionMode = selectedHistoryIds.isNotEmpty(),
                        onSelect = {
                            if (selectedHistoryIds.isEmpty()) {
                                onSelectConversation(conversation.id)
                            } else {
                                selectedHistoryIds = if (conversation.id in selectedHistoryIds) {
                                    selectedHistoryIds - conversation.id
                                } else {
                                    selectedHistoryIds + conversation.id
                                }
                            }
                        },
                        onLongPress = { actionConversation = conversation },
                        modifier = historyContentModifier,
                    )
                }
            }
        } else if (historyExpanded) {
            projectGroups.forEach { (project, conversations) ->
                val projectExpanded = project.id !in collapsedProjectIds
                item(key = "project-${project.id}") {
                    ProjectHeaderRow(
                        project = project,
                        conversationCount = conversationSnapshot.count { it.projectId == project.id },
                        expanded = projectExpanded,
                        active = controller.activeProjectId() == project.id,
                        onToggle = {
                            collapsedProjectIds = if (project.id in collapsedProjectIds) {
                                collapsedProjectIds - project.id
                            } else {
                                collapsedProjectIds + project.id
                            }
                        },
                        onNewConversation = { onNewProjectConversation(project.id) },
                        onLongPress = { actionProject = project },
                        modifier = historyContentModifier,
                    )
                }
                if (conversations.isEmpty()) {
                    item(key = "project-empty-${project.id}") {
                        AnimatedVisibility(
                            visible = projectExpanded,
                            enter = fadeIn(tween(180)) + expandVertically(
                                animationSpec = tween(240),
                                expandFrom = Alignment.Top,
                            ),
                            exit = fadeOut(tween(140)) + shrinkVertically(
                                animationSpec = tween(220),
                                shrinkTowards = Alignment.Top,
                            ),
                        ) {
                            Text(
                                context.getString(R.string.notice_no_project_sessions),
                                color = KimiMuted,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = historyContentModifier
                                    .padding(start = 42.dp, end = 8.dp),
                            )
                        }
                    }
                } else {
                    items(conversations, key = { "project-session-${it.id}" }) { conversation ->
                        AnimatedVisibility(
                            visible = projectExpanded,
                            enter = fadeIn(tween(180)) + expandVertically(
                                animationSpec = tween(240),
                                expandFrom = Alignment.Top,
                            ),
                            exit = fadeOut(tween(140)) + shrinkVertically(
                                animationSpec = tween(220),
                                shrinkTowards = Alignment.Top,
                            ),
                        ) {
                            KimiConversationRow(
                                conversation = conversation,
                                selected = controller.activeConversationId.value == conversation.id,
                                multiSelected = false,
                                selectionMode = false,
                                onSelect = { onSelectConversation(conversation.id) },
                                onLongPress = { actionConversation = conversation },
                                modifier = historyContentModifier
                                    .padding(start = 28.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun HistoryModeButton(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = KimiPillShape
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        animationSpec = tween(240),
        label = "history-mode-container",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(240),
        label = "history-mode-content",
    )
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxSize(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = contentColor)
            Spacer(Modifier.width(6.dp))
            Text(label, color = contentColor, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun NewProjectButton(onClick: () -> Unit) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(15.dp)
    Card(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)),
                shape,
            ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.CreateNewFolder,
                contentDescription = context.getString(R.string.action_create_project),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

private fun functionPageIcon(index: Int): ImageVector = when (index) {
    0 -> Icons.Default.ChatBubble
    1 -> Icons.Default.FolderOpen
    2 -> Icons.Default.Terminal
    3 -> Icons.Default.ReceiptLong
    4 -> Icons.Default.BarChart
    5 -> Icons.Default.TaskAlt
    6 -> Icons.Default.Inventory2
    7 -> Icons.Default.Settings
    else -> Icons.Default.Info
}

@Composable
private fun KimiFunctionRow(
    icon: ImageVector,
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.68f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(220),
        label = "drawer-function-container",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(220),
        label = "drawer-function-content",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(shape)
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
        Box(
            modifier = Modifier.width(50.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = title,
            color = contentColor,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun groupConversationsByTime(
    conversations: List<Conversation>,
    context: android.content.Context,
    languageMode: String,
    nowMillis: Long = System.currentTimeMillis(),
): List<Pair<String, List<Conversation>>> {
    if (conversations.isEmpty()) return emptyList()
    val calendar = Calendar.getInstance()
    fun startOfDay(time: Long): Long = calendar.run {
        timeInMillis = time
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }
    val todayStart = startOfDay(nowMillis)
    calendar.timeInMillis = todayStart
    calendar.add(Calendar.DAY_OF_YEAR, -1)
    val yesterdayStart = calendar.timeInMillis
    calendar.timeInMillis = todayStart
    calendar.add(Calendar.DAY_OF_YEAR, -7)
    val weekStart = calendar.timeInMillis
    calendar.timeInMillis = todayStart
    calendar.add(Calendar.MONTH, -1)
    val monthStart = calendar.timeInMillis

    val groups = linkedMapOf<String, MutableList<Conversation>>()
    conversations.forEach { conversation ->
        val label = when {
            conversation.pinnedAt > 0L -> context.getString(R.string.label_pinned)
            conversation.updatedAt >= todayStart -> context.getString(R.string.label_today)
            conversation.updatedAt >= yesterdayStart -> context.getString(R.string.label_yesterday)
            conversation.updatedAt >= weekStart -> context.getString(R.string.label_this_week)
            conversation.updatedAt >= monthStart -> context.getString(R.string.label_this_month)
            else -> formatConversationYearMonth(conversation.updatedAt, languageMode)
        }
        groups.getOrPut(label) { mutableListOf() }.add(conversation)
    }
    return groups.map { it.key to it.value }
}

internal fun formatConversationYearMonth(
    timestamp: Long,
    languageMode: String,
    systemLocale: Locale = Locale.getDefault(),
): String {
    val normalizedMode = AppSettings.normalizeLanguageMode(languageMode)
    val useEnglish = normalizedMode == AppSettings.LANGUAGE_EN ||
        (normalizedMode == AppSettings.LANGUAGE_SYSTEM && systemLocale.language == Locale.ENGLISH.language)
    return if (useEnglish) {
        SimpleDateFormat("MMM yyyy", Locale.ENGLISH).format(Date(timestamp))
    } else {
        SimpleDateFormat("yyyy年M月", Locale.SIMPLIFIED_CHINESE).format(Date(timestamp))
    }
}

@Composable
internal fun CapsuleTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String,
    enabled: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = 1,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        minLines = minLines,
        maxLines = maxLines,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        decorationBox = { innerTextField ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(KimiPillShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                leadingIcon?.invoke()
                Box(Modifier.weight(1f)) {
                    if (value.isBlank()) {
                        AnimatedContent(
                            targetState = placeholder,
                            transitionSpec = {
                                fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                            },
                            label = "capsule-placeholder",
                        ) { currentPlaceholder ->
                            Text(
                                currentPlaceholder,
                                color = KimiMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    innerTextField()
                }
            }
        },
    )
}

@Composable
internal fun UserAvatar(avatarPath: String?, fallback: String, modifier: Modifier = Modifier) {
    val bitmap = remember(avatarPath) {
        avatarPath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
    }
    Box(
        modifier
            .clip(CircleShape)
            .background(Color(0xFFC6A990)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(fallback, color = Color(0xFF121212), style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
internal fun KimiDrawerShortcut(icon: String, label: String, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(icon, style = MaterialTheme.typography.titleLarge)
        Text(label, color = KimiMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun DrawerSectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val expandIconRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(220),
        label = "drawerSectionExpandIconRotation",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
        )
        Icon(
            Icons.Default.ExpandMore,
            contentDescription = if (expanded) {
                context.getString(R.string.action_collapse_section, title)
            } else {
                context.getString(R.string.action_expand_section, title)
            },
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer { rotationZ = expandIconRotation },
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ProjectHeaderRow(
    project: ChatProject,
    conversationCount: Int,
    expanded: Boolean,
    active: Boolean,
    onToggle: () -> Unit,
    onNewConversation: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(18.dp)
    val expandIconRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(220),
        label = "projectExpandIconRotation",
    )
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                BorderStroke(
                    if (active) 2.dp else 0.dp,
                    if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                ),
                shape,
            )
            .combinedClickable(onClick = onToggle, onLongClick = onLongPress),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (project.pinnedAt > 0L) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = context.getString(R.string.label_pinned_icon),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        project.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    context.getString(R.string.project_session_count, conversationCount),
                    color = KimiMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            IconButton(onClick = onNewConversation) {
                Icon(
                    Icons.Default.AddComment,
                    contentDescription = context.getString(R.string.action_new_project_session),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = if (expanded) {
                    context.getString(R.string.action_collapse_project)
                } else {
                    context.getString(R.string.action_expand_project)
                },
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = expandIconRotation },
                tint = KimiMuted,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun KimiConversationRow(
    conversation: Conversation,
    selected: Boolean,
    multiSelected: Boolean,
    selectionMode: Boolean,
    onSelect: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowShape = RoundedCornerShape(18.dp)
    val borderWidth = if (selected) 2.dp else if (multiSelected) 1.5.dp else 0.dp
    val borderColor = when {
        selected -> MaterialTheme.colorScheme.primary
        multiSelected -> KimiBlue
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0f)
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(BorderStroke(borderWidth, borderColor), rowShape)
            .combinedClickable(onClick = onSelect, onLongClick = onLongPress),
        shape = rowShape,
        colors = CardDefaults.cardColors(
            containerColor = if (selected || multiSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                Checkbox(checked = multiSelected, onCheckedChange = { onSelect() })
                Spacer(Modifier.width(6.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                if (conversation.pinnedAt > 0L) {
                        val contextForPinned = LocalContext.current
                        Icon(Icons.Default.PushPin, contentDescription = contextForPinned.getString(R.string.label_pinned_icon), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    listOf(conversation.status, conversation.model).filter { it.isNotBlank() }.joinToString(" · "),
                    color = KimiMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun ProjectActionsDialog(
    project: ChatProject,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var name by rememberSaveable(project.id) { mutableStateOf(project.name) }
    var confirmingDelete by rememberSaveable(project.id) { mutableStateOf(false) }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(context.getString(R.string.title_delete_project)) },
            text = { Text(context.getString(R.string.confirm_delete_project, project.name)) },
            confirmButton = {
                TextButton(onClick = onDelete) {
                    Text(
                        context.getString(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(context.getString(R.string.action_cancel))
                }
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.title_project_actions)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(context.getString(R.string.label_project_name)) },
                )
                Button(
                    onClick = { onRename(name.trim().ifBlank { project.name }) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(context.getString(R.string.action_save_project_name))
                }
                OutlinedButton(onClick = onPin, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PushPin, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (project.pinnedAt > 0L) {
                            context.getString(R.string.action_unpin_project)
                        } else {
                            context.getString(R.string.action_pin_project)
                        },
                    )
                }
                OutlinedButton(onClick = onArchive, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Archive, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(context.getString(R.string.action_archive_project))
                }
                OutlinedButton(onClick = { confirmingDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        context.getString(R.string.action_delete_project),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(context.getString(R.string.action_close)) }
        },
    )
}

@Composable
internal fun HistoryConversationActionsDialog(
    conversation: Conversation,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onMultiSelect: () -> Unit,
    showMultiSelect: Boolean = true,
) {
    val context = LocalContext.current
    var title by rememberSaveable(conversation.id) { mutableStateOf(conversation.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.title_session_actions)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(context.getString(R.string.label_chat_title)) },
                )
                Button(onClick = { onRename(title.trim().ifBlank { conversation.title }) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(context.getString(R.string.action_save_title))
                }
                OutlinedButton(onClick = onPin, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PushPin, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (conversation.pinnedAt > 0L) context.getString(R.string.action_unpin) else context.getString(R.string.action_pin_chat))
                }
                if (showMultiSelect) {
                    OutlinedButton(onClick = onMultiSelect, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(context.getString(R.string.action_multi_select))
                    }
                }
                OutlinedButton(onClick = onArchive, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Archive, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(context.getString(R.string.action_archive_chat))
                }
                OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(context.getString(R.string.action_delete_chat))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(context.getString(R.string.action_close)) }
        },
    )
}



