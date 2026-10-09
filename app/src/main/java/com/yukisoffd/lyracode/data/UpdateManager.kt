package com.yukisoffd.lyracode.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.yukisoffd.lyracode.R
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val versionName: String,
    val versionCode: Long,
    val apkUrl: String,
    val apkSha256: String,
    val releaseNotes: String,
    val releaseNotesUrl: String,
    val webUrl: String,
    val mandatory: Boolean,
) {
    fun isNewerThan(currentVersionCode: Long, currentVersionName: String): Boolean {
        return if (versionCode > 0L) {
            versionCode > currentVersionCode
        } else {
            compareVersionNames(versionName, currentVersionName)?.let { it > 0 } == true
        }
    }
}

data class UpdateDownloadProgress(
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val status: String = "",
) {
    val percent: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

class UpdateManager(private val context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("lyra_update_state", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val accelerationSettings = GitHubAccelerationSettings(appContext)
    private val apkDownloader = UpdateApkDownloader(
        client.newBuilder().readTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(false).build(),
        "LyraCode/${currentVersionCode()} AndroidUpdateClient",
    )

    fun manifestUrl(): String = com.yukisoffd.lyracode.BuildConfig.LYRA_UPDATE_MANIFEST_URL.trim()

    fun currentVersionCode(): Long {
        val info = packageInfo()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    fun currentVersionName(): String = packageInfo().versionName.orEmpty()

    private fun packageInfo(): android.content.pm.PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageInfo(appContext.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }
    }

    fun checkForUpdate(): Result<AppUpdateInfo?> = runCatching {
        val sources = buildList<Pair<String, () -> AppUpdateInfo>> {
            manifestUrl().takeIf { it.isNotBlank() }?.let { url ->
                add("网站 JSON" to { parseUpdateInfo(fetchJson(url)) })
            }
            add("GitHub" to {
                parseReleaseUpdateInfo(
                    json = fetchJson(GITHUB_LATEST_RELEASE_API),
                    fallbackWebUrl = GITHUB_RELEASES_URL,
                )
            })
            add("Gitee" to {
                parseReleaseUpdateInfo(
                    json = fetchJson(GITEE_LATEST_RELEASE_API),
                    fallbackWebUrl = GITEE_RELEASES_URL,
                )
            })
        }
        val failures = mutableListOf<String>()
        for ((name, load) in sources) {
            try {
                val info = load()
                return@runCatching if (info.isNewerThan(currentVersionCode(), currentVersionName())) info else null
            } catch (error: Exception) {
                failures += "$name：${error.message.orEmpty().ifBlank { error.javaClass.simpleName }}"
            }
        }
        error("版本检测失败，所有更新来源均不可用：${failures.joinToString("；")}")
    }

    fun checkDailyForUpdateIfNeeded(): Result<AppUpdateInfo?> = runCatching {
        val today = java.time.LocalDate.now().toString()
        if (prefs.getString(KEY_LAST_DAILY_CHECK_DATE, "") == today) {
            return@runCatching latestAvailableUpdate()
        }
        val info = checkForUpdate().getOrThrow()
        prefs.edit().putString(KEY_LAST_DAILY_CHECK_DATE, today).apply()
        if (info == null) {
            clearLatestAvailableUpdate()
        } else {
            saveLatestAvailableUpdate(info)
        }
        info
    }

    fun latestAvailableUpdate(): AppUpdateInfo? {
        val versionCode = prefs.getLong(KEY_LATEST_VERSION_CODE, 0L)
        val info = AppUpdateInfo(
            versionName = prefs.getString(KEY_LATEST_VERSION_NAME, "").orEmpty(),
            versionCode = versionCode,
            apkUrl = prefs.getString(KEY_LATEST_APK_URL, "").orEmpty(),
            apkSha256 = prefs.getString(KEY_LATEST_APK_SHA256, "").orEmpty(),
            releaseNotes = prefs.getString(KEY_LATEST_RELEASE_NOTES, "").orEmpty(),
            releaseNotesUrl = prefs.getString(KEY_LATEST_RELEASE_NOTES_URL, "").orEmpty(),
            webUrl = prefs.getString(KEY_LATEST_WEB_URL, "").orEmpty(),
            mandatory = prefs.getBoolean(KEY_LATEST_MANDATORY, false),
        )
        if (!info.isNewerThan(currentVersionCode(), currentVersionName())) {
            clearLatestAvailableUpdate()
            return null
        }
        return info
    }

    fun hasAvailableUpdate(): Boolean = latestAvailableUpdate() != null

    fun updatePromptDisabled(): Boolean = prefs.getBoolean(KEY_UPDATE_PROMPT_DISABLED, false)

    fun setUpdatePromptDisabled(disabled: Boolean) {
        prefs.edit().putBoolean(KEY_UPDATE_PROMPT_DISABLED, disabled).apply()
    }

    fun shouldShowDailyUpdatePrompt(): Boolean {
        if (updatePromptDisabled()) return false
        if (latestAvailableUpdate() == null) return false
        val today = java.time.LocalDate.now().toString()
        return prefs.getString(KEY_LAST_UPDATE_PROMPT_DATE, "") != today
    }

    fun markDailyUpdatePromptShown() {
        prefs.edit()
            .putString(KEY_LAST_UPDATE_PROMPT_DATE, java.time.LocalDate.now().toString())
            .apply()
    }

    fun saveLatestAvailableUpdate(info: AppUpdateInfo) {
        prefs.edit()
            .putLong(KEY_LATEST_VERSION_CODE, info.versionCode)
            .putString(KEY_LATEST_VERSION_NAME, info.versionName)
            .putString(KEY_LATEST_APK_URL, info.apkUrl)
            .putString(KEY_LATEST_APK_SHA256, info.apkSha256)
            .putString(KEY_LATEST_RELEASE_NOTES, info.releaseNotes)
            .putString(KEY_LATEST_RELEASE_NOTES_URL, info.releaseNotesUrl)
            .putString(KEY_LATEST_WEB_URL, info.webUrl)
            .putBoolean(KEY_LATEST_MANDATORY, info.mandatory)
            .apply()
    }

    fun clearLatestAvailableUpdate() {
        prefs.edit()
            .remove(KEY_LATEST_VERSION_CODE)
            .remove(KEY_LATEST_VERSION_NAME)
            .remove(KEY_LATEST_APK_URL)
            .remove(KEY_LATEST_APK_SHA256)
            .remove(KEY_LATEST_RELEASE_NOTES)
            .remove(KEY_LATEST_RELEASE_NOTES_URL)
            .remove(KEY_LATEST_WEB_URL)
            .remove(KEY_LATEST_MANDATORY)
            .apply()
    }

    fun downloadApk(
        info: AppUpdateInfo,
        onProgress: (UpdateDownloadProgress) -> Unit,
    ): Result<File> = runCatching {
        require(info.apkUrl.startsWith("https://") || info.apkUrl.startsWith("http://")) { "安装包下载地址无效" }
        val version = info.versionName.ifBlank { info.versionCode.toString() }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val output = File(updateDownloadDir(), "LyraCode-$version.apk")
        val file = apkDownloader.download(
            targets = githubDownloadTargets(info.apkUrl, accelerationSettings.load()),
            output = output,
            expectedSha256 = info.apkSha256,
        ) { target, downloaded, total ->
            val status = target.acceleratorName?.let {
                context.getString(R.string.github_acceleration_download_via, it)
            } ?: context.getString(R.string.github_acceleration_download_direct)
            onProgress(UpdateDownloadProgress(downloaded, total, status))
        }
        pruneCachedUpdateArtifacts(keepFile = file)
        savePendingApk(file, info)
        onProgress(UpdateDownloadProgress(file.length(), file.length(), context.getString(R.string.github_acceleration_download_complete)))
        file
    }

    fun pendingDownloadedApk(): File? {
        val path = prefs.getString(KEY_PENDING_APK_PATH, null).orEmpty()
        if (path.isBlank()) {
            pruneCachedUpdateArtifacts()
            return null
        }
        val pendingVersionCode = prefs.getLong(KEY_PENDING_VERSION_CODE, 0L)
        val pendingVersionName = prefs.getString(KEY_PENDING_VERSION_NAME, "").orEmpty()
        val installedTargetVersion = if (pendingVersionCode > 0L) {
            currentVersionCode() >= pendingVersionCode
        } else {
            compareVersionNames(currentVersionName(), pendingVersionName)?.let { it >= 0 } == true
        }
        if (installedTargetVersion) {
            clearPendingApk()
            pruneCachedUpdateArtifacts()
            return null
        }
        val file = File(path)
        if (!file.exists() || file.length() <= 0L || !isZipApk(file)) {
            clearPendingApk()
            pruneCachedUpdateArtifacts()
            return null
        }
        val expected = prefs.getString(KEY_PENDING_APK_SHA256, "").orEmpty()
        if (expected.isNotBlank()) {
            val valid = runCatching { apkSha256(file).equals(expected, ignoreCase = true) }.getOrDefault(false)
            if (!valid) {
                clearPendingApk()
                pruneCachedUpdateArtifacts()
                return null
            }
        }
        pruneCachedUpdateArtifacts(keepFile = file)
        return file
    }

    fun pendingDownloadedApkLabel(): String {
        val version = prefs.getString(KEY_PENDING_VERSION_NAME, "").orEmpty()
        return if (version.isNotBlank()) "继续安装 $version" else "继续安装已下载更新"
    }

    fun clearPendingApk(deleteFile: Boolean = true) {
        if (deleteFile) {
            prefs.getString(KEY_PENDING_APK_PATH, null)
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { File(it).delete() } }
        }
        prefs.edit()
            .remove(KEY_PENDING_APK_PATH)
            .remove(KEY_PENDING_APK_SHA256)
            .remove(KEY_PENDING_VERSION_NAME)
            .remove(KEY_PENDING_VERSION_CODE)
            .apply()
    }

    fun needsInstallPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !appContext.packageManager.canRequestPackageInstalls()
    }

    fun installPermissionIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${appContext.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun installIntent(apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", apkFile)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun installOrRequestPermission(apkFile: File): Intent {
        if (needsInstallPermission()) {
            return installPermissionIntent()
        }
        return installIntent(apkFile)
    }

    private fun parseUpdateInfo(json: JSONObject): AppUpdateInfo {
        val versionCode = json.optLong("versionCode").takeIf { it > 0 }
            ?: json.optLong("version_code").takeIf { it > 0 }
            ?: error("更新清单缺少 versionCode")
        val apkUrl = json.optString("apkUrl").ifBlank { json.optString("apk_url") }
        require(apkUrl.startsWith("https://") || apkUrl.startsWith("http://")) { "更新清单缺少有效的 apkUrl" }
        val releaseNotesUrl = json.optString("releaseNotesUrl").ifBlank { json.optString("release_notes_url") }
        val inlineNotes = json.optString("releaseNotes").ifBlank { json.optString("release_notes") }
        val releaseNotes = if (inlineNotes.isNotBlank() || releaseNotesUrl.isBlank()) {
            inlineNotes
        } else {
            runCatching {
                val request = Request.Builder().url(releaseNotesUrl).get().build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.body?.string().orEmpty() else ""
                }
            }.getOrDefault("")
        }
        return AppUpdateInfo(
            versionName = json.optString("versionName").ifBlank { json.optString("version_name") },
            versionCode = versionCode,
            apkUrl = apkUrl,
            apkSha256 = json.optString("apkSha256").ifBlank { json.optString("apk_sha256").ifBlank { json.optString("sha256") } },
            releaseNotes = releaseNotes.ifBlank { "发现新版本，暂无更新说明。" },
            releaseNotesUrl = releaseNotesUrl,
            webUrl = json.optString("webUrl").ifBlank { json.optString("web_url") },
            mandatory = json.optBoolean("mandatory", false),
        )
    }

    private fun fetchJson(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "LyraCode/${currentVersionCode()} AndroidUpdateClient")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body?.string().orEmpty()
            require(body.isNotBlank()) { "响应为空" }
            return JSONObject(body)
        }
    }

    private fun savePendingApk(file: File, info: AppUpdateInfo) {
        prefs.edit()
            .putString(KEY_PENDING_APK_PATH, file.absolutePath)
            .putString(KEY_PENDING_APK_SHA256, normalizeApkSha256(info.apkSha256).orEmpty())
            .putString(KEY_PENDING_VERSION_NAME, info.versionName)
            .putLong(KEY_PENDING_VERSION_CODE, info.versionCode)
            .apply()
    }

    private fun updateDownloadDir(): File = File(appContext.externalCacheDir ?: appContext.cacheDir, "updates")

    private fun pruneCachedUpdateArtifacts(keepFile: File? = null) {
        buildList {
            add(File(appContext.cacheDir, "updates"))
            appContext.externalCacheDir?.let { add(File(it, "updates")) }
        }.distinctBy { it.safeCanonicalPath() }
            .forEach { deleteCachedUpdateArtifacts(it, keepFile) }
    }

    private companion object {
        const val KEY_PENDING_APK_PATH = "pending_apk_path"
        const val KEY_PENDING_APK_SHA256 = "pending_apk_sha256"
        const val KEY_PENDING_VERSION_NAME = "pending_version_name"
        const val KEY_PENDING_VERSION_CODE = "pending_version_code"
        const val KEY_LAST_DAILY_CHECK_DATE = "last_daily_check_date"
        const val KEY_LATEST_VERSION_CODE = "latest_version_code"
        const val KEY_LATEST_VERSION_NAME = "latest_version_name"
        const val KEY_LATEST_APK_URL = "latest_apk_url"
        const val KEY_LATEST_APK_SHA256 = "latest_apk_sha256"
        const val KEY_LATEST_RELEASE_NOTES = "latest_release_notes"
        const val KEY_LATEST_RELEASE_NOTES_URL = "latest_release_notes_url"
        const val KEY_LATEST_WEB_URL = "latest_web_url"
        const val KEY_LATEST_MANDATORY = "latest_mandatory"
        const val KEY_UPDATE_PROMPT_DISABLED = "update_prompt_disabled"
        const val KEY_LAST_UPDATE_PROMPT_DATE = "last_update_prompt_date"

        const val GITHUB_LATEST_RELEASE_API = "https://api.github.com/repos/lyracode-app/Lyra-Code/releases/latest"
        const val GITHUB_RELEASES_URL = "https://github.com/lyracode-app/Lyra-Code/releases"
        const val GITEE_LATEST_RELEASE_API = "https://gitee.com/api/v5/repos/yukisoffd/lyra-code/releases/latest"
        const val GITEE_RELEASES_URL = "https://gitee.com/yukisoffd/lyra-code/releases"
    }
}

internal fun parseReleaseUpdateInfo(json: JSONObject, fallbackWebUrl: String): AppUpdateInfo {
    val tagName = json.optString("tag_name").trim()
    require(versionComponents(tagName) != null) { "Release 缺少可识别的版本号" }
    val assets = json.optJSONArray("assets") ?: error("Release 缺少安装包")
    val apkAsset = (0 until assets.length())
        .asSequence()
        .mapNotNull { assets.optJSONObject(it) }
        .firstOrNull { asset -> asset.optString("name").endsWith(".apk", ignoreCase = true) }
    val apkUrl = apkAsset?.optString("browser_download_url").orEmpty()
    require(apkUrl.startsWith("https://") || apkUrl.startsWith("http://")) {
        "Release 缺少 APK 的 browser_download_url"
    }
    val versionName = tagName.removePrefix("v").removePrefix("V")
    val webUrl = json.optString("html_url").ifBlank { fallbackWebUrl }
    return AppUpdateInfo(
        versionName = versionName,
        versionCode = 0L,
        apkUrl = apkUrl,
        apkSha256 = apkAsset?.takeUnless { it.isNull("digest") }?.optString("digest").orEmpty(),
        releaseNotes = json.optString("body").ifBlank { "发现新版本，暂无更新说明。" },
        releaseNotesUrl = webUrl,
        webUrl = webUrl,
        mandatory = false,
    )
}

internal fun compareVersionNames(left: String, right: String): Int? {
    val leftComponents = versionComponents(left) ?: return null
    val rightComponents = versionComponents(right) ?: return null
    val componentCount = maxOf(leftComponents.size, rightComponents.size)
    for (index in 0 until componentCount) {
        val comparison = (leftComponents.getOrNull(index) ?: 0L)
            .compareTo(rightComponents.getOrNull(index) ?: 0L)
        if (comparison != 0) return comparison
    }
    return 0
}

private fun versionComponents(value: String): List<Long>? {
    val version = Regex("(?i)(?:^|[^0-9])v?(\\d+(?:\\.\\d+)*)(?:$|[^0-9])")
        .find(value.trim())
        ?.groupValues
        ?.getOrNull(1)
        ?: return null
    return version.split('.').map { component -> component.toLongOrNull() ?: return null }
}

internal fun deleteCachedUpdateArtifacts(directory: File, keepFile: File? = null): Int {
    val keepPath = keepFile?.safeCanonicalPath()
    return directory.listFiles().orEmpty().count { candidate ->
        val isUpdateArtifact = candidate.isFile && (
            candidate.name.endsWith(".apk", ignoreCase = true) ||
                candidate.name.endsWith(".part", ignoreCase = true)
            )
        if (!isUpdateArtifact || candidate.safeCanonicalPath() == keepPath) {
            false
        } else {
            runCatching { candidate.delete() }.getOrDefault(false)
        }
    }
}

private fun File.safeCanonicalPath(): String = runCatching { canonicalPath }.getOrDefault(absolutePath)
