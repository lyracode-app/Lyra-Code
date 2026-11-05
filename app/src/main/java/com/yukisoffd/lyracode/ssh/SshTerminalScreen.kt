package com.yukisoffd.lyracode

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.SshServerConfig
import com.yukisoffd.lyracode.debian.ProotLinuxManager
import com.yukisoffd.lyracode.ssh.LocalProotTerminalSessionManager
import com.yukisoffd.lyracode.ssh.SshTerminalSessionManager
import com.yukisoffd.lyracode.ssh.SshTerminalMessage
import com.yukisoffd.lyracode.ssh.SshTerminalState
import com.yukisoffd.lyracode.ssh.SshTerminalStatus
import com.yukisoffd.lyracode.ssh.TerminalConnection
import com.yukisoffd.lyracode.ssh.TerminalSession
import com.yukisoffd.lyracode.ssh.TerminalInputEncoder
import com.yukisoffd.lyracode.ssh.TerminalInputView
import com.yukisoffd.lyracode.ssh.TerminalPosition
import com.yukisoffd.lyracode.ssh.TerminalSelection
import com.yukisoffd.lyracode.ssh.TerminalSelectionText
import com.yukisoffd.lyracode.ssh.TerminalViewport
import com.yukisoffd.lyracode.ssh.TerminalSnapshot
import com.yukisoffd.lyracode.ssh.TerminalGridRenderer
import com.yukisoffd.lyracode.ssh.TerminalPalette
import com.yukisoffd.lyracode.ssh.TerminalLinks
import kotlinx.coroutines.delay

@Composable
internal fun TerminalScreen(
    settings: AppSettings,
    sessionManager: SshTerminalSessionManager,
    localSessionManager: LocalProotTerminalSessionManager,
) {
    val context = LocalContext.current
    val runtime = remember(context) { ProotLinuxManager.getInstance(context) }
    val runtimeState by runtime.state.collectAsState()
    val servers = remember { settings.sshServers().filter { it.enabled } }
    val localInstances = runtimeState.instances.filter { it.enabled }
    val targets = buildList {
        localInstances.forEach { linux ->
            add(TerminalTarget("proot:${linux.id}", "${linux.name} · ${linux.id}", linuxId = linux.id))
        }
        servers.forEach { add(TerminalTarget(it.id, it.name, it)) }
    }
    var selectedId by rememberSaveable { mutableStateOf(localInstances.firstOrNull()?.let { "proot:${it.id}" } ?: servers.firstOrNull()?.id.orEmpty()) }
    val selectedTarget = targets.firstOrNull { it.id == selectedId } ?: targets.firstOrNull()
    LaunchedEffect(selectedTarget?.id) {
        if (selectedTarget != null && selectedTarget.id != selectedId) selectedId = selectedTarget.id
    }
    val connection: TerminalSession? = remember(selectedTarget?.id) {
        selectedTarget?.linuxId?.let(localSessionManager::connection)
            ?: selectedTarget?.server?.let(sessionManager::connection)
    }
    val state by connection?.state?.collectAsState() ?: remember { mutableStateOf(SshTerminalState()) }
    val screen by connection?.screen?.collectAsState() ?: remember { mutableStateOf(com.yukisoffd.lyracode.ssh.TerminalEmulator().snapshot()) }
    var pickerExpanded by remember { mutableStateOf(false) }
    val selectionUi = remember(connection, screen.columns, screen.resetGeneration) { TerminalSelectionUiState() }
    val palette = TerminalPalette.fromId(settings.terminalColorScheme)
    var pageOrigin by remember { mutableStateOf(Offset.Zero) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(palette.background))
            .onGloballyPositioned { pageOrigin = it.positionInRoot() }
            .dismissTerminalSelectionOnTap(selectionUi) { pageOrigin },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xff111827))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { pickerExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xff8b95a7)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xffe8edf5)),
                ) {
                    Text(
                        selectedTarget?.label ?: uiText(R.string.ssh_terminal_no_servers),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = uiText(R.string.ssh_terminal_select_server))
                }
                DropdownMenu(expanded = pickerExpanded, onDismissRequest = { pickerExpanded = false }) {
                    targets.forEach { target ->
                        DropdownMenuItem(
                            text = { Text(target.label) },
                            onClick = { selectedId = target.id; pickerExpanded = false },
                        )
                    }
                }
            }
            if (state.status == SshTerminalStatus.CONNECTED || state.status == SshTerminalStatus.RECONNECTING) {
                OutlinedButton(
                    onClick = { connection?.disconnect() },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xff8b95a7)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xffe8edf5)),
                ) { Text(uiText(R.string.ssh_terminal_disconnect)) }
            } else {
                Button(
                    onClick = {
                        val server = selectedTarget?.server
                        if (server != null) (connection as? TerminalConnection)?.connect(server) else connection?.connect()
                    },
                    enabled = selectedTarget != null,
                    shape = RoundedCornerShape(12.dp),
                ) { Text(uiText(R.string.ssh_terminal_connect)) }
            }
        }
        TerminalStatusBar(state, connection, selectionUi::clear)
        TerminalSurface(liveScreen = screen, connection = connection, enabled = state.status == SshTerminalStatus.CONNECTED, settings = settings, selectionUi = selectionUi)
    }
}

@Composable
internal fun TerminalClearButton(connection: TerminalSession?, onClearSelection: () -> Unit) {
    TextButton(
        onClick = { onClearSelection(); connection?.clear() },
        enabled = connection != null,
        modifier = Modifier.semantics { contentDescription = "terminal-clear" },
    ) {
        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(uiText(R.string.terminal_clear), color = Color(0xffe8edf5), modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun TerminalStatusBar(state: SshTerminalState, connection: TerminalSession?, onClearSelection: () -> Unit) {
    val color = when (state.status) {
        SshTerminalStatus.CONNECTED -> Color(0xff73daca)
        SshTerminalStatus.CONNECTING, SshTerminalStatus.RECONNECTING -> Color(0xffe0af68)
        SshTerminalStatus.ERROR -> Color(0xfff7768e)
        else -> Color(0xff7f8ca3)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xff0e1521))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Canvas(Modifier.size(7.dp)) { drawCircle(color) }
        Text(terminalStatusText(state), color = color, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.weight(1f))
        TerminalClearButton(connection, onClearSelection)
    }
}

@Composable
private fun terminalStatusText(state: SshTerminalState): String = when (state.message) {
    SshTerminalMessage.IDLE -> uiText(R.string.ssh_terminal_idle)
    SshTerminalMessage.CONNECTING -> uiText(R.string.ssh_terminal_connecting)
    SshTerminalMessage.CONNECTED -> uiText(R.string.ssh_terminal_connected)
    SshTerminalMessage.RECONNECTING -> uiText(R.string.ssh_terminal_reconnecting, state.reconnectAttempt.coerceAtLeast(1))
    SshTerminalMessage.DISCONNECTED -> uiText(R.string.ssh_terminal_disconnected)
    SshTerminalMessage.HOST_KEY_CHANGED -> uiText(R.string.ssh_terminal_host_key_changed)
    SshTerminalMessage.AUTH_FAILED -> uiText(R.string.ssh_terminal_auth_failed)
    SshTerminalMessage.TIMEOUT -> uiText(R.string.ssh_terminal_timeout)
    SshTerminalMessage.CONNECTION_FAILED -> uiText(R.string.ssh_terminal_connection_failed)
}

internal class TerminalSelectionUiState {
    var selection by mutableStateOf<TerminalSelection?>(null)
    var snapshot by mutableStateOf<TerminalSnapshot?>(null)
    var dragPosition by mutableStateOf<Offset?>(null)
    var actionBounds: Rect? = null

    fun clear() {
        selection = null
        snapshot = null
        dragPosition = null
        actionBounds = null
    }
}

// Observe the entire terminal page, including its header. Action buttons keep the selection;
// stationary taps elsewhere dismiss it, while drags and long presses remain selection gestures.
private fun Modifier.dismissTerminalSelectionOnTap(
    state: TerminalSelectionUiState,
    origin: () -> Offset,
): Modifier = pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val dismiss = state.selection != null && state.actionBounds?.contains(down.position + origin()) != true
        var moved = false
        var releasedAt = down.uptimeMillis
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pointer = event.changes.firstOrNull { it.id == down.id }
            if (pointer != null) {
                moved = moved || (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop
                releasedAt = pointer.uptimeMillis
            }
        } while (event.changes.any { it.pressed })
        if (dismiss && !moved && releasedAt - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) state.clear()
    }
}

@Composable
internal fun TerminalSurface(
    liveScreen: TerminalSnapshot,
    connection: TerminalSession?,
    enabled: Boolean,
    settings: AppSettings,
    selectionUi: TerminalSelectionUiState = remember(connection, liveScreen.columns, liveScreen.resetGeneration) { TerminalSelectionUiState() },
) {
    val screen = selectionUi.snapshot ?: liveScreen
    val density = LocalDensity.current
    val context = LocalContext.current
    val keyboardOffsetPx = rememberAnimatedKeyboardAvoidanceOffsetPx()
    val keyboardOffsetDp = with(density) { keyboardOffsetPx.toDp() }
    val keyboard = LocalSoftwareKeyboardController.current
    var terminalInputView by remember { mutableStateOf<TerminalInputView?>(null) }
    var composingText by remember { mutableStateOf("") }
    var ctrl by rememberSaveable { mutableStateOf(false) }
    var alt by rememberSaveable { mutableStateOf(false) }
    var renderWidth by remember { mutableIntStateOf(0) }
    var renderHeight by remember { mutableIntStateOf(0) }
    val scrollOffsetState = remember(connection, liveScreen.columns, liveScreen.resetGeneration) { mutableIntStateOf(0) }
    var scrollOffsetRows by scrollOffsetState
    var priorSnapshotRows by remember(scrollOffsetState) { mutableIntStateOf(screen.rows) }
    var scrollRemainder by remember(scrollOffsetState) { mutableStateOf(0f) }
    var selection by selectionUi::selection
    val fontPath = settings.terminalFontPath
    val textSizePx = with(density) { settings.terminalFontSize.sp.toPx() }
    val palette = TerminalPalette.fromId(settings.terminalColorScheme)
    val renderer = remember(fontPath, textSizePx, palette) {
        val typeface = fontPath?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() } ?: Typeface.MONOSPACE
        TerminalGridRenderer(typeface, textSizePx, palette)
    }
    val cellWidth = renderer.cellWidth
    val cellHeight = renderer.cellHeight
    val visibleRows = (renderHeight / cellHeight).toInt().coerceAtLeast(1)
    val followCursorFirstRow = TerminalViewport.firstVisibleRow(screen.rows, screen.cursorRow, visibleRows)
    val firstVisibleRow = (followCursorFirstRow - scrollOffsetRows)
        .coerceIn(0, (screen.rows - visibleRows).coerceAtLeast(0))
    val lastVisibleRow = (firstVisibleRow + visibleRows).coerceAtMost(screen.rows)
    val links = remember(screen, firstVisibleRow, lastVisibleRow) { TerminalLinks.find(screen, firstVisibleRow, lastVisibleRow) }
    val currentLinks by rememberUpdatedState(links)
    var linkToCopy by remember(connection, liveScreen.resetGeneration) { mutableStateOf<String?>(null) }
    val currentScreen by rememberUpdatedState(screen)
    val currentFirstRow by rememberUpdatedState(firstVisibleRow)
    val currentFollowRow by rememberUpdatedState(followCursorFirstRow)
    var surfaceOrigin by remember { mutableStateOf(Offset.Zero) }

    fun positionAt(offset: Offset): TerminalPosition = TerminalPosition(
        row = (currentFirstRow + (offset.y / cellHeight).toInt()).coerceIn(0, currentScreen.rows - 1),
        column = (offset.x / cellWidth).toInt().coerceIn(0, currentScreen.columns - 1),
    )

    fun moveSelectionHandle(absolutePosition: Offset, isStart: Boolean, fixedEndpoint: TerminalPosition) {
        val edge = cellHeight * 1.5f
        if (absolutePosition.y < edge && scrollOffsetRows < followCursorFirstRow) {
            scrollOffsetRows++
        } else if (absolutePosition.y > renderHeight - edge && scrollOffsetRows > 0) {
            scrollOffsetRows--
        }
        val targetFirstRow = (followCursorFirstRow - scrollOffsetRows)
            .coerceIn(0, (screen.rows - visibleRows).coerceAtLeast(0))
        val row = (targetFirstRow + (absolutePosition.y / cellHeight).toInt() - 1).coerceIn(0, screen.rows - 1)
        val column = ((absolutePosition.x / cellWidth).toInt() - if (isStart) 0 else 1).coerceIn(0, screen.columns - 1)
        val candidate = TerminalPosition(row, column)
        val candidateIndex = row * screen.columns + column
        val fixedIndex = fixedEndpoint.row * screen.columns + fixedEndpoint.column
        val constrained = when {
            isStart && candidateIndex > fixedIndex -> fixedEndpoint
            !isStart && candidateIndex < fixedIndex -> fixedEndpoint
            else -> candidate
        }
        selection = if (isStart) TerminalSelection(constrained, fixedEndpoint) else TerminalSelection(fixedEndpoint, constrained)
        selectionUi.dragPosition = Offset(absolutePosition.x.coerceIn(0f, renderWidth.toFloat()), absolutePosition.y - cellHeight / 2f)
    }

    fun sendRaw(text: String) {
        if (enabled) connection?.write(text)
    }

    fun sendText(text: String) {
        // Batch ordinary IME/clipboard text so a large paste does not enqueue one I/O per codepoint.
        if (enabled) {
            if (ctrl || alt) {
                var first = true
                val bytes = java.io.ByteArrayOutputStream()
                text.codePoints().forEach { codePoint ->
                    bytes.write(TerminalInputEncoder.encode(codePoint, ctrl = first && ctrl, alt = first && alt))
                    first = false
                }
                connection?.write(bytes.toByteArray())
            } else connection?.write(text)
        }
        ctrl = false
        alt = false
        scrollOffsetRows = 0
    }

    fun focusTerminalInput() {
        terminalInputView?.focusAndShowKeyboard() ?: keyboard?.show()
    }

    fun sendKey(sequence: String) {
        selectionUi.clear()
        val value = if (alt) "\u001b$sequence" else sequence
        if (enabled) connection?.write(value)
        if (sequence.length == 1 && sequence[0].code >= 32) { ctrl = false; alt = false }
    }

    LaunchedEffect(renderWidth, renderHeight, cellWidth, cellHeight, connection) {
        if (renderWidth > 0 && renderHeight > 0 && connection != null) {
            delay(TERMINAL_RESIZE_SETTLE_MILLIS)
            connection.resize(
                (renderWidth / cellWidth).toInt().coerceIn(2, 500),
                (renderHeight / cellHeight).toInt().coerceIn(2, 300),
                renderWidth, renderHeight,
            )
        }
    }
    LaunchedEffect(screen.rows, followCursorFirstRow, liveScreen.resetGeneration) {
        val added = (screen.rows - priorSnapshotRows).coerceAtLeast(0)
        if (scrollOffsetRows > 0) scrollOffsetRows = (scrollOffsetRows + added).coerceAtMost(followCursorFirstRow)
        priorSnapshotRows = screen.rows
    }
    LaunchedEffect(connection, liveScreen.resetGeneration) {
        selectionUi.clear()
        terminalInputView?.discardComposingInput()
        ctrl = false
        alt = false
    }
    DisposableEffect(connection) {
        onDispose { terminalInputView?.clearFocus() }
    }

    Column(
        Modifier.fillMaxSize().background(Color(palette.background))
            .padding(horizontal = 4.dp).padding(bottom = keyboardOffsetDp)
            .onGloballyPositioned { surfaceOrigin = it.positionInRoot() }
            .dismissTerminalSelectionOnTap(selectionUi) { surfaceOrigin },
    ) {
        Box(
            Modifier.weight(1f).fillMaxWidth().background(Color(palette.background))
                // Initial PTY sizing and session resets replace the remembered scroll state.
                // Restart gestures with that state, or they keep writing to a discarded buffer.
                .pointerInput(connection, scrollOffsetState, selectionUi, selection == null) {
                    val selectingAtStart = selection != null
                    detectTapGestures(onTap = { offset ->
                        if (selectingAtStart) selectionUi.clear() else {
                            val link = currentLinks.firstOrNull { it.contains(positionAt(offset), currentScreen.columns) }
                            if (link != null) {
                                terminalInputView?.clearFocus()
                                keyboard?.hide()
                                linkToCopy = link.text
                            } else {
                                scrollOffsetRows = 0
                                focusTerminalInput()
                            }
                        }
                    })
                }
                .pointerInput(connection, scrollOffsetState, selectionUi, cellHeight, selection == null) {
                    if (selection == null) detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        scrollRemainder += dragAmount
                        val rowDelta = (scrollRemainder / cellHeight).toInt()
                        if (rowDelta != 0) {
                            scrollOffsetRows = (scrollOffsetRows + rowDelta).coerceIn(0, currentFollowRow)
                            scrollRemainder -= rowDelta * cellHeight
                        }
                    }
                }
                .pointerInput(connection, scrollOffsetState, selectionUi, screen.columns, renderer) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            terminalInputView?.finishComposingInput()
                            terminalInputView?.clearFocus()
                            keyboard?.hide()
                            selectionUi.snapshot = currentScreen
                            selection = TerminalSelectionText.wordAt(currentScreen, positionAt(offset))
                            selectionUi.dragPosition = offset
                            selectionUi.actionBounds = null
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            selection = selection?.copy(extent = positionAt(change.position))
                            selectionUi.dragPosition = change.position
                        },
                        onDragEnd = { selectionUi.dragPosition = null },
                        onDragCancel = { selectionUi.dragPosition = null },
                    )
                }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                    // Leave Backspace/Enter to the InputConnection so local CJK composition can
                    // finish or delete candidates before any key is sent to the remote program.
                    val sequence = when (native.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> "\u001b[A"
                        KeyEvent.KEYCODE_DPAD_DOWN -> "\u001b[B"
                        KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001b[C"
                        KeyEvent.KEYCODE_DPAD_LEFT -> "\u001b[D"
                        KeyEvent.KEYCODE_MOVE_HOME -> "\u001b[H"
                        KeyEvent.KEYCODE_MOVE_END -> "\u001b[F"
                        KeyEvent.KEYCODE_PAGE_UP -> "\u001b[5~"
                        KeyEvent.KEYCODE_PAGE_DOWN -> "\u001b[6~"
                        KeyEvent.KEYCODE_TAB -> "\t"
                        KeyEvent.KEYCODE_ESCAPE -> "\u001b"
                        else -> null
                    }
                    if (sequence != null) { terminalInputView?.finishComposingInput(); sendRaw(sequence); true }
                    else if (native.isCtrlPressed && native.unicodeChar != 0) { connection?.write(byteArrayOf((native.unicodeChar and 0x1f).toByte())); true }
                    else if (native.isAltPressed && native.unicodeChar != 0) { sendRaw("\u001b" + String(Character.toChars(native.unicodeChar))); true }
                    else false
                },
        ) {
            Canvas(
                Modifier.fillMaxSize().semantics { contentDescription = "terminal-output" }
                    .onSizeChanged { renderWidth = it.width; renderHeight = it.height },
            ) {
                drawIntoCanvas { renderer.draw(it.nativeCanvas, screen, firstVisibleRow, lastVisibleRow, selection, enabled, links) }
            }
            AndroidView(
                factory = { context -> TerminalInputView(context).also { terminalInputView = it } },
                update = {
                    terminalInputView = it
                    it.onText = ::sendText
                    it.onBackspace = { sendRaw("\u007f") }
                    it.onEnter = { sendRaw("\r") }
                    it.onTerminalKey = ::sendRaw
                    it.onComposition = { value -> composingText = value }
                },
                modifier = Modifier.size(1.dp).align(Alignment.BottomStart),
            )
            if (composingText.isNotEmpty()) {
                Text(
                    composingText,
                    color = Color(palette.foreground),
                    modifier = Modifier.align(Alignment.BottomStart).background(Color(0xff1d2939), RoundedCornerShape(6.dp)).padding(8.dp),
                )
            }
            selection?.let { activeSelection ->
                val ordered = TerminalSelectionText.normalize(screen, activeSelection).ordered(screen.columns)
                val startCenter = Offset(ordered.first.column * cellWidth, (ordered.first.row - firstVisibleRow + 1) * cellHeight)
                val endCenter = Offset((ordered.second.column + 1) * cellWidth, (ordered.second.row - firstVisibleRow + 1) * cellHeight)
                if (ordered.first.row in firstVisibleRow until lastVisibleRow) {
                    TerminalSelectionHandle(
                        center = startCenter, isStart = true,
                        onDragStart = { selectionUi.dragPosition = startCenter - Offset(0f, cellHeight / 2f) },
                        onPosition = { point, fixed -> moveSelectionHandle(point, true, fixed) },
                        fixedEndpoint = ordered.second,
                        onDragEnd = { selectionUi.dragPosition = null },
                    )
                }
                if (ordered.second.row in firstVisibleRow until lastVisibleRow) {
                    TerminalSelectionHandle(
                        center = endCenter, isStart = false,
                        onDragStart = { selectionUi.dragPosition = endCenter - Offset(0f, cellHeight / 2f) },
                        onPosition = { point, fixed -> moveSelectionHandle(point, false, fixed) },
                        fixedEndpoint = ordered.first,
                        onDragEnd = { selectionUi.dragPosition = null },
                    )
                }
                if (selectionUi.dragPosition == null) {
                    var toolbarSize by remember { mutableStateOf(IntSize.Zero) }
                    val gap = with(density) { 10.dp.toPx() }
                    val anchorX = if (ordered.first.row == ordered.second.row) (startCenter.x + endCenter.x) / 2f else startCenter.x
                    val top = startCenter.y - cellHeight - toolbarSize.height - gap
                    val toolbarY = if (top >= 0f) top else endCenter.y + gap + with(density) { 14.dp.toPx() }
                    Row(
                        Modifier.offset {
                            IntOffset(
                                (anchorX - toolbarSize.width / 2f).toInt().coerceIn(0, (renderWidth - toolbarSize.width).coerceAtLeast(0)),
                                toolbarY.toInt().coerceIn(0, (renderHeight - toolbarSize.height).coerceAtLeast(0)),
                            )
                        }.zIndex(5f).onSizeChanged { toolbarSize = it }
                            .onGloballyPositioned { selectionUi.actionBounds = it.boundsInRoot() }
                            .semantics { contentDescription = "terminal-selection-actions" }
                            .background(Color(0xff1d2939), RoundedCornerShape(12.dp)).padding(horizontal = 4.dp),
                    ) {
                        TextButton(onClick = {
                            val copied = TerminalSelectionText.extract(screen, activeSelection)
                            if (copied.isNotEmpty()) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("terminal", copied))
                            }
                            selectionUi.clear()
                        }) { Text(uiText(R.string.action_copy), color = Color(0xffe8edf5)) }
                        TextButton(enabled = enabled, onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val value = clipboard.primaryClip?.let { clip ->
                                (0 until clip.itemCount).joinToString("\n") { clip.getItemAt(it).coerceToText(context).toString() }
                            }.orEmpty()
                            selectionUi.clear()
                            terminalInputView?.pasteText(value)
                            focusTerminalInput()
                        }) { Text(uiText(R.string.terminal_paste), color = Color(0xffe8edf5)) }
                    }
                } else {
                    TerminalSelectionMagnifier(renderer, screen, firstVisibleRow, lastVisibleRow, activeSelection,
                        selectionUi.dragPosition!!, renderWidth, renderHeight)
                }
            }
            if (scrollOffsetRows > 0 && selection == null) {
                Text("↑ $scrollOffsetRows", color = Color(0xffe8edf5), style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color(0xcc1d2939), RoundedCornerShape(8.dp)).padding(8.dp))
            }
        }
        TerminalKeyToolbar(
            ctrl = ctrl, alt = alt,
            onCtrl = { selectionUi.clear(); terminalInputView?.finishComposingInput(); ctrl = !ctrl; focusTerminalInput() },
            onAlt = { selectionUi.clear(); terminalInputView?.finishComposingInput(); alt = !alt; focusTerminalInput() },
            onKey = { sequence -> terminalInputView?.finishComposingInput(); sendKey(sequence); focusTerminalInput() },
        )
    }
    linkToCopy?.let { link ->
        AlertDialog(
            onDismissRequest = { linkToCopy = null },
            title = { Text(uiText(R.string.terminal_link)) },
            text = {
                SelectionContainer {
                    Text(link, Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("terminal-link", link))
                    linkToCopy = null
                }) { Text(uiText(R.string.action_copy)) }
            },
            dismissButton = {
                TextButton(onClick = { linkToCopy = null }) { Text(uiText(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun TerminalSelectionMagnifier(
    renderer: TerminalGridRenderer,
    screen: TerminalSnapshot,
    firstRow: Int,
    lastRow: Int,
    selection: TerminalSelection,
    source: Offset,
    viewportWidth: Int,
    viewportHeight: Int,
) {
    val density = LocalDensity.current
    val width = with(density) { 144.dp.toPx() }
    val height = with(density) { 64.dp.toPx() }
    val gap = with(density) { 30.dp.toPx() }
    Canvas(
        Modifier.offset {
            IntOffset(
                (source.x - width / 2f).toInt().coerceIn(0, (viewportWidth - width.toInt()).coerceAtLeast(0)),
                (source.y - height - gap).toInt().coerceIn(0, (viewportHeight - height.toInt()).coerceAtLeast(0)),
            )
        }.size(144.dp, 64.dp).zIndex(6f).clip(RoundedCornerShape(28.dp))
            .background(Color(renderer.palette.background)).border(1.5.dp, Color(0xffa9b5c8), RoundedCornerShape(28.dp))
            .semantics { contentDescription = "terminal-selection-magnifier" },
    ) {
        drawIntoCanvas {
            val native = it.nativeCanvas
            native.save()
            native.translate(size.width / 2f, size.height / 2f)
            native.scale(1.65f, 1.65f)
            native.translate(-source.x, -source.y)
            renderer.draw(native, screen, firstRow, lastRow, selection, false)
            native.restore()
        }
    }
}

private const val TERMINAL_RESIZE_SETTLE_MILLIS = 120L

private data class TerminalTarget(
    val id: String,
    val label: String,
    val server: SshServerConfig? = null,
    val linuxId: String? = null,
)

@Composable
private fun TerminalSelectionHandle(
    center: Offset,
    isStart: Boolean,
    fixedEndpoint: TerminalPosition,
    onDragStart: () -> Unit,
    onPosition: (Offset, TerminalPosition) -> Unit,
    onDragEnd: () -> Unit,
) {
    val density = LocalDensity.current
    // Preserve a comfortable touch target while drawing only a small knob BELOW the text.
    val target = 40.dp
    val halfTarget = with(density) { target.toPx() / 2f }
    val inset = with(density) { 8.dp.toPx() }
    val latestCenter by rememberUpdatedState(center)
    val latestFixed by rememberUpdatedState(fixedEndpoint)
    val latestOnPosition by rememberUpdatedState(onPosition)
    val latestStart by rememberUpdatedState(onDragStart)
    val latestEnd by rememberUpdatedState(onDragEnd)
    Canvas(
        Modifier.offset { IntOffset((center.x - halfTarget).toInt(), (center.y - inset).toInt()) }
            .size(target).zIndex(4f)
            .semantics { contentDescription = if (isStart) "terminal-selection-start" else "terminal-selection-end" }
            .pointerInput(isStart) {
                var dragCenter = Offset.Zero
                var fixed = fixedEndpoint
                detectDragGestures(
                    onDragStart = { dragCenter = latestCenter; fixed = latestFixed; latestStart() },
                    onDrag = { change, amount -> change.consume(); dragCenter += amount; latestOnPosition(dragCenter, fixed) },
                    onDragEnd = { latestEnd() },
                    onDragCancel = { latestEnd() },
                )
            },
    ) {
        val knob = Offset(size.width / 2f, inset + 10.dp.toPx())
        drawLine(Color(0xff7aa2f7), Offset(knob.x, inset), knob, strokeWidth = 2.dp.toPx())
        drawCircle(Color(0xff7aa2f7), radius = 5.dp.toPx(), center = knob)
        drawCircle(Color(0xffdbe7ff), radius = 3.dp.toPx(), center = knob)
    }
}

@Composable
private fun TerminalKeyToolbar(
    ctrl: Boolean,
    alt: Boolean,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onKey: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xff111827))
            .padding(horizontal = 4.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TerminalKey("Esc", Modifier.weight(1f)) { onKey("\u001b") }
            TerminalKey("/", Modifier.weight(1f)) { onKey("/") }
            TerminalKey("-", Modifier.weight(1f)) { onKey("-") }
            TerminalKey("Home", Modifier.weight(1f)) { onKey("\u001b[H") }
            TerminalKey("↑", Modifier.weight(1f)) { onKey("\u001b[A") }
            TerminalKey("End", Modifier.weight(1f)) { onKey("\u001b[F") }
            TerminalKey("PgUp", Modifier.weight(1f)) { onKey("\u001b[5~") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TerminalKey("Tab", Modifier.weight(1f)) { onKey("\t") }
            TerminalKey("Ctrl", Modifier.weight(1f), selected = ctrl, onClick = onCtrl)
            TerminalKey("Alt", Modifier.weight(1f), selected = alt, onClick = onAlt)
            TerminalKey("←", Modifier.weight(1f)) { onKey("\u001b[D") }
            TerminalKey("↓", Modifier.weight(1f)) { onKey("\u001b[B") }
            TerminalKey("→", Modifier.weight(1f)) { onKey("\u001b[C") }
            TerminalKey("PgDn", Modifier.weight(1f)) { onKey("\u001b[6~") }
        }
    }
}

@Composable
private fun TerminalKey(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier
            .height(38.dp)
            .background(if (selected) Color(0xff7aa2f7) else Color(0xff1d2939), shape)
            .border(1.dp, if (selected) Color(0xff9db8ff) else Color(0xff344054), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Color(0xff07101f) else Color(0xffd7e0ea), style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
    }
}
