package com.yukisoffd.lyracode.interaction.overlay

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

internal object OverlayPermission {
    fun isGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun openSettings(context: Context) {
        val appUri = "package:${context.packageName}".toUri()
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, appUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
    }
}
