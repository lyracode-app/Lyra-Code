package com.yukisoffd.lyracode

import android.net.Uri
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.debian.ProotArchitecture
import com.yukisoffd.lyracode.debian.ProotLinuxManager
import com.yukisoffd.lyracode.debian.RootfsImportException
import com.yukisoffd.lyracode.debian.RootfsImportProblem
import com.yukisoffd.lyracode.debian.ProotOperationPhase
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ProotRuntimeInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val nativeDir get() = File(context.applicationInfo.nativeLibraryDir)

    @Test fun architectureNoticeAndImportFailureAreVisible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val manager = ProotLinuxManager.getInstance(context)
        manager.refresh()
        val architecture = checkNotNull(manager.runtimeArchitecture())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                AppStrings.initialize(activity)
                activity.setContent {
                    MaterialTheme {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { ProotLinuxSettings() }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.waitForIdle(500, 5000)
            val notice = uiText(R.string.proot_linux_device_architecture, architecture.abi)
            assertVisibleText(notice)
            val badArchive = File.createTempFile("invalid-ui-rootfs", ".tar", context.cacheDir)
            try {
                badArchive.writeBytes(ByteArray(512) { 65 })
                runBlocking { runCatching { manager.importRootfs(Uri.fromFile(badArchive), "Invalid UI import") } }
                instrumentation.waitForIdleSync()
                instrumentation.uiAutomation.waitForIdle(500, 5000)
                assertVisibleText(uiText(R.string.proot_linux_import_failed))
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.cacheDir, "proot-import-feedback.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            } finally {
                badArchive.delete()
                manager.refresh()
            }
        }
    }

    private fun assertVisibleText(text: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun contains(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.text?.contains(text) == true) return true
            return (0 until node.childCount).any { contains(node.getChild(it)) }
        }
        repeat(20) {
            if (contains(automation.rootInActiveWindow)) return
            android.os.SystemClock.sleep(250)
        }
        automation.takeScreenshot()?.let { bitmap ->
            File(context.cacheDir, "proot-import-feedback.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        fail("Visible text missing: $text; window: ${automation.rootInActiveWindow?.packageName}")
    }

    @Test fun invalidImportsReportTheReasonAndPreserveExistingInstances() = runBlocking {
        val manager = ProotLinuxManager.getInstance(context)
        val expected = checkNotNull(manager.runtimeArchitecture())
        val otherMachine = if (expected == ProotArchitecture.ARM64) 62 else 183
        val elf = ByteArray(64).apply {
            this[0] = 0x7f; this[1] = 69; this[2] = 76; this[3] = 70
            this[4] = 2; this[5] = 1; this[18] = otherMachine.toByte()
        }
        val cases = listOf(
            tar("bin/sh", elf) to RootfsImportProblem.ARCHITECTURE_MISMATCH,
            tar("bin/sh", elf.copyOf().apply { this[4] = 1; this[18] = 3 }) to RootfsImportProblem.ARCHITECTURE_MISMATCH,
            tar("README", "not a rootfs".toByteArray()) to RootfsImportProblem.MISSING_SHELL,
            tar("bin/sh", "invalid shell".toByteArray()) to RootfsImportProblem.INVALID_SHELL,
            tar("bin/sh", ByteArray(0), link = "missing-shell") to RootfsImportProblem.INVALID_SHELL,
            tar("../escape", elf) to RootfsImportProblem.INVALID_ARCHIVE,
            ByteArray(512) { 65 } to RootfsImportProblem.INVALID_ARCHIVE,
            tar("bin/sh", elf).copyOf(520) to RootfsImportProblem.INVALID_ARCHIVE,
            tar("bin/sh", elf).apply { this[0] = 88 } to RootfsImportProblem.INVALID_ARCHIVE,
            gzip(tar("bin/sh", elf)).apply { this[size - 8] = (this[size - 8].toInt() xor 1).toByte() } to RootfsImportProblem.INVALID_ARCHIVE,
        )
        val originalIds = manager.state.value.instances.map { it.id }.toSet()
        val archive = File.createTempFile("invalid-rootfs", ".tar", context.cacheDir)
        try {
            for ((contents, problem) in cases) {
                archive.writeBytes(contents)
                val failure = try {
                    manager.importRootfs(Uri.fromFile(archive), "Invalid import ${System.nanoTime()}")
                    fail("Invalid rootfs was accepted: $problem")
                    error("unreachable")
                } catch (error: RootfsImportException) { error }
                assertEquals(problem, failure.problem)
                assertEquals(ProotOperationPhase.ERROR, manager.state.value.phase)
                assertEquals(problem, manager.state.value.importFailure?.problem)
                assertEquals(originalIds, manager.state.value.instances.map { it.id }.toSet())
                if (problem == RootfsImportProblem.ARCHITECTURE_MISMATCH) assertEquals(expected, failure.expected)
            }
            archive.delete()
            try {
                manager.importRootfs(Uri.fromFile(archive), "Unreadable test")
                fail("Missing file was accepted")
            } catch (error: RootfsImportException) {
                assertEquals(RootfsImportProblem.CANNOT_READ, error.problem)
            }
        } finally {
            archive.delete()
            manager.refresh()
        }
    }

    private fun gzip(contents: ByteArray): ByteArray = java.io.ByteArrayOutputStream().use { output ->
        java.util.zip.GZIPOutputStream(output).use { it.write(contents) }
        output.toByteArray()
    }

    private fun tar(name: String, contents: ByteArray, link: String? = null): ByteArray {
        val header = ByteArray(512)
        fun put(offset: Int, value: String) { value.toByteArray(Charsets.US_ASCII).copyInto(header, offset) }
        put(0, name)
        put(100, "0000755\u0000")
        put(124, contents.size.toString(8).padStart(11, '0') + "\u0000")
        header[156] = (if (link == null) '0' else '2').code.toByte()
        if (link != null) put(157, link)
        put(257, "ustar\u0000")
        for (index in 148..155) header[index] = 32
        val checksum = header.sumOf { it.toInt() and 0xff }
        put(148, checksum.toString(8).padStart(6, '0') + "\u0000 ")
        return header + contents + ByteArray((512 - contents.size % 512) % 512) + ByteArray(1024)
    }

    private fun runProot(args: List<String>, l2s: File? = null): String {
        val output = File.createTempFile("proot-output", ".txt", context.cacheDir)
        val process = ProcessBuilder(listOf(File(nativeDir, "libproot_exec.so").path) + args)
            .redirectErrorStream(true).redirectOutput(output)
            .apply {
                environment().remove("LD_PRELOAD")
                environment()["PROOT_LOADER"] = File(nativeDir, "libproot_loader.so").path
                environment()["PROOT_TMP_DIR"] = context.cacheDir.path
                if (l2s != null) environment()["PROOT_L2S_DIR"] = l2s.path
            }.start()
        try {
            assertTrue("PRoot timed out", process.waitFor(30, TimeUnit.SECONDS))
            val text = output.readText()
            assertEquals(text, 0, process.exitValue())
            return text
        } finally {
            if (process.isAlive) process.destroyForcibly()
            output.delete()
        }
    }

    @Test fun packagedPairMatchesAndroidAndExecutes() {
        assertNotNull(ProotArchitecture.installed(nativeDir, Build.SUPPORTED_ABIS.toList()))
        assertTrue(runProot(listOf("--version")).contains("5.1.107.91-lyra.2"))
        assertTrue(runProot(listOf("-l", "/system/bin/sh", "-c", "echo NATIVE_EXEC_OK")).contains("NATIVE_EXEC_OK"))
    }

    @Test fun linkDirectoryReplacementDoesNotWriteOutside() {
        for (pinFirst in listOf(false, true)) {
            val root = File(context.cacheDir, "l2s-test-${System.nanoTime()}").apply { mkdirs() }
            val l2s = File(root, "links").apply { mkdirs() }
            val outside = File(root, "outside").apply { mkdirs() }
            try {
                val prepare = if (pinFirst) {
                    "echo seed > seed; ln seed seed-link; mv links pinned;"
                } else "rmdir links;"
                val command = "set -eu; cd '${root.path}'; $prepare " +
                    "ln -s '${outside.path}' links; test -L links; " +
                    "echo data > original; ln original linked 2>/dev/null || true; echo ATTACK_ATTEMPTED"
                assertTrue(runProot(listOf("-l", "/system/bin/sh", "-c", command), l2s).contains("ATTACK_ATTEMPTED"))
                assertTrue("Backing files escaped the pinned directory", outside.listFiles().orEmpty().isEmpty())
            } finally {
                // Remove the test-created symlink before recursively cleaning its parent.
                if (java.nio.file.Files.isSymbolicLink(l2s.toPath())) l2s.delete()
                root.deleteRecursively()
            }
        }
    }

    @Test fun importedRootfsRunsCommandsAndLinks() = runBlocking {
        val archive = File(context.cacheDir, "proot-test-rootfs.tgz")
        assumeTrue("Provide a matching rootfs archive in the app cache for this integration test", archive.isFile)
        val manager = ProotLinuxManager.getInstance(context)
        val id = manager.importRootfs(Uri.fromFile(archive), "ABI test ${System.nanoTime()}")
        try {
            val command = manager.startCommand(id, null, "/root",
                "set -eu; echo test > original; ln original hard; ln -s original soft; " +
                    "test \"\$(cat hard)\" = test; test \"\$(cat soft)\" = test; " +
                    "rm original; test \"\$(cat hard)\" = test; dpkg --print-architecture; echo ROOTFS_OK",
                System.nanoTime(), false)
            try {
                assertTrue(command.process.waitFor(60, TimeUnit.SECONDS))
                val output = command.process.inputStream.bufferedReader().readText()
                val error = command.process.errorStream.bufferedReader().readText()
                assertTrue(output + error, output.contains("ROOTFS_OK"))
                assertEquals(output + error, "0", command.completionFile.readText().trim())
            } finally {
                if (command.process.isAlive) command.process.destroyForcibly()
            }
        } finally {
            manager.delete(id)
        }
    }
}
