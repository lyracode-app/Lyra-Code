package com.yukisoffd.lyracode.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.io.File
import java.io.IOException

class UpdateManagerCacheTest {
    @Test
    fun removesNestedLegacyDownloadsButPreservesUnrelatedExternalFiles() {
        val root = Files.createTempDirectory("lyra-update-storage").toFile()
        try {
            val cache = File(root, "cache").apply { mkdirs() }
            val files = File(root, "files").apply { mkdirs() }
            val external = File(root, "external-files").apply { mkdirs() }
            val cache2 = File(root, "secondary-cache").apply { mkdirs() }
            val legacy = File(cache, "updates/previous/nested.apk").apply { parentFile.mkdirs(); writeText("old") }
            val secondary = File(cache2, "updates/old.apk.part").apply { parentFile.mkdirs(); writeText("partial") }
            val pending = File(external, "LyraCode-4.2.apk").apply { writeText("pending") }
            val old = File(external, "LyraCode-3.0.APK").apply { writeText("old") }
            val project = File(external, "project.apk").apply { writeText("user project") }
            val nestedProject = File(external, "workspace/LyraCode-example.apk").apply { parentFile.mkdirs(); writeText("user project") }
            val storage = UpdateApkStorage(external, updateArtifactRoots(cache, files, listOf(cache2), listOf(external)))

            val result = storage.cleanup(pending)

            assertEquals(3, result.deletedCount)
            assertTrue(result.failedFiles.isEmpty())
            assertFalse(legacy.exists()); assertFalse(secondary.exists()); assertFalse(old.exists())
            assertTrue(pending.exists()); assertTrue(project.exists()); assertTrue(nestedProject.exists())
            assertEquals(listOf(pending), storage.artifacts())
            assertFalse(storage.owns(project))
            assertFalse(storage.owns(nestedProject))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun reportsDeletionFailureAndStillRemovesOtherArtifacts() {
        val root = Files.createTempDirectory("lyra-update-clean-failure").toFile()
        try {
            val bad = File(root, "bad.apk").apply { writeText("locked") }
            val good = File(root, "good.apk").apply { writeText("old") }
            val result = cleanUpdateArtifacts(listOf(UpdateArtifactRoot(root, true))) { file ->
                if (file == bad) throw IOException("in use") else file.delete()
            }
            assertEquals(listOf(bad), result.failedFiles)
            assertEquals(1, result.deletedCount)
            assertTrue(bad.exists()); assertFalse(good.exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun cleanupDoesNotRemoveActiveDownloadOrPartialFile() {
        val root = Files.createTempDirectory("lyra-update-active").toFile()
        try {
            val output = File(root, "LyraCode-new.apk").apply { writeText("previous complete file") }
            val partial = File(root, "${output.name}.part").apply { writeText("downloading") }
            val old = File(root, "LyraCode-old.apk").apply { writeText("old") }
            synchronized(UpdateDownloadFiles.lock) { UpdateDownloadFiles.activeFile = output }
            val storage = UpdateApkStorage(root, listOf(UpdateArtifactRoot(root, false)))
            assertEquals(listOf(old), storage.artifacts())
            assertEquals(1, storage.cleanup().deletedCount)
            assertTrue(output.exists()); assertTrue(partial.exists()); assertFalse(old.exists())
        } finally {
            synchronized(UpdateDownloadFiles.lock) { UpdateDownloadFiles.activeFile = null }
            root.deleteRecursively()
        }
    }

    @Test
    fun removesEveryOldUpdateArtifactAndKeepsPendingPackage() {
        val directory = Files.createTempDirectory("lyra-update-cache").toFile()
        try {
            val pending = directory.resolve("LyraCode-3.0.apk").apply { writeText("pending") }
            val previous = directory.resolve("LyraCode-2.0.apk").apply { writeText("previous") }
            val muchOlder = directory.resolve("LyraCode-1.0.APK").apply { writeText("older") }
            val partial = directory.resolve("LyraCode-4.0.apk.part").apply { writeText("partial") }
            val unrelated = directory.resolve("metadata.json").apply { writeText("keep") }

            val deleted = deleteCachedUpdateArtifacts(directory, keepFile = pending)

            assertEquals(3, deleted)
            assertTrue(pending.exists())
            assertFalse(previous.exists())
            assertFalse(muchOlder.exists())
            assertFalse(partial.exists())
            assertTrue(unrelated.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun removesAllUpdateArtifactsAfterUpgradeCompletes() {
        val directory = Files.createTempDirectory("lyra-update-cache").toFile()
        try {
            val previous = directory.resolve("LyraCode-2.0.apk").apply { writeText("previous") }
            val muchOlder = directory.resolve("LyraCode-1.0.apk").apply { writeText("older") }

            val deleted = deleteCachedUpdateArtifacts(directory)

            assertEquals(2, deleted)
            assertFalse(previous.exists())
            assertFalse(muchOlder.exists())
            assertTrue(directory.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
