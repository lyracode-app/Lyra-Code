package com.yukisoffd.lyracode.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class UpdateCheckChannelTest {
    private val manifest = UpdateCheckChannel.MANIFEST
    private val github = UpdateCheckChannel.GITHUB
    private val release = AppUpdateInfo("4.3.0", 92, "", "", "", "", "", false)

    @Test fun restoresEnabledChannelsInOrderAndDropsRetiredChannels() {
        assertEquals(listOf(manifest, github), decodeUpdateCheckChannels(null))
        assertEquals(listOf(github), decodeUpdateCheckChannels("github,gitee,github"))
        assertEquals(listOf(github, manifest), decodeUpdateCheckChannels("github,manifest"))
    }

    @Test fun singleChannelFailureDoesNotTryAnotherSource() {
        val attempts = mutableListOf<UpdateCheckChannel>()
        val result = runCatching {
            loadFromUpdateChannels(listOf(github), { channel, _ -> channel.id }) {
                attempts += it
                throw IOException("offline")
            }
        }
        assertEquals(listOf(github), attempts)
        assertEquals("github", result.exceptionOrNull()?.message)
    }

    @Test fun multipleChannelsTryInOrderAndStopAtTheFirstValidResponse() {
        val attempts = mutableListOf<UpdateCheckChannel>()
        assertEquals(release, loadFromUpdateChannels(listOf(github, manifest), { channel, _ -> channel.id }) {
            attempts += it
            if (it == github) throw IOException("offline")
            release
        })
        assertEquals(listOf(github, manifest), attempts)
        attempts.clear()
        val alreadyInstalled = release.copy(versionName = "4.2.2", versionCode = 91)
        assertEquals(alreadyInstalled, loadFromUpdateChannels(listOf(github, manifest), { _, _ -> "" }) {
            attempts += it
            alreadyInstalled
        })
        assertEquals(listOf(github), attempts)
    }

    @Test fun comparesDownloadsWithReleaseMetadataFromEitherChannel() {
        val pending = DownloadedUpdate(File("update.apk"), "4.3.0", 0, "")
        assertTrue(pending.matches(release))
        assertTrue(pending.isOlderThan(release.copy(versionName = "4.3.1")))
        assertTrue(pending.copy(versionCode = 91).isOlderThan(release))
    }
}
