package com.yukisoffd.lyracode

import android.content.Context
import android.graphics.Bitmap
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.AppUpdateInfo
import com.yukisoffd.lyracode.data.GitHubAccelerationConfig
import com.yukisoffd.lyracode.data.GitHubAccelerationSettings
import com.yukisoffd.lyracode.data.GitHubAccelerator
import com.yukisoffd.lyracode.data.GitHubConnectionResult
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Collections
import java.io.File
import kotlin.math.max

class GitHubAccelerationInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = GitHubAccelerationSettings(context)
    private val sourceUrl = "https://github.com/test/app/releases/download/v1/app.apk"

    @Before fun prepare() {
        context.getSharedPreferences("lyra_github_acceleration", Context.MODE_PRIVATE).edit().clear().commit()
        AppStrings.initialize(context.localizedContext(AppSettings.LANGUAGE_EN))
    }

    @After fun clean() {
        context.getSharedPreferences("lyra_github_acceleration", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
    }

    private fun swipeToEnd() {
        val list = compose.onNode(hasScrollToIndexAction())
        repeat(40) {
            val range = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            if (range.value() >= range.maxValue()) return
            list.performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        fail("The bottom of the acceleration list could not be reached by swiping")
    }

    private fun screenshot(name: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @SdkSuppress(minSdkVersion = 30)
    private fun assertButtonAboveSystemUi(text: String) {
        val button = compose.onNodeWithText(text).fetchSemanticsNode()
        val buttonBottom = button.positionOnScreen.y + button.size.height
        val metrics = context.getSystemService(WindowManager::class.java).currentWindowMetrics
        val navigationBottom = metrics.windowInsets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout()).bottom
        val keyboardBottom = WindowInspector.getGlobalWindowViews().filter { it.hasWindowFocus() }
            .maxOfOrNull { it.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0 } ?: 0
        val safeBottom = metrics.bounds.bottom - max(navigationBottom, keyboardBottom)
        val minimumGap = 12 * context.resources.displayMetrics.density
        assertTrue("$text ends at $buttonBottom, safe bottom is $safeBottom", buttonBottom <= safeBottom - minimumGap)
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun bottomActionsAreReachableBySwipingWithLargeTextAndKeyboard() {
        store.save(GitHubAccelerationConfig(links = List(12) { index ->
            GitHubAccelerator("link-$index", "Acceleration ${index + 1}", "https://proxy$index.example/")
        }))
        compose.setContent {
            MaterialTheme {
                MainActivityWindow(LocalContext.current as ComponentActivity) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                        SettingsDetailPage(scroll = false) {
                            GitHubAccelerationSettingsScreen(initialTestUrl = sourceUrl)
                        }
                    }
                }
            }
        }
        swipeToEnd()
        compose.onNodeWithText("Add acceleration link").assertIsDisplayed()
        assertButtonAboveSystemUi("Add acceleration link")
        screenshot("github-acceleration-safe-bottom.png")

        scrollTo("GitHub download URL to test")
        compose.onNode(hasSetTextAction() and hasText("GitHub download URL to test")).performTouchInput { click() }
        compose.waitUntil(5_000) {
            WindowInspector.getGlobalWindowViews().any { it.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
        }
        swipeToEnd()
        compose.onNodeWithText("Add acceleration link").assertIsDisplayed()
        assertButtonAboveSystemUi("Add acceleration link")
        screenshot("github-acceleration-safe-keyboard.png")
        compose.onNodeWithText("Add acceleration link").performTouchInput { click() }
        compose.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Added with keyboard")
        compose.onNode(hasSetTextAction() and hasText("HTTPS acceleration prefix")).performTextInput("https://added.example/")
        compose.onNodeWithText("Save").assertIsDisplayed().performTouchInput { click() }
        compose.waitUntil { store.load().links.size == 13 }
    }

    @Test fun addEditToggleDeleteAndReopenPreserveSettings() {
        store.save(GitHubAccelerationConfig(enabled = false, links = emptyList()))
        val shown = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                Column {
                    if (shown.value) {
                        Button(onClick = { shown.value = false }) { Text("Back") }
                        SettingsDetailPage(scroll = false) {
                            GitHubAccelerationSettingsScreen(initialTestUrl = sourceUrl)
                        }
                    } else {
                        Button(onClick = { shown.value = true }) { Text("Open") }
                    }
                }
            }
        }
        scrollTo("Add acceleration link")
        compose.onNodeWithText("Add acceleration link").performClick()
        compose.onNode(hasSetTextAction() and hasText("Name")).performTextInput("First")
        compose.onNode(hasSetTextAction() and hasText("HTTPS acceleration prefix")).performTextInput("https://first.example")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil { store.load().links.size == 1 }
        assertEquals("https://first.example/", store.load().links.single().prefix)

        scrollTo("Automatic fallback")
        compose.onNodeWithContentDescription("Automatic fallback").performClick()
        assertTrue(store.load().enabled)
        scrollTo("First")
        compose.onNodeWithContentDescription("First").performClick()
        assertFalse(store.load().links.single().enabled)
        compose.onNodeWithContentDescription("Edit acceleration link").performClick()
        compose.onNode(hasSetTextAction() and hasText("Name")).performTextReplacement("Edited")
        compose.onNode(hasSetTextAction() and hasText("HTTPS acceleration prefix")).performTextReplacement("https://edited.example/")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil { store.load().links.single().name == "Edited" }
        assertEquals("https://edited.example/", store.load().links.single().prefix)

        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Open").performClick()
        scrollTo("Edited")
        compose.onNodeWithText("Edited").assertIsDisplayed()
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.waitUntil { store.load().links.isEmpty() }
        assertTrue(store.load().enabled)
    }

    @Test fun manualDirectAndAcceleratedTestsWorkWhenFallbackIsDisabled() {
        store.save(GitHubAccelerationConfig(enabled = false, links = listOf(GitHubAccelerator("first", "First", "https://first.example/", enabled = false))))
        val requests = Collections.synchronizedList(mutableListOf<String>())
        compose.setContent {
            MaterialTheme {
                SettingsDetailPage(scroll = false) {
                    GitHubAccelerationSettingsScreen(initialTestUrl = sourceUrl, testConnection = { url ->
                        requests += url
                        GitHubConnectionResult(successful = true, elapsedMs = 12, httpStatus = 206)
                    })
                }
            }
        }
        scrollTo("Direct GitHub connection")
        compose.onAllNodesWithText("Test Connection").onFirst().performClick()
        compose.waitUntil { requests.size == 1 }
        scrollTo("First")
        compose.onAllNodesWithText("Test Connection").onLast().performClick()
        compose.waitUntil { requests.size == 2 }
        assertEquals(listOf(sourceUrl, "https://first.example/$sourceUrl"), requests.toList())
        assertFalse(store.load().enabled)
        assertFalse(store.load().links.single().enabled)
    }

    @Test fun aboutUpdatePromptSurvivesOpeningAndReturningFromSubpage() {
        val infoState = mutableStateOf<AppUpdateInfo?>(AppUpdateInfo("99.0", 999L, sourceUrl, "", "Release notes", "", "https://github.com/test/app/releases", false))
        val accelerationPage = mutableStateOf(false)
        val selectedUrl = mutableStateOf("")
        compose.setContent {
            MaterialTheme {
                if (accelerationPage.value) {
                    Column {
                        Button(onClick = { accelerationPage.value = false }) { Text("Back") }
                        SettingsDetailPage(scroll = false) {
                            GitHubAccelerationSettingsScreen(initialTestUrl = selectedUrl.value)
                        }
                    }
                } else {
                    AboutSoftwareScreen(false, {}, {}, {}, onOpenGitHubAcceleration = { url ->
                        selectedUrl.value = url
                        accelerationPage.value = true
                    }, updateInfoState = infoState)
                }
            }
        }
        compose.onNodeWithText("Release notes").assertIsDisplayed()
        compose.onNode(hasText("GitHub acceleration links") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(sourceUrl, selectedUrl.value)
        compose.onNodeWithText("Automatic fallback").assertIsDisplayed()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Release notes").assertIsDisplayed()
        compose.onNodeWithText("Later").performClick()
        assertNull(infoState.value)
    }
}
