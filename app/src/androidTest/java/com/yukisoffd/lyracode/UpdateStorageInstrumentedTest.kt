package com.yukisoffd.lyracode

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.data.AppUpdateInfo
import com.yukisoffd.lyracode.data.UpdateManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class UpdateStorageInstrumentedTest {
    private class TestContext(base: Context, val root: File, private val externalAvailable: Boolean) : ContextWrapper(base) {
        val external = File(root, "external-files").apply { mkdirs() }
        private val suffix = UUID.randomUUID().toString()
        private val preferenceNames = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getExternalFilesDir(type: String?): File? = external.takeIf { externalAvailable }
        override fun getExternalFilesDirs(type: String?): Array<File> = if (externalAvailable) arrayOf(external) else emptyArray()
        override fun getExternalCacheDir(): File = File(root, "external-cache").apply { mkdirs() }
        override fun getExternalCacheDirs(): Array<File> = arrayOf(getExternalCacheDir())
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = "$name-$suffix"
            preferenceNames += isolated
            return baseContext.getSharedPreferences(isolated, mode)
        }
        fun close() {
            preferenceNames.forEach { baseContext.deleteSharedPreferences(it) }
            root.deleteRecursively()
        }
    }

    private fun withContext(externalAvailable: Boolean = true, block: (TestContext, UpdateManager) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = TestContext(base, File(base.cacheDir, "update-test-${UUID.randomUUID()}").apply { mkdirs() }, externalAvailable)
        try { block(context, UpdateManager(context)) } finally { context.close() }
    }

    private fun apkBytes() = ByteArrayOutputStream().apply {
        ZipOutputStream(this).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("installer test fixture".toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

    private fun pending(context: Context, file: File, version: Long) {
        context.getSharedPreferences("lyra_update_state", Context.MODE_PRIVATE).edit()
            .putString("pending_apk_path", file.absolutePath)
            .putLong("pending_version_code", version)
            .putString("pending_version_name", "99.0")
            .commit()
    }

    @Test fun downloadsToExternalFilesAndSurvivesConcurrentCleanup() = withContext { context, manager ->
        val bytes = apkBytes()
        val old = File(context.cacheDir, "updates/nested/old.apk").apply { parentFile.mkdirs(); writeBytes(bytes) }
        val userFile = File(context.external, "project.apk").apply { writeBytes(bytes) }
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val response = Thread {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* consume request headers */ }
                    socket.getOutputStream().use { output ->
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(bytes)
                    }
                }
            }.apply { isDaemon = true; start() }
            val info = AppUpdateInfo(
                "99.0", manager.currentVersionCode() + 1,
                "http://127.0.0.1:${server.localPort}/update.apk",
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
                "", "", "", false,
            )
            var clearedDuringDownload = false
            val file = manager.downloadApk(info) { progress ->
                if (progress.downloadedBytes > 0) {
                    manager.clearDownloadedApks().getOrThrow()
                    clearedDuringDownload = true
                }
            }.getOrThrow()
            response.join(10_000)
            assertTrue(clearedDuringDownload)
            assertEquals(context.external.canonicalFile, file.parentFile!!.canonicalFile)
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(file.canonicalFile, manager.pendingDownloadedApk()!!.canonicalFile)
            assertFalse(old.exists())
            assertTrue(userFile.exists())
            assertEquals("content", manager.installIntent(file).data?.scheme)
            manager.clearDownloadedApks().getOrThrow()
            assertFalse(file.exists())
            assertNull(manager.pendingDownloadedApk())
            assertTrue(userFile.exists())
        }
    }

    @Test fun removesInstalledAndOrphanedOldPackagesOnNextStartup() = withContext { context, manager ->
        val installed = File(context.external, "LyraCode-old.apk").apply { writeBytes(apkBytes()) }
        val orphan = File(context.filesDir, "updates/archive/old.apk.part").apply { parentFile.mkdirs(); writeText("partial") }
        pending(context, installed, manager.currentVersionCode())
        assertNull(manager.pendingDownloadedApk())
        assertFalse(installed.exists())
        assertFalse(orphan.exists())
    }

    @Test fun neverDeletesPendingPathOutsideManagedDownloads() = withContext { context, manager ->
        val userApk = File(context.root, "user/LyraCode-user.apk").apply { parentFile.mkdirs(); writeBytes(apkBytes()) }
        pending(context, userApk, manager.currentVersionCode() + 1)
        assertNull(manager.pendingDownloadedApk())
        assertTrue(userApk.exists())
    }

    @Test fun unavailableExternalFilesNeverFallsBackToInternalCache() = withContext(false) { context, manager ->
        val result = manager.downloadApk(AppUpdateInfo("99", manager.currentVersionCode() + 1,
            "https://example.com/not-requested.apk", "", "", "", "", false)) { }
        assertTrue(result.isFailure)
        assertTrue(context.cacheDir.listFiles().orEmpty().isEmpty())
    }

    @Test fun refreshesOldUniversalSelectionCacheWithoutDeletingPendingInstaller() = withContext { context, manager ->
        val file = File(context.external, "LyraCode-99.apk").apply { writeBytes(apkBytes()) }
        pending(context, file, manager.currentVersionCode() + 1)
        for (policy in listOf(0, 1)) {
            context.getSharedPreferences("lyra_update_state", Context.MODE_PRIVATE).edit()
                .putInt("apk_selection_policy", policy).putString("apk_selection_abis", "different-device")
                .putLong("latest_version_code", manager.currentVersionCode() + 1)
                .putString("latest_apk_url", "https://example.com/old-universal.apk")
                .putString("last_daily_check_date", "2026-10-25").commit()
            val refreshed = UpdateManager(context)
            assertFalse(refreshed.hasAvailableUpdate())
            assertEquals(file.canonicalFile, refreshed.pendingDownloadedApk()!!.canonicalFile)
            assertFalse(context.getSharedPreferences("lyra_update_state", Context.MODE_PRIVATE).contains("last_daily_check_date"))
        }
    }
}
