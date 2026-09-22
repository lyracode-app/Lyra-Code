package com.yukisoffd.lyracode.debian

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class ProotCommandExecutor(context: Context) {
    private val manager = ProotLinuxManager.getInstance(context)
    private val auditLogStore = com.yukisoffd.lyracode.data.AuditLogStore(context)

    fun isAvailable(): Boolean = manager.hasActiveInstances()

    fun inventoryForAgent(): String = manager.inventoryForAgent()

    fun hasAllFilesAccess(): Boolean = manager.hasAllFilesAccess()

    suspend fun execute(
        linuxId: String,
        command: String,
        workspaceRoot: String?,
        workDir: String?,
        timeoutSeconds: Int,
        background: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val id = runCatching {
            auditLogStore.add("proot", command, "linux_id=$linuxId\nwork_dir=${workDir.orEmpty()}").also { id ->
                auditLogStore.section(id, "execution.request", JSONObject()
                    .put("linux_id", linuxId).put("command", command)
                    .put("workspace_root", workspaceRoot ?: JSONObject.NULL).put("work_dir", workDir ?: JSONObject.NULL)
                    .put("timeout_seconds", timeoutSeconds).put("background", background).toString())
            }
        }.getOrDefault(-1L)
        try {
            executeCommand(linuxId, command, workspaceRoot, workDir, timeoutSeconds, background).also { result ->
                runCatching { auditLogStore.section(id, "execution.result", result) }
            }
        } catch (error: Exception) {
            runCatching { auditLogStore.section(id, "execution.error", error.toString()) }
            throw error
        } finally {
            runCatching { auditLogStore.section(id, "duration_ms", ((System.nanoTime() - started) / 1_000_000).toString()) }
        }
    }

    private suspend fun executeCommand(
        linuxId: String,
        command: String,
        workspaceRoot: String?,
        workDir: String?,
        timeoutSeconds: Int,
        background: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        require(linuxId.isNotBlank()) { "proot_command requires linux_id." }
        require(command.isNotBlank()) { "proot_command requires a non-empty command." }
        val workContext = resolveWorkContext(workspaceRoot, workDir)
        val executionId = nextExecutionId.getAndIncrement()
        val started = manager.startCommand(
            id = linuxId,
            workspaceRoot = workContext.workspaceRoot,
            guestWorkDir = workContext.guestWorkDir,
            command = command,
            executionId = executionId,
            background = background,
        )
        val process = started.process
        process.outputStream.close()

        val stdout = CapturedOutputBuffer()
        val stderr = CapturedOutputBuffer()
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
        var shellExitCode: Int? = null
        while (process.isAlive && shellExitCode == null && System.nanoTime() < deadlineNanos) {
            drainAvailable(process.inputStream, stdout)
            drainAvailable(process.errorStream, stderr)
            shellExitCode = readCompletionCode(started.completionFile)
            if (process.isAlive && shellExitCode == null) Thread.sleep(COMMAND_POLL_INTERVAL_MILLIS)
        }
        drainAvailable(process.inputStream, stdout)
        drainAvailable(process.errorStream, stderr)
        shellExitCode = shellExitCode ?: readCompletionCode(started.completionFile)

        val timedOut = process.isAlive && shellExitCode == null && System.nanoTime() >= deadlineNanos
        val supervisorPid = readSupervisorPid(started.supervisorPidFile)
        var retainedBackgroundProcesses = false
        if (timedOut) {
            manager.terminateCommandProcess(process, supervisorPid)
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        } else if (shellExitCode != null && process.isAlive) {
            // The command shell is done, but PRoot is still tracing one or more detached
            // descendants. Keep this exact PRoot Process alive; later calls get their own tracer.
            waitForNaturalProotExit(process, stdout, stderr)
            if (process.isAlive) {
                manager.retainBackgroundCommand(executionId, linuxId, process, supervisorPid)
                retainedBackgroundProcesses = true
            }
        }

        if (!process.isAlive) {
            captureRemaining(process.inputStream, stdout)
            captureRemaining(process.errorStream, stderr)
        } else if (timedOut) {
            drainAvailable(process.inputStream, stdout)
            drainAvailable(process.errorStream, stderr)
        }
        if (!retainedBackgroundProcesses) {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
        started.completionFile.delete()
        started.supervisorPidFile.delete()

        val processFinished = !process.isAlive
        val exitCode = when {
            timedOut -> 124
            shellExitCode != null -> shellExitCode
            processFinished -> process.exitValue()
            else -> 124
        }
        val stdoutResult = stdout.result()
        val stderrResult = stderr.result()

        JSONObject()
            .put("exit_code", exitCode)
            .put("stdout", stdoutResult.text)
            .put("stderr", stderrResult.text)
            .put("stdout_original_bytes", stdoutResult.originalBytes)
            .put("stderr_original_bytes", stderrResult.originalBytes)
            .put("stdout_truncated", stdoutResult.truncated)
            .put("stderr_truncated", stderrResult.truncated)
            .put("timed_out", timedOut)
            .put("background_requested", background)
            .put("background_processes_retained", retainedBackgroundProcesses)
            .put("environment", "internal-proot-linux")
            .put("linux_id", linuxId)
            .put("work_dir", workContext.guestWorkDir)
            .put("shared_storage_mounted", manager.hasAllFilesAccess())
            .toString()
    }

    private fun resolveWorkContext(workspaceRoot: String?, rawWorkDir: String?): ProotWorkContext {
        val workspace = workspaceRoot?.trim()?.takeIf(String::isNotBlank)?.let(::File)?.canonicalFile
            ?.takeIf(File::isDirectory)
        val raw = rawWorkDir.orEmpty().trim().replace('\\', '/')
        if (raw.isBlank() || raw == "." || raw == "./") {
            return ProotWorkContext(workspace, if (workspace == null) "/root" else WORKSPACE_DIR)
        }

        // Workspace metadata may contain an Android-private rootfs path. Translate it
        // to the bind mount before interpreting absolute paths as Linux guest paths.
        if (workspace != null && (raw == workspace.path || raw.startsWith(workspace.path + "/"))) {
            val relative = raw.removePrefix(workspace.path).trim('/')
            validateRelative(relative)
            val directory = File(workspace, relative).canonicalFile
            require(isInside(workspace, directory) && directory.isDirectory) {
                "proot_command workDir is not an accessible workspace directory: $raw"
            }
            return ProotWorkContext(workspace, if (relative.isEmpty()) WORKSPACE_DIR else "$WORKSPACE_DIR/$relative")
        }

        if (raw == WORKSPACE_DIR || raw.startsWith("$WORKSPACE_DIR/")) {
            require(workspace != null) { "workDir uses /workspace but no directly accessible workspace is selected." }
            val relative = raw.removePrefix(WORKSPACE_DIR).trim('/')
            validateRelative(relative)
            val hostDirectory = File(workspace, relative).canonicalFile
            require(isInside(workspace, hostDirectory) && hostDirectory.isDirectory) {
                "proot_command workDir is not an accessible workspace directory: $raw"
            }
            return ProotWorkContext(workspace, if (relative.isBlank()) WORKSPACE_DIR else "$WORKSPACE_DIR/$relative")
        }

        val primaryStorage = manager.sharedStorageRoot()
        val storageContainer = manager.sharedStorageContainer()
        val primaryRelative = when {
            raw == "/sdcard" -> ""
            raw.startsWith("/sdcard/") -> raw.removePrefix("/sdcard/")
            else -> null
        }
        if (primaryRelative != null) {
            require(manager.hasAllFilesAccess()) {
                "Access to shared storage outside the workspace requires Android's All files access permission."
            }
            validateRelative(primaryRelative)
            val hostDirectory = File(primaryStorage, primaryRelative).canonicalFile
            require(isInside(primaryStorage, hostDirectory) && hostDirectory.isDirectory) {
                "proot_command shared-storage workDir is not an accessible directory: $raw"
            }
            val guest = if (primaryRelative.isBlank()) "/sdcard" else "/sdcard/$primaryRelative"
            return ProotWorkContext(workspace, guest)
        }

        val storagePath = storageContainer.absolutePath.replace('\\', '/')
        if (raw == storagePath || raw.startsWith("$storagePath/")) {
            require(manager.hasAllFilesAccess()) {
                "Access to shared storage outside the workspace requires Android's All files access permission."
            }
            val storageRelative = raw.removePrefix(storagePath).trim('/')
            validateRelative(storageRelative)
            val hostDirectory = File(storageContainer, storageRelative).canonicalFile
            require(isInside(storageContainer, hostDirectory) && hostDirectory.isDirectory) {
                "proot_command shared-storage workDir is not an accessible directory: $raw"
            }
            return ProotWorkContext(workspace, if (storageRelative.isBlank()) "/storage" else "/storage/$storageRelative")
        }

        if (raw.startsWith('/')) {
            val normalized = java.nio.file.Paths.get(raw).normalize().toString().replace('\\', '/')
            require(normalized.startsWith('/')) { "Invalid Linux workDir: $raw" }
            return ProotWorkContext(workspace, normalized)
        }

        validateRelative(raw)
        if (workspace != null) {
            val hostDirectory = File(workspace, raw).canonicalFile
            require(isInside(workspace, hostDirectory) && hostDirectory.isDirectory) {
                "proot_command workDir is not an accessible workspace directory: $raw"
            }
            return ProotWorkContext(workspace, "$WORKSPACE_DIR/${raw.trim('/')}")
        }
        return ProotWorkContext(null, "/root/${raw.trim('/')}")
    }

    private fun validateRelative(relative: String) {
        require(relative.split('/').none { it == ".." }) { "proot_command workDir contains a parent-directory escape." }
    }

    private fun isInside(root: File, candidate: File): Boolean =
        candidate == root || candidate.absolutePath.startsWith(root.absolutePath + File.separator)

    private fun waitForNaturalProotExit(
        process: Process,
        stdout: CapturedOutputBuffer,
        stderr: CapturedOutputBuffer,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROOT_EXIT_GRACE_MILLIS)
        while (process.isAlive && System.nanoTime() < deadline) {
            drainAvailable(process.inputStream, stdout)
            drainAvailable(process.errorStream, stderr)
            if (process.isAlive) Thread.sleep(COMMAND_POLL_INTERVAL_MILLIS)
        }
        drainAvailable(process.inputStream, stdout)
        drainAvailable(process.errorStream, stderr)
    }

    private fun readCompletionCode(completionFile: File): Int? = runCatching {
        if (!completionFile.isFile) return@runCatching null
        completionFile.readText().trim().toIntOrNull()
    }.getOrNull()

    private fun readSupervisorPid(supervisorPidFile: File): Int? = runCatching {
        if (!supervisorPidFile.isFile) return@runCatching null
        supervisorPidFile.readText().trim().toIntOrNull()?.takeIf { it > 1 }
    }.getOrNull()

    private fun drainAvailable(input: InputStream, output: CapturedOutputBuffer) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        runCatching {
            while (true) {
                val available = input.available()
                if (available <= 0) break
                val count = input.read(buffer, 0, minOf(buffer.size, available))
                if (count <= 0) break
                output.append(buffer, count)
            }
        }
    }

    private fun captureRemaining(input: InputStream, output: CapturedOutputBuffer) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        runCatching {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.append(buffer, count)
            }
        }
    }

    private data class ProotWorkContext(val workspaceRoot: File?, val guestWorkDir: String)
    private data class CapturedOutput(val text: String, val originalBytes: Long, val truncated: Boolean)

    private class CapturedOutputBuffer {
        private val visible = ByteArrayOutputStream(MAX_CAPTURE_BYTES)
        private var total = 0L

        fun append(buffer: ByteArray, count: Int) {
            val remaining = MAX_CAPTURE_BYTES - visible.size()
            if (remaining > 0) visible.write(buffer, 0, minOf(count, remaining))
            total += count
        }

        fun result(): CapturedOutput = CapturedOutput(
            visible.toString(StandardCharsets.UTF_8.name()),
            total,
            total > MAX_CAPTURE_BYTES,
        )
    }

    private companion object {
        const val WORKSPACE_DIR = "/workspace"
        const val MAX_CAPTURE_BYTES = 256 * 1024
        const val COMMAND_POLL_INTERVAL_MILLIS = 25L
        const val PROOT_EXIT_GRACE_MILLIS = 250L
        val nextExecutionId = AtomicLong(System.currentTimeMillis())
    }
}
