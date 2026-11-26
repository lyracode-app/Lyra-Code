package com.yukisoffd.lyracode.filemanager

import android.content.Context
import android.os.SystemClock
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.system.SystemCommandExecutor
import com.yukisoffd.lyracode.system.SystemCommandResult
import java.io.File
import java.nio.charset.StandardCharsets

internal class PrivilegedFileOperations(
    context: Context,
    private val settings: AppSettings,
    private val executor: SystemCommandExecutor,
) {
    private val appContext = context.applicationContext
    private val directlyAccessibleAppRoots = listOf(
        appContext.filesDir,
        appContext.cacheDir,
        appContext.noBackupFilesDir,
        appContext.codeCacheDir,
    ).map(::canonicalPath)
    private val managedProotInstancesRoot = canonicalPath(File(appContext.filesDir, "proot-linux/instances"))

    fun hasConfiguredAccess(): Boolean =
        settings.requestRootAccess || (settings.requestShellAccess && executor.hasShellPermission())

    fun accessLabel(): String = when {
        settings.requestRootAccess -> "Root"
        settings.requestShellAccess && executor.hasShellPermission() -> "Shizuku Shell"
        else -> ""
    }

    fun invalidate(directory: File) {
        val path = canonicalPath(directory)
        synchronized(LIST_CACHE) {
            LIST_CACHE.entries.removeAll { (_, cached) ->
                cached.path == path || cached.path.startsWith("$path${File.separator}")
            }
        }
    }

    suspend fun list(directory: File): Result<List<LocalFileEntry>> {
        val restricted = isRestricted(directory)
        if (!restricted) {
            LocalFileOperations.list(directory).onSuccess { return Result.success(it) }
        }
        val cacheKey = "${accessLabel()}:${canonicalPath(directory)}"
        cachedListing(cacheKey)?.let { return Result.success(it) }
        return privilegedResult(buildListCommand(directory)) { parseEntries(it.stdout) }
            .onSuccess { cacheListing(cacheKey, directory, it) }
    }

    suspend fun search(
        directory: File,
        query: String,
        includeFiles: Boolean,
        includeDirectories: Boolean,
    ): Result<List<LocalFileEntry>> {
        if (!isRestricted(directory)) {
            LocalFileOperations.search(directory, query, includeFiles, includeDirectories)
                .onSuccess { return Result.success(it) }
        }
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return Result.failure(IllegalArgumentException("Search query is empty"))
        val typeExpression = when {
            includeFiles && includeDirectories -> ""
            includeDirectories -> "-type d "
            includeFiles -> "-type f "
            else -> return Result.failure(IllegalArgumentException("At least one result type must be enabled"))
        }
        val command = """
            dir=${shellQuote(directory.absolutePath)}
            [ -d "${'$'}dir" ] || { echo "Not a directory: ${directory.absolutePath}" >&2; exit 2; }
            separator=${'$'}(printf '\034')
            find "${'$'}dir" -mindepth 1 ${typeExpression}-iname ${shellQuote("*$cleanQuery*")} 2>/dev/null | head -n 500 | while IFS= read -r item; do
              ${entryRecordScript("item")}
            done
        """.trimIndent()
        return privilegedResult(command) { parseEntries(it.stdout) }
    }

    suspend fun readUtf8(file: File, allowBinaryPreview: Boolean = false): Result<TextFileContent> {
        if (!isRestricted(file)) {
            LocalFileOperations.readUtf8(file, allowBinaryPreview).onSuccess { return Result.success(it) }
        }
        return prepareReadableCopy(file).fold(
            onSuccess = { staged ->
                LocalFileOperations.readUtf8(staged, allowBinaryPreview).also {
                    if (staged != file) staged.delete()
                }
            },
            onFailure = { Result.failure(it) },
        )
    }

    suspend fun prepareReadableCopy(file: File): Result<File> {
        if (!isRestricted(file) && file.isFile && file.canRead()) return Result.success(file)
        val stage = newStageFile(file.name)
        val command = """
            [ -f ${shellQuote(file.absolutePath)} ] &&
            mkdir -p ${shellQuote(stage.parentFile!!.absolutePath)} &&
            cp -f ${shellQuote(file.absolutePath)} ${shellQuote(stage.absolutePath)} &&
            chmod 0644 ${shellQuote(stage.absolutePath)}
        """.trimIndent()
        return privilegedResult(command) { stage }.onFailure { stage.delete() }
    }

    suspend fun saveUtf8WithBackup(file: File, text: String): Result<File?> {
        if (!isRestricted(file)) {
            LocalFileOperations.saveUtf8WithBackup(file, text).onSuccess { return Result.success(it) }
        }
        val stage = newStageFile(file.name)
        return runCatching {
            stage.writeText(text, StandardCharsets.UTF_8)
            val backup = File(file.parentFile, "${file.name}.bak")
            val command = """
                backup=0
                if [ -e ${shellQuote(file.absolutePath)} ]; then
                  cp -f ${shellQuote(file.absolutePath)} ${shellQuote(backup.absolutePath)} || exit ${'$'}?
                  backup=1
                fi
                cp -f ${shellQuote(stage.absolutePath)} ${shellQuote(file.absolutePath)} || exit ${'$'}?
                printf '%s' "${'$'}backup"
            """.trimIndent()
            executePrivileged(command, 60).getOrThrow().stdout.trim().let { if (it == "1") backup else null }
        }.also { result ->
            stage.delete()
            if (result.isSuccess) file.parentFile?.let(::invalidate)
        }
    }

    suspend fun createDirectory(parent: File, name: String): Result<File> {
        val target = safeChild(parent, name)
        if (!isRestricted(parent)) {
            LocalFileOperations.createDirectory(parent, name).onSuccess { return Result.success(it) }
        }
        return privilegedResult("mkdir ${shellQuote(target.absolutePath)}") { target }
            .onSuccess { invalidate(parent) }
    }

    suspend fun createFile(parent: File, name: String): Result<File> {
        val target = safeChild(parent, name)
        if (!isRestricted(parent)) {
            LocalFileOperations.createFile(parent, name).onSuccess { return Result.success(it) }
        }
        val command = "[ ! -e ${shellQuote(target.absolutePath)} ] && : > ${shellQuote(target.absolutePath)}"
        return privilegedResult(command) { target }
            .onSuccess { invalidate(parent) }
    }

    suspend fun rename(source: File, name: String): Result<File> {
        managedInstanceMutationError(source)?.let { return Result.failure(it) }
        val target = safeChild(source.parentFile ?: return Result.failure(IllegalArgumentException("Missing parent")), name)
        if (!isRestricted(source)) {
            LocalFileOperations.rename(source, name).onSuccess { return Result.success(it) }
        }
        val command = "[ ! -e ${shellQuote(target.absolutePath)} ] && mv ${shellQuote(source.absolutePath)} ${shellQuote(target.absolutePath)}"
        return privilegedResult(command) { target }
            .onSuccess { invalidate(source.parentFile ?: target.parentFile ?: source) }
    }

    suspend fun copy(source: File, destinationDirectory: File): Result<File> {
        val target = File(destinationDirectory, source.name)
        if (!isRestricted(source) && !isRestricted(destinationDirectory)) {
            LocalFileOperations.copy(source, destinationDirectory).onSuccess { return Result.success(it) }
        }
        require(!isInside(destinationDirectory, source)) { "Cannot copy a folder into itself" }
        val command = "[ ! -e ${shellQuote(target.absolutePath)} ] && cp -Rp ${shellQuote(source.absolutePath)} ${shellQuote(target.absolutePath)}"
        return privilegedResult(command, timeoutSeconds = 300) { target }
            .onSuccess { invalidate(destinationDirectory) }
    }

    suspend fun move(source: File, destinationDirectory: File): Result<File> {
        managedInstanceMutationError(source)?.let { return Result.failure(it) }
        val target = File(destinationDirectory, source.name)
        if (!isRestricted(source) && !isRestricted(destinationDirectory)) {
            LocalFileOperations.move(source, destinationDirectory).onSuccess { return Result.success(it) }
        }
        require(!isInside(destinationDirectory, source)) { "Cannot move a folder into itself" }
        val command = "[ ! -e ${shellQuote(target.absolutePath)} ] && mv ${shellQuote(source.absolutePath)} ${shellQuote(target.absolutePath)}"
        return privilegedResult(command, timeoutSeconds = 300) { target }
            .onSuccess {
                source.parentFile?.let(::invalidate)
                invalidate(destinationDirectory)
            }
    }

    suspend fun copyAll(sources: Collection<File>, destinationDirectory: File): Result<List<File>> =
        runCatching { sources.map { copy(it, destinationDirectory).getOrThrow() } }

    suspend fun moveAll(sources: Collection<File>, destinationDirectory: File): Result<List<File>> =
        runCatching { sources.map { move(it, destinationDirectory).getOrThrow() } }

    suspend fun delete(source: File): Result<Unit> {
        managedInstanceMutationError(source)?.let { return Result.failure(it) }
        if (!isRestricted(source)) {
            LocalFileOperations.delete(source).onSuccess { return Result.success(Unit) }
        }
        require(source.canonicalFile != LocalFileOperations.storageRoot.canonicalFile) {
            "Storage root cannot be deleted"
        }
        return privilegedResult("rm -rf ${shellQuote(source.absolutePath)}") { Unit }
            .onSuccess {
                invalidate(source)
                source.parentFile?.let(::invalidate)
            }
    }

    suspend fun deleteAll(sources: Collection<File>): Result<Unit> =
        runCatching { sources.forEach { delete(it).getOrThrow() } }

    suspend fun unzip(source: File, destinationDirectory: File): Result<File> {
        if (!isRestricted(source) && !isRestricted(destinationDirectory)) {
            LocalFileOperations.unzip(source, destinationDirectory).onSuccess { return Result.success(it) }
        }
        val output = File(destinationDirectory, source.nameWithoutExtension.ifBlank { "archive" })
        val command = "mkdir -p ${shellQuote(output.absolutePath)} && unzip -oq ${shellQuote(source.absolutePath)} -d ${shellQuote(output.absolutePath)}"
        return privilegedResult(command, timeoutSeconds = 300) { output }
            .onSuccess { invalidate(destinationDirectory) }
    }

    suspend fun totalSize(source: File): Result<Long> {
        if (!isRestricted(source)) {
            LocalFileOperations.totalSize(source).onSuccess { return Result.success(it) }
        }
        val command = "du -sk ${shellQuote(source.absolutePath)} | head -n 1 | cut -f 1"
        return privilegedResult(command, timeoutSeconds = 120) {
            it.stdout.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()?.times(1024L)
                ?: error("Unable to read folder size")
        }
    }

    suspend fun testAccess(): Result<String> = privilegedResult(
        "id; test -d /storage/emulated/0/Android/data && ls /storage/emulated/0/Android/data >/dev/null",
    ) { it.mode }

    private fun buildListCommand(directory: File): String = """
        dir=${shellQuote(directory.absolutePath)}
        [ -d "${'$'}dir" ] || { echo "Not a directory: ${directory.absolutePath}" >&2; exit 2; }
        separator=${'$'}(printf '\034')
        set --
        for item in "${'$'}dir"/* "${'$'}dir"/.[!.]* "${'$'}dir"/..?*; do
          [ -e "${'$'}item" ] || [ -L "${'$'}item" ] || continue
          set -- "${'$'}@" "${'$'}item"
        done
        [ "${'$'}#" -eq 0 ] || stat -c "%f${'$'}{separator}%s${'$'}{separator}%Y${'$'}{separator}%n" "${'$'}@"
    """.trimIndent()

    private fun entryRecordScript(variable: String): String = """
        stat -c "%f${'$'}{separator}%s${'$'}{separator}%Y${'$'}{separator}%n" "${'$'}$variable" 2>/dev/null
    """.trimIndent()

    private fun parseEntries(stdout: String): List<LocalFileEntry> = stdout.lineSequence()
        .mapNotNull(::parseEntry)
        .sortedWith(compareByDescending<LocalFileEntry> { it.directory }.thenBy { it.name.lowercase() })
        .toList()

    private fun parseEntry(line: String): LocalFileEntry? {
        val parts = line.split(ENTRY_SEPARATOR, limit = 4)
        if (parts.size != 4) return null
        val path = parts[3].takeIf { it.startsWith("/") } ?: return null
        val mode = parts[0].toIntOrNull(16) ?: return null
        val file = File(path)
        return LocalFileEntry(
            file = file,
            name = file.name.ifBlank { path },
            directory = mode and FILE_TYPE_MASK == DIRECTORY_MODE,
            size = parts[1].toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
            modifiedAt = (parts[2].toLongOrNull()?.coerceAtLeast(0L) ?: 0L) * 1000L,
        )
    }

    private suspend fun <T> privilegedResult(
        command: String,
        timeoutSeconds: Int = 60,
        transform: (SystemCommandResult) -> T,
    ): Result<T> = executePrivileged(command, timeoutSeconds).map(transform)

    private suspend fun executePrivileged(command: String, timeoutSeconds: Int): Result<SystemCommandResult> {
        if (!settings.requestRootAccess && !settings.requestShellAccess) {
            return Result.failure(IllegalStateException("Enable Root or Shizuku Shell access first"))
        }
        val result = if (settings.requestRootAccess) {
            executor.executeRoot(command, timeoutSeconds, allowShellFallback = true)
        } else {
            executor.executeShell(command, timeoutSeconds)
        }
        return if (result.ok) {
            Result.success(result)
        } else {
            Result.failure(
                IllegalStateException(
                    result.stderr.ifBlank { result.message }.ifBlank { "Privileged file operation failed" },
                ),
            )
        }
    }

    private fun newStageFile(originalName: String): File {
        val directory = File(appContext.externalCacheDir ?: appContext.cacheDir, "privileged-files").apply { mkdirs() }
        val cleanName = originalName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "file" }
        return File(directory, "${System.nanoTime()}-$cleanName")
    }

    private fun safeChild(parent: File, name: String): File {
        val clean = name.trim()
        require(clean.isNotBlank() && clean != "." && clean != "..") { "Invalid name" }
        require('/' !in clean && '\\' !in clean) { "Name cannot contain path separators" }
        val child = File(parent, clean).canonicalFile
        require(child.parentFile == parent.canonicalFile) { "Invalid path" }
        return child
    }

    private fun managedInstanceMutationError(file: File): IllegalArgumentException? {
        val path = canonicalPath(file)
        return if (File(path).parent == managedProotInstancesRoot) {
            IllegalArgumentException(
                "Whole PRoot Linux instances must be renamed or deleted from the PRoot Linux management page.",
            )
        } else null
    }

    private fun isRestricted(file: File): Boolean {
        val path = canonicalPath(file)
        if (directlyAccessibleAppRoots.any { root -> path == root || path.startsWith("$root${File.separator}") }) {
            return false
        }
        return RESTRICTED_PREFIXES.any { path == it || path.startsWith("$it/") }
    }

    private fun canonicalPath(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    private fun cachedListing(key: String): List<LocalFileEntry>? = synchronized(LIST_CACHE) {
        val cached = LIST_CACHE[key] ?: return@synchronized null
        if (SystemClock.elapsedRealtime() - cached.loadedAt > LIST_CACHE_TTL_MS) {
            LIST_CACHE.remove(key)
            null
        } else {
            cached.entries
        }
    }

    private fun cacheListing(key: String, directory: File, entries: List<LocalFileEntry>) {
        synchronized(LIST_CACHE) {
            LIST_CACHE[key] = CachedListing(
                path = canonicalPath(directory),
                loadedAt = SystemClock.elapsedRealtime(),
                entries = entries,
            )
            while (LIST_CACHE.size > LIST_CACHE_MAX_ENTRIES) {
                LIST_CACHE.entries.iterator().run {
                    if (hasNext()) {
                        next()
                        remove()
                    }
                }
            }
        }
    }

    private fun isInside(candidate: File, parent: File): Boolean {
        val candidatePath = runCatching { candidate.canonicalPath }.getOrDefault(candidate.absolutePath)
        val parentPath = runCatching { parent.canonicalPath }.getOrDefault(parent.absolutePath)
        return candidatePath == parentPath || candidatePath.startsWith("$parentPath${File.separator}")
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    companion object {
        private const val ENTRY_SEPARATOR = '\u001C'
        private const val FILE_TYPE_MASK = 0xF000
        private const val DIRECTORY_MODE = 0x4000
        private const val LIST_CACHE_TTL_MS = 5 * 60 * 1000L
        private const val LIST_CACHE_MAX_ENTRIES = 64
        private data class CachedListing(
            val path: String,
            val loadedAt: Long,
            val entries: List<LocalFileEntry>,
        )
        private val LIST_CACHE = object : LinkedHashMap<String, CachedListing>(16, 0.75f, true) {}
        private val RESTRICTED_PREFIXES = listOf(
            "/storage/emulated/0/Android/data",
            "/storage/emulated/0/Android/obb",
            "/sdcard/Android/data",
            "/sdcard/Android/obb",
            "/data",
        )
    }
}
