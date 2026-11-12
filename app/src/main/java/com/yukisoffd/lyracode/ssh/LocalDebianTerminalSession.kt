package com.yukisoffd.lyracode.ssh

import android.content.Context
import android.util.Log
import com.yukisoffd.lyracode.debian.ProotInteractiveShell
import com.yukisoffd.lyracode.debian.ProotLinuxManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** App-long local Debian shell session backed by the `script` utility's PTY. */
class LocalProotTerminalSessionManager(
    context: Context,
    private val workspaceRoot: () -> String?,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtime = ProotLinuxManager.getInstance(context)
    private val connections = ConcurrentHashMap<String, LocalProotTerminalConnection>()
    private val deleteListener = runtime.addBeforeDeleteListener(::close)
    private val runtimeJob = scope.launch {
        runtime.state.collect { state ->
            val activeIds = state.instances.filter { it.enabled }.mapTo(mutableSetOf()) { it.id }
            connections.keys.filterNot { it in activeIds }.forEach(::close)
        }
    }

    fun connection(linuxId: String): LocalProotTerminalConnection = connections.getOrPut(linuxId) {
        LocalProotTerminalConnection(linuxId, runtime, workspaceRoot, scope)
    }

    fun close(linuxId: String) {
        connections.remove(linuxId)?.close()
    }

    override fun close() {
        connections.values.forEach(LocalProotTerminalConnection::close)
        connections.clear()
        runtimeJob.cancel()
        deleteListener.close()
        scope.cancel()
    }
}

class LocalProotTerminalConnection internal constructor(
    private val linuxId: String,
    private val runtime: ProotLinuxManager,
    private val workspaceRoot: () -> String?,
    private val scope: CoroutineScope,
) : TerminalSession {
    @Volatile private var interactiveShell: ProotInteractiveShell? = null
    @Volatile private var requestedClose = false
    @Volatile private var cols = 80
    @Volatile private var rows = 24
    private val generation = AtomicLong(0L)
    private var processJob: Job? = null
    private val terminal = TerminalEmulator(cols, rows)
    private val writeQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private val resizeQueue = Channel<TerminalResize>(Channel.CONFLATED)
    private val _state = MutableStateFlow(SshTerminalState())
    override val state: StateFlow<SshTerminalState> = _state.asStateFlow()
    private val _screen = MutableStateFlow(terminal.snapshot())
    override val screen: StateFlow<TerminalSnapshot> = _screen.asStateFlow()
    private val writerJob = scope.launch {
        for (bytes in writeQueue) {
            val active = interactiveShell?.process ?: continue
            runCatching {
                active.outputStream.write(bytes)
                active.outputStream.flush()
            }.onFailure { Log.e(TAG, "Local PTY write failed", it) }
        }
    }
    private val resizeJob = scope.launch {
        for (resize in resizeQueue) {
            val active = interactiveShell ?: continue
            if (active.process.isAlive) {
                runtime.resizeInteractiveShell(active, resize.columns, resize.rows)
            }
        }
    }

    override fun connect() {
        if (interactiveShell?.process?.isAlive == true || processJob?.isActive == true) return
        requestedClose = false
        val myGeneration = generation.incrementAndGet()
        processJob = scope.launch {
            _state.value = SshTerminalState(SshTerminalStatus.CONNECTING, SshTerminalMessage.CONNECTING)
            var started: ProotInteractiveShell? = null
            try {
                val active = runtime.startInteractiveShell(linuxId, workspaceRoot(), cols, rows)
                started = active
                if (generation.get() != myGeneration || requestedClose) {
                    active.process.destroyForcibly()
                    return@launch
                }
                synchronized(terminal) {
                    if (generation.get() != myGeneration || requestedClose) return@launch
                    interactiveShell = active
                    terminal.resetForNewSession()
                    _screen.value = terminal.snapshot()
                }
                resizeQueue.trySend(TerminalResize(cols, rows))
                _state.value = SshTerminalState(
                    SshTerminalStatus.CONNECTED,
                    SshTerminalMessage.CONNECTED,
                    connectedAt = System.currentTimeMillis(),
                )
                val buffer = ByteArray(16 * 1024)
                active.process.inputStream.use { input ->
                    while (!requestedClose && active.process.isAlive) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) {
                            synchronized(terminal) {
                                if (generation.get() != myGeneration || requestedClose || interactiveShell !== active) return@launch
                                terminal.accept(buffer.copyOf(count))
                                _screen.value = terminal.snapshot()
                            }
                        }
                    }
                }
                if (!requestedClose && generation.get() == myGeneration) {
                    _state.value = SshTerminalState(SshTerminalStatus.DISCONNECTED, SshTerminalMessage.DISCONNECTED)
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to start local Debian terminal", error)
                if (!requestedClose && generation.get() == myGeneration) {
                    _state.value = SshTerminalState(SshTerminalStatus.ERROR, SshTerminalMessage.CONNECTION_FAILED)
                }
            } finally {
                started?.let { active ->
                    if (interactiveShell === active) interactiveShell = null
                    runtime.closeInteractiveShell(active)
                }
            }
        }
    }

    override fun write(bytes: ByteArray): Boolean {
        if (interactiveShell?.process?.isAlive != true) return false
        return writeQueue.trySend(bytes.copyOf()).isSuccess
    }

    override fun write(text: String): Boolean = write(text.toByteArray(Charsets.UTF_8))

    override fun clear() = synchronized(terminal) {
        terminal.clear()
        _screen.value = terminal.snapshot()
    }

    @Synchronized
    override fun resize(newCols: Int, newRows: Int, widthPx: Int, heightPx: Int) {
        val targetCols = newCols.coerceIn(2, 500)
        val targetRows = newRows.coerceIn(2, 300)
        if (targetCols == cols && targetRows == rows) return
        cols = targetCols
        rows = targetRows
        synchronized(terminal) {
            terminal.resize(targetCols, targetRows)
            _screen.value = terminal.snapshot()
        }
        if (interactiveShell?.process?.isAlive == true) {
            resizeQueue.trySend(TerminalResize(targetCols, targetRows))
        }
    }

    override fun disconnect(markRequested: Boolean) {
        requestedClose = markRequested
        generation.incrementAndGet()
        processJob?.cancel()
        processJob = null
        interactiveShell?.let { active ->
            runCatching { active.process.outputStream.close() }
            active.process.destroy()
            if (active.process.isAlive) active.process.destroyForcibly()
            runtime.closeInteractiveShell(active)
        }
        interactiveShell = null
        if (markRequested) {
            _state.value = SshTerminalState(SshTerminalStatus.DISCONNECTED, SshTerminalMessage.DISCONNECTED)
        }
    }

    override fun close() {
        writeQueue.close()
        resizeQueue.close()
        writerJob.cancel()
        resizeJob.cancel()
        disconnect(true)
    }

    private companion object {
        const val TAG = "LyraTerminalDebian"
    }

    private data class TerminalResize(val columns: Int, val rows: Int)
}
