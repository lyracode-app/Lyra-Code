package com.yukisoffd.lyracode.data

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal data class UpdateArtifactRoot(val directory: File, val dedicated: Boolean)
internal data class UpdateArtifactCleanup(val deletedCount: Int, val failedFiles: List<File>)
private data class UpdateArtifactScan(val files: List<File>, val failures: List<File>)

/** Shared by all UpdateManager instances; no network I/O is performed under this lock. */
internal object UpdateDownloadFiles {
    val lock = Any()
    var activeFile: File? = null

    fun protectedFiles(): List<File> = activeFile?.let { listOf(it, File(it.parentFile, "${it.name}.part")) }.orEmpty()
}

internal class UpdateApkStorage(
    val downloadDirectory: File?,
    private val roots: List<UpdateArtifactRoot>,
) {
    fun artifacts(): List<File> {
        val protected = synchronized(UpdateDownloadFiles.lock) { UpdateDownloadFiles.protectedFiles().map(::updatePath).toSet() }
        return scanUpdateArtifacts(roots).files.filter { updatePath(it) !in protected }
    }

    fun owns(file: File): Boolean = roots.any { root ->
        !Files.isSymbolicLink(root.directory.toPath()) &&
            isUpdateArtifactName(file.name, root.dedicated) &&
            runCatching {
                val path = file.canonicalFile.toPath()
                val directory = root.directory.canonicalFile.toPath()
                if (root.dedicated) path.startsWith(directory) && path != directory else path.parent == directory
            }.getOrDefault(false)
    }

    fun cleanup(keepFile: File? = null): UpdateArtifactCleanup = synchronized(UpdateDownloadFiles.lock) {
        cleanUpdateArtifacts(roots, listOfNotNull(keepFile) + UpdateDownloadFiles.protectedFiles())
    }
}

internal fun updateArtifactRoots(
    cacheDirectory: File,
    filesDirectory: File,
    externalCacheDirectories: List<File>,
    externalFilesDirectories: List<File>,
): List<UpdateArtifactRoot> = buildList {
    (listOf(cacheDirectory, filesDirectory) + externalCacheDirectories + externalFilesDirectories).forEach { directory ->
        // Shared roots contain user data; only our LyraCode-* download names are owned.
        add(UpdateArtifactRoot(directory, dedicated = false))
        add(UpdateArtifactRoot(File(directory, "updates"), dedicated = true))
    }
}.distinctBy { updatePath(it.directory) to it.dedicated }

private fun isUpdateArtifactName(name: String, dedicated: Boolean): Boolean =
    (dedicated || name.startsWith("LyraCode-", ignoreCase = true)) &&
        (name.endsWith(".apk", ignoreCase = true) || name.endsWith(".part", ignoreCase = true))

private fun scanUpdateArtifacts(roots: List<UpdateArtifactRoot>): UpdateArtifactScan {
    val files = linkedMapOf<Path, File>()
    val failures = mutableListOf<File>()
    roots.forEach { root ->
        val path = root.directory.toPath()
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return@forEach
        runCatching {
            Files.walkFileTree(path, emptySet(), if (root.dedicated) Int.MAX_VALUE else 1,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (!attrs.isDirectory && isUpdateArtifactName(file.fileName.toString(), root.dedicated)) {
                            files[file.toAbsolutePath().normalize()] = file.toFile()
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        failures += file.toFile()
                        return FileVisitResult.CONTINUE
                    }
                })
        }.onFailure { failures += root.directory }
    }
    return UpdateArtifactScan(files.values.toList(), failures.distinct())
}

internal fun cleanUpdateArtifacts(
    roots: List<UpdateArtifactRoot>,
    keepFiles: List<File> = emptyList(),
    deleteFile: (File) -> Boolean = { Files.deleteIfExists(it.toPath()) },
): UpdateArtifactCleanup {
    val keepPaths = keepFiles.map(::updatePath).toSet()
    val scan = scanUpdateArtifacts(roots)
    val failures = scan.failures.toMutableList()
    var deleted = 0
    scan.files.forEach { file ->
        if (updatePath(file) in keepPaths) return@forEach
        runCatching {
            if (deleteFile(file)) deleted++
            else if (!Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) failures += file
        }.onFailure { failures += file }
    }
    return UpdateArtifactCleanup(deleted, failures.distinct())
}

private fun updatePath(file: File): Path = runCatching { file.canonicalFile.toPath() }
    .getOrElse { file.toPath().toAbsolutePath().normalize() }
