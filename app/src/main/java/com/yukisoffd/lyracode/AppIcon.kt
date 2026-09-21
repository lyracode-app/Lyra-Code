package com.yukisoffd.lyracode

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.app.ActivityManager
import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.yukisoffd.lyracode.data.AppSettings

enum class AppIcon(val id: String, val alias: String, val preview: Int, val title: Int, val splashTheme: Int) {
    DEFAULT("default", "DefaultLauncher", R.mipmap.ic_launcher, R.string.app_icon_default, R.style.Theme_LyraCode_Splash_Default),
    ANGEL("angel", "AngelLauncher", R.mipmap.ic_launcher_angel, R.string.app_icon_angel, R.style.Theme_LyraCode_Splash_Angel);

    fun component(context: Context) = ComponentName(context.packageName, "com.yukisoffd.lyracode.$alias")

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

internal object AppIconManager {
    private var activityStarted = false

    fun syncSplashTheme(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 31) {
            val icon = AppIcon.fromId(AppSettings(activity).appIconId)
            // This is persisted by Android and used before the next activity/process is created.
            activity.splashScreen.setSplashScreenTheme(icon.splashTheme)
        }
    }

    fun restart(activity: MainActivity) {
        syncSplashTheme(activity)
        applySavedIcon(activity)
        // Recreate the app host through Android's lifecycle, so old controllers are closed before
        // new ones start. Clearing a resumed task races top-resumed transactions on Android 17.
        activity.recreate()
    }

    fun onActivityCreated(context: Context, restoringActivity: Boolean) {
        // A process restart can restore a task bundle; UI recreation in the same process must wait.
        if (!activityStarted || !restoringActivity) applyAfterLauncherFinishes(context.applicationContext)
        activityStarted = true
    }

    private val handler = Handler(Looper.getMainLooper())
    private var startupCheck: Runnable? = null

    private fun applyAfterLauncherFinishes(context: Context) {
        startupCheck?.let(handler::removeCallbacks)
        startupCheck = null
        val startupSelection = AppSettings(context).appIconId
        if (AppIcon.entries.filter { isActive(context, it) }.map { it.id } == listOf(startupSelection)) return
        val aliases = AppIcon.entries.map { it.component(context) }.toSet()
        var attempts = 0
        val check = object : Runnable {
            override fun run() {
                val launcherStillPresent = context.getSystemService(ActivityManager::class.java)
                    .appTasks.any { it.taskInfo?.baseIntent?.component in aliases }
                if (launcherStillPresent) {
                    // Never disable a component while its launch/finish transactions are in flight.
                    // If it cannot finish, leave the saved choice for onStop instead.
                    if (++attempts < 50) handler.postDelayed(this, 100L)
                    else startupCheck = null
                    return
                }
                startupCheck = null
                Looper.myQueue().addIdleHandler {
                    if (AppSettings(context).appIconId == startupSelection) applySavedIcon(context)
                    false
                }
            }
        }
        startupCheck = check
        handler.post(check)
    }

    fun isActive(context: Context, icon: AppIcon): Boolean =
        when (context.packageManager.getComponentEnabledSetting(icon.component(context))) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == AppIcon.DEFAULT
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            else -> false
        }

    // Apply when leaving the foreground. Startup also repairs a pending change after force-stop;
    // MainActivity's task is independent of the switchable aliases, so it stays alive.
    fun applySavedIcon(context: Context) {
        val selected = AppIcon.fromId(AppSettings(context).appIconId)
        val changes = AppIcon.entries.filter { isActive(context, it) != (it == selected) }
        if (changes.isEmpty()) return
        runCatching {
            val manager = context.packageManager
            if (Build.VERSION.SDK_INT >= 33) {
                manager.setComponentEnabledSettings(changes.map { icon ->
                    PackageManager.ComponentEnabledSetting(
                        icon.component(context),
                        if (icon == selected) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                })
            } else {
                // Enable first so an interrupted update can never leave the app without a launcher.
                changes.sortedBy { it != selected }.forEach { icon ->
                    manager.setComponentEnabledSetting(
                        icon.component(context),
                        if (icon == selected) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                }
            }
        }.onFailure { Log.e("AppIconManager", "Unable to apply saved launcher icon; will retry next launch", it) }
    }
}
