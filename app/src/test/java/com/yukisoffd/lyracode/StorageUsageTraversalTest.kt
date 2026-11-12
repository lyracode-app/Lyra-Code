package com.yukisoffd.lyracode

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class StorageUsageTraversalTest {
    @Test
    fun storageScreenRunsRecursiveScansOffTheComposeMainThread() {
        val source = File(
            "src/main/java/com/yukisoffd/lyracode/ui/settings/system/SettingsStorageScreens.kt",
        ).readText()

        assertFalse(source.contains("mutableStateOf(scanStorageUsage(context))"))
        assertTrue(source.contains("withContext(Dispatchers.IO) { scanStorageUsage(context.applicationContext) }"))
    }

    @Test
    fun nestedRegularFilesAreCountedOnce() {
        val root = Files.createTempDirectory("lyracode-storage-regular-test")
        try {
            Files.write(root.resolve("one.bin"), ByteArray(1024))
            val nested = Files.createDirectory(root.resolve("nested"))
            Files.write(nested.resolve("two.bin"), ByteArray(2048))

            assertEquals(StorageTreeUsage(3072L, 2), safeTreeUsage(root.toFile()))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun overlappingCleanableRootsAreNotCountedTwice() {
        val root = Files.createTempDirectory("lyra-storage-overlap").toFile()
        try {
            val apk = root.resolve("old.apk").apply { writeBytes(ByteArray(1024)) }
            assertEquals(StorageTreeUsage(1024L, 1), safeTreeUsage(listOf(root, apk, root)))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun apkFileTotalExcludesNativeDirectoriesAndDuplicateApkPaths() {
        val root = Files.createTempDirectory("lyra-installed-apk-size").toFile()
        try {
            val base = root.resolve("base.apk").apply { writeBytes(ByteArray(1024)) }
            val split = root.resolve("split.apk").apply { writeBytes(ByteArray(512)) }
            val native = root.resolve("lib").apply { mkdirs(); resolve("runtime.so").writeBytes(ByteArray(8192)) }
            assertEquals(1536L, installedApkFileBytes(listOf(base, split, base, native)))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun cleanupReportsUnreadableDirectoryInsteadOfClaimingSuccess() {
        val root = Files.createTempDirectory("lyra-unreadable-cache").toFile()
        try {
            val child = root.resolve("keep.bin").apply { writeText("not deleted") }
            val unreadable = object : File(root.absolutePath) {
                override fun listFiles(): Array<File>? = null
            }
            assertTrue(deleteCacheTarget(unreadable).isFailure)
            assertTrue(child.exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun systemStorageStatsWinWithoutEvaluatingInflatedRecursiveFallback() {
        var fallbackCalled = false
        val totals = resolveStorageTotals(
            SystemStorageStats(appBytes = 100L, dataBytes = 9_000L, cacheBytes = 50L),
        ) {
            fallbackCalled = true
            StorageTotals(appBytes = 100L, dataBytes = 120_000L, cacheBytes = 50L)
        }

        assertEquals(StorageTotals(100L, 8_950L, 50L), totals)
        assertEquals(9_100L, totals.appBytes + totals.dataBytes + totals.cacheBytes)
        assertFalse(fallbackCalled)
    }

    @Test
    fun storageScanDoesNotFollowDirectorySymbolicLinks() {
        val base = Files.createTempDirectory("lyracode-storage-link-test")
        val scanRoot = Files.createDirectory(base.resolve("scan"))
        val outside = Files.createDirectory(base.resolve("outside"))
        val payload = ByteArray(64 * 1024) { 7 }
        Files.write(outside.resolve("payload.bin"), payload)
        val link = scanRoot.resolve("rootfs-lib-link")
        val linkCreated = runCatching { Files.createSymbolicLink(link, outside) }.isSuccess
        try {
            assumeTrue("Symbolic links are unavailable on this test host", linkCreated)
            val usage = safeTreeUsage(scanRoot.toFile())

            assertTrue("The linked payload must not be included", usage.bytes < payload.size)
            assertEquals(1, usage.fileCount)
        } finally {
            Files.deleteIfExists(link)
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun cacheCleanupDeletesLinkInsteadOfItsTarget() {
        val base = Files.createTempDirectory("lyracode-cache-link-test")
        val cache = Files.createDirectory(base.resolve("cache"))
        val outside = Files.createDirectory(base.resolve("outside"))
        val sentinel = Files.writeString(outside.resolve("keep.txt"), "keep")
        val link = cache.resolve("linked-directory")
        val linkCreated = runCatching { Files.createSymbolicLink(link, outside) }.isSuccess
        try {
            assumeTrue("Symbolic links are unavailable on this test host", linkCreated)
            deleteCacheTarget(cache.toFile())

            assertTrue("External target content must survive cache cleanup", Files.exists(sentinel))
            assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertTrue(Files.isDirectory(cache))
        } finally {
            Files.deleteIfExists(link)
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun hardLinkedFilesAreCountedOnlyOnceWhenFileKeysAreAvailable() {
        val root = Files.createTempDirectory("lyracode-storage-hardlink-test")
        val original = Files.write(root.resolve("original.bin"), ByteArray(8192) { 3 })
        val alias = root.resolve("alias.bin")
        val linkCreated = runCatching { Files.createLink(alias, original) }.isSuccess
        try {
            assumeTrue("Hard links are unavailable on this test host", linkCreated)
            val hostFileKey = Files.readAttributes(
                original,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ).fileKey()
            assumeTrue("This test host does not expose hard-link file keys", hostFileKey != null)
            val usage = safeTreeUsage(root.toFile())

            assertEquals(8192L, usage.bytes)
            assertEquals(1, usage.fileCount)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
