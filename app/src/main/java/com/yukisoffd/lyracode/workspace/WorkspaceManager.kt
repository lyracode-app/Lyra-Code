package com.yukisoffd.lyracode.workspace

import android.content.Context
import android.content.Intent
import com.yukisoffd.lyracode.R
import com.yukisoffd.lyracode.uiText
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.yukisoffd.lyracode.data.AppSettings

class WorkspaceManager(
    private val context: Context,
    @Suppress("unused") private val settings: AppSettings,
) {
    private var activeWorkspaceUri: String = ""
    private val fileIndexer by lazy { WorkspaceFileIndexer(context, this) }

    fun persistWorkspace(uri: Uri): String {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (uri.scheme == ProotWorkspace.SCHEME) {
            ProotWorkspace.directory(context, uri)
        } else if (uri.scheme == "file") {
            SharedStorageWorkspace.directory(context, uri)
        } else {
            require(uri.scheme == "content") { uiText(R.string.workspace_error_unsupported) }
            val anchor = DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri))
            if (context.contentResolver.persistedUriPermissions.none { it.uri == anchor && it.isReadPermission && it.isWritePermission }) {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }
            require(grantedWorkspaceDirectory(context, uri)?.isDirectory == true) { uiText(R.string.workspace_error_missing) }
        }
        val nextUri = uri.toString()
        if (activeWorkspaceUri != nextUri) {
            activeWorkspaceUri = nextUri
            fileIndexer.invalidate()
        }
        return activeWorkspaceUri
    }

    fun setActiveWorkspaceUri(uri: String?) {
        val nextUri = uri.orEmpty()
        if (activeWorkspaceUri != nextUri) {
            activeWorkspaceUri = nextUri
            fileIndexer.invalidate()
        }
    }

    fun activeWorkspaceUri(): String = activeWorkspaceUri

    fun rootUri(): Uri? = activeWorkspaceUri.takeIf { it.isNotBlank() }?.let(Uri::parse)

    fun prootLinuxId(): String? = rootUri()?.takeIf { it.scheme == ProotWorkspace.SCHEME }?.authority

    fun prootGuestPath(): String? = rootUri()?.takeIf { it.scheme == ProotWorkspace.SCHEME }?.path

    fun root(): DocumentFile? = rootUri()?.let(::rootFor)

    /**
     * The directory a stored workspace uri names. A conversation keeps its own workspace, so a
     * recovery path must resolve against that workspace, not whichever one is active now.
     */
    fun rootOf(workspaceUri: String): DocumentFile? =
        workspaceUri.takeIf { it.isNotBlank() }?.let(Uri::parse)?.let(::rootFor)

    private fun rootFor(uri: Uri): DocumentFile? {
        if (uri.scheme == ProotWorkspace.SCHEME) {
            return runCatching { ProotWorkspace.document(context, uri) }.getOrNull()
        }
        if (uri.scheme == "file") return runCatching { androidx.documentfile.provider.SharedStorageDocumentFile(context,SharedStorageWorkspace.directory(context, uri)) }.getOrNull()
        return grantedWorkspaceDirectory(context, uri)
    }

    fun displayName(): String = if (prootLinuxId() != null) {
        "${prootLinuxId()}:${prootGuestPath()}"
    } else root()?.name ?: "未选择工作目录"

    fun displayPath(): String? {
        val uri = rootUri() ?: return null
        if (uri.scheme == ProotWorkspace.SCHEME) return "${uri.authority}:${uri.path}"
        if (uri.scheme == "file") return runCatching { SharedStorageWorkspace.directory(context, uri).path }.getOrNull()
        val docId = runCatching { if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.getDocumentId(uri) else DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return root()?.name
        val split = docId.split(":", limit = 2)
        if (split.size != 2) return root()?.name
        val relative = split[1].trimStart('/')
        val storageRoot = if (split[0] == "primary") {
            "/storage/emulated/0"
        } else {
            "/storage/${split[0]}"
        }
        return if (relative.isBlank()) "$storageRoot/" else "$storageRoot/$relative"
    }

    fun searchFiles(query: String, limit: Int = 80): List<WorkspaceFileReference> =
        fileIndexer.search(query, limit, quickReturnOnStrongMatch = true)

    internal fun searchEntries(query: String, basePath: String, limit: Int): List<WorkspaceFileReference> =
        fileIndexer.search(query, limit, basePath, includeDirectories = true)

    fun invalidateFileIndex() {
        fileIndexer.invalidate()
    }

    fun termuxRootPath(): String? {
        val uri = rootUri() ?: return null
        if (uri.scheme == ProotWorkspace.SCHEME) {
            return runCatching { ProotWorkspace.directory(context, uri).absolutePath }.getOrNull()
        }
        if (uri.scheme == "file") return runCatching { SharedStorageWorkspace.directory(context, uri).path }.getOrNull()
        val docId = runCatching { if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.getDocumentId(uri) else DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val split = docId.split(":", limit = 2)
        if (split.size != 2) return null
        return when (split[0]) {
            "primary" -> "/storage/emulated/0/${split[1].trimStart('/')}"
            else -> null
        }
    }

    fun termuxPath(relativePath: String): String? {
        val root = termuxRootPath() ?: return null
        val normalized = relativePath.trim('/').replace('\\', '/')
        return if (normalized.isBlank()) root else "$root/$normalized"
    }
}
