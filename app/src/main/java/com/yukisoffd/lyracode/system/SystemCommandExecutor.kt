package com.yukisoffd.lyracode.system

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.yukisoffd.lyracode.data.AppSettings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class SystemCommandResult(
    val ok: Boolean,
    val mode: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val message: String = "",
) {
    fun toJson(): String = JSONObject()
        .put("schema", "lyra_system_command_v1")
        .put("ok", ok)
        .put("mode", mode)
        .put("exit_code", exitCode)
        .put("stdout", stdout)
        .put("stderr", stderr)
        .put("message", message)
        .toString()

    companion object {
        fun fromJson(value: String): SystemCommandResult {
            val json = JSONObject(value)
            return SystemCommandResult(
                ok = json.optBoolean("ok"),
                mode = json.optString("mode", "shell"),
                exitCode = json.optInt("exit_code", -1),
                stdout = json.optString("stdout"),
                stderr = json.optString("stderr"),
                message = json.optString("message"),
            )
        }
    }
}

class SystemCommandExecutor(
    context: Context,
    private val settings: AppSettings,
) {
    private val appContext = context.applicationContext
    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext, ShizukuShellUserService::class.java),
    )
        .daemon(false)
        .processNameSuffix("lyra_shell")
        .version(1)
    private val serviceLock = Any()
    @Volatile
    private var shellService: ISystemShellService? = null
    private var pendingService: CompletableDeferred<ISystemShellService>? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = ISystemShellService.Stub.asInterface(binder)
            shellService = service
            synchronized(serviceLock) {
                pendingService?.complete(service)
                pendingService = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
        }
    }

    fun isShizukuRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasShellPermission(): Boolean {
        return isShizukuRunning() &&
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    }

    fun shouldShowShellTool(): Boolean = settings.requestShellAccess

    fun shouldShowRootTool(): Boolean = settings.requestRootAccess

    suspend fun probeRoot(): SystemCommandResult = withContext(Dispatchers.IO) {
        runRootProcess("id -u", 12)
    }

    suspend fun executeShell(command: String, timeoutSeconds: Int, beforeDispatch: suspend () -> Unit = {}): SystemCommandResult = withContext(Dispatchers.IO) {
        if (!settings.requestShellAccess) {
            return@withContext unavailable("shell", "Shell 权限开关已关闭。")
        }
        if (!isShizukuRunning()) {
            return@withContext unavailable(
                "shell",
                "Shizuku 服务未运行。请先在 Shizuku 中通过无线调试或电脑 ADB 启动服务。",
            )
        }
        if (!hasShellPermission()) {
            return@withContext unavailable("shell", "尚未授予 Lyra Code 的 Shizuku 权限。")
        }
        runCatching {
            val service = requireShellService()
            currentCoroutineContext().ensureActive()
            beforeDispatch()
            SystemCommandResult.fromJson(service.execute(command, timeoutSeconds.coerceIn(3, 600)))
        }.getOrElse {
            shellService = null
            unavailable("shell", "Shell 命令启动失败: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    suspend fun executeRoot(
        command: String,
        timeoutSeconds: Int,
        allowShellFallback: Boolean = true,
        beforeDispatch: suspend () -> Unit = {},
    ): SystemCommandResult = withContext(Dispatchers.IO) {
        if (!settings.requestRootAccess) {
            return@withContext unavailable("root", "Root 权限开关已关闭。")
        }
        val probe = runRootProcess("id -u", 12)
        if (probe.ok && probe.stdout.trim().lineSequence().lastOrNull() == "0") {
            currentCoroutineContext().ensureActive()
            try { beforeDispatch() }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { return@withContext unavailable("root", "操作取消：${error.message.orEmpty()}") }
            currentCoroutineContext().ensureActive()
            return@withContext runRootProcess(command, timeoutSeconds)
        }
        if (allowShellFallback && settings.requestShellAccess) {
            val fallback = executeShell(command, timeoutSeconds, beforeDispatch)
            return@withContext fallback.copy(
                message = "Root 不可用，已按设置回退到 Shizuku Shell。${fallback.message}",
            )
        }
        unavailable(
            "root",
            "未获得 Root 权限，且没有可用的 Shell 回退通道。${probe.stderr.ifBlank { probe.message }}",
        )
    }

    private fun runRootProcess(command: String, timeoutSeconds: Int): SystemCommandResult {
        val quoted = shellQuote(command)
        val template = settings.customSuCommand
        val invocation = if ("{command}" in template) {
            template.replace("{command}", quoted)
        } else {
            "$template $quoted"
        }
        return runCatching {
            collectProcess(
                ProcessBuilder("/system/bin/sh", "-c", invocation).start(),
                "root",
                timeoutSeconds,
            )
        }.getOrElse {
            unavailable("root", "Root 命令启动失败: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun collectProcess(process: Process, mode: String, timeoutSeconds: Int): SystemCommandResult {
        val readers = Executors.newFixedThreadPool(2)
        val stdoutFuture = readers.submit(Callable { process.inputStream.bufferedReader().use { it.readText() } })
        val stderrFuture = readers.submit(Callable { process.errorStream.bufferedReader().use { it.readText() } })
        val timeout = timeoutSeconds.coerceIn(3, 600)
        val finished = process.waitFor(timeout.toLong(), TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        val stdout = runCatching { stdoutFuture.get(3, TimeUnit.SECONDS) }.getOrDefault("")
        val stderr = runCatching { stderrFuture.get(3, TimeUnit.SECONDS) }.getOrDefault("")
        readers.shutdownNow()
        if (!finished) {
            return SystemCommandResult(
                ok = false,
                mode = mode,
                exitCode = -1,
                stdout = stdout,
                stderr = stderr,
                message = "命令执行超过 ${timeout} 秒，已终止。",
            )
        }
        val exitCode = process.exitValue()
        return SystemCommandResult(
            ok = exitCode == 0,
            mode = mode,
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
            message = if (exitCode == 0) "" else "命令退出码为 $exitCode。",
        )
    }

    private fun unavailable(mode: String, message: String): SystemCommandResult {
        return SystemCommandResult(false, mode, -1, "", "", message)
    }

    private suspend fun requireShellService(): ISystemShellService {
        shellService?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        val deferred = synchronized(serviceLock) {
            shellService?.takeIf { it.asBinder().isBinderAlive }?.let {
                return it
            }
            pendingService ?: CompletableDeferred<ISystemShellService>().also {
                pendingService = it
                Shizuku.bindUserService(serviceArgs, serviceConnection)
            }
        }
        return withTimeout(10_000L) { deferred.await() }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
}
