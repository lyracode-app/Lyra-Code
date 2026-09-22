package com.yukisoffd.lyracode.workspace

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.documentfile.provider.ProotDocumentFile
import java.io.File
import java.nio.file.LinkOption
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayDeque

internal class WorkspaceFileIndexer(
    private val context: Context,
    private val workspaceManager: WorkspaceManager,
) {
    private val cacheLock = Any()
    private var cachedState: WorkspaceIndexState? = null

    fun invalidate() {
        synchronized(cacheLock) {
            cachedState = null
        }
    }

    fun search(
        query: String,
        limit: Int,
        basePath: String = "",
        includeDirectories: Boolean = false,
        quickReturnOnStrongMatch: Boolean = false,
    ): List<WorkspaceFileReference> {
        val workspaceUri = workspaceManager.activeWorkspaceUri()
        if (workspaceUri.isBlank()) return emptyList()
        val resultLimit = limit.coerceIn(1, MAX_SEARCH_RESULTS)
        val cleanBasePath = normalizeSearchBasePath(basePath)
        val matcher = FileSearchMatcher(query)
        return synchronized(cacheLock) {
            val now = System.currentTimeMillis()
            val state = cachedState?.takeIf {
                it.workspaceUri == workspaceUri && now - it.createdAt < INDEX_CACHE_TTL_MS
            }
                ?: createState(workspaceUri).also { cachedState = it }
            val startedAt = System.currentTimeMillis()
            val entriesBefore = state.entries.size
            val directoriesBefore = state.visitedDirectories
            extendIndex(
                state,
                matcher,
                query,
                cleanBasePath,
                includeDirectories,
                resultLimit,
                quickReturnOnStrongMatch,
            )
            val results = searchWorkspaceFileIndex(
                files = state.entries,
                query = query,
                limit = resultLimit,
                basePath = cleanBasePath,
                includeDirectories = includeDirectories,
            )
            Log.d(
                TAG,
                "workspace_index_search source=${state.source} query='$query' base='$cleanBasePath' " +
                    "added=${state.entries.size - entriesBefore} entries=${state.entries.size} " +
                    "directoriesAdded=${state.visitedDirectories - directoriesBefore} " +
                    "results=${results.size} complete=${state.complete} truncated=${state.truncated} " +
                    "failures=${state.failures} durationMs=${System.currentTimeMillis() - startedAt}",
            )
            results
        }
    }

    private fun createState(workspaceUri: String): WorkspaceIndexState {
        val state = WorkspaceIndexState(workspaceUri, Uri.parse(workspaceUri))
        if (workspaceManager.prootLinuxId() != null) {
            switchToDocumentFile(state)
            return state
        }
        val directRoot = workspaceManager.termuxRootPath()
            ?.let(::File)
            ?.takeIf { runCatching { it.isDirectory }.getOrDefault(false) }
        if (directRoot != null) {
            state.source = IndexSource.DIRECT
            state.directDirectories.add(directRoot to "")
        } else {
            switchToSaf(state)
        }
        return state
    }

    private fun extendIndex(
        state: WorkspaceIndexState,
        matcher: FileSearchMatcher,
        query: String,
        basePath: String,
        includeDirectories: Boolean,
        limit: Int,
        quickReturnOnStrongMatch: Boolean,
    ) {
        if (state.complete) return
        var strictMatches = 0
        var strongMatchFound = false
        for (entry in state.entries) {
            if (!entry.isInSearchScope(basePath, includeDirectories)) continue
            val score = matcher.strictMatchScore(entry.name, entry.relativePath)
            if (score != null) {
                strictMatches++
                if (shouldQuickReturnFromWorkspaceIndex(query, score)) {
                    strongMatchFound = true
                    if (quickReturnOnStrongMatch) break
                }
                if (strictMatches >= limit) break
            }
        }
        if (quickReturnOnStrongMatch && strongMatchFound) return
        while (!state.complete && strictMatches < limit) {
            if (state.visitedDirectories >= MAX_INDEX_DIRECTORIES || state.entries.size >= MAX_INDEX_ENTRIES) {
                truncate(state)
                break
            }
            val entriesBefore = state.entries.size
            val step = when (state.source) {
                IndexSource.DIRECT -> processDirectDirectory(state, matcher, basePath, includeDirectories)
                IndexSource.SAF -> processSafDirectory(state, matcher, basePath, includeDirectories)
                IndexSource.DOCUMENT_FILE -> processDocumentFileDirectory(state, matcher, basePath, includeDirectories)
            }
            strictMatches += step.matchesAdded
            if (
                quickReturnOnStrongMatch &&
                state.entries.subList(entriesBefore, state.entries.size).any { entry ->
                    entry.isInSearchScope(basePath, includeDirectories) &&
                        shouldQuickReturnFromWorkspaceIndex(
                            query,
                            matcher.strictMatchScore(entry.name, entry.relativePath) ?: 0,
                        )
                }
            ) {
                break
            }
            if (!step.progressed) break
        }
    }

    private fun processDirectDirectory(
        state: WorkspaceIndexState,
        matcher: FileSearchMatcher,
        basePath: String,
        includeDirectories: Boolean,
    ): IndexStep {
        val pending = pollRelevantDirectory(state.directDirectories, state.deferredDirectDirectories, basePath)
            ?: return finishDirectSource(state)
        val (directory, prefix) = pending
        state.visitedDirectories++
        val children = directory.listFiles()
        if (children == null) {
            state.failures++
            if (prefix.isBlank() && state.entries.isEmpty()) switchToSaf(state)
            return IndexStep(progressed = true)
        }
        var matchesAdded = 0
        for (child in children) {
            if (state.entries.size >= MAX_INDEX_ENTRIES) {
                truncate(state)
                break
            }
            val attributes = readDirectAttributes(child) ?: run {
                state.failures++
                continue
            }
            val path = joinPath(prefix, child.name)
            val reference = directReference(child, path, attributes) ?: continue
            matchesAdded += addEntry(state, reference, matcher, basePath, includeDirectories)
            if (reference.directory && !attributes.isSymbolicLink && !state.complete) {
                enqueueDirectory(state.directDirectories, state.deferredDirectDirectories, child, path)
            }
        }
        if (state.directDirectories.isEmpty() && state.deferredDirectDirectories.isEmpty()) {
            if (state.entries.isEmpty() && state.failures > 0) switchToSaf(state) else state.complete = true
        }
        return IndexStep(matchesAdded, progressed = true)
    }

    private fun processSafDirectory(
        state: WorkspaceIndexState,
        matcher: FileSearchMatcher,
        basePath: String,
        includeDirectories: Boolean,
    ): IndexStep {
        val pending = pollRelevantDirectory(state.safDirectories, state.deferredSafDirectories, basePath)
            ?: return finishSafSource(state)
        val (documentId, prefix) = pending
        state.visitedDirectories++
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(state.treeUri, documentId)
        var matchesAdded = 0
        var querySucceeded = false
        var queryFailed = false
        runCatching {
            context.contentResolver.query(childrenUri, INDEX_PROJECTION, null, null, null)?.use { cursor ->
                querySucceeded = true
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext() && !state.complete) {
                    val childId = cursor.stringOrEmpty(idIndex)
                    val name = cursor.stringOrEmpty(nameIndex)
                    if (childId.isBlank() || name.isBlank()) continue
                    val path = joinPath(prefix, name)
                    val directory = cursor.stringOrEmpty(mimeIndex) == DocumentsContract.Document.MIME_TYPE_DIR
                    val reference = WorkspaceFileReference(
                        name = name,
                        relativePath = path,
                        uri = DocumentsContract.buildDocumentUriUsingTree(state.treeUri, childId).toString(),
                        size = if (directory) 0L else cursor.longOrZero(sizeIndex).coerceAtLeast(0L),
                        directory = directory,
                        modifiedAt = cursor.longOrZero(modifiedIndex).coerceAtLeast(0L),
                    )
                    matchesAdded += addEntry(state, reference, matcher, basePath, includeDirectories)
                    if (directory && !state.complete) {
                        enqueueDirectory(state.safDirectories, state.deferredSafDirectories, childId, path)
                    }
                }
            }
        }.onFailure {
            queryFailed = true
            Log.w(TAG, "workspace_index_query_failed path='$prefix' uri=$childrenUri", it)
        }
        if (!querySucceeded || queryFailed) {
            state.failures++
            if (prefix.isBlank() && state.entries.isEmpty()) switchToDocumentFile(state)
        }
        if (
            state.source == IndexSource.SAF &&
            state.safDirectories.isEmpty() &&
            state.deferredSafDirectories.isEmpty()
        ) {
            state.complete = true
        }
        return IndexStep(matchesAdded, progressed = true)
    }

    private fun processDocumentFileDirectory(
        state: WorkspaceIndexState,
        matcher: FileSearchMatcher,
        basePath: String,
        includeDirectories: Boolean,
    ): IndexStep {
        val pending = pollRelevantDirectory(
            state.documentFileDirectories,
            state.deferredDocumentFileDirectories,
            basePath,
        )
            ?: return finishDocumentFileSource(state)
        val (directory, prefix) = pending
        state.visitedDirectories++
        val children = runCatching { directory.listFiles() }
            .onFailure {
                state.failures++
                Log.w(TAG, "workspace_document_file_list_failed path='$prefix' uri=${directory.uri}", it)
            }
            .getOrDefault(emptyArray())
        var matchesAdded = 0
        for (child in children) {
            val name = child.name.orEmpty()
            if (name.isBlank()) continue
            val path = joinPath(prefix, name)
            val directoryEntry = child.isDirectory
            if (!directoryEntry && !child.isFile) continue
            val reference = WorkspaceFileReference(
                name = name,
                relativePath = path,
                uri = child.uri.toString(),
                size = if (directoryEntry) 0L else child.length().coerceAtLeast(0L),
                directory = directoryEntry,
                modifiedAt = child.lastModified().coerceAtLeast(0L),
            )
            matchesAdded += addEntry(state, reference, matcher, basePath, includeDirectories)
            if (directoryEntry && !state.complete && !(child is ProotDocumentFile && child.isSymbolicLink)) {
                enqueueDirectory(
                    state.documentFileDirectories,
                    state.deferredDocumentFileDirectories,
                    child,
                    path,
                )
            }
            if (state.complete) break
        }
        if (state.documentFileDirectories.isEmpty() && state.deferredDocumentFileDirectories.isEmpty()) {
            state.complete = true
        }
        return IndexStep(matchesAdded, progressed = true)
    }

    private fun addEntry(
        state: WorkspaceIndexState,
        entry: WorkspaceFileReference,
        matcher: FileSearchMatcher,
        basePath: String,
        includeDirectories: Boolean,
    ): Int {
        if (state.entries.size >= MAX_INDEX_ENTRIES) {
            truncate(state)
            return 0
        }
        state.entries += entry
        return if (
            entry.isInSearchScope(basePath, includeDirectories) &&
            matcher.strictMatchScore(entry.name, entry.relativePath) != null
        ) {
            1
        } else {
            0
        }
    }

    private fun directReference(
        file: File,
        relativePath: String,
        attributes: BasicFileAttributes,
    ): WorkspaceFileReference? {
        val symbolicLink = attributes.isSymbolicLink
        val directory = attributes.isDirectory || symbolicLink && file.isDirectory
        val regularFile = attributes.isRegularFile || symbolicLink && file.isFile
        if (!directory && !regularFile) return null
        return WorkspaceFileReference(
            name = file.name,
            relativePath = relativePath,
            uri = file.toURI().toString(),
            size = if (directory) 0L else if (symbolicLink) file.length().coerceAtLeast(0L) else attributes.size().coerceAtLeast(0L),
            directory = directory,
            modifiedAt = if (symbolicLink) file.lastModified().coerceAtLeast(0L) else attributes.lastModifiedTime().toMillis().coerceAtLeast(0L),
        )
    }

    private fun readDirectAttributes(file: File): BasicFileAttributes? =
        runCatching {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull()

    private fun finishDirectSource(state: WorkspaceIndexState): IndexStep {
        if (state.directDirectories.isEmpty() && state.deferredDirectDirectories.isEmpty()) {
            if (state.entries.isEmpty() && state.failures > 0) {
                switchToSaf(state)
                return IndexStep(progressed = true)
            }
            state.complete = true
        }
        return IndexStep(progressed = false)
    }

    private fun finishSafSource(state: WorkspaceIndexState): IndexStep {
        if (state.safDirectories.isEmpty() && state.deferredSafDirectories.isEmpty()) state.complete = true
        return IndexStep(progressed = false)
    }

    private fun finishDocumentFileSource(state: WorkspaceIndexState): IndexStep {
        if (state.documentFileDirectories.isEmpty() && state.deferredDocumentFileDirectories.isEmpty()) {
            state.complete = true
        }
        return IndexStep(progressed = false)
    }

    private fun switchToSaf(state: WorkspaceIndexState) {
        state.complete = false
        state.source = IndexSource.SAF
        state.directDirectories.clear()
        state.deferredDirectDirectories.clear()
        val rootDocumentId = runCatching { DocumentsContract.getTreeDocumentId(state.treeUri) }.getOrNull()
        if (rootDocumentId == null) {
            state.failures++
            switchToDocumentFile(state)
        } else {
            state.safDirectories.clear()
            state.deferredSafDirectories.clear()
            state.safDirectories.add(rootDocumentId to "")
        }
    }

    private fun switchToDocumentFile(state: WorkspaceIndexState) {
        state.complete = false
        state.source = IndexSource.DOCUMENT_FILE
        state.safDirectories.clear()
        state.deferredSafDirectories.clear()
        state.documentFileDirectories.clear()
        state.deferredDocumentFileDirectories.clear()
        workspaceManager.root()?.let { state.documentFileDirectories.add(it to "") }
        if (state.documentFileDirectories.isEmpty()) state.complete = true
    }

    private fun truncate(state: WorkspaceIndexState) {
        state.truncated = true
        state.complete = true
        state.directDirectories.clear()
        state.deferredDirectDirectories.clear()
        state.safDirectories.clear()
        state.deferredSafDirectories.clear()
        state.documentFileDirectories.clear()
        state.deferredDocumentFileDirectories.clear()
    }

    private fun <T> pollRelevantDirectory(
        directories: ArrayDeque<Pair<T, String>>,
        deferredDirectories: ArrayDeque<Pair<T, String>>,
        basePath: String,
    ): Pair<T, String>? {
        return pollRelevantDirectory(directories, basePath)
            ?: pollRelevantDirectory(deferredDirectories, basePath)
    }

    private fun <T> pollRelevantDirectory(
        directories: ArrayDeque<Pair<T, String>>,
        basePath: String,
    ): Pair<T, String>? {
        if (directories.isEmpty()) return null
        if (basePath.isBlank()) return directories.pollFirst()
        val iterator = directories.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (workspaceIndexDirectoryMayContainBase(candidate.second, basePath)) {
                iterator.remove()
                return candidate
            }
        }
        return null
    }

    private fun <T> enqueueDirectory(
        directories: ArrayDeque<Pair<T, String>>,
        deferredDirectories: ArrayDeque<Pair<T, String>>,
        value: T,
        path: String,
    ) {
        val target = if (shouldDeferWorkspaceIndexDirectory(path)) deferredDirectories else directories
        target.add(value to path)
    }

    private fun joinPath(parent: String, child: String): String =
        if (parent.isBlank()) child else "$parent/$child"

    private data class WorkspaceIndexState(
        val workspaceUri: String,
        val treeUri: Uri,
        val createdAt: Long = System.currentTimeMillis(),
        val entries: ArrayList<WorkspaceFileReference> = ArrayList(),
        var source: IndexSource = IndexSource.DIRECT,
        val directDirectories: ArrayDeque<Pair<File, String>> = ArrayDeque(),
        val deferredDirectDirectories: ArrayDeque<Pair<File, String>> = ArrayDeque(),
        val safDirectories: ArrayDeque<Pair<String, String>> = ArrayDeque(),
        val deferredSafDirectories: ArrayDeque<Pair<String, String>> = ArrayDeque(),
        val documentFileDirectories: ArrayDeque<Pair<DocumentFile, String>> = ArrayDeque(),
        val deferredDocumentFileDirectories: ArrayDeque<Pair<DocumentFile, String>> = ArrayDeque(),
        var visitedDirectories: Int = 0,
        var failures: Int = 0,
        var complete: Boolean = false,
        var truncated: Boolean = false,
    )

    private data class IndexStep(
        val matchesAdded: Int = 0,
        val progressed: Boolean,
    )

    private enum class IndexSource {
        DIRECT,
        SAF,
        DOCUMENT_FILE,
    }

    private companion object {
        const val TAG = "WorkspaceFileIndexer"
        const val INDEX_CACHE_TTL_MS = 5 * 60_000L
        const val MAX_SEARCH_RESULTS = 200
        const val MAX_INDEX_DIRECTORIES = 100_000
        const val MAX_INDEX_ENTRIES = 500_000
        val INDEX_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}

internal fun shouldQuickReturnFromWorkspaceIndex(query: String, score: Int): Boolean =
    query.trim().length >= MIN_QUICK_RETURN_QUERY_LENGTH && score >= MIN_QUICK_RETURN_MATCH_SCORE

private const val MIN_QUICK_RETURN_QUERY_LENGTH = 3
private const val MIN_QUICK_RETURN_MATCH_SCORE = 70

internal fun workspaceIndexDirectoryMayContainBase(directoryPath: String, basePath: String): Boolean {
    return directoryPath.isBlank() ||
        directoryPath == basePath ||
        basePath.startsWith("$directoryPath/") ||
        directoryPath.startsWith("$basePath/")
}

internal fun shouldDeferWorkspaceIndexDirectory(path: String): Boolean =
    path.substringAfterLast('/').lowercase() in WORKSPACE_INDEX_DEFERRED_DIRECTORY_NAMES

private val WORKSPACE_INDEX_DEFERRED_DIRECTORY_NAMES = setOf(
    ".git",
    ".gradle",
    ".idea",
    ".kotlin",
    ".vs",
    ".cache",
    ".cxx",
    ".externalnativebuild",
    ".venv",
    "__pycache__",
    "build",
    "coverage",
    "dist",
    "node_modules",
    "out",
    "target",
    "venv",
)

private fun normalizeSearchBasePath(basePath: String): String =
    basePath.replace('\\', '/').trim().trim('/')

private fun WorkspaceFileReference.isInSearchScope(
    basePath: String,
    includeDirectories: Boolean,
): Boolean {
    if (!includeDirectories && directory) return false
    return basePath.isBlank() || relativePath.startsWith("$basePath/")
}

internal fun searchWorkspaceFileIndex(
    files: List<WorkspaceFileReference>,
    query: String,
    limit: Int,
    basePath: String = "",
    includeDirectories: Boolean = false,
): List<WorkspaceFileReference> {
    val matcher = FileSearchMatcher(query)
    val cleanBasePath = normalizeSearchBasePath(basePath)
    val resultLimit = limit.coerceIn(1, 200)
    val bestFirst = compareByDescending<ScoredFileReference> { it.score }
        .thenBy { it.file.relativePath.length }
        .thenBy { it.sortPath }
    val bestMatches = java.util.PriorityQueue(resultLimit, bestFirst.reversed())

    fun addMatch(file: WorkspaceFileReference, score: Int) {
        val candidate = ScoredFileReference(score, file, file.relativePath.lowercase())
        if (bestMatches.size < resultLimit) {
            bestMatches += candidate
        } else if (bestFirst.compare(candidate, bestMatches.peek()) < 0) {
            bestMatches.poll()
            bestMatches += candidate
        }
    }

    files.forEach { file ->
        if (!file.isInSearchScope(cleanBasePath, includeDirectories)) return@forEach
        val score = matcher.strictMatchScore(file.name, file.relativePath) ?: return@forEach
        addMatch(file, score)
    }
    if (bestMatches.isEmpty()) {
        files.forEach { file ->
            if (!file.isInSearchScope(cleanBasePath, includeDirectories)) return@forEach
            val score = matcher.matchScore(file.name, file.relativePath) ?: return@forEach
            addMatch(file, score)
        }
    }
    return bestMatches.sortedWith(bestFirst).map { it.file }
}

private data class ScoredFileReference(
    val score: Int,
    val file: WorkspaceFileReference,
    val sortPath: String,
)

private fun android.database.Cursor.stringOrEmpty(index: Int): String =
    if (index >= 0 && !isNull(index)) getString(index).orEmpty() else ""

private fun android.database.Cursor.longOrZero(index: Int): Long =
    if (index >= 0 && !isNull(index)) getLong(index) else 0L
