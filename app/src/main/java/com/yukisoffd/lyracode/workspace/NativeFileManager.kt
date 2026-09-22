package com.yukisoffd.lyracode.workspace

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.FileNotFoundException
import java.io.InputStream

data class WorkspaceFile(
    val name: String,
    val path: String,
    val directory: Boolean,
    val size: Long,
    val modifiedAt: Long,
)

class NativeFileManager(
    private val context: Context,
    private val workspaceManager: WorkspaceManager,
) {
    fun hasWorkspaceRoot(): Boolean = workspaceManager.rootUri() != null

    fun listDirectory(path: String = ""): Result<List<WorkspaceFile>> = runCatching {
        val dir = resolve(path) ?: throw FileNotFoundException("目录不存在: $path")
        require(dir.isDirectory) { "不是目录: $path" }
        dir.listFiles()
            .sortedWith(compareByDescending<DocumentFile> { it.isDirectory }.thenBy { it.name.orEmpty().lowercase() })
            .map {
                WorkspaceFile(
                    name = it.name.orEmpty(),
                    path = joinPath(path, it.name.orEmpty()),
                    directory = it.isDirectory,
                    size = it.length(),
                    modifiedAt = it.lastModified(),
                )
            }
    }

    fun readFile(path: String): Result<String> = runCatching {
        readText(path, MAX_READ_BYTES)
    }

    fun readFileForEdit(path: String): Result<String> = runCatching {
        readText(path, MAX_EDIT_BYTES)
    }

    private fun readText(path: String, maxBytes: Long): String {
        val file = resolve(path) ?: throw FileNotFoundException("文件不存在: $path")
        require(file.isFile) { "不是文件: $path" }
        require(file.length() <= maxBytes) { "文件超过 ${maxBytes / 1024 / 1024}MB，无法安全编辑: $path" }
        return context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() }
            ?: throw FileNotFoundException("无法读取: $path")
    }

    fun readBytes(path: String, maxBytes: Long = MAX_BINARY_BYTES): Result<ByteArray> = runCatching {
        val file = resolve(path) ?: throw FileNotFoundException("文件不存在: $path")
        require(file.isFile) { "不是文件: $path" }
        require(file.length() <= maxBytes) { "文件超过 ${maxBytes / 1024 / 1024}MB: $path" }
        context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
            ?: throw FileNotFoundException("无法读取: $path")
    }

    fun writeFile(path: String, content: String): Result<String> = runCatching {
        backupExistingTextFile(path)
        val file = findOrCreateFile(path)
        context.contentResolver.openOutputStream(file.uri, "wt")?.bufferedWriter()?.use { it.write(content) }
            ?: throw FileNotFoundException("无法写入: $path")
        workspaceManager.invalidateFileIndex()
        "已写入 ${content.length} 字符: $path"
    }

    fun writeBytes(path: String, bytes: ByteArray): Result<String> = runCatching {
        val file = findOrCreateFile(path)
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(bytes) }
            ?: throw FileNotFoundException("无法写入: $path")
        workspaceManager.invalidateFileIndex()
        "已写入 ${bytes.size} 字节: $path"
    }

    fun writeStream(path: String, input: InputStream): Result<Long> = runCatching {
        val file = findOrCreateFile(path)
        val written = context.contentResolver.openOutputStream(file.uri, "wt")?.buffered()?.use { output ->
            input.copyTo(output)
        } ?: throw FileNotFoundException("无法写入: $path")
        workspaceManager.invalidateFileIndex()
        written
    }

    fun appendFile(path: String, content: String): Result<String> = runCatching {
        backupExistingTextFile(path)
        val file = findOrCreateFile(path)
        context.contentResolver.openOutputStream(file.uri, "wa")?.bufferedWriter()?.use { it.write(content) }
            ?: throw FileNotFoundException("无法追加: $path")
        workspaceManager.invalidateFileIndex()
        "已追加 ${content.length} 字符: $path"
    }

    fun createFolder(path: String): Result<String> = runCatching {
        val clean = normalize(path)
        require(clean.isNotBlank()) { "目录名不能为空" }
        val segments = clean.split("/")
        val folderName = segments.last()
        val parent = resolve(segments.dropLast(1).joinToString("/")) ?: throw FileNotFoundException("父目录不存在")
        parent.findFile(folderName) ?: parent.createDirectory(folderName)
            ?: throw FileNotFoundException("无法创建目录: $path")
        workspaceManager.invalidateFileIndex()
        "已创建目录: $path"
    }

    fun delete(path: String): Result<String> = runCatching {
        val file = resolve(path) ?: throw FileNotFoundException("不存在: $path")
        require(file.delete()) { "删除失败，非空目录请改用 Termux rm -rf 并确认风险" }
        workspaceManager.invalidateFileIndex()
        "已删除: $path"
    }

    fun renameMove(from: String, to: String): Result<String> = runCatching {
        val source = resolve(from) ?: throw FileNotFoundException("源不存在: $from")
        val sourceParent = parentPath(from)
        val targetParent = parentPath(to)
        require(sourceParent == targetParent) { "SAF 原生工具只支持同目录重命名，跨目录移动请使用 Termux" }
        require(source.renameTo(normalize(to).substringAfterLast("/"))) { "重命名失败: $from" }
        workspaceManager.invalidateFileIndex()
        "已重命名: $from -> $to"
    }

    fun searchFiles(query: String, basePath: String = ""): Result<List<WorkspaceFile>> = runCatching {
        val startedAt = System.currentTimeMillis()
        val cleanBasePath = normalize(basePath)
        val base = resolve(cleanBasePath) ?: throw FileNotFoundException("目录不存在: $basePath")
        require(base.isDirectory) { "不是目录: $basePath" }
        val matcher = FileSearchMatcher(query)
        val results = LinkedHashMap<String, WorkspaceFile>()
        Log.d(TAG, "search_start query='$query' base='$cleanBasePath' source=workspace_index")
        workspaceManager.searchEntries(query, cleanBasePath, SEARCH_LIMIT).forEach { file ->
            results[file.relativePath] = WorkspaceFile(
                name = file.name,
                path = file.relativePath,
                directory = file.directory,
                size = file.size,
                modifiedAt = file.modifiedAt,
            )
        }
        if (results.isEmpty() && cleanBasePath.isBlank()) {
            fuzzyPathCandidates(matcher).forEach { candidate ->
                resolve(candidate)?.let {
                    results[candidate] = WorkspaceFile(it.name.orEmpty(), candidate, it.isDirectory, it.length(), it.lastModified())
                }
            }
        }
        val sorted = results.values.toList()
        Log.d(
            TAG,
            "search_end query='$query' base='$cleanBasePath' results=${sorted.size} " +
                "source=workspace_index durationMs=${System.currentTimeMillis() - startedAt} " +
                "sample=${sorted.take(8).joinToString { it.path }}",
        )
        sorted
    }

    fun fileInfo(path: String): Result<String> = runCatching {
        val file = resolve(path) ?: throw FileNotFoundException("不存在: $path")
        val clean = normalize(path)
        """
        path: ${clean.ifBlank { "." }}
        name: ${file.name}
        type: ${if (file.isDirectory) "directory" else "file"}
        size: ${file.length()}
        modifiedAt: ${file.lastModified()}
        uri: ${file.uri}
        termuxPath: ${workspaceManager.termuxPath(clean).orEmpty()}
        """.trimIndent()
    }

    private fun findOrCreateFile(path: String): DocumentFile {
        val clean = normalize(path)
        require(clean.isNotBlank()) { "文件路径不能为空" }
        val parent = findOrCreateDirectory(parentPath(clean))
        val name = clean.substringAfterLast("/")
        parent.findFile(name)?.let { return it }
        val created = parent.createFile(mimeFor(name), name)
            ?: throw FileNotFoundException("无法创建文件: $path")
        if (created.name != name) {
            val renamed = created.renameTo(name)
            val resolved = parent.findFile(name)
            if (!renamed || resolved == null) {
                val actual = created.name.orEmpty()
                created.delete()
                throw FileNotFoundException("SAF 创建文件时被系统改名为 $actual，无法创建目标文件名: $name")
            }
            return resolved
        }
        return created
    }

    private fun backupExistingTextFile(path: String) {
        if (path.endsWith(".bak", ignoreCase = true)) return
        val source = resolve(path)?.takeIf { it.isFile } ?: return
        val backup = findOrCreateFile("${normalize(path)}.bak")
        context.contentResolver.openInputStream(source.uri)?.buffered()?.use { input ->
            context.contentResolver.openOutputStream(backup.uri, "wt")?.buffered()?.use { output ->
                input.copyTo(output)
            } ?: throw FileNotFoundException("无法写入备份: $path.bak")
        } ?: throw FileNotFoundException("无法读取原文件以生成备份: $path")
    }

    private fun findOrCreateDirectory(path: String): DocumentFile {
        var current = workspaceManager.root() ?: throw FileNotFoundException("未选择工作目录")
        val clean = normalize(path)
        if (clean.isBlank()) return current
        clean.split("/").forEach { segment ->
            val existing = current.findFile(segment)
            current = when {
                existing == null -> current.createDirectory(segment)
                    ?: throw FileNotFoundException("无法创建目录: $segment")
                existing.isDirectory -> existing
                else -> throw FileNotFoundException("父路径不是目录: $segment")
            }
        }
        return current
    }

    private fun resolve(path: String): DocumentFile? {
        var current = workspaceManager.root() ?: return null
        val clean = normalize(path)
        if (clean.isBlank()) return current
        for (segment in clean.split("/")) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    private fun fuzzyPathCandidates(matcher: FileSearchMatcher): List<String> {
        return matcher.rawTerms
            .filter { it.contains("/") }
            .flatMap { term ->
                val clean = runCatching { normalize(term) }.getOrNull().orEmpty()
                listOf(clean, clean.substringAfterLast("/", ""))
            }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun normalize(path: String): String {
        var clean = path.replace('\\', '/').trim()
        if (clean.isBlank() || clean == "." || clean == "./" || clean == "/") return ""
        if (clean.startsWith("./")) clean = clean.removePrefix("./")

        workspaceManager.termuxRootPath()?.trimEnd('/')?.let { root ->
            val aliases = listOf(root, root.replace("/storage/emulated/0", "/sdcard")) +
                if (workspaceManager.prootLinuxId() != null) listOfNotNull("/workspace", workspaceManager.prootGuestPath()?.trimEnd('/')) else emptyList()
            aliases.forEach { alias ->
                when {
                    clean == alias -> return ""
                    clean.startsWith("$alias/") -> {
                        clean = clean.removePrefix("$alias/")
                        return normalizeRelative(clean)
                    }
                }
            }
        }

        require(!clean.startsWith("/data/data/com.termux")) {
            "文件工具只能访问 Lyra Code 工作目录，不能访问 Termux 私有目录。请使用相对路径或工作区路径。"
        }
        require(!clean.startsWith("/data/data/")) {
            "文件工具只能访问 Lyra Code 工作目录，不能访问 Android 应用私有目录。"
        }
        require(!clean.startsWith("/")) {
            "绝对路径不在当前工作目录内: $path。请改用相对路径，例如 . 或 src/main.py。"
        }
        return normalizeRelative(clean)
    }

    private fun normalizeRelative(path: String): String {
        val parts = path.trim('/').split('/').filter { it.isNotBlank() && it != "." }
        require(parts.none { it == ".." }) { "路径不能包含 .." }
        return parts.joinToString("/")
    }

    private fun parentPath(path: String): String = normalize(path).substringBeforeLast("/", missingDelimiterValue = "")

    private fun joinPath(parent: String, child: String): String {
        val cleanParent = normalize(parent)
        return if (cleanParent.isBlank()) child else "$cleanParent/$child"
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html"
        "json" -> "application/json"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    companion object {
        private const val TAG = "LyraSearch"
        private const val MAX_READ_BYTES = 1_048_576L
        private const val MAX_EDIT_BYTES = 16L * 1024L * 1024L
        private const val MAX_BINARY_BYTES = 200L * 1024L * 1024L
        private const val SEARCH_LIMIT = 200
    }
}

internal class FileSearchMatcher(query: String) {
    val rawTerms: List<String> = query
        .replace('\\', '/')
        .split(Regex("\\s+"))
        .map { it.trim().trim('"', '\'', '`') }
        .filter { it.isNotBlank() }

    private val terms = rawTerms.map { normalizeToken(it) }.filter { it.isNotBlank() }

    fun matches(name: String, path: String): Boolean {
        return matchScore(name, path) != null
    }

    fun score(name: String, path: String): Int {
        return matchScore(name, path) ?: 0
    }

    internal fun strictMatchScore(name: String, path: String): Int? {
        if (rawTerms.isEmpty()) return 1
        var total = 0
        rawTerms.forEach { term ->
            val termScore = when {
                name.equals(term, ignoreCase = true) -> 100
                path.endsWith("/$term", ignoreCase = true) -> 90
                name.startsWith(term, ignoreCase = true) -> 80
                name.contains(term, ignoreCase = true) -> 70
                path.contains(term, ignoreCase = true) -> 50
                else -> 0
            }
            if (termScore == 0) return null
            total += termScore
        }
        return total
    }

    internal fun matchScore(name: String, path: String): Int? {
        if (terms.isEmpty()) return 1
        val normalizedName = normalizeToken(name)
        val normalizedPath = normalizeToken(path)
        var total = 0
        terms.forEach { term ->
            val termScore = when {
                normalizedName == term -> 100
                normalizedPath.endsWith("/$term") -> 90
                normalizedName.startsWith(term) -> 80
                normalizedName.contains(term) -> 70
                normalizedPath.contains(term) -> 50
                fuzzyContains(normalizedName, term) -> 30
                fuzzyContains(normalizedPath, term) -> 20
                else -> 0
            }
            if (termScore == 0) return null
            total += termScore
        }
        return total
    }

    private fun normalizeToken(value: String): String {
        return value.lowercase()
            .replace('\\', '/')
            .replace(TOKEN_SEPARATOR_REGEX, "")
            .trim('/')
    }

    private fun fuzzyContains(haystack: String, needle: String): Boolean {
        if (needle.length < 3) return false
        var index = 0
        for (char in haystack) {
            if (char == needle[index]) index++
            if (index == needle.length) return true
        }
        return false
    }

    private companion object {
        val TOKEN_SEPARATOR_REGEX = Regex("[_\\-.]+")
    }
}
