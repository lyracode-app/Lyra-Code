package com.yukisoffd.lyracode

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.yukisoffd.lyracode.data.SkillPack
import com.yukisoffd.lyracode.workspace.WorkspaceFileReference
import java.io.File
import kotlin.math.max
import kotlin.math.abs


@Composable
internal fun WorkspaceFileMentionPicker(
    input: String,
    matches: List<WorkspaceFileReference>,
    selected: List<WorkspaceFileReference>,
    enabled: Boolean,
    hasWorkspace: Boolean,
    loading: Boolean,
    onToggle: (WorkspaceFileReference) -> Unit,
    onRemove: (WorkspaceFileReference) -> Unit,
    onDone: () -> Unit,
) {
    AnimatedContent(
        targetState = selected,
        transitionSpec = {
            (fadeIn() + expandIn()) togetherWith (fadeOut() + shrinkOut())
        },
        label = "workspaceFileSelection",
    ) { visibleSelection ->
        if (visibleSelection.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                visibleSelection.forEach { file ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Row(
                            Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(file.name, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                            IconButton(onClick = { onRemove(file) }, enabled = enabled, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.workspace_file_remove), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    val showPicker = shouldShowWorkspaceFilePicker(input, enabled, hasWorkspace)
    AnimatedVisibility(showPicker) {
    Card(
        Modifier.fillMaxWidth().heightIn(max = 280.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f)),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.workspace_file_picker_title), style = MaterialTheme.typography.labelMedium)
                    if (selected.isNotEmpty()) {
                        Text(uiText(R.string.streaming_selected) + " ${selected.size}/24", color = KimiMuted, style = MaterialTheme.typography.labelSmall)
                    }
                }
                TextButton(onClick = onDone, enabled = enabled) { Text(uiText(R.string.status_done)) }
            }
            if (loading) {
                Text(
                    stringResource(R.string.workspace_file_indexing),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    color = KimiMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (matches.isEmpty()) {
                Text(
                    stringResource(R.string.workspace_file_no_matches),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    color = KimiMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 210.dp)) {
                items(matches, key = { it.relativePath }) { file ->
                    val isSelected = selected.any { it.relativePath == file.relativePath }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                            .clickable(enabled = enabled) { onToggle(file) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text(file.relativePath, maxLines = 1, overflow = TextOverflow.Ellipsis, color = KimiMuted, style = MaterialTheme.typography.labelSmall)
                        }
                        Icon(
                            if (isSelected) Icons.Default.CheckCircle else Icons.Default.Add,
                            contentDescription = stringResource(if (isSelected) R.string.workspace_file_remove else R.string.workspace_file_select),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
    }
}

internal fun workspaceFilePickerQuery(input: String): String? {
    val prefix = input.firstOrNull() ?: return null
    if (prefix !in WORKSPACE_FILE_PREFIXES) return null
    return input.drop(1).substringBefore(' ').trim()
}

internal fun shouldShowWorkspaceFilePicker(input: String, enabled: Boolean, hasWorkspace: Boolean): Boolean =
    enabled && hasWorkspace && workspaceFilePickerQuery(input) != null

internal fun removeWorkspaceMentionPrefix(input: String): String {
    if (workspaceFilePickerQuery(input) == null) return input
    val body = input.drop(1)
    val firstWhitespace = body.indexOfFirst { it.isWhitespace() }
    if (firstWhitespace < 0) return ""
    return body.drop(firstWhitespace + 1).trimStart()
}

@Composable
internal fun ForcedSkillControls(
    input: String,
    installedSkills: List<SkillPack>,
    forcedSkillIds: List<String>,
    enabled: Boolean,
    onSkillIdsChange: (List<String>) -> Unit,
    onInputChange: (String) -> Unit,
) {
    val forcedSkills = forcedSkillIds.mapNotNull { id -> installedSkills.firstOrNull { it.id == id } }
    val query = skillPickerQuery(input).orEmpty()
    val showPicker = shouldShowSkillPicker(input, enabled)
    val matches = installedSkills
        .filterNot { it.id in forcedSkillIds }
        .filter { skill ->
            query.isBlank() ||
                skill.name.contains(query, ignoreCase = true) ||
                skill.description.contains(query, ignoreCase = true) ||
                skill.id.contains(query, ignoreCase = true)
        }
        .take(8)

    if (forcedSkills.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(uiText(R.string.ui_forced_this_request), color = KimiMuted, style = MaterialTheme.typography.labelSmall)
            forcedSkills.forEach { skill ->
                TextButton(
                    onClick = { onSkillIdsChange(forcedSkillIds - skill.id) },
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = uiText(R.string.ui_remove_skill), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(skill.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    AnimatedVisibility(showPicker) {
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)),
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(uiText(R.string.ui_choose_skill), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(uiText(R.string.ui_type_to_choose_skills_for_this_request), style = MaterialTheme.typography.labelSmall, color = KimiMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (matches.isEmpty()) {
                    Text(
                        uiText(R.string.ui_no_matching_skills),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        color = KimiMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    matches.forEach { skill ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    onSkillIdsChange(forcedSkillIds + skill.id)
                                    onInputChange(removeSkillSlashPrefix(input))
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(skill.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                    if (!skill.enabled) {
                                        Text(uiText(R.string.skill_status_disabled), color = KimiMuted, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (skill.description.isNotBlank()) {
                                    Text(skill.description, maxLines = 1, overflow = TextOverflow.Ellipsis, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun skillPickerQuery(input: String): String? {
    if (!input.startsWith("/")) return null
    return input.drop(1).substringBefore(' ').trim()
}

internal fun shouldShowSkillPicker(input: String, enabled: Boolean): Boolean =
    enabled && skillPickerQuery(input) != null

internal fun removeSkillSlashPrefix(input: String): String {
    if (skillPickerQuery(input) == null) return input
    val body = input.drop(1)
    val firstWhitespace = body.indexOfFirst { it.isWhitespace() }
    if (firstWhitespace < 0) return ""
    return body.drop(firstWhitespace + 1).trimStart()
}

private val WORKSPACE_FILE_PREFIXES = setOf('#', '@')

internal class NavigationSwipeGuard {
    var blockedDownTime: Long? = null
}

internal val LocalNavigationSwipeGuard = staticCompositionLocalOf<NavigationSwipeGuard?> { null }
internal fun Modifier.observeLeftSwipe(
    key: Any?,
    navigationSwipeGuard: NavigationSwipeGuard,
    onLeftSwipe: () -> Unit,
): Modifier = pointerInput(key, navigationSwipeGuard, onLeftSwipe) {
    awaitPointerEventScope {
        var downTime: Long? = null
        var startX: Float? = null
        var startY: Float? = null
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull() ?: continue
            when {
                change.pressed && !change.previousPressed -> {
                    downTime = change.uptimeMillis
                    startX = change.position.x
                    startY = change.position.y
                }
                !change.pressed && change.previousPressed -> {
                    val originX = startX
                    val originY = startY
                    val blocked = navigationSwipeGuard.blockedDownTime == downTime
                    downTime = null
                    if (!blocked && originX != null && originY != null) {
                        val deltaX = change.position.x - originX
                        val deltaY = change.position.y - originY
                        if (deltaX < -80f && abs(deltaX) > abs(deltaY) * 1.2f) onLeftSwipe()
                    }
                    startX = null
                    startY = null
                }
            }
        }
    }
}

@Composable
internal fun ConversationNavigationVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInHorizontally { it } + fadeIn(),
        exit = slideOutHorizontally { it } + fadeOut(),
    ) { content() }
}

@Composable
internal fun ConversationNavigationControls(
    onInteraction: () -> Unit,
    onTop: () -> Unit,
    onPreviousUser: () -> Unit,
    onNextUser: () -> Unit,
    onBottom: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(
            Triple(Icons.Default.KeyboardDoubleArrowUp, uiText(R.string.ui_back_to_top), onTop),
            Triple(Icons.Default.KeyboardArrowUp, uiText(R.string.ui_previous_user_message), onPreviousUser),
            Triple(Icons.Default.KeyboardArrowDown, uiText(R.string.ui_next_user_message), onNextUser),
            Triple(Icons.Default.KeyboardDoubleArrowDown, uiText(R.string.ui_back_to_bottom), onBottom),
        ).forEach { (icon, description, action) ->
            IconButton(
                onClick = {
                    onInteraction()
                    action()
                },
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            ) {
                Icon(
                    icon,
                    contentDescription = description,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}


