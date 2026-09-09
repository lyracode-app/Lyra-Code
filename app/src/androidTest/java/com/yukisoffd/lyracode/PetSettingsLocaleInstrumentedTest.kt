package com.yukisoffd.lyracode

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.BackEventCompat
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.interaction.pet.DesktopPetSettingsActivity
import com.yukisoffd.lyracode.interaction.pet.DevicePetStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
class PetSettingsLocaleInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val settings = AppSettings(context)
    private fun open() = (instrumentation.startActivitySync(Intent(context, DesktopPetSettingsActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DesktopPetSettingsActivity).also { SystemClock.sleep(350); instrumentation.waitForIdleSync() }
    private fun roots(view: View): List<ViewRootForTest> = buildList {
        if (view is ViewRootForTest) add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(roots(view.getChildAt(i)))
    }
    private fun all(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> = listOf(node) + node.children.flatMap(::all)
    private fun texts(activity: DesktopPetSettingsActivity): List<String> {
        var result = emptyList<String>()
        instrumentation.runOnMainSync { result = roots(activity.window.decorView).flatMap { root ->
            all(root.semanticsOwner.rootSemanticsNode).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }
        } }
        return result
    }
    private fun titleX(activity: DesktopPetSettingsActivity): Float {
        var result = Float.NaN
        instrumentation.runOnMainSync {
            val title = activity.getString(R.string.pet_settings_title)
            result = roots(activity.window.decorView).flatMap { all(it.semanticsOwner.rootSemanticsNode) }
                .first { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == title } == true }.boundsInRoot.left
        }
        return result
    }
    @Test fun hostUsesSelectedLanguageWhilePackageLabelsStayOriginal() {
        val old = settings.languageMode
        try {
            for ((mode, title, entry) in listOf(
                Triple(AppSettings.LANGUAGE_ZH_CN, "桌宠与悬浮对话", "桌宠与截图设置"),
                Triple(AppSettings.LANGUAGE_ZH_TW, "桌寵與懸浮對話", "桌寵與截圖設定"),
                Triple(AppSettings.LANGUAGE_EN, "Pet and floating chat", "Pet and screenshot settings")
            )) {
                settings.languageMode = mode
                val activity = open()
                try {
                    assertEquals(title, activity.getString(R.string.pet_settings_title))
                    assertEquals(entry, activity.getString(R.string.pet_settings_entry))
                    assertTrue(texts(activity).contains(title))
                    assertTrue(texts(activity).contains(DevicePetStore.load(context).getString("name")))
                    val controls = DevicePetStore.load(context).optJSONArray("controls")
                    if (controls != null) for (i in 0 until controls.length()) {
                        assertTrue("Package label must remain unchanged", texts(activity).contains(controls.getJSONObject(i).getString("label")))
                    }
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        java.io.File(context.externalCacheDir, "pet-settings-$mode.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                    }
                } finally { instrumentation.runOnMainSync { activity.finish() } }
            }
        } finally { settings.languageMode = old; AppStrings.initialize(context.localizedContext(old)) }
    }
    @Test fun permissionShortcutsHaveOwnTargetsAndDisclosuresExistInEachLanguage() {
        val audit = com.yukisoffd.lyracode.data.AuditLogStore(context)
        try {
            val termux = com.yukisoffd.lyracode.termux.TermuxExecutor(context, audit)
            val rows = appPermissionRows(context, termux)
            assertEquals(1, rows.count { it.target == "overlay" })
            assertEquals(1, rows.count { it.target == "accessibility" })
            assertEquals(com.yukisoffd.lyracode.interaction.overlay.OverlayPermission.isGranted(context), rows.single { it.target == "overlay" }.granted)
            assertEquals(com.yukisoffd.lyracode.interaction.service.AccessibilityConnection.isEnabledInSystem(context), rows.single { it.target == "accessibility" }.granted)
            val launched = mutableListOf<Intent>()
            val wrapped = object : android.content.ContextWrapper(context) { override fun startActivity(intent: Intent) { launched += intent } }
            com.yukisoffd.lyracode.interaction.overlay.OverlayPermission.openSettings(wrapped)
            com.yukisoffd.lyracode.interaction.ui.openAccessibilitySettings(wrapped)
            assertEquals(listOf(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS), launched.map { it.action })
            for (language in listOf(AppSettings.LANGUAGE_ZH_CN, AppSettings.LANGUAGE_ZH_TW, AppSettings.LANGUAGE_EN)) {
                val localized = context.localizedContext(language)
                for (id in listOf(R.string.compliance_user_device_body, R.string.compliance_privacy_device_body,
                    R.string.compliance_personal_screen_body, R.string.compliance_third_pets_body,
                    R.string.compliance_permission_overlay_body, R.string.compliance_permission_accessibility_body)) {
                    assertTrue(localized.getString(id).length > 100)
                }
            }
        } finally { audit.close() }
    }
    @Test fun predictiveBackHonorsSwitchAndBothEdgesThenCancelsOrCommits() {
        val old = settings.predictiveBackEnabled
        try {
            for (enabled in listOf(true, false)) {
                settings.predictiveBackEnabled = enabled
                val activity = open()
                try {
                    for (edge in listOf(BackEventCompat.EDGE_LEFT, BackEventCompat.EDGE_RIGHT)) {
                        val baseline = titleX(activity)
                        instrumentation.runOnMainSync {
                            activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, edge))
                            activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 300f, .3f, edge))
                        }
                        SystemClock.sleep(180)
                        val delta = titleX(activity) - baseline
                        if (!enabled) assertEquals(0f, delta, 2f)
                        else if (edge == BackEventCompat.EDGE_LEFT) assertTrue("Left edge must move right: $delta", delta > 10f)
                        else assertTrue("Right edge must move left: $delta", delta < -10f)
                        instrumentation.runOnMainSync { activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
                        SystemClock.sleep(800)
                        assertFalse(activity.isFinishing)
                        assertEquals(baseline, titleX(activity), 2f)
                    }
                    instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
                    SystemClock.sleep(400)
                    assertTrue(activity.isFinishing)
                } finally { instrumentation.runOnMainSync { if (!activity.isFinishing) activity.finish() } }
            }
        } finally { settings.predictiveBackEnabled = old }
    }
}
