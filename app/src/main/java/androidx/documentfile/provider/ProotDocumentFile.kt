// DocumentFile's constructor is package-private, so implementations must share its package.
package androidx.documentfile.provider

import com.yukisoffd.lyracode.R
import com.yukisoffd.lyracode.uiText
import android.net.Uri
import android.webkit.MimeTypeMap
import com.yukisoffd.lyracode.workspace.resolveLocalizedProotPath
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** File-backed documents retain Unix permissions and Linux symlink semantics. */
internal class ProotDocumentFile(
    private val rootfs: File,
    private var guestPath: String,
    parent: DocumentFile? = null,
) : DocumentFile(parent) {
    private fun file() = resolveLocalizedProotPath(rootfs, guestPath)
    val isSymbolicLink: Boolean
        get() = Files.isSymbolicLink(resolveLocalizedProotPath(rootfs, guestPath, false).toPath())
    private fun child(name: String): ProotDocumentFile {
        require(name.isNotBlank() && name != "." && name != ".." && '/' !in name) { uiText(R.string.workspace_error_invalid_name) }
        return ProotDocumentFile(rootfs, guestPath.trimEnd('/') + "/" + name, this)
    }
    override fun getUri(): Uri = Uri.fromFile(file())
    override fun getName(): String = guestPath.trimEnd('/').substringAfterLast('/').ifBlank { rootfs.name }
    override fun getType(): String? = if (isDirectory) null else
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    override fun isDirectory(): Boolean = runCatching { file().isDirectory }.getOrDefault(false)
    override fun isFile(): Boolean = runCatching { file().isFile }.getOrDefault(false)
    override fun isVirtual(): Boolean = false
    override fun lastModified(): Long = runCatching { file().lastModified() }.getOrDefault(0L)
    override fun length(): Long = runCatching { file().length() }.getOrDefault(0L)
    override fun canRead(): Boolean = runCatching { file().canRead() }.getOrDefault(false)
    override fun canWrite(): Boolean = runCatching { file().canWrite() }.getOrDefault(false)
    override fun exists(): Boolean = runCatching {
        Files.exists(resolveLocalizedProotPath(rootfs, guestPath, false).toPath(), LinkOption.NOFOLLOW_LINKS)
    }.getOrDefault(false)
    override fun listFiles(): Array<DocumentFile> = file().list()?.map { child(it) }?.toTypedArray() ?: emptyArray()
    override fun findFile(displayName: String): DocumentFile? = child(displayName).takeIf { it.exists() }
    override fun createFile(mimeType: String, displayName: String): DocumentFile? = child(displayName).let {
        if (it.file().createNewFile()) it else null
    }
    override fun createDirectory(displayName: String): DocumentFile? = child(displayName).let {
        if (it.file().mkdir()) it else null
    }
    override fun renameTo(displayName: String): Boolean {
        val target = ProotDocumentFile(rootfs, guestPath.substringBeforeLast('/', "") + "/" + displayName)
        require(displayName.isNotBlank() && displayName != "." && displayName != ".." && '/' !in displayName)
        if (target.exists()) return false
        val renamed = resolveLocalizedProotPath(rootfs, guestPath, false).renameTo(resolveLocalizedProotPath(rootfs, target.guestPath, false))
        if (renamed) guestPath = target.guestPath
        return renamed
    }
    override fun delete(): Boolean {
        fun deleteEntry(file: File): Boolean {
            if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory) {
                val children = file.listFiles() ?: return false
                if (!children.all(::deleteEntry)) return false
            }
            return file.delete()
        }
        return deleteEntry(resolveLocalizedProotPath(rootfs, guestPath, false))
    }
}
