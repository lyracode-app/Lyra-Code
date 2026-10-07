package com.yukisoffd.lyracode.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerReleaseTest {
    @Test
    fun preservesGithubAssetDigestForAcceleratedDownloadVerification() {
        val digest = "sha256:" + "a".repeat(64)
        val info = parseReleaseUpdateInfo(
            JSONObject().put("tag_name", "v4.0.7").put("assets", org.json.JSONArray().put(
                JSONObject().put("name", "lyra.apk").put("browser_download_url", "https://github.com/test/lyra.apk").put("digest", digest),
            )),
            fallbackWebUrl = "https://github.com/test/releases",
        )
        assertEquals(digest, info.apkSha256)
    }

    @Test
    fun acceptsOlderReleaseAssetsWithNullDigest() {
        val info = parseReleaseUpdateInfo(
            JSONObject().put("tag_name", "v4.0.7").put("assets", org.json.JSONArray().put(
                JSONObject().put("name", "lyra.apk").put("browser_download_url", "https://github.com/test/lyra.apk").put("digest", JSONObject.NULL),
            )),
            fallbackWebUrl = "https://github.com/test/releases",
        )
        assertEquals("", info.apkSha256)
    }

    @Test
    fun parsesGithubReleaseBodyAndApkDownloadUrl() {
        val info = parseReleaseUpdateInfo(
            JSONObject(
                """
                {
                  "tag_name": "v3.7.0",
                  "body": "Release details",
                  "html_url": "https://github.com/lyracode-app/Lyra-Code/releases/tag/v3.7.0",
                  "assets": [
                    {
                      "name": "lyracode-3.7.0.apk",
                      "browser_download_url": "https://github.com/lyracode-app/Lyra-Code/releases/download/v3.7.0/lyracode-3.7.0.apk"
                    }
                  ]
                }
                """.trimIndent(),
            ),
            fallbackWebUrl = "https://github.com/lyracode-app/Lyra-Code/releases",
        )

        assertEquals("3.7.0", info.versionName)
        assertEquals("Release details", info.releaseNotes)
        assertEquals(
            "https://github.com/lyracode-app/Lyra-Code/releases/download/v3.7.0/lyracode-3.7.0.apk",
            info.apkUrl,
        )
        assertTrue(info.isNewerThan(currentVersionCode = 68L, currentVersionName = "3.6.1"))
    }

    @Test
    fun selectsApkFromGiteeAssetsAndUsesFallbackReleasePage() {
        val info = parseReleaseUpdateInfo(
            JSONObject(
                """
                {
                  "tag_name": "v3.10.0",
                  "body": "Gitee details",
                  "assets": [
                    {
                      "name": "v3.10.0.zip",
                      "browser_download_url": "https://gitee.com/example/archive/v3.10.0.zip"
                    },
                    {
                      "name": "lyracode-3.10.0.apk",
                      "browser_download_url": "https://gitee.com/example/lyracode-3.10.0.apk"
                    }
                  ]
                }
                """.trimIndent(),
            ),
            fallbackWebUrl = "https://gitee.com/yukisoffd/lyra-code/releases",
        )

        assertEquals("https://gitee.com/example/lyracode-3.10.0.apk", info.apkUrl)
        assertEquals("https://gitee.com/yukisoffd/lyra-code/releases", info.webUrl)
        assertEquals(1, compareVersionNames("3.10.0", "3.9.9"))
        assertEquals(0, compareVersionNames("v3.10", "3.10.0-debug"))
    }
}
