package com.yukisoffd.lyracode.ssh

import android.util.Log
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import com.yukisoffd.lyracode.data.SshServerConfig
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlin.math.min

enum class SshTerminalStatus { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }

enum class SshTerminalMessage {
    IDLE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    DISCONNECTED,
    HOST_KEY_CHANGED,
    AUTH_FAILED,
    TIMEOUT,
    CONNECTION_FAILED,
}

data class SshTerminalState(
    val status: SshTerminalStatus = SshTerminalStatus.DISCONNECTED,
    val message: SshTerminalMessage = SshTerminalMessage.IDLE,
    val reconnectAttempt: Int = 0,
    val connectedAt: Long = 0L,
)

/** Transport-neutral contract shared by remote SSH and the local Debian terminal. */
interface TerminalSession : AutoCloseable {
    val state: StateFlow<SshTerminalState>
    val screen: StateFlow<TerminalSnapshot>
    fun connect()
    fun disconnect(markRequested: Boolean = true)
    fun write(bytes: ByteArray): Boolean
    fun write(text: String): Boolean
    fun clear()
    fun resize(newCols: Int, newRows: Int, widthPx: Int, heightPx: Int)
}

/**
 * Owns app-long SSH shell channels. A screen can detach and attach again without closing the PTY,
 * so running jobs and editors survive navigation. Sessions use SSH keepalives and bounded reconnects.
 */
class SshTerminalSessionManager(private val executor: SshExecutor) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = ConcurrentHashMap<String, TerminalConnection>()

    fun connection(server: SshServerConfig): TerminalConnection =
        connections.getOrPut(server.id) { TerminalConnection(server, executor, scope) }

    fun remove(serverId: String) {
        connections.remove(serverId)?.close()
    }

    override fun close() {
        connections.values.forEach(TerminalConnection::close)
        connections.clear()
        scope.cancel()
    }
}

class TerminalConnection internal constructor(
    initialServer: SshServerConfig,
    private val executor: SshExecutor,
    private val scope: CoroutineScope,
) : TerminalSession {
    @Volatile private var server = initialServer
    @Volatile private var transport: TerminalTransport? = null
    @Volatile private var requestedClose = false
    @Volatile private var cols = 80
    @Volatile private var rows = 24
    @Volatile private var pixelWidth = 0
    @Volatile private var pixelHeight = 0
    private var connectionJob: Job? = null
    private val writeLock = Any()
    private val generation = AtomicLong(0L)
    private val terminal = TerminalEmulator(cols, rows)
    private val writeQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private val resizeQueue = Channel<TerminalResize>(Channel.CONFLATED)
    private val writerJob = scope.launch {
        for (bytes in writeQueue) writeOnIo(bytes)
    }
    private val resizeJob = scope.launch {
        for (resize in resizeQueue) resizeOnIo(resize)
    }

    private val _state = MutableStateFlow(SshTerminalState())
    override val state: StateFlow<SshTerminalState> = _state.asStateFlow()
    private val _output = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val outputBytes: SharedFlow<ByteArray> = _output.asSharedFlow()
    private val _screen = MutableStateFlow(terminal.snapshot())
    override val screen: StateFlow<TerminalSnapshot> = _screen.asStateFlow()

    override fun connect() = connect(server)

    fun connect(updatedServer: SshServerConfig) {
        server = updatedServer
        if (connectionJob?.isActive == true || transport?.channel?.isConnected == true) return
        requestedClose = false
        startConnectionLoop()
    }

    fun reconnect(updatedServer: SshServerConfig = server) {
        server = updatedServer
        requestedClose = false
        generation.incrementAndGet()
        connectionJob?.cancel()
        connectionJob = null
        detachAndCloseTransport()
        startConnectionLoop()
    }

    private fun startConnectionLoop() {
        val myGeneration = generation.incrementAndGet()
        connectionJob = scope.launch { runConnectionLoop(myGeneration) }
    }

    /** Queues terminal input without performing socket I/O on Android's main/IME thread. */
    override fun write(bytes: ByteArray): Boolean {
        val active = transport
        Log.d(
            TAG,
            "enqueue bytes=${bytes.size} channel=${active?.channel?.isConnected == true} " +
                "session=${active?.session?.isConnected == true}",
        )
        if (active?.channel?.isConnected != true || active.session.isConnected != true) return false
        return writeQueue.trySend(bytes.copyOf()).isSuccess
    }

    private fun writeOnIo(bytes: ByteArray) = runCatching {
        synchronized(writeLock) {
            val active = transport
            val stream = active?.output
            Log.d(
                TAG,
                "write bytes=${bytes.size} output=${stream != null} " +
                    "channel=${active?.channel?.isConnected == true} session=${active?.session?.isConnected == true}",
            )
            if (stream == null || active.channel.isConnected != true || active.session.isConnected != true) {
                return@runCatching false
            }
            try {
                stream.write(bytes)
                stream.flush()
            } catch (error: Exception) {
                // isConnected can briefly remain true after the channel's output stream closes.
                // Detach this exact transport so the connection loop cannot leave a false
                // CONNECTED state and subsequent input cannot keep targeting a dead stream.
                if (transport === active) transport = null
                active.close()
                throw error
            }
        }
        Log.d(TAG, "write complete bytes=${bytes.size}")
        true
    }.onFailure { Log.e(TAG, "write failed type=${it.javaClass.simpleName}", it) }
        .getOrDefault(false)

    override fun write(text: String): Boolean = write(text.toByteArray(Charsets.UTF_8))

    override fun clear() = synchronized(terminal) {
        terminal.clear()
        _screen.value = terminal.snapshot()
    }

    @Synchronized
    override fun resize(newCols: Int, newRows: Int, widthPx: Int, heightPx: Int) {
        val targetCols = newCols.coerceIn(2, 500)
        val targetRows = newRows.coerceIn(2, 300)
        val targetWidth = widthPx.coerceAtLeast(0)
        val targetHeight = heightPx.coerceAtLeast(0)
        if (targetCols == cols && targetRows == rows && targetWidth == pixelWidth && targetHeight == pixelHeight) return
        cols = targetCols
        rows = targetRows
        pixelWidth = targetWidth
        pixelHeight = targetHeight
        synchronized(terminal) {
            terminal.resize(targetCols, targetRows)
            _screen.value = terminal.snapshot()
        }
        if (transport?.channel?.isConnected == true && transport?.session?.isConnected == true) {
            resizeQueue.trySend(TerminalResize(targetCols, targetRows, targetWidth, targetHeight))
        }
    }

    private fun resizeOnIo(resize: TerminalResize) = runCatching {
        synchronized(writeLock) {
            // A conflated request can still become stale while waiting for an in-flight write.
            if (
                resize.columns != cols || resize.rows != rows ||
                resize.widthPx != pixelWidth || resize.heightPx != pixelHeight
            ) return@runCatching false
            val active = transport ?: return@runCatching false
            if (active.channel.isConnected != true || active.session.isConnected != true) {
                return@runCatching false
            }
            active.channel.setPtySize(resize.columns, resize.rows, resize.widthPx, resize.heightPx)
        }
        Log.d(TAG, "PTY resized cols=${resize.columns} rows=${resize.rows}")
        true
    }.onFailure { Log.w(TAG, "PTY resize failed type=${it.javaClass.simpleName}", it) }
        .getOrDefault(false)

    private suspend fun runConnectionLoop(myGeneration: Long) {
        var reconnecting = false
        var attempts = 0
        while (isCurrent(myGeneration) && !requestedClose && currentCoroutineContext().isActive) {
            val result = connectAndRead(reconnecting, myGeneration)
            if (!isCurrent(myGeneration) || requestedClose || !currentCoroutineContext().isActive) return
            if (!result.connected && !reconnecting) return
            attempts = if (result.connectedDurationMs >= STABLE_CONNECTION_MS) 1 else attempts + 1
            reconnecting = true
            _state.value = SshTerminalState(
                SshTerminalStatus.RECONNECTING,
                SshTerminalMessage.RECONNECTING,
                reconnectAttempt = attempts,
            )
            delay(min(MAX_RECONNECT_DELAY_MS, attempts * RECONNECT_STEP_DELAY_MS))
        }
    }

    private suspend fun connectAndRead(autoReconnect: Boolean, myGeneration: Long): ConnectionResult {
        _state.value = SshTerminalState(
            if (autoReconnect) SshTerminalStatus.RECONNECTING else SshTerminalStatus.CONNECTING,
            if (autoReconnect) SshTerminalMessage.RECONNECTING else SshTerminalMessage.CONNECTING,
        )
        var localTransport: TerminalTransport? = null
        var connectedAt = 0L
        try {
            val newSession = executor.createSession(server)
            newSession.connect(server.timeoutSeconds.coerceIn(5, 120) * 1000)
            val newChannel = newSession.openChannel("shell") as ChannelShell
            newChannel.setPty(true)
            newChannel.setPtyType("xterm-256color", cols, rows, pixelWidth, pixelHeight)
            // RFC 8160: let the remote line discipline erase a UTF-8 character as one unit.
            newChannel.setTerminalMode(byteArrayOf(42, 0, 0, 0, 1, 0))
            newChannel.setEnv("TERM", "xterm-256color")
            newChannel.setEnv("COLORTERM", "truecolor")
            val newInput = newChannel.inputStream
            val newOutput = newChannel.outputStream
            val connectedTransport = TerminalTransport(newSession, newChannel, newInput, newOutput)
            localTransport = connectedTransport
            newChannel.connect(15_000)
            synchronized(writeLock) {
                if (!isCurrent(myGeneration) || requestedClose) {
                    connectedTransport.close()
                    return ConnectionResult(false, 0L)
                }
                transport = connectedTransport
                synchronized(terminal) {
                    terminal.resetForNewSession()
                    _screen.value = terminal.snapshot()
                }
            }
            resizeQueue.trySend(TerminalResize(cols, rows, pixelWidth, pixelHeight))
            Log.d(TAG, "PTY connected cols=$cols rows=$rows")
            _state.value = SshTerminalState(
                status = SshTerminalStatus.CONNECTED,
                message = SshTerminalMessage.CONNECTED,
                connectedAt = System.currentTimeMillis(),
            )
            connectedAt = System.currentTimeMillis()

            val buffer = ByteArray(16 * 1024)
            while (currentCoroutineContext().isActive && !requestedClose && newChannel.isConnected) {
                val count = newInput.read(buffer)
                if (count < 0) break
                if (count > 0) {
                    Log.d(TAG, "PTY read bytes=$count")
                    val received = buffer.copyOf(count)
                    synchronized(terminal) {
                        if (!isCurrent(myGeneration) || requestedClose || transport !== connectedTransport) {
                            return ConnectionResult(false, 0L)
                        }
                        terminal.accept(received)
                        _screen.value = terminal.snapshot()
                    }
                    _output.emit(received)
                }
            }
            return ConnectionResult(true, System.currentTimeMillis() - connectedAt)
        } catch (error: Exception) {
            Log.e(TAG, "PTY connection failed type=${error.javaClass.simpleName}", error)
            if (!requestedClose && !autoReconnect && connectedAt == 0L) {
                _state.value = SshTerminalState(SshTerminalStatus.ERROR, friendlyError(error))
            }
            return ConnectionResult(
                connected = connectedAt != 0L,
                connectedDurationMs = if (connectedAt == 0L) 0L else System.currentTimeMillis() - connectedAt,
            )
        } finally {
            localTransport?.let { detachAndCloseTransport(it) }
        }
    }

    override fun disconnect(markRequested: Boolean) {
        requestedClose = markRequested
        generation.incrementAndGet()
        connectionJob?.cancel()
        connectionJob = null
        detachAndCloseTransport()
        if (markRequested) _state.value = SshTerminalState(SshTerminalStatus.DISCONNECTED, SshTerminalMessage.DISCONNECTED)
    }

    private fun detachAndCloseTransport(expected: TerminalTransport? = null) {
        val detached = synchronized(writeLock) {
            val active = transport
            if (expected != null && active !== expected) return@synchronized expected
            transport = null
            active
        }
        detached?.close()
    }

    private fun isCurrent(myGeneration: Long): Boolean = generation.get() == myGeneration

    private fun friendlyError(error: Throwable): SshTerminalMessage {
        val detail = error.message.orEmpty()
        return when {
            detail.contains("HostKey has been changed", true) -> SshTerminalMessage.HOST_KEY_CHANGED
            detail.contains("Auth fail", true) -> SshTerminalMessage.AUTH_FAILED
            detail.contains("timeout", true) -> SshTerminalMessage.TIMEOUT
            else -> SshTerminalMessage.CONNECTION_FAILED
        }
    }

    override fun close() {
        writeQueue.close()
        resizeQueue.close()
        writerJob.cancel()
        resizeJob.cancel()
        disconnect(markRequested = true)
    }

    private data class ConnectionResult(val connected: Boolean, val connectedDurationMs: Long)
    private data class TerminalResize(
        val columns: Int,
        val rows: Int,
        val widthPx: Int,
        val heightPx: Int,
    )

    private class TerminalTransport(
        val session: Session,
        val channel: ChannelShell,
        val input: InputStream,
        val output: OutputStream,
    ) {
        fun close() {
            runCatching { channel.disconnect() }
            runCatching { session.disconnect() }
        }
    }

    private companion object {
        const val TAG = "LyraTerminalSsh"
        const val STABLE_CONNECTION_MS = 60_000L
        const val RECONNECT_STEP_DELAY_MS = 1_500L
        const val MAX_RECONNECT_DELAY_MS = 30_000L
    }
}
