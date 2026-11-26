package com.yukisoffd.lyracode.widgets

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.yukisoffd.lyracode.browser.buildCustomTabIntent
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

private const val PREVIEW_AUTHORITY = "com.yukisoffd.lyracode"
internal const val MAX_HTML_BYTES = 2 * 1024 * 1024

internal fun previewLink(uri: Uri) = uri.scheme == "content" && uri.authority == PREVIEW_AUTHORITY

internal fun htmlPathParts(path: String): List<String> {
    val parts = path.split('/')
    require(path.length <= 4096 && parts.size <= 64 && parts.all { part ->
        part.isNotBlank() && part != "." && part != ".." && part.none { it == '\\' || it.code < 32 }
    }) { "Expected a workspace-relative HTML path." }
    require(parts.last().substringAfterLast('.', "").lowercase() in setOf("htm", "html")) { "Expected an .html or .htm file." }
    return parts
}

internal fun htmlPathFromLink(uri: Uri): String {
    require(previewLink(uri) && uri.query == null && uri.fragment == null) { "Invalid workspace preview link." }
    val parts = uri.encodedPath.orEmpty().removePrefix("/").split('/').map { segment ->
        Uri.decode(segment).also { require('/' !in it) { "Invalid encoded path separator." } }
    }
    return parts.joinToString("/").also(::htmlPathParts)
}

internal fun InputStream.readPreviewHtml(): String = use { input ->
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val count = input.read(buf)
        if (count < 0) break
        require(out.size() + count <= MAX_HTML_BYTES) { "HTML preview exceeds 2 MiB." }
        out.write(buf, 0, count)
    }
    out.toString("UTF-8").removePrefix("\uFEFF")
}

internal fun readWorkspaceHtml(context: Context, root: DocumentFile?, path: String): String {
    var document = requireNotNull(root) { "Select a workspace to preview an HTML file." }
    val rootFile = document.uri.takeIf { it.scheme == "file" }?.path?.let { File(it).canonicalFile }
    for (segment in htmlPathParts(path)) {
        document = requireNotNull(document.findFile(segment)) { "HTML file not found: $path" }
    }
    val uri = document.uri
    if (rootFile != null) {
        val target = File(requireNotNull(uri.path)).canonicalFile
        require(uri.scheme == "file" && target.toPath().startsWith(rootFile.toPath())) { "File is outside the workspace." }
    }
    require(document.isFile && document.length() <= MAX_HTML_BYTES) { "Expected an HTML file of at most 2 MiB." }
    return requireNotNull(context.contentResolver.openInputStream(uri)).readPreviewHtml()
}

/** Only a read-only snapshot of the selected HTML is granted to the browser. */
internal fun htmlSnapshotUri(context: Context, source: String): Uri {
    require(source.length <= MAX_HTML_BYTES) { "HTML preview exceeds 2 MiB." }
    val bytes = source.toByteArray()
    require(bytes.size <= MAX_HTML_BYTES) { "HTML preview exceeds 2 MiB." }
    val dir = File(context.cacheDir, "html-previews").apply { mkdirs() }
    val cutoff = System.currentTimeMillis() - 86_400_000L
    dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    val file = File(dir, "$digest.html")
    if (file.exists()) {
        file.setLastModified(System.currentTimeMillis())
    } else {
        val pending = File.createTempFile("preview-", ".tmp", dir)
        try {
            pending.writeBytes(bytes)
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            pending.delete()
        }
    }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

internal fun openHtmlTab(context: Context, uri: Uri, dark: Boolean, toolbarColor: Int): Boolean {
    val provider = CustomTabsClient.getPackageName(context, null) ?: return false
    val intent = buildCustomTabIntent(dark, toolbarColor).intent.apply {
        setPackage(provider)
        setDataAndType(uri, "text/html")
        clipData = ClipData.newRawUri("HTML", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return runCatching { context.startActivity(intent); true }.getOrDefault(false)
}
