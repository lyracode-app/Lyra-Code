package com.yukisoffd.lyracode.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Finish cleanup after our own upgrade, even if the user does not reopen the app. */
class UpdateCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        Thread({
            try {
                UpdateManager(context.applicationContext).pendingDownloadedApk()
            } catch (error: Exception) {
                Log.w("UpdateCleanup", "Unable to clean old update packages", error)
            } finally {
                pending?.finish()
            }
        }, "lyra-update-cleanup").start()
    }
}
