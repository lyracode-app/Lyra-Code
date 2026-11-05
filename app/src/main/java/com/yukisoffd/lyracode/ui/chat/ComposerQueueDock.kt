package com.yukisoffd.lyracode

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal enum class ComposerQueueItemState { QUEUED, PARKED, WAITING_TO_INSERT }

internal data class ComposerQueueRowUi(
    val id: String,
    val excerpt: String,
    val attachmentCount: Int,
    val state: ComposerQueueItemState,
    val missingAttachmentCount: Int = 0,
)

/**
 * Collapsible queue panel above the composer, in the order given.
 *
 * One item renders a single row without a header. Two or more render a count header that starts
 * collapsed and toggles the scrollable row list. Rows expose Remove always, Steer while a run
 * accepts it, and Send now for parked items when the conversation is idle.
 */
@Composable
internal fun ComposerQueueDock(
    conversationId: Long,
    items: List<ComposerQueueRowUi>,
    runActive: Boolean,
    steerAccepted: Boolean,
    onRemove: (String) -> Unit,
    onSendParked: (String) -> Unit,
    onSteer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(conversationId) { mutableStateOf(false) }
    val hasWaitingSteer = items.any { it.state == ComposerQueueItemState.WAITING_TO_INSERT }
    LaunchedEffect(items.isEmpty(), hasWaitingSteer) {
        if (items.isEmpty()) expanded = false
        else if (hasWaitingSteer) expanded = true
    }
    if (items.isEmpty()) return
    val collapsible = items.size > 1
    val dockDescription = stringResource(R.string.composer_queue_dock_description, items.size)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = dockDescription },
    ) {
        if (collapsible) {
            val toggleLabel = stringResource(
                if (expanded) R.string.composer_queue_dock_collapse else R.string.composer_queue_dock_expand,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { expanded = !expanded }
                    .semantics { contentDescription = toggleLabel }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.composer_queue_dock_title, items.size),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Icon(
                    imageVector = if (expanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!collapsible || expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                items.forEach { item ->
                    ComposerQueueRow(
                        item = item,
                        action = composerQueueRowAction(
                            state = item.state,
                            runActive = runActive,
                            steerAccepted = steerAccepted,
                            attachmentCount = item.attachmentCount,
                        ),
                        onRemove = onRemove,
                        onSendParked = onSendParked,
                        onSteer = onSteer,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerQueueRow(
    item: ComposerQueueRowUi,
    action: ComposerQueueRowAction,
    onRemove: (String) -> Unit,
    onSendParked: (String) -> Unit,
    onSteer: (String) -> Unit,
) {
    val sendNowDescription = stringResource(R.string.composer_queue_send_now_description, item.excerpt)
    val steerDescription = stringResource(R.string.composer_queue_steer_description, item.excerpt)
    val removeDescription = stringResource(R.string.composer_queue_remove_description, item.excerpt)
    val stateLabel = stringResource(
        when (item.state) {
            ComposerQueueItemState.PARKED -> R.string.composer_queue_state_parked
            ComposerQueueItemState.QUEUED -> R.string.composer_queue_state_queued
            ComposerQueueItemState.WAITING_TO_INSERT -> R.string.composer_queue_state_waiting_to_insert
        },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = item.excerpt,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stateLabel,
                    color = if (item.state == ComposerQueueItemState.PARKED) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
                if (item.attachmentCount > 0) {
                    Text(
                        text = stringResource(R.string.composer_queue_attachment_count, item.attachmentCount),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
                if (item.missingAttachmentCount > 0) {
                    Text(
                        text = stringResource(R.string.composer_queue_missing_attachment, item.missingAttachmentCount),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }
        when (action) {
            ComposerQueueRowAction.STEER, ComposerQueueRowAction.STEER_PENDING -> QueueRowActionButton(
                description = steerDescription,
                onClick = { onSteer(item.id) },
                enabled = action == ComposerQueueRowAction.STEER,
            )
            ComposerQueueRowAction.SEND_NOW -> QueueRowActionButton(
                description = sendNowDescription,
                onClick = { onSendParked(item.id) },
            )
            ComposerQueueRowAction.NONE -> Unit
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable { onRemove(item.id) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = removeDescription,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QueueRowActionButton(
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (enabled) colors.primary else colors.onSurface.copy(alpha = 0.12f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Send,
            contentDescription = description,
            modifier = Modifier.size(18.dp),
            tint = if (enabled) colors.onPrimary else colors.onSurface.copy(alpha = 0.38f),
        )
    }
}
