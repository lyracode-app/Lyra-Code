package com.yukisoffd.lyracode

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import android.system.Os
import com.yukisoffd.lyracode.data.UpdateManager
import com.yukisoffd.lyracode.data.DeveloperWallManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayDeque
import java.util.Locale



@Composable
internal fun StorageCacheSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val developerWall = remember(context) { DeveloperWallManager.get(context) }
    val developerWallState by developerWall.state.collectAsState()
    var scan by remember { mutableStateOf<StorageScanResult?>(null) }
    var scanning by remember { mutableStateOf(true) }
    var cleaning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    fun refresh(completionStatus: String? = null) {
        if (scanning) return
        scanning = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { scanStorageUsage(context.applicationContext) }
            scan = result
            scanning = false
            if (completionStatus != null) status = completionStatus
        }
    }

    LaunchedEffect(context.applicationContext) {
        scan = withContext(Dispatchers.IO) { scanStorageUsage(context.applicationContext) }
        scanning = false
    }

    fun clean(items: List<StorageCacheItem>, success: String) {
        if (scanning || cleaning) return
        cleaning = true
        status = uiText(R.string.ui_clearing_cache)
        scope.launch {
            val failures = withContext(Dispatchers.IO) {
                items.sumOf { item ->
                    if (item.developerWall) {
                        try {
                            developerWall.sync(force = true, clearCache = true)
                            0
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            1
                        }
                    } else if (item.updatePackages) {
                        if (UpdateManager(context).clearDownloadedApks().isFailure) 1 else 0
                    } else {
                        item.cleanTargets.count { deleteCacheTarget(it).isFailure }
                    }
                }
            }
            status = if (failures == 0) success else context.getString(R.string.storage_cleanup_failed, failures)
            cleaning = false
            refresh()
        }
    }

    val currentScan = scan
    val calculating = uiText(R.string.file_property_calculating_size)
    KimiCardBox {
        Text(uiText(R.string.title_storage_usage), style = MaterialTheme.typography.titleMedium)
        KimiDivider()
        KimiMenuRow(Icons.Default.Storage, uiText(R.string.menu_total_usage), currentScan?.let { formatBytes(it.totalBytes) } ?: calculating)
        KimiDivider()
        KimiMenuRow(Icons.Default.Android, uiText(R.string.menu_app_package), currentScan?.let { formatBytes(it.appBytes) } ?: calculating)
        KimiDivider()
        KimiMenuRow(Icons.Default.Android, uiText(R.string.menu_installed_apk_files), currentScan?.let { formatBytes(it.installedApkBytes) } ?: calculating)
        KimiDivider()
        KimiMenuRow(Icons.Default.Folder, uiText(R.string.menu_app_data), currentScan?.let { formatBytes(it.dataBytes) } ?: calculating)
        KimiDivider()
        KimiMenuRow(Icons.Default.Memory, uiText(R.string.menu_system_cache), currentScan?.let { formatBytes(it.cacheBytes) } ?: calculating)
        KimiDivider()
        KimiMenuRow(Icons.Default.CleaningServices, uiText(R.string.menu_cleanable_cache), currentScan?.let { formatBytes(it.cleanableBytes) } ?: calculating)
        Text(uiText(R.string.storage_explanation), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
    }
    currentScan?.items.orEmpty().forEach { item ->
        KimiCardBox {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall)
                    Text(item.path, color = KimiMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                    Text(uiText(R.string.storage_item_summary, formatBytes(item.bytes), item.fileCount), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
                }
                if (item.cleanable && item.bytes > 0L) {
                    OutlinedButton(
                        enabled = !scanning && !cleaning,
                        onClick = { clean(listOf(item), uiText(R.string.status_cleaned, item.title)) },
                        shape = KimiPillShape,
                    ) { Text(uiText(R.string.action_clean)) }
                }
            }
            if (item.developerWall && developerWall.configured) {
                Text(uiText(R.string.developer_wall_daily_hint), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    enabled = !scanning && !cleaning && !developerWallState.syncing,
                    onClick = {
                        scope.launch {
                            val result = developerWall.sync(force = true)
                            status = uiText(if (result.succeeded) R.string.developer_wall_sync_success else R.string.developer_wall_sync_failed)
                            refresh()
                        }
                    },
                    shape = KimiPillShape,
                ) { Text(uiText(if (developerWallState.syncing) R.string.developer_wall_syncing else R.string.developer_wall_sync)) }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            enabled = !scanning && !cleaning,
            onClick = { refresh(uiText(R.string.status_scan_complete)) },
            shape = KimiPillShape,
        ) { Text(uiText(R.string.action_rescan)) }
        OutlinedButton(
            enabled = !scanning && !cleaning && (currentScan?.cleanableBytes ?: 0L) > 0L,
            onClick = {
                clean(currentScan?.items.orEmpty().filter { it.cleanable }, uiText(R.string.status_cache_cleaned))
            },
            shape = KimiPillShape,
        ) { Text(uiText(R.string.action_clean_all_cache)) }
    }
    if (scanning) Text(calculating, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
    if (status.isNotBlank()) Text(status, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
}

internal data class StorageScanResult(
    val totalBytes: Long,
    val appBytes: Long,
    val dataBytes: Long,
    val cacheBytes: Long,
    val cleanableBytes: Long,
    val items: List<StorageCacheItem>,
    val installedApkBytes: Long = 0L,
)

internal data class StorageCacheItem(
    val title: String,
    val file: File,
    val path: String,
    val bytes: Long,
    val fileCount: Int,
    val cleanable: Boolean,
    val cleanTargets: List<File> = listOf(file),
    val updatePackages: Boolean = false,
    val developerWall: Boolean = false,
)

internal fun scanStorageUsage(context: Context): StorageScanResult = runCatching {
    val updater = UpdateManager(context)
    updater.pendingDownloadedApk()
    val systemStats = querySystemStorageStats(context)
    val totals = resolveStorageTotals(systemStats) {
        val recursiveAppBytes = safeInstalledAppBytes(context)
        val recursiveCacheBytes = saturatedSum(
            safeDirSize(context.cacheDir),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) safeDirSize(context.codeCacheDir) else 0L,
            *context.externalCacheDirs.filterNotNull().map { safeDirSize(it) }.toLongArray(),
        )
        val recursiveDataRootBytes = saturatedSum(
            safeDirSize(File(context.applicationInfo.dataDir)),
            *context.getExternalFilesDirs(null).filterNotNull().map { safeDirSize(it) }.toLongArray(),
            *context.externalCacheDirs.filterNotNull().map { safeDirSize(it) }.toLongArray(),
        )
        StorageTotals(
            appBytes = recursiveAppBytes,
            dataBytes = (recursiveDataRootBytes - recursiveCacheBytes).coerceAtLeast(0L),
            cacheBytes = recursiveCacheBytes,
        )
    }
    val items = buildList {
        val packages = updater.apkStorage.artifacts()
        val packageUsage = safeTreeUsage(packages)
        val packageDirectory = updater.apkStorage.downloadDirectory ?: context.filesDir
        add(StorageCacheItem(
            title = uiText(R.string.cache_update_packages),
            file = packageDirectory,
            path = updater.apkStorage.downloadDirectory?.absolutePath ?: context.getString(R.string.update_storage_unavailable),
            bytes = packageUsage.bytes,
            fileCount = packageUsage.fileCount,
            cleanable = true,
            cleanTargets = packages,
            updatePackages = true,
        ))
        add(storageItem(uiText(R.string.cache_ai_response), File(context.cacheDir, "ai_response_cache"), cleanable = true))
        add(storageItem(uiText(R.string.developer_wall_cache), DeveloperWallManager.get(context).cacheDirectory,
            cleanable = true).copy(developerWall = true))
        add(storageItem(uiText(R.string.cache_crop_image), File(context.cacheDir, "uploads"), cleanable = true))
        add(storageItem(uiText(R.string.cache_camera_upload), File(context.cacheDir, "upload_crop"), cleanable = true))
        add(storageItem(uiText(R.string.cache_system_temp), context.cacheDir, cleanable = false))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) add(storageItem(uiText(R.string.cache_code), context.codeCacheDir, cleanable = false))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) add(storageItem(uiText(R.string.cache_no_backup), context.noBackupFilesDir, cleanable = false))
        add(storageItem(uiText(R.string.cache_persistent), context.filesDir, cleanable = false))
        context.getExternalFilesDirs(null).filterNotNull().forEachIndexed { index, dir ->
            add(storageItem(context.getString(R.string.cache_external_private, index + 1), dir, cleanable = false))
        }
        context.externalCacheDirs.filterNotNull().forEachIndexed { index, dir ->
            add(storageItem(context.getString(R.string.cache_external_cache, index + 1), dir, cleanable = true))
        }
    }
    val total = saturatedSum(totals.appBytes, totals.dataBytes, totals.cacheBytes)
    val cleanable = safeTreeUsage(items.filter { it.cleanable }.flatMap { it.cleanTargets }).bytes
    StorageScanResult(total, totals.appBytes, totals.dataBytes, totals.cacheBytes, cleanable, items, installedApkBytes(context))
}.getOrDefault(StorageScanResult(0L, 0L, 0L, 0L, 0L, emptyList()))

internal data class SystemStorageStats(val appBytes: Long, val dataBytes: Long, val cacheBytes: Long)
internal data class StorageTotals(val appBytes: Long, val dataBytes: Long, val cacheBytes: Long)

internal fun resolveStorageTotals(
    systemStats: SystemStorageStats?,
    fallback: () -> StorageTotals,
): StorageTotals = systemStats?.let {
    val cacheBytes = it.cacheBytes.coerceAtLeast(0L)
    StorageTotals(
        appBytes = it.appBytes.coerceAtLeast(0L),
        // StorageStats.dataBytes includes cache/code-cache. Split it the same way Android
        // Settings presents user data and cache, then add each component exactly once.
        dataBytes = (it.dataBytes.coerceAtLeast(0L) - cacheBytes).coerceAtLeast(0L),
        cacheBytes = cacheBytes,
    )
} ?: fallback()

internal fun querySystemStorageStats(context: Context): SystemStorageStats? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
    return runCatching {
        val manager = context.getSystemService(StorageStatsManager::class.java)
        val storageManager = context.getSystemService(StorageManager::class.java)
        val storageUuid = runCatching {
            storageManager.getUuidForPath(File(context.applicationInfo.dataDir))
        }.getOrDefault(StorageManager.UUID_DEFAULT)
        val stats = manager.queryStatsForUid(storageUuid, context.applicationInfo.uid)
        SystemStorageStats(
            appBytes = stats.appBytes,
            dataBytes = stats.dataBytes,
            cacheBytes = stats.cacheBytes,
        )
    }.getOrNull()
}

internal fun safeInstalledAppBytes(context: Context): Long = runCatching {
    val appInfo = context.applicationInfo
    val files = buildList {
        add(File(appInfo.sourceDir))
        appInfo.splitSourceDirs?.forEach { add(File(it)) }
        appInfo.nativeLibraryDir?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
    }
    saturatedSum(*files.distinctBy { it.absolutePath }.map { safeDirSize(it) }.toLongArray())
}.getOrDefault(0L)

private fun installedApkBytes(context: Context): Long = installedApkFileBytes(buildList {
    add(File(context.applicationInfo.sourceDir))
    context.applicationInfo.splitSourceDirs?.forEach { add(File(it)) }
})

internal fun installedApkFileBytes(files: List<File>): Long = saturatedSum(
    *files.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        .filter { it.isFile }.map { it.length() }.toLongArray(),
)

internal fun storageItem(title: String, file: File, cleanable: Boolean): StorageCacheItem {
    val usage = safeTreeUsage(file)
    return StorageCacheItem(
        title = title,
        file = file,
        path = file.absolutePath,
        bytes = usage.bytes,
        fileCount = usage.fileCount,
        cleanable = cleanable,
    )
}

internal data class StorageTreeUsage(val bytes: Long, val fileCount: Int)

internal fun safeDirSize(file: File): Long = safeTreeUsage(file).bytes

internal fun safeFileCount(file: File): Int = safeTreeUsage(file).fileCount

/** Iterative, no-follow traversal that also counts hard-linked inodes only once. */
internal fun safeTreeUsage(file: File): StorageTreeUsage = safeTreeUsage(listOf(file))

internal fun safeTreeUsage(files: List<File>): StorageTreeUsage = runCatching {
    val pending = ArrayDeque<Path>()
    val visitedKeys = HashSet<Any>()
    val visitedPaths = HashSet<Path>()
    files.forEach { pending.add(it.toPath().toAbsolutePath().normalize()) }
    var bytes = 0L
    var fileCount = 0
    while (pending.isNotEmpty()) {
        val path = pending.removeLast()
        if (!visitedPaths.add(path)) continue
        val attributes = runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull() ?: continue
        val fileKey = storageFileKey(path, attributes)
        if (fileKey != null && !visitedKeys.add(fileKey)) continue
        if (attributes.isDirectory) {
            path.toFile().listFiles().orEmpty().forEach { pending.add(it.toPath()) }
        } else {
            bytes = saturatedSum(bytes, attributes.size().coerceAtLeast(0L))
            if (fileCount < Int.MAX_VALUE) fileCount++
        }
    }
    StorageTreeUsage(bytes, fileCount)
}.getOrDefault(StorageTreeUsage(0L, 0))

private data class AndroidStorageFileKey(val device: Long, val inode: Long)

private fun storageFileKey(path: Path, attributes: BasicFileAttributes): Any? =
    attributes.fileKey() ?: runCatching {
        val stat = Os.lstat(path.toString())
        AndroidStorageFileKey(stat.st_dev, stat.st_ino)
    }.getOrNull()

internal fun deleteCacheTarget(file: File): Result<Unit> = runCatching {
        if (Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return@runCatching
        val attributes = Files.readAttributes(
            file.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (attributes.isDirectory) {
            val children = file.listFiles() ?: throw IOException("Cannot list cache directory: ${file.absolutePath}")
            val failures = children.mapNotNull { child -> runCatching { deleteTreeNoFollow(child.toPath()) }.exceptionOrNull() }
            if (failures.isNotEmpty()) throw IOException("Cannot remove ${failures.size} cache entries", failures.first())
        } else {
            Files.deleteIfExists(file.toPath())
        }
        Unit
}

private fun deleteTreeNoFollow(root: Path) {
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        },
    )
}

private fun saturatedSum(vararg values: Long): Long {
    var total = 0L
    values.forEach { value ->
        val safeValue = value.coerceAtLeast(0L)
        total = if (Long.MAX_VALUE - total < safeValue) Long.MAX_VALUE else total + safeValue
    }
    return total
}

internal fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble().coerceAtLeast(0.0)
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return if (index == 0) "${bytes.coerceAtLeast(0)} ${units[index]}" else String.format(Locale.US, "%.1f %s", value, units[index])
}

