package com.yukisoffd.lyracode

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle

/** Keeps switchable launcher aliases out of the real app task's base component. */
class AppLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Configure the real activity's system splash before asking Android to create its window.
        AppIconManager.syncSplashTheme(this)
        // An upgrade can retain a task whose root was one of the old MainActivity aliases.
        // Do not let NEW_TASK reuse that root, since disabling it would remove the app again.
        val aliases = AppIcon.entries.map { it.component(this) }.toSet()
        getSystemService(ActivityManager::class.java).appTasks.forEach { task ->
            val info = task.taskInfo ?: return@forEach
            if (info.taskId != taskId && info.baseIntent.component in aliases) task.finishAndRemoveTask()
        }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
