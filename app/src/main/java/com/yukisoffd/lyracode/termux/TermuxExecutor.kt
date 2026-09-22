package com.yukisoffd.lyracode.termux

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.yukisoffd.lyracode.data.AuditLogStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

data class TermuxResult(
    val ok: Boolean,
    val message: String,
)

class TermuxExecutor(
    private val context: Context,
    private val auditLogStore: AuditLogStore,
) {
    suspend fun execute(
        command: String,
        workDir: String? = null,
        timeoutSeconds: Int = DEFAULT_COMMAND_RESULT_TIMEOUT_SECONDS,
        allowApiFallback: Boolean = true,
        background: Boolean = false,
    ): TermuxResult = withContext(Dispatchers.IO) {
        val normalizedCommand = normalizeShellRedirection(command)
        val runDetached = shouldDetachTermuxCommand(normalizedCommand, background)
        validateCommand(normalizedCommand)?.let { reason ->
            return@withContext TermuxResult(false, reason)
        }
        if (!isPackageInstalled(PACKAGE_TERMUX)) {
            return@withContext TermuxResult(false, "未检测到 Termux，请先安装 Termux 并开启 allow-external-apps。")
        }
        if (!hasRunCommandPermission()) {
            return@withContext TermuxResult(
                false,
                "Lyra Code 尚未获得 Termux RUN_COMMAND 权限。请在 Lyra Code 设置 > 应用权限 > 与 Termux 通信 中点击授予。",
            )
        }
        val result = runCatching {
            runWithTermuxService(
                command = normalizedCommand,
                workDir = workDir,
                timeoutSeconds = timeoutSeconds.coerceIn(5, MAX_COMMAND_RESULT_TIMEOUT_SECONDS),
                runDetached = runDetached,
            )
        }.fold(
            onSuccess = { TermuxResult(true, it) },
            onFailure = {
                if (it is CancellationException) throw it
                if (it is TermuxCommandTimeoutException) {
                    return@fold TermuxResult(
                        false,
                        it.message ?: "Termux 命令执行超时。请确认 Termux 已在后台运行，并已开启 allow-external-apps=true。",
                    )
                }
                if (allowApiFallback && isPackageInstalled(PACKAGE_TERMUX_API)) {
                    runCatching { runWithTermuxApi(normalizedCommand, workDir) }
                        .fold(
                            onSuccess = { message -> TermuxResult(true, message) },
                            onFailure = { apiError ->
                                TermuxResult(
                                    false,
                                    "提交 Termux 失败: ${it.message}\nTermux:API 回退也失败: ${apiError.message}\n请确认已安装新版 Termux，并在 ~/.termux/termux.properties 中设置 allow-external-apps=true 后重启 Termux。",
                                )
                            },
                        )
                } else {
                    TermuxResult(
                        false,
                        "提交 Termux 失败: ${it.message}\n请确认已安装新版 Termux，并在 ~/.termux/termux.properties 中设置 allow-external-apps=true 后重启 Termux。",
                    )
                }
            },
        )
        auditLogStore.add(
            kind = "command",
            title = normalizedCommand.take(160),
            detail = "command=$normalizedCommand\nworkDir=${workDir.orEmpty()}\nbackground=$runDetached\n${result.message}",
        )
        result
    }

    fun isTermuxInstalled(): Boolean = isPackageInstalled(PACKAGE_TERMUX)

    fun isTermuxApiInstalled(): Boolean = isPackageInstalled(PACKAGE_TERMUX_API)

    fun hasRunCommandPermission(): Boolean {
        return context.checkSelfPermission(PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED
    }

    private fun runWithTermuxApi(command: String, workDir: String?): String {
        val intent = Intent("com.termux.api.RUN_COMMAND").apply {
            setPackage(PACKAGE_TERMUX_API)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("com.termux.api.RUN_COMMAND_PATH", TERMUX_BASH)
            putExtra("com.termux.api.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
            workDir?.let { putExtra("com.termux.api.RUN_COMMAND_WORKDIR", it) }
            putExtra("com.termux.api.RUN_COMMAND_BACKGROUND", true)
        }
        context.startActivity(intent)
        return "已通过 Termux:API 静默提交命令。"
    }

    private suspend fun runWithTermuxService(
        command: String,
        workDir: String?,
        timeoutSeconds: Int,
        runDetached: Boolean,
    ): String {
        val executionId = nextExecutionId.getAndIncrement()
        val resultDeferred = TermuxCommandResultReceiver.register(executionId)
        val pendingIntent = createResultPendingIntent(executionId)
        val submittedCommand = if (runDetached) {
            buildDetachedTermuxCommand(command, executionId, TERMUX_BASH)
        } else {
            command
        }
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName(PACKAGE_TERMUX, "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", submittedCommand))
            workDir?.let { putExtra("com.termux.RUN_COMMAND_WORKDIR", it) }
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            val effectiveTimeoutSeconds = if (runDetached) {
                timeoutSeconds.coerceAtMost(BACKGROUND_LAUNCH_TIMEOUT_SECONDS)
            } else {
                timeoutSeconds
            }
            val timeoutMs = effectiveTimeoutSeconds * 1000L
            val commandResult = withTimeoutOrNull(timeoutMs) {
                resultDeferred.await()
            }
            if (commandResult != null) {
                return commandResult.toAgentText()
            }
            throw TermuxCommandTimeoutException(
                if (runDetached) {
                    """
                    Termux 已提交后台启动器，但 ${effectiveTimeoutSeconds} 秒内没有收到启动结果，已终止本次等待。
                    后台命令可能已经启动；重试前请先通过进程或日志检查当前状态，避免重复启动。
                    请确认 Termux 正在后台运行，且 ~/.termux/termux.properties 中有 allow-external-apps=true。
                    """.trimIndent()
                } else {
                    """
                    Termux RunCommandService 已提交命令，但 ${effectiveTimeoutSeconds} 秒内没有收到 stdout/stderr 回传，已终止本次等待。
                    可能原因：Termux 没有在后台运行、allow-external-apps 未开启、Termux 版本过旧、不支持 PendingIntent 结果回传、命令仍在运行或输出过大。
                    请先打开 Termux 保持后台活跃，确认 ~/.termux/termux.properties 中有 allow-external-apps=true 并执行 termux-reload-settings，然后重试。
                    如命令确实需要更久，请在 run_command 参数中设置 timeout_seconds。
                    """.trimIndent()
                },
            )
        } finally {
            TermuxCommandResultReceiver.unregister(executionId)
        }
    }

    private fun createResultPendingIntent(executionId: Int): PendingIntent {
        val intent = Intent(context, TermuxCommandResultReceiver::class.java).apply {
            action = ACTION_TERMUX_COMMAND_RESULT
            setPackage(context.packageName)
            putExtra(TermuxCommandResultReceiver.EXTRA_EXECUTION_ID, executionId)
        }
        val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, executionId, intent, flags)
    }

    private fun normalizeShellRedirection(command: String): String {
        return command.replace(Regex("""(>\s*)(\S+?)2>&1""")) { match ->
            "${match.groupValues[1]}${match.groupValues[2]} 2>&1"
        }
    }

    private fun validateCommand(command: String): String? {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return "命令不能为空"
        val dangerous = DANGEROUS_COMMAND_PATTERNS.firstOrNull { it.containsMatchIn(trimmed) }
        if (dangerous != null) return "命令包含高风险操作，已拦截"
        if (trimmed.replace(Regex("""\s+"""), "").contains(":(){:|:&};:")) {
            return "命令包含高风险操作，已拦截"
        }
        if (ROOT_COMMAND_PATTERN.containsMatchIn(trimmed)) return "Root 命令需要使用 execute_root_command 单独授权"
        return null
    }

    @Suppress("DEPRECATION")
    private fun isPackageInstalled(packageName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            context.packageManager.getPackageInfo(packageName, 0)
        }
    }.isSuccess

    companion object {
        private const val PACKAGE_TERMUX = "com.termux"
        private const val PACKAGE_TERMUX_API = "com.termux.api"
        private const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
        private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
        private const val DEFAULT_COMMAND_RESULT_TIMEOUT_SECONDS = 60
        private const val MAX_COMMAND_RESULT_TIMEOUT_SECONDS = 600
        private const val BACKGROUND_LAUNCH_TIMEOUT_SECONDS = 15
        internal const val ACTION_TERMUX_COMMAND_RESULT = "com.yukisoffd.lyracode.termux.COMMAND_RESULT"
        private val nextExecutionId = AtomicInteger(1000)
        private val ROOT_COMMAND_PATTERN = Regex("""(?i)^\s*su(?:\s|$)""")
        private val DANGEROUS_COMMAND_PATTERNS = listOf(
            Regex("""(?i)(^|[;&|]\s*)rm\s+(?=[^;&|]*\s/)(?=[^;&|]*(?:-[^\s;&|]*r|--recursive))(?=[^;&|]*(?:-[^\s;&|]*f|--force))[^;&|]*\s/(?:\s|$|[;&|*])"""),
            Regex("""(?i)>\s*/dev/block/"""),
            Regex("""(?i)(^|[;&|]\s*)dd\s+.*\bof=/dev/(block|mmcblk|sda|vda)"""),
            Regex("""(?i)(^|[;&|]\s*)mkfs(?:\.[a-z0-9]+)?\b"""),
            Regex("""(?i)(^|[;&|]\s*)chmod\s+-?R?\s*777\s+/(?:\s|$|[;&|*])"""),
            Regex("""(?i)(^|[;&|]\s*)chown\s+-R\s+\S+\s+/(?:\s|$|[;&|*])"""),
        )
    }
}

private class TermuxCommandTimeoutException(message: String) : IllegalStateException(message)

internal fun shouldDetachTermuxCommand(command: String, backgroundRequested: Boolean): Boolean {
    if (backgroundRequested) return true
    if (WAIT_COMMAND_PATTERN.containsMatchIn(command)) return false
    return containsStandaloneBackgroundOperator(command)
}

internal fun containsStandaloneBackgroundOperator(command: String): Boolean {
    var quote: Char? = null
    var escaped = false
    command.forEachIndexed { index, char ->
        if (escaped) {
            escaped = false
            return@forEachIndexed
        }
        if (char == '\\' && quote != '\'') {
            escaped = true
            return@forEachIndexed
        }
        if (quote != null) {
            if (char == quote) quote = null
            return@forEachIndexed
        }
        if (char == '\'' || char == '"') {
            quote = char
            return@forEachIndexed
        }
        if (char != '&') return@forEachIndexed

        val previous = command.getOrNull(index - 1)
        val next = command.getOrNull(index + 1)
        val isRedirectionOrPipe = previous == '>' || previous == '|' || next == '>'
        val isLogicalAnd = previous == '&' || next == '&'
        if (!isRedirectionOrPipe && !isLogicalAnd) return true
    }
    return false
}

internal fun buildDetachedTermuxCommand(command: String, executionId: Int, bashPath: String): String {
    val quotedBash = shellSingleQuoteForTermux(bashPath)
    val quotedCommand = shellSingleQuoteForTermux(command)
    return """
        lyra_output_dir="${'$'}{TMPDIR:-/data/data/com.termux/files/usr/tmp}"
        mkdir -p "${'$'}lyra_output_dir" || exit 1
        lyra_output_file="${'$'}lyra_output_dir/lyracode-run-$executionId-${'$'}${'$'}.log"
        nohup $quotedBash -lc $quotedCommand </dev/null >"${'$'}lyra_output_file" 2>&1 &
        lyra_launcher_pid=${'$'}!
        printf 'background_started: true\nlauncher_pid: %s\noutput_file: %s\nnote: Launch accepted; verify the process and log separately.\n' "${'$'}lyra_launcher_pid" "${'$'}lyra_output_file"
    """.trimIndent()
}

private fun shellSingleQuoteForTermux(value: String): String =
    "'${value.replace("'", "'\"'\"'")}'"

private val WAIT_COMMAND_PATTERN = Regex("""(?im)(^|[;&|]\s*)wait(?:\s|$)""")
