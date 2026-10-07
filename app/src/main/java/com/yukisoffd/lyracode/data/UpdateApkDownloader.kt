package com.yukisoffd.lyracode.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

private class RetryableUpdateDownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A fresh partial file for each route; only a completely verified package is published. */
internal class UpdateApkDownloader(private val client: OkHttpClient, private val userAgent: String) {
    fun download(
        targets: List<GitHubDownloadTarget>,
        output: File,
        expectedSha256: String,
        onProgress: (target: GitHubDownloadTarget, downloaded: Long, total: Long) -> Unit,
    ): File {
        val expected = if (expectedSha256.isBlank()) null else
            requireNotNull(normalizeApkSha256(expectedSha256)) { "更新清单中的 apkSha256 格式无效" }
        require(targets.isNotEmpty())
        val directory = output.parentFile ?: throw IOException("保存安装包失败")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建更新下载目录")
        val partial = File(directory, "${output.name}.part")
        val failures = mutableListOf<String>()
        try {
            for (target in targets) {
                if (partial.exists() && !partial.delete()) throw IOException("无法清理未完成的更新文件")
                onProgress(target, 0, -1)
                try {
                    downloadAttempt(target.url, partial, expected) { downloaded, total ->
                        onProgress(target, downloaded, total)
                    }
                } catch (error: RetryableUpdateDownloadException) {
                    failures += "${target.acceleratorName ?: "GitHub"}：${error.message}"
                    continue
                }
                if (output.exists() && !output.delete()) throw IOException("无法替换已下载的更新文件")
                if (!partial.renameTo(output)) throw IOException("保存安装包失败")
                return output
            }
            throw IOException("下载安装包失败：${failures.joinToString("；")}")
        } finally {
            partial.delete()
        }
    }

    private fun downloadAttempt(
        url: String,
        partial: File,
        expected: String?,
        onProgress: (Long, Long) -> Unit,
    ) {
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
            .header("Accept-Encoding", "identity")
            .header("User-Agent", userAgent).get().build()
        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            throw RetryableUpdateDownloadException(error.message.orEmpty(), error)
        }
        response.use {
            if (!it.isSuccessful) throw RetryableUpdateDownloadException("HTTP ${it.code}")
            val body = it.body ?: throw RetryableUpdateDownloadException("响应为空")
            val total = body.contentLength()
            body.byteStream().use { input ->
                partial.outputStream().use { stream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        val read = try {
                            input.read(buffer)
                        } catch (error: IOException) {
                            throw RetryableUpdateDownloadException(error.message.orEmpty(), error)
                        }
                        if (read < 0) break
                        stream.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
            if (partial.length() == 0L || (total >= 0 && partial.length() != total)) {
                throw RetryableUpdateDownloadException("安装包为空或下载不完整")
            }
            if (!isZipApk(partial)) throw RetryableUpdateDownloadException("下载到的不是 APK 文件")
            if (expected != null && !apkSha256(partial).equals(expected, ignoreCase = true)) {
                throw RetryableUpdateDownloadException("安装包校验失败：SHA-256 不一致")
            }
        }
    }
}

internal fun apkSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun normalizeApkSha256(value: String): String? {
    val trimmed = value.trim().removePrefix("sha256:").removePrefix("SHA256:")
    if (trimmed.matches(Regex("[0-9a-fA-F]{64}"))) return trimmed.lowercase()
    return runCatching {
        val decoded = Base64.getDecoder().decode(trimmed)
        if (decoded.size == 32) decoded.joinToString("") { "%02x".format(it) } else null
    }.getOrNull()
}

internal fun isZipApk(file: File): Boolean {
    val header = ByteArray(4)
    val count = file.inputStream().use { it.read(header) }
    return hasZipHeader(header, count)
}

internal fun hasZipHeader(bytes: ByteArray, count: Int): Boolean = count >= 4 &&
    bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
    ((bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()) ||
        (bytes[2] == 0x05.toByte() && bytes[3] == 0x06.toByte()) ||
        (bytes[2] == 0x07.toByte() && bytes[3] == 0x08.toByte()))
