package com.yukisoffd.lyracode

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import android.os.SystemClock
import android.app.ActivityManager
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import com.yukisoffd.lyracode.data.AppSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppIconInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun wakeDevice() {
        for (command in listOf("input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard")) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command),
            ).use { it.readBytes() }
        }
    }

    private fun resumedMain(): MainActivity? {
        var result: MainActivity? = null
        instrumentation.runOnMainSync {
            result = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull()
        }
        return result
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertTrue(message, condition())
    }

    private fun launchFromDesktop(icon: AppIcon): MainActivity {
        context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(icon.component(context)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        awaitCondition("First launcher tap must open MainActivity") { resumedMain() != null }
        // The old bug briefly resumed the UI before PackageManager removed its task.
        SystemClock.sleep(2_000)
        val activity = requireNotNull(resumedMain()) { "Launcher change removed the newly opened task" }
        val task = context.getSystemService(ActivityManager::class.java).appTasks
            .mapNotNull { it.taskInfo }.first { it.taskId == activity.taskId }
        assertEquals(MainActivity::class.java.name, task.baseIntent.component?.className)
        return activity
    }

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun clickText(activity: MainActivity, text: String) {
        fun roots(view: View): List<ViewRootForTest> = buildList {
            if (view is ViewRootForTest) add(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) addAll(roots(view.getChildAt(i)))
        }
        fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)
        var clicked = false
        var labels = emptyList<String>()
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!clicked && SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                val nodes = roots(activity.window.decorView).flatMap { all(it.semanticsOwner.rootSemanticsNode) }
                labels = nodes.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { label -> label.text } }
                val button = nodes.firstOrNull { node ->
                    node.config.getOrNull(SemanticsActions.OnClick)?.action != null && all(node).any {
                        it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { label -> label.text == text }
                    }
                }
                clicked = button?.config?.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true
            }
            if (!clicked) SystemClock.sleep(100)
        }
        assertTrue("Could not click '$text'. Visible labels: $labels", clicked)
    }

    @Test
    fun restartButtonAppliesIconAndReopensWithMatchingSplashTheme() {
        val settings = AppSettings(context)
        val previous = settings.appIconId
        var activity: MainActivity? = null
        try {
            settings.appIconId = AppIcon.DEFAULT.id
            AppIconManager.applySavedIcon(context)
            activity = launchFromDesktop(AppIcon.DEFAULT)
            for (icon in listOf(AppIcon.ANGEL, AppIcon.DEFAULT)) {
                val old = requireNotNull(activity)
                instrumentation.runOnMainSync {
                    old.setContent { MaterialTheme { AppIconSettings(settings) } }
                }
                SystemClock.sleep(300)
                clickText(old, old.getString(icon.title))
                assertEquals(icon.id, AppSettings(context).appIconId)
                clickText(old, old.getString(R.string.app_icon_restart_now))
                awaitCondition("Restart button must replace the old activity") {
                    old.isDestroyed && resumedMain()?.let { it !== old } == true
                }
                SystemClock.sleep(2_000)
                activity = requireNotNull(resumedMain())
                assertEquals(listOf(icon), AppIcon.entries.filter { AppIconManager.isActive(context, it) })
                if (Build.VERSION.SDK_INT >= 31) {
                    val value = TypedValue()
                    assertTrue(activity.theme.resolveAttribute(android.R.attr.windowSplashScreenAnimatedIcon, value, true))
                    assertEquals(icon.preview, value.resourceId)
                }
            }
        } finally {
            settings.appIconId = previous
            activity?.let { last -> instrumentation.runOnMainSync { last.finishAndRemoveTask() } }
            AppIconManager.applySavedIcon(context)
        }
    }

    @Test
    fun leavingAppAppliesIconAndFirstRelaunchSurvives() {
        val settings = AppSettings(context)
        val previous = settings.appIconId
        var activity: MainActivity? = null
        try {
            settings.appIconId = AppIcon.DEFAULT.id
            AppIconManager.applySavedIcon(context)
            activity = launchFromDesktop(AppIcon.DEFAULT)
            for (icon in listOf(AppIcon.ANGEL, AppIcon.DEFAULT, AppIcon.ANGEL)) {
                val current = requireNotNull(activity)
                val old = AppIcon.entries.single { AppIconManager.isActive(context, it) }
                settings.appIconId = icon.id
                assertTrue(AppIconManager.isActive(context, old))
                instrumentation.runOnMainSync { current.moveTaskToBack(true) }
                awaitCondition("Icon must change on exit, before reopening") { AppIconManager.isActive(context, icon) }
                instrumentation.runOnMainSync { current.finishAndRemoveTask() }
                awaitCondition("Previous task must finish") { current.isDestroyed }
                activity = launchFromDesktop(icon)
                assertEquals(icon.id, AppSettings(context).appIconId)
            }
            // A force-stop can bypass onStop. A pending selection must also be safe on startup.
            val current = requireNotNull(activity)
            instrumentation.runOnMainSync { current.finishAndRemoveTask() }
            awaitCondition("Previous task must finish") { current.isDestroyed }
            settings.appIconId = AppIcon.DEFAULT.id
            activity = launchFromDesktop(AppIcon.ANGEL)
            assertTrue(AppIconManager.isActive(context, AppIcon.DEFAULT))
        } finally {
            settings.appIconId = previous
            activity?.let { last -> instrumentation.runOnMainSync { last.finishAndRemoveTask() } }
            AppIconManager.applySavedIcon(context)
        }
    }

    @Test
    fun selectionPersistsAndOnlyChangesLauncherWhenApplied() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = AppSettings(context)
        val previous = settings.appIconId
        try {
            for (icon in listOf(AppIcon.ANGEL, AppIcon.DEFAULT, AppIcon.ANGEL)) {
                val before = AppIcon.entries.map { AppIconManager.isActive(context, it) }
                settings.appIconId = icon.id
                assertEquals(icon.id, AppSettings(context).appIconId)
                assertEquals(before, AppIcon.entries.map { AppIconManager.isActive(context, it) })
                AppIconManager.applySavedIcon(context)
                assertEquals(listOf(icon), AppIcon.entries.filter { AppIconManager.isActive(context, it) })
                val launchers = context.packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName),
                    0,
                )
                assertEquals(1, launchers.size)
                assertEquals(icon.component(context).className, launchers.single().activityInfo.name)
                assertEquals(icon.preview, launchers.single().activityInfo.icon)
                val drawable = requireNotNull(context.getDrawable(icon.preview))
                drawable.setBounds(0, 0, 192, 192)
                drawable.draw(android.graphics.Canvas(android.graphics.Bitmap.createBitmap(
                    192, 192, android.graphics.Bitmap.Config.ARGB_8888,
                )))
                // Repeated starts must preserve the same icon and leave the real activity enabled.
                AppIconManager.applySavedIcon(context)
                assertTrue(AppIconManager.isActive(context, icon))
                assertNotEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    context.packageManager.getComponentEnabledSetting(android.content.ComponentName(context, MainActivity::class.java)))
            }
            settings.appIconId = "unknown-future-icon"
            assertEquals(AppIcon.DEFAULT.id, AppSettings(context).appIconId)
            AppIconManager.applySavedIcon(context)
            assertTrue(AppIconManager.isActive(context, AppIcon.DEFAULT))
        } finally {
            settings.appIconId = previous
            AppIconManager.applySavedIcon(context)
        }
    }
}
