package com.yukisoffd.lyracode

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.BackEventCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.AppUpdateInfo
import com.yukisoffd.lyracode.data.GitHubAccelerationSettings
import com.yukisoffd.lyracode.data.UpdateManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.time.LocalDate

class GitHubAccelerationNavigationInstrumentedTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val sourceUrl = "https://github.com/test/app/releases/download/v99/app.apk"
    private val savedPreferences = mutableMapOf<String, Map<String, *>>()

    private val setup = object : TestWatcher() {
        override fun starting(description: Description) {
            for (name in listOf("lyra_settings", "lyra_update_state", "first_use_consent", "lyra_github_acceleration", "android_compatibility")) {
                savedPreferences[name] = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap()
            }
            FirstUseConsentStore(context).accept()
            context.getSharedPreferences("android_compatibility", Context.MODE_PRIVATE).edit()
                .putBoolean("android_17_local_network_rationale_seen", true).commit()
            AppSettings(context).apply {
                languageMode = AppSettings.LANGUAGE_EN
                fontScaleMode = AppSettings.FONT_SCALE_NORMAL
                predictiveBackEnabled = true
                dynamicColorEnabled = false
                themeMode = "light"
            }
            context.getSharedPreferences("lyra_github_acceleration", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("lyra_update_state", Context.MODE_PRIVATE).edit().clear()
                .putString("last_daily_check_date", LocalDate.now().toString()).commit()
            if (description.methodName.startsWith("startup")) {
                UpdateManager(context).saveLatestAvailableUpdate(
                    AppUpdateInfo("99.0", 999L, sourceUrl, "", "Navigation test release notes", "", "https://github.com/test/app/releases", false),
                )
            }
        }

        override fun finished(description: Description) {
            for ((name, values) in savedPreferences) {
                val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                for ((key, value) in values) {
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
                editor.commit()
            }
        }
    }

    @get:Rule val rule: RuleChain = RuleChain.outerRule(setup).around(compose)

    @Before fun dismissUnrelatedFirstLaunchNotice() {
        val title = compose.activity.getString(R.string.local_network_permission_title)
        if (compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()) {
            val noticeDialog = isDialog() and hasAnyDescendant(hasText(title))
            compose.onNode(hasText(compose.activity.getString(R.string.action_later)) and hasAnyAncestor(noticeDialog)).performClick()
        }
    }

    private fun assertSubpage() {
        compose.onNodeWithText("Automatic fallback").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()) {
            fail(compose.onNode(isDialog()).printToString())
        }
    }

    private fun systemBack() {
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, BackEventCompat.EDGE_LEFT))
            compose.activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 300f, 0.4f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test fun aboutEntryUsesSettingsSubpageAndBothBackActionsReturnToAbout() {
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("About").performScrollTo().performClick()
        compose.onNodeWithText("GitHub acceleration links").performScrollTo().performClick()
        assertSubpage()
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), "github-acceleration-subpage.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("About").assertIsDisplayed()
        compose.onNodeWithText("GitHub acceleration links").performScrollTo().performClick()
        assertSubpage()
        systemBack()
        compose.onNodeWithText("About").assertIsDisplayed()
    }

    @Test fun startupUpdateEntryReturnsToOriginalPromptAndRetainsSettings() {
        val title = compose.activity.getString(R.string.title_new_version, "99.0")
        compose.waitUntil(10_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("GitHub acceleration links").performClick()
        assertSubpage()
        val accelerationList = hasScrollToIndexAction() and hasAnyDescendant(hasText("Automatic fallback") or hasText("GitHub download URL to test"))
        compose.onNode(accelerationList).performScrollToNode(hasText("GitHub download URL to test"))
        compose.onNode(hasSetTextAction() and hasText(sourceUrl)).assertExists()
        compose.onNode(accelerationList).performScrollToNode(hasText("Automatic fallback"))
        compose.onNodeWithContentDescription("Automatic fallback").performClick()
        assertFalse(GitHubAccelerationSettings(context).load().enabled)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText("GitHub acceleration links").performClick()
        assertSubpage()
        assertFalse(GitHubAccelerationSettings(context).load().enabled)
        systemBack()
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.action_later)).performClick()
        compose.onNodeWithContentDescription("Menu").assertIsDisplayed()
        compose.onNodeWithText("GitHub acceleration links").assertDoesNotExist()
    }
}
