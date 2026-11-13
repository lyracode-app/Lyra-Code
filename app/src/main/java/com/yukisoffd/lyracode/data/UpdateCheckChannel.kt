package com.yukisoffd.lyracode.data

import androidx.annotation.StringRes
import com.yukisoffd.lyracode.R
import kotlinx.coroutines.CancellationException
import java.io.IOException

internal enum class UpdateCheckChannel(val id: String, @get:StringRes val title: Int) {
    MANIFEST("manifest", R.string.update_channel_manifest),
    GITHUB("github", R.string.update_channel_github),
}

internal fun decodeUpdateCheckChannels(value: String?): List<UpdateCheckChannel> =
    value.orEmpty().split(',').mapNotNull { id ->
        UpdateCheckChannel.entries.firstOrNull { it.id == id }
    }.distinct().ifEmpty { UpdateCheckChannel.entries.toList() }

internal fun loadFromUpdateChannels(
    channels: List<UpdateCheckChannel>,
    onFailure: (UpdateCheckChannel, Exception) -> String,
    load: (UpdateCheckChannel) -> AppUpdateInfo,
): AppUpdateInfo {
    val failures = mutableListOf<String>()
    for (channel in channels) {
        try {
            return load(channel)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failures += onFailure(channel, error)
        }
    }
    throw IOException(failures.joinToString("\n"))
}
