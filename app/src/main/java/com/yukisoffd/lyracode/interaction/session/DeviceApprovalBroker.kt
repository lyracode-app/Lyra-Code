package com.yukisoffd.lyracode.interaction.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

internal data class DeviceApproval(val id: String, val title: String, val detail: String,
    val packageName: String?, val secondConfirmation: Boolean, val stage: Int = 1)

/** Exact single-use foreground approval. Scripts never receive the response channel. */
internal object DeviceApprovalBroker {
    private var pendingSession = 0L
    private var pendingSnapshot: String? = null
    private var pending: DeviceApproval? = null
    private var answer: CompletableDeferred<Boolean>? = null
    suspend fun request(title: String, detail: String, packageName: String? = null, twice: Boolean = false): Boolean {
        require(detail.length <= 16000) { "确认内容过长，请拆分操作。" }
        val initial = ManualControlController.state.value
        val session = initial.sessionId
        val snapshot = initial.latestSnapshot?.snapshotId
        val reply = CompletableDeferred<Boolean>()
        val proposal = DeviceApproval(UUID.randomUUID().toString(), title, detail, packageName, twice)
        synchronized(this) {
            check(pending == null) { "已有操作等待确认。" }
            pendingSession = session; pendingSnapshot = snapshot
            pending = proposal; answer = reply
            ManualControlController.setApproval(proposal)
        }
        try {
            while (!reply.isCompleted) {
                val state = ManualControlController.state.value
                if (!state.isActive() || state.sessionId != session || !state.chat.running ||
                    (packageName != null && (state.targetPackage != packageName || state.latestSnapshot?.snapshotId != snapshot))) return false
                withTimeoutOrNull(150) { reply.await() }
            }
            return reply.await()
        } finally {
            synchronized(this) {
                if (answer === reply) { pending = null; answer = null; ManualControlController.setApproval(null) }
            }
        }
    }
    @Synchronized fun respond(id: String, approved: Boolean) {
        val current = pending?.takeIf { it.id == id } ?: return
        val state = ManualControlController.state.value
        if (!state.isActive() || !state.chat.running || state.sessionId != pendingSession ||
            (current.packageName != null && (state.targetPackage != current.packageName || state.latestSnapshot?.snapshotId != pendingSnapshot))) {
            answer?.complete(false); return
        }
        if (approved && current.secondConfirmation && current.stage == 1) {
            pending = current.copy(id = UUID.randomUUID().toString(), stage = 2)
            ManualControlController.setApproval(pending)
        } else answer?.complete(approved)
    }
    @Synchronized fun cancel() { answer?.complete(false) }
}
