package com.yukisoffd.lyracode.workspace

import android.content.Context
import com.yukisoffd.lyracode.R
import com.yukisoffd.lyracode.uiText
import android.net.Uri
import androidx.documentfile.provider.ProotDocumentFile
import com.yukisoffd.lyracode.debian.ProotLinuxManager
import java.io.File

/** Stores the Linux identity and guest path, never an arbitrary Android private path. */
internal object ProotWorkspace {
    const val SCHEME = "lyracode-proot"

    fun uri(linuxId: String, path: String): Uri = Uri.Builder()
        .scheme(SCHEME).authority(linuxId).path(path).build()

    fun directory(context: Context, uri: Uri): File {
        require(uri.scheme == SCHEME) { uiText(R.string.workspace_error_not_proot) }
        val instance = ProotLinuxManager.getInstance(context).instance(uri.authority.orEmpty())
        val root = instance.rootfsDir.canonicalFile
        require(root.isDirectory) { uiText(R.string.workspace_error_linux_missing) }
        val relative = uri.path.orEmpty().trimStart('/')
        require(relative.split('/').none { it == ".." }) { uiText(R.string.workspace_error_parent_path) }
        val directory = resolveLocalizedProotPath(root, relative)
        require(directory == root || directory.path.startsWith(root.path + File.separator)) {
            uiText(R.string.workspace_error_outside_linux)
        }
        require(directory.isDirectory) { uiText(R.string.workspace_error_missing) }
        return directory
    }

    fun document(context: Context, uri: Uri): ProotDocumentFile {
        directory(context, uri)
        val instance = ProotLinuxManager.getInstance(context).instance(uri.authority.orEmpty())
        return ProotDocumentFile(instance.rootfsDir, uri.path.orEmpty())
    }
}

/** Keep the filesystem resolver usable without Android resources in JVM tests. */
internal fun resolveLocalizedProotPath(rootfs: File, path: String, followLastLink: Boolean = true): File =
    resolveProotPath(
        rootfs, path, followLastLink,
        escapeMessage = { uiText(R.string.workspace_error_escape) },
        linkMessage = { uiText(R.string.workspace_error_symlink_loop) },
    )
