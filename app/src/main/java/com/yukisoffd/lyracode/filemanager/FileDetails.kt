package com.yukisoffd.lyracode.filemanager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.FileProvider
import com.yukisoffd.lyracode.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

internal suspend fun fileShareIntent(context: Context, file: File, operations: PrivilegedFileOperations): Intent {
    val authority = "${context.packageName}.fileprovider"
    val readable = operations.prepareReadableCopy(file).getOrThrow()
    val uri = try {
        if (readable == file) {
            // A directly readable file can be outside the configured provider roots.
            try {
                FileProvider.getUriForFile(context, authority, file, file.name)
            } catch (_: IllegalArgumentException) {
                shareCachedCopy(context, authority, file, file.name)
            }
        } else {
            shareCachedCopy(context, authority, readable, file.name)
        }
    } finally {
        if (readable != file) readable.delete()
    }
    return Intent(Intent.ACTION_SEND).apply {
        type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun shareCachedCopy(context: Context, authority: String, file: File, name: String): android.net.Uri {
    val directory = File(context.cacheDir, "file-shares").apply { mkdirs() }
    val expiry = System.currentTimeMillis() - 86_400_000L
    directory.listFiles()?.filter { it.lastModified() < expiry }?.forEach { it.delete() }
    val copy = File.createTempFile("share-", "." + name.substringAfterLast('.', "bin"), directory)
    try {
        file.copyTo(copy, overwrite = true)
        return FileProvider.getUriForFile(context, authority, copy, name)
    } catch (error: Exception) {
        copy.delete()
        throw error
    }
}

internal suspend fun fileChecksums(input: InputStream): Map<String, String> {
    val digests = listOf("MD5", "SHA-256").associateWith(MessageDigest::getInstance)
    val buffer = ByteArray(64 * 1024)
    while (true) {
        currentCoroutineContext().ensureActive()
        val size = input.read(buffer)
        if (size < 0) break
        digests.values.forEach { it.update(buffer, 0, size) }
    }
    return digests.mapValues { (_, digest) -> digest.digest().joinToString("") { "%02x".format(it) } }
}

@Composable
internal fun FileChecksums(entry: LocalFileEntry, operations: PrivilegedFileOperations) {
    val context = LocalContext.current
    val result by produceState<Result<Map<String, String>>?>(null, entry, operations) {
        value = withContext(Dispatchers.IO) {
            try {
                val readable = operations.prepareReadableCopy(entry.file).getOrThrow()
                try {
                    Result.success(readable.inputStream().use { fileChecksums(it) })
                } finally {
                    if (readable != entry.file) readable.delete()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
    }
    val hashes = result
    if (hashes == null) {
        Text("MD5 / SHA-256 · " + context.getString(R.string.file_property_calculating_size))
    } else {
        hashes.fold(onSuccess = { values ->
            values.forEach { (algorithm, hex) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(algorithm, style = MaterialTheme.typography.labelMedium)
                        Text(hex, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(algorithm, hex))
                    }) { Icon(Icons.Default.ContentCopy, context.getString(R.string.file_copy_hash, algorithm)) }
                }
            }
        }, onFailure = { Text(context.getString(R.string.file_hash_error, it.localizedMessage.orEmpty())) })
    }
}
