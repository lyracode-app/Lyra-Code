package com.yukisoffd.lyracode

import android.app.NotificationManager
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.debian.ProotCommandExecutor
import com.yukisoffd.lyracode.debian.ProotLinuxManager
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProotCommandCancellationInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class Fixture(val id: String, val root: File, val executor: ProotCommandExecutor) {
        private val identities = mutableMapOf<Int, String>()
        fun ticks(name: String) = File(root, "root/$name.ticks")
        fun worker(name: String): String = """
            # $id
            echo ${'$'}${'$'} > /root/$name.pid
            while :; do printf x >> /root/$name.ticks; /system/bin/sleep 0.05; done
        """.trimIndent()
        fun foreground(name: String): String = """
            # $id
            /system/bin/sh -c '${worker(name)}' &
            wait ${'$'}!
        """.trimIndent()
        fun discardPidMetadata() {
            File(root, "root/.lyracode/commands").listFiles().orEmpty()
                .filter { it.extension == "pid" }.forEach { check(it.delete()) }
        }
        fun rememberPid(file: File): Int {
            val pid = file.readText().trim().toInt()
            identities[pid] = checkNotNull(identity(pid))
            return pid
        }
        private fun identity(pid: Int): String? = runCatching {
            File("/proc/$pid/stat").readText().substringAfterLast(") ").split(' ')[19]
        }.getOrNull()
        fun running(pid: Int): Boolean = runCatching {
            val stat = File("/proc/$pid/stat").readText().substringAfterLast(") ")
            stat.first() !in "ZX" && identity(pid) == identities[pid]
        }.getOrDefault(false)
        fun stopWorkers() {
            File(root, "root").listFiles().orEmpty().filter { it.extension == "pid" }.forEach { file ->
                val pid = file.readText().trim().toIntOrNull() ?: return@forEach
                // Only this fixture's command text may be signalled, even if a PID was reused.
                val commandLine = runCatching { File("/proc/$pid/cmdline").readText() }.getOrDefault("")
                if (id in commandLine || (identities[pid] != null && identity(pid) == identities[pid])) {
                    runCatching { Os.kill(pid, OsConstants.SIGKILL) }
                }
            }
        }
    }

    private suspend fun withFixture(block: suspend CoroutineScope.(Fixture) -> Unit) = coroutineScope {
        assumeTrue(InstrumentationRegistry.getArguments().getString("disposableTarget") == "true")
        val manager = ProotLinuxManager.getInstance(context)
        val id = "cancel-fixture-${UUID.randomUUID()}"
        val root = File(context.filesDir, "proot-linux/instances/$id/rootfs")
        File(root, "bin").mkdirs()
        File(root, "usr/bin").mkdirs()
        // A minimal native Android rootfs uses the packaged PRoot binary and its real tracer.
        // The manager binds /system and /apex, providing these ELF files' Android linker/libs.
        File("/system/bin/sh").copyTo(File(root, "bin/sh")).setExecutable(true)
        File("/system/bin/toybox").copyTo(File(root, "usr/bin/env")).setExecutable(true)
        File("/system/bin/toybox").copyTo(File(root, "usr/bin/printf")).setExecutable(true)
        val fixture = Fixture(id, root, ProotCommandExecutor(context))
        manager.refresh()
        try { block(fixture) }
        finally {
            withContext(NonCancellable) {
                fixture.stopWorkers()
                manager.delete(id)
            }
        }
    }

    private suspend fun awaitTicks(fixture: Fixture, name: String, command: Deferred<String>? = null) {
        withTimeout(15_000) {
            while (fixture.ticks(name).length() < 3) {
                if (command?.isCompleted == true) fail("Command finished before its worker started: ${command.await()}")
                delay(25)
            }
            fixture.rememberPid(File(fixture.root, "root/$name.pid"))
        }
    }

    private suspend fun assertStopped(fixture: Fixture, name: String) {
        val pid = File(fixture.root, "root/$name.pid").readText().trim().toInt()
        withTimeoutOrNull(2000) { while (fixture.running(pid)) delay(25) }
        assertFalse("Cancelled foreground worker is still alive", fixture.running(pid))
        val before = fixture.ticks(name).length()
        delay(350)
        assertEquals("Cancelled foreground worker is still running", before, fixture.ticks(name).length())
    }

    private suspend fun cancelCommand(fixture: Fixture, command: Job) {
        command.cancel()
        if (withTimeoutOrNull(5000) { command.join(); true } != true) {
            fixture.stopWorkers()
            withTimeout(5000) { command.join() }
            fail("Cancellation did not release the command within five seconds")
        }
    }

    @Test fun notificationStopTerminatesForegroundSleep() = runBlocking {
        checkNotificationStop(child = true)
    }

    @Test fun notificationStopTerminatesExecSleep() = runBlocking {
        checkNotificationStop(child = false)
    }

    @Test fun notificationStopWithoutGuestPidMetadataTerminatesSleep() = runBlocking {
        checkNotificationStop(child = false, discardPidMetadata = true)
    }

    private suspend fun checkNotificationStop(child: Boolean, discardPidMetadata: Boolean = false) {
        withFixture { fixture ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            lateinit var runtime: ChatRuntime
            lateinit var command: Job
            var conversation = 0L
            val sleepPidFile = File(fixture.root, "root/sleep.pid")
            instrumentation.runOnMainSync {
                runtime = ChatRuntime.get(context)
                conversation = runtime.conversationStore.createConversation("test", "test", "PRoot notification cancellation")
                command = runtime.controller.launchTestConversationJob(conversation) {
                    val sleep = if (child) """
                        # ${fixture.id}
                        /system/bin/sleep 30 &
                        echo ${'$'}! > /root/sleep.pid
                        wait ${'$'}!
                    """.trimIndent() else """
                        # ${fixture.id}
                        echo ${'$'}${'$'} > /root/sleep.pid
                        exec /system/bin/sleep 30
                    """.trimIndent()
                    fixture.executor.execute(fixture.id, sleep, null, null, 30, background = false)
                }
            }
            try {
                withTimeout(15_000) { while (!sleepPidFile.isFile) { check(!command.isCompleted); delay(25) } }
                val pid = fixture.rememberPid(sleepPidFile)
                assertTrue("Sleep did not start", fixture.running(pid))
                if (discardPidMetadata) fixture.discardPidMetadata()
                val notifications = context.getSystemService(NotificationManager::class.java)
                val notification = withTimeout(10_000) {
                    var current = notifications.activeNotifications.firstOrNull { it.notification.channelId == "agent_runs" }
                    while (current == null) {
                        delay(25)
                        current = notifications.activeNotifications.firstOrNull { it.notification.channelId == "agent_runs" }
                    }
                    current
                }
                notification.notification.actions.single().actionIntent.send()
                withTimeout(5000) { while (!command.isCancelled) delay(25) }
                cancelCommand(fixture, command)
                withTimeoutOrNull(2000) { while (fixture.running(pid)) delay(25) }
                assertFalse("Notification cancelled the run but its foreground sleep is still running", fixture.running(pid))
            } finally {
                cancelCommand(fixture, command)
                runtime.conversationStore.deleteConversation(conversation)
            }
        }
    }

    @Test fun normalCommandPreservesOutputArgumentsAndExitCode() = runBlocking {
        withFixture { fixture ->
            val result = JSONObject(fixture.executor.execute(fixture.id,
                "printf '%s\\n' \"it's alive\" '空 格'; exit 7", null, null, 10, background = false))
            assertEquals(result.toString(), 7, result.getInt("exit_code"))
            assertEquals("it's alive\n空 格\n", result.getString("stdout"))
            assertFalse(result.getBoolean("timed_out"))
            assertFalse(result.getBoolean("background_processes_retained"))
        }
    }

    @Test fun interactiveShellStillAcceptsInputAndExits() = runBlocking {
        withFixture { fixture ->
            val manager = ProotLinuxManager.getInstance(context)
            val shell = manager.startInteractiveShell(fixture.id, null, 80, 24)
            try {
                shell.process.outputStream.write("echo INTERACTIVE_OK\nexit\n".toByteArray())
                shell.process.outputStream.close()
                assertTrue("Interactive shell did not exit", shell.process.waitFor(10, TimeUnit.SECONDS))
                val output = shell.process.inputStream.bufferedReader().readText()
                assertTrue(output, "INTERACTIVE_OK" in output)
                assertEquals(0, shell.process.exitValue())
            } finally {
                if (shell.process.isAlive) shell.process.destroyForcibly()
                manager.closeInteractiveShell(shell)
            }
        }
    }

    @Test fun cancellationStopsForegroundChildren() = runBlocking {
        withFixture { fixture ->
            val command = async {
                fixture.executor.execute(fixture.id, fixture.foreground("foreground"), null, null, 30, background = false)
            }
            try {
                awaitTicks(fixture, "foreground", command)
                cancelCommand(fixture, command)
                assertStopped(fixture, "foreground")
            } finally { cancelCommand(fixture, command) }
        }
    }

    @Test fun cancellationWithoutGuestPidMetadataStillStopsForegroundChildren() = runBlocking {
        withFixture { fixture ->
            val command = async {
                fixture.executor.execute(fixture.id, fixture.foreground("foreground"), null, null, 30, background = false)
            }
            try {
                awaitTicks(fixture, "foreground", command)
                fixture.discardPidMetadata()
                cancelCommand(fixture, command)
                assertStopped(fixture, "foreground")
            } finally { cancelCommand(fixture, command) }
        }
    }

    @Test fun timeoutWithoutGuestPidMetadataStillStopsForegroundChildren() = runBlocking {
        withFixture { fixture ->
            val command = async {
                fixture.executor.execute(fixture.id, fixture.foreground("foreground"), null, null, 3, background = false)
            }
            try {
                awaitTicks(fixture, "foreground", command)
                fixture.discardPidMetadata()
                val result = JSONObject(command.await())
                assertTrue(result.toString(), result.getBoolean("timed_out"))
                assertStopped(fixture, "foreground")
            } finally { cancelCommand(fixture, command) }
        }
    }

    @Test fun cancellingForegroundDoesNotStopExplicitBackgroundCommand() = runBlocking {
        withFixture { fixture ->
            val background = JSONObject(fixture.executor.execute(fixture.id, fixture.worker("background"), null, null, 15, background = true))
            assertTrue(background.toString(), background.getBoolean("background_processes_retained"))
            awaitTicks(fixture, "background")
            val command = async {
                fixture.executor.execute(fixture.id, fixture.foreground("foreground"), null, null, 30, background = false)
            }
            try {
                awaitTicks(fixture, "foreground", command)
                cancelCommand(fixture, command)
                assertStopped(fixture, "foreground")
                val before = fixture.ticks("background").length()
                delay(350)
                assertTrue("A separate background=true command was stopped", fixture.ticks("background").length() > before)
            } finally { cancelCommand(fixture, command) }
        }
    }
}
