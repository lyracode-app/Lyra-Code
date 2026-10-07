package com.yukisoffd.lyracode.data

import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UpdateApkDownloaderTest {
    private val githubUrl = "https://github.com/test/app/releases/download/v1/app.apk"
    private val config = GitHubAccelerationConfig(links = listOf(
        GitHubAccelerator("first", "First", "https://first.example/"),
        GitHubAccelerator("second", "Second", "https://second.example/"),
    ))
    private val apk = ByteArrayOutputStream().apply {
        ZipOutputStream(this).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("test manifest".toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()
    private val digest = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }

    private fun response(request: Request, code: Int = 200, body: ResponseBody = apk.toResponseBody()): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Test response").body(body).build()

    private fun withOutput(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("lyra-update-fallback").toFile()
        try { block(File(directory, "update.apk")) } finally { directory.deleteRecursively() }
    }

    @Test fun successfulDirectDownloadDoesNotContactAccelerators() = withOutput { output ->
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain -> requests += chain.request().url.toString(); response(chain.request()) }.build()
        UpdateApkDownloader(client, "test").download(githubDownloadTargets(githubUrl, config), output, "sha256:$digest") { _, _, _ -> }
        assertEquals(listOf(githubUrl), requests)
        assertArrayEquals(apk, output.readBytes())
        assertFalse(File(output.parentFile, "update.apk.part").exists())
    }

    @Test fun timeoutAndConnectionFailureFallBackInConfiguredOrder() = withOutput { output ->
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request().url.toString()
            when (requests.size) {
                1 -> throw SocketTimeoutException("read timed out")
                2 -> throw ConnectException("cannot connect")
                else -> response(chain.request())
            }
        }.build()
        UpdateApkDownloader(client, "test").download(githubDownloadTargets(githubUrl, config), output, digest) { _, _, _ -> }
        assertEquals(githubDownloadTargets(githubUrl, config).map { it.url }, requests)
        assertArrayEquals(apk, output.readBytes())
    }

    @Test fun rejectsHttpFailureAndHashMismatchBeforeTryingNextRoute() = withOutput { output ->
        var attempts = 0
        val altered = apk + "altered".toByteArray()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            when (++attempts) {
                1 -> response(chain.request(), code = 503)
                2 -> response(chain.request(), body = altered.toResponseBody())
                else -> response(chain.request())
            }
        }.build()
        UpdateApkDownloader(client, "test").download(githubDownloadTargets(githubUrl, config), output, digest) { _, _, _ -> }
        assertEquals(3, attempts)
        assertArrayEquals(apk, output.readBytes())
    }

    @Test fun midStreamDisconnectionRestartsFromZeroWithNoMixedPartialData() = withOutput { output ->
        var attempts = 0
        val progress = mutableListOf<Pair<String, Long>>()
        val broken = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = apk.size.toLong()
            override fun source(): BufferedSource = object : ForwardingSource(Buffer().write(apk)) {
                var readOnce = false
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (readOnce) throw IOException("connection reset")
                    readOnce = true
                    return super.read(sink, 8)
                }
            }.buffer()
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), body = if (++attempts == 1) broken else apk.toResponseBody())
        }.build()
        UpdateApkDownloader(client, "test").download(githubDownloadTargets(githubUrl, config), output, digest) { target, bytes, _ -> progress += (target.acceleratorName ?: "direct") to bytes }
        assertEquals(2, attempts)
        assertTrue(progress.contains("direct" to 8L))
        assertTrue(progress.contains("First" to 0L))
        assertArrayEquals(apk, output.readBytes())
    }

    @Test fun allFailuresCleanPartialFilesAndKeepPreviouslyVerifiedPackage() = withOutput { output ->
        output.writeBytes(apk)
        val client = OkHttpClient.Builder().addInterceptor { chain -> response(chain.request(), body = "<html>blocked</html>".toResponseBody()) }.build()
        val failure = runCatching { UpdateApkDownloader(client, "test").download(githubDownloadTargets(githubUrl, config), output, digest) { _, _, _ -> } }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(failure!!.message!!.contains("First"))
        assertTrue(failure.message!!.contains("Second"))
        assertArrayEquals(apk, output.readBytes())
        assertFalse(File(output.parentFile, "update.apk.part").exists())
    }

    @Test fun disabledAccelerationDoesNotRetryAfterDirectFailure() = withOutput { output ->
        var attempts = 0
        val client = OkHttpClient.Builder().addInterceptor { attempts++; throw ConnectException("offline") }.build()
        val failure = runCatching { UpdateApkDownloader(client, "test").download(githubDownloadTargets(githubUrl, config.copy(enabled = false)), output, digest) { _, _, _ -> } }
        assertTrue(failure.isFailure)
        assertEquals(1, attempts)
        assertFalse(output.exists())
    }

    @Test fun malformedHashAndLocalStorageFailureDoNotContactAnyRoute() = withOutput { output ->
        var attempts = 0
        val client = OkHttpClient.Builder().addInterceptor { chain -> attempts++; response(chain.request()) }.build()
        val downloader = UpdateApkDownloader(client, "test")
        assertTrue(runCatching { downloader.download(githubDownloadTargets(githubUrl, config), output, "invalid hash") { _, _, _ -> } }.isFailure)
        val parentFile = File(output.parentFile, "not-a-directory").apply { writeText("file") }
        assertTrue(runCatching { downloader.download(githubDownloadTargets(githubUrl, config), File(parentFile, "update.apk"), digest) { _, _, _ -> } }.isFailure)
        assertEquals(0, attempts)
    }

    @Test fun cancellationDoesNotFallBackAndCleansPartialFile() = withOutput { output ->
        var attempts = 0
        val client = OkHttpClient.Builder().addInterceptor { chain -> attempts++; response(chain.request()) }.build()
        val failure = runCatching { UpdateApkDownloader(client, "test").download(githubDownloadTargets(githubUrl, config), output, digest) { _, bytes, _ -> if (bytes > 0) throw CancellationException("cancelled") } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(1, attempts)
        assertFalse(File(output.parentFile, "update.apk.part").exists())
    }
}
