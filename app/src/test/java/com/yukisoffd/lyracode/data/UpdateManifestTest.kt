package com.yukisoffd.lyracode.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateManifestTest {
    private val universalHash = "a".repeat(64)
    private val armHash = "b".repeat(64)
    private val x86Hash = "c".repeat(64)

    private fun manifest() = JSONObject()
        .put("versionCode", 89).put("versionName", "4.1.4")
        .put("apkUrl", "https://example.com/universal.apk").put("apkSha256", universalHash)
        .put("apkUrlArm64", "https://example.com/arm64.apk").put("apkSha256Arm64", armHash)
        .put("apkUrlX86_64", "https://example.com/x86_64.apk").put("apkSha256X86_64", x86Hash)

    @Test fun arm64PrefersItsOwnUrlAndHashOverUniversal() {
        val json = manifest().put("apkSha256X86_64", "invalid and unsupported")
        val info = parseWebsiteUpdateInfo(json, listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals("https://example.com/arm64.apk", info.apkUrl)
        assertEquals(armHash, info.apkSha256)
        assertEquals(89L, info.versionCode)
    }

    @Test fun x86_64PrefersItsOwnChannelAndIgnoresArmHash() {
        val info = parseWebsiteUpdateInfo(manifest().put("apkSha256Arm64", "bad"), listOf("x86_64", "x86"))
        assertEquals("https://example.com/x86_64.apk", info.apkUrl)
        assertEquals(x86Hash, info.apkSha256)
    }

    @Test fun universalChannelCanBeEmptyOrAbsent() {
        val json = manifest().put("apkUrl", "").put("apkSha256", "")
        assertEquals(armHash, parseWebsiteUpdateInfo(json, listOf("arm64-v8a")).apkSha256)
        json.remove("apkUrl"); json.remove("apkSha256")
        assertEquals(x86Hash, parseWebsiteUpdateInfo(json, listOf("x86_64")).apkSha256)
    }

    @Test fun acceptsJsonNullForEmptyChannelsAndHashes() {
        val json = manifest().put("apkUrl", JSONObject.NULL).put("apkSha256", JSONObject.NULL)
            .put("apkSha256Arm64", JSONObject.NULL)
        val info = parseWebsiteUpdateInfo(json, listOf("arm64-v8a"))
        assertEquals("https://example.com/arm64.apk", info.apkUrl)
        assertEquals("", info.apkSha256)
    }

    @Test fun usesUniversalOnlyWhenCompatibleSplitIsMissing() {
        val info = parseWebsiteUpdateInfo(manifest().put("apkUrlArm64", ""), listOf("arm64-v8a"))
        assertEquals("https://example.com/universal.apk", info.apkUrl)
        assertEquals(universalHash, info.apkSha256)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsOnlyIncompatibleChannelsWithoutUniversal() {
        parseWebsiteUpdateInfo(manifest().put("apkUrl", "").put("apkUrlArm64", ""), listOf("arm64-v8a"))
    }

    @Test(expected = IllegalStateException::class)
    fun doesNotOffer64BitPackagesTo32BitDevices() {
        parseWebsiteUpdateInfo(manifest().put("apkUrl", ""), listOf("armeabi-v7a", "x86"))
    }

    @Test fun legacyUniversalManifestStillWorks() {
        val info = parseWebsiteUpdateInfo(JSONObject().put("version_code", 89)
            .put("apk_url", "https://example.com/old.apk").put("sha256", universalHash), listOf("arm64-v8a"))
        assertEquals("https://example.com/old.apk", info.apkUrl)
        assertEquals(universalHash, info.apkSha256)
    }

    @Test fun legacyCamelCaseUniversalManifestWorksOnAllSupportedDeviceTypes() {
        val json = JSONObject().put("versionCode", 89).put("versionName", "4.2.0")
            .put("apkUrl", "https://example.com/universal.apk").put("apkSha256", universalHash)
        for (abis in listOf(listOf("arm64-v8a", "armeabi-v7a"), listOf("x86_64", "x86"),
            listOf("armeabi-v7a"), listOf("x86"))) {
            val info = parseWebsiteUpdateInfo(json, abis)
            assertEquals("https://example.com/universal.apk", info.apkUrl)
            assertEquals(universalHash, info.apkSha256)
        }
    }

    @Test fun universalOnlyManifestDoesNotRequireOptionalHash() {
        val json = JSONObject().put("versionCode", 89).put("apkUrl", "https://example.com/universal.apk")
        assertEquals("", parseWebsiteUpdateInfo(json, listOf("arm64-v8a")).apkSha256)
    }

    @Test fun emptyOrNullSplitUrlsKeepUniversalUrlAndHashTogether() {
        for (empty in listOf("", "   ", JSONObject.NULL)) {
            val json = manifest().put("apkUrlArm64", empty).put("apkUrlX86_64", empty)
                .put("apkSha256Arm64", "unused old hash").put("apkSha256X86_64", "unused old hash")
            for (abi in listOf("arm64-v8a", "x86_64")) {
                val info = parseWebsiteUpdateInfo(json, listOf(abi))
                assertEquals("https://example.com/universal.apk", info.apkUrl)
                assertEquals(universalHash, info.apkSha256)
            }
        }
    }

    @Test fun documentedUniversalAndSplitExamplesRemainCompatible() {
        val universal = JSONObject(java.io.File("../docs/update-manifest.example.json").readText())
        val split = JSONObject(java.io.File("../docs/update-manifest.split.example.json").readText())
        for (abi in listOf("arm64-v8a", "x86_64")) {
            assertEquals(universal.getString("apkUrl"), parseWebsiteUpdateInfo(universal, listOf(abi)).apkUrl)
        }
        assertEquals(split.getString("apkUrlArm64"), parseWebsiteUpdateInfo(split, listOf("arm64-v8a")).apkUrl)
        assertEquals(split.getString("apkUrlX86_64"), parseWebsiteUpdateInfo(split, listOf("x86_64")).apkUrl)
    }

    @Test fun doesNotBorrowUniversalHashForArchitectureUrl() {
        assertEquals("", parseWebsiteUpdateInfo(manifest().put("apkSha256Arm64", ""), listOf("arm64-v8a")).apkSha256)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBadHashForSelectedPackage() {
        parseWebsiteUpdateInfo(manifest().put("apkSha256Arm64", "broken hash"), listOf("arm64-v8a"))
    }

    @Test fun respectsDeviceAbiPreferenceOrder() {
        val info = parseWebsiteUpdateInfo(manifest(), listOf("x86_64", "arm64-v8a"))
        assertEquals(x86Hash, info.apkSha256)
    }

    @Test fun acceptsSnakeCaseArchitectureFieldsAndInlineNotes() {
        val json = JSONObject().put("version_code", 89).put("apk_url_arm64", "https://example.com/arm.apk")
            .put("apk_sha256_arm64", armHash).put("release_notes", "Inline notes").put("mandatory", true)
        val info = parseWebsiteUpdateInfo(json, listOf("arm64-v8a")) { error("Inline notes must take precedence") }
        assertEquals("Inline notes", info.releaseNotes)
        assertEquals(armHash, info.apkSha256)
        assertTrue(info.mandatory)
    }

    @Test fun invalidSplitUrlFallsBackWithoutFetchingItsNotesOrHash() {
        val json = manifest().put("apkUrlArm64", "file:///bad.apk").put("apkSha256Arm64", "bad")
            .put("releaseNotesUrl", "https://example.com/notes.md")
        var requested = ""
        val info = parseWebsiteUpdateInfo(json, listOf("arm64-v8a")) { requested = it; "Remote notes" }
        assertEquals(universalHash, info.apkSha256)
        assertEquals("https://example.com/notes.md", requested)
        assertEquals("Remote notes", info.releaseNotes)
    }
}
