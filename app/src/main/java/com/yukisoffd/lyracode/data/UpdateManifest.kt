package com.yukisoffd.lyracode.data

import org.json.JSONObject

internal val distributedUpdateAbis = setOf("arm64-v8a", "x86_64")

private fun JSONObject.updateString(vararg keys: String): String =
    keys.firstNotNullOfOrNull { key ->
        if (!has(key) || isNull(key)) null else optString(key).trim().takeIf(String::isNotBlank)
    }.orEmpty()

internal fun parseWebsiteUpdateInfo(
    json: JSONObject,
    supportedAbis: List<String>,
    loadReleaseNotes: (String) -> String = { "" },
): AppUpdateInfo {
    val versionCode = json.optLong("versionCode").takeIf { it > 0 }
        ?: json.optLong("version_code").takeIf { it > 0 }
        ?: error("更新清单缺少 versionCode")
    // Architecture fields are optional extensions to the original universal-only
    // manifest. Empty/absent split URLs leave the universal URL/hash pair intact.
    val channels = supportedAbis.distinct().filter { it in distributedUpdateAbis }.map { abi ->
        when (abi) {
            "arm64-v8a" -> json.updateString("apkUrlArm64", "apk_url_arm64") to
                json.updateString("apkSha256Arm64", "apk_sha256_arm64")
            else -> json.updateString("apkUrlX86_64", "apk_url_x86_64") to
                json.updateString("apkSha256X86_64", "apk_sha256_x86_64")
        }
    } + (json.updateString("apkUrl", "apk_url") to json.updateString("apkSha256", "apk_sha256", "sha256"))
    val (url, hash) = channels.firstOrNull { (url, _) -> isUpdateDownloadUrl(url) }
        ?: error("更新清单没有适用于当前设备的安装包：${supportedAbis.joinToString()}")
    require(hash.isBlank() || normalizeApkSha256(hash) != null) { "所选安装包的 SHA-256 格式无效" }
    val notesUrl = json.updateString("releaseNotesUrl", "release_notes_url")
    val inlineNotes = json.updateString("releaseNotes", "release_notes")
    val notes = inlineNotes.ifBlank {
        if (notesUrl.isBlank()) "" else runCatching { loadReleaseNotes(notesUrl) }.getOrDefault("")
    }
    return AppUpdateInfo(
        versionName = json.updateString("versionName", "version_name"),
        versionCode = versionCode,
        apkUrl = url,
        apkSha256 = hash,
        releaseNotes = notes.ifBlank { "发现新版本，暂无更新说明。" },
        releaseNotesUrl = notesUrl,
        webUrl = json.updateString("webUrl", "web_url"),
        mandatory = json.optBoolean("mandatory", false),
    )
}

internal fun isUpdateDownloadUrl(url: String): Boolean = url.startsWith("https://") || url.startsWith("http://")
