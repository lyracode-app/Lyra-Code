package com.yukisoffd.lyracode.data

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException

class GitHubAccelerationTest {
    private val githubUrl = "https://github.com/lyracode-app/Lyra-Code/releases/download/v4.0.7/lyra.apk?download=1"
    private val first = GitHubAccelerator("first", "First", "https://first.example/")
    private val second = GitHubAccelerator("second", "Second", "https://second.example/")

    @Test fun directAlwaysPrecedesEnabledAcceleratorsAndDuplicatesAreRemoved() {
        val config = GitHubAccelerationConfig(links = listOf(first, second.copy(enabled = false), first.copy(id = "duplicate"), second))
        assertEquals(listOf(githubUrl, first.prefix + githubUrl, second.prefix + githubUrl), githubDownloadTargets(githubUrl, config).map { it.url })
    }

    @Test fun disabledFallbackAndNonGithubHostsOnlyTryOriginalUrl() {
        assertEquals(1, githubDownloadTargets(githubUrl, GitHubAccelerationConfig(enabled = false)).size)
        assertEquals(1, githubDownloadTargets("https://github.com.example/lyra.apk", GitHubAccelerationConfig()).size)
        assertEquals(1, githubDownloadTargets("https://example.com/https://github.com/lyra.apk", GitHubAccelerationConfig()).size)
        assertEquals(1, githubDownloadTargets("https://github.com@other.example/lyra.apk", GitHubAccelerationConfig()).size)
        assertEquals(1, githubDownloadTargets(githubUrl, GitHubAccelerationConfig(links = emptyList())).size)
    }

    @Test fun validatesHttpsPrefixesAndNormalizesTrailingSlash() {
        assertEquals("https://first.example/path/", normalizeGitHubAcceleratorPrefix(" https://FIRST.example/path "))
        for (invalid in listOf("http://first.example", "https://user:password@first.example", "https://first.example/?url=", "https://first.example/#fragment", "https://github.com/")) {
            assertNull(invalid, normalizeGitHubAcceleratorPrefix(invalid))
        }
    }

    @Test fun editedDeletedAndDisabledSettingsSurviveSerialization() {
        val config = GitHubAccelerationConfig(enabled = false, links = listOf(first.copy(name = "Edited", prefix = "https://edited.example", enabled = false)))
        val restored = decodeGitHubAccelerationConfig(encodeGitHubAccelerationConfig(config))
        assertFalse(restored.enabled)
        assertEquals("Edited", restored.links.single().name)
        assertEquals("https://edited.example/", restored.links.single().prefix)
        assertFalse(restored.links.single().enabled)
        assertTrue(decodeGitHubAccelerationConfig(encodeGitHubAccelerationConfig(config.copy(links = emptyList()))).links.isEmpty())
        assertFalse(decodeGitHubAccelerationConfig("broken JSON").enabled)
        assertTrue(decodeGitHubAccelerationConfig(null).links.isNotEmpty())
    }

    @Test fun probeUsesGetWithByteRangeAndReportsValidArchive() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("GET", chain.request().method)
            assertEquals("bytes=0-4095", chain.request().header("Range"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("Partial Content")
                .body(byteArrayOf(0x50, 0x4b, 0x03, 0x04, 1).toResponseBody()).build()
        }.build()
        val result = probeGitHubConnection("https://github.com/test/app.apk", client)
        assertTrue(result.successful)
        assertEquals(206, result.httpStatus)
    }

    @Test fun probeRejectsHtmlWithSuccessfulStatusAndSurfacesTimeout() {
        val htmlClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("<html>Proxy landing page</html>".toResponseBody()).build()
        }.build()
        assertFalse(probeGitHubConnection("https://first.example/https://github.com/test/app.apk", htmlClient).successful)
        val timeoutClient = OkHttpClient.Builder().addInterceptor { throw SocketTimeoutException("timeout") }.build()
        val failure = probeGitHubConnection(githubUrl, timeoutClient)
        assertFalse(failure.successful)
        assertEquals("timeout", failure.error)
    }
}
