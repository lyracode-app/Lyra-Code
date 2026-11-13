package com.yukisoffd.lyracode

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yukisoffd.lyracode.data.DeveloperWallManager
import com.yukisoffd.lyracode.data.DeveloperWallPerson
import com.yukisoffd.lyracode.data.DeveloperWallRepository
import com.yukisoffd.lyracode.data.wallHttpUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DeveloperWallEntry(
    onClick: () -> Unit,
    repository: DeveloperWallRepository = DeveloperWallManager.get(LocalContext.current),
) {
    LaunchedEffect(repository) { repository.sync() }
    KimiCardBox {
        KimiMenuRow(Icons.Default.Groups, uiText(R.string.developer_wall_title), onClick = onClick)
    }
}

@Composable
internal fun DeveloperWallInformationAction() {
    val uriHandler = LocalUriHandler.current
    var showInformation by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { showInformation = true }) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = uiText(R.string.developer_wall_information))
    }
    if (showInformation) {
        AlertDialog(
            onDismissRequest = { showInformation = false },
            icon = { Icon(Icons.Outlined.ErrorOutline, contentDescription = null) },
            title = { Text(uiText(R.string.developer_wall_information)) },
            text = { Text(uiText(R.string.developer_wall_information_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showInformation = false
                    runCatching { uriHandler.openUri("https://github.com/lyracode-app/Lyra-Code/issues/new") }
                }) { Text(uiText(R.string.developer_wall_report_issue)) }
            },
            dismissButton = {
                TextButton(onClick = { showInformation = false }) { Text(uiText(R.string.action_close)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeveloperWallScreen(repository: DeveloperWallRepository = DeveloperWallManager.get(LocalContext.current)) {
    val state by repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    val pullState = rememberPullToRefreshState()
    var refreshing by remember { mutableStateOf(false) }
    val isRefreshing = refreshing || state.syncing
    val threshold = PullToRefreshDefaults.PositionalThreshold
    val thresholdPx = with(LocalDensity.current) { threshold.toPx() }
    LaunchedEffect(repository) { repository.sync() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = when {
            maxWidth < 240.dp -> 2
            maxWidth < 480.dp -> 3
            else -> (maxWidth / 112.dp).toInt().coerceIn(4, 6)
        }
        PullToRefreshBox(
            modifier = Modifier.fillMaxSize().clipToBounds(),
            state = pullState,
            isRefreshing = isRefreshing,
            onRefresh = {
                if (!isRefreshing) {
                    refreshing = true
                    scope.launch {
                        try {
                            repository.sync(force = true)
                        } finally {
                            refreshing = false
                            // Also reset after a refresh that finishes before the next UI frame.
                            pullState.animateToHidden()
                        }
                    }
                }
            },
            indicator = {},
        ) {
            Box(
                Modifier.fillMaxWidth().height(threshold * pullState.distanceFraction).clipToBounds(),
                contentAlignment = Alignment.Center,
            ) {
                if (isRefreshing) {
                    val syncingDescription = uiText(R.string.developer_wall_syncing)
                    CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = syncingDescription })
                } else if (dragging && pullState.distanceFraction > 0f) {
                    Text(uiText(R.string.developer_wall_release_to_refresh), color = KimiMuted,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("developer-wall-list").graphicsLayer {
                    translationY = pullState.distanceFraction * thresholdPx
                },
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (state.people.isEmpty() && !isRefreshing && state.syncedAt != 0L) {
                    item {
                        Text(
                            uiText(if (state.succeeded) R.string.developer_wall_empty else R.string.developer_wall_server_unavailable),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            color = KimiMuted,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
                val roleTitles = listOf(
                    R.string.developer_wall_developers,
                    R.string.developer_wall_sponsors,
                    R.string.developer_wall_contributors,
                    R.string.developer_wall_thanks,
                )
                roleTitles.forEachIndexed { role, title ->
                    val people = state.people.filter { role in it.roles }
                    if (people.isNotEmpty()) {
                        item(key = "role-$role") {
                            Text(uiText(title), modifier = Modifier.padding(top = 8.dp),
                                style = MaterialTheme.typography.titleSmall, color = KimiMuted)
                        }
                        itemsIndexed(people.chunked(columns), key = { index, _ -> "role-$role-row-$index" }) { _, row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { person ->
                                    DeveloperWallMember(person, state.syncedAt, Modifier.weight(1f))
                                }
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeveloperWallMember(person: DeveloperWallPerson, revision: Long, modifier: Modifier) {
    val uriHandler = LocalUriHandler.current
    val link = remember(person.link) { wallHttpUrl(person.link) }
    val avatar by produceState<ImageBitmap?>(null, person.avatarFile, revision) {
        value = withContext(Dispatchers.IO) {
            person.avatarFile?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }.getOrNull() }
        }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier.size(64.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(if (link == null) Modifier else Modifier.clickable(role = Role.Button,
                    onClickLabel = uiText(R.string.developer_wall_open_profile, person.name),
                ) { runCatching { uriHandler.openUri(link) } }),
            contentAlignment = Alignment.Center,
        ) {
            if (avatar != null) {
                Image(avatar!!, contentDescription = person.name, modifier = Modifier.size(64.dp), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Default.Person, contentDescription = person.name, modifier = Modifier.size(36.dp), tint = KimiMuted)
            }
        }
        Text(person.name, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}
