package com.yukisoffd.lyracode

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.data.DeveloperWallRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class DeveloperWallInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File

    @Before fun prepare() {
        AppStrings.initialize(context)
        directory = File(context.cacheDir, "developer-wall-test-${System.nanoTime()}")
    }

    @After fun clean() { directory.deleteRecursively() }

    private fun repository(json: String, onFetch: () -> Unit = {}) = DeveloperWallRepository(
        directory, "https://example.com/wall.json", fetchJson = { onFetch(); json }, fetchAvatar = { null },
    ).also { runBlocking { it.sync() } }

    @Test fun groupsMultipleRolesAndOnlyLinkedAvatarsOpenProfiles() {
        val repository = repository("""[
            {"name":"Alice","role":[0,1,2],"link":"https://example.com/alice"},
            {"name":"Bob","role":0}, {"name":"Missing role"}
        ]""")
        var opened = ""
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) { opened = uri }
                }) { DeveloperWallScreen(repository) }
            }
        }
        compose.onAllNodesWithText("Alice").assertCountEquals(3)
        compose.onAllNodesWithText("Missing role").assertCountEquals(0)
        compose.onNodeWithText(context.getString(R.string.developer_wall_developers)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.developer_wall_sponsors)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.developer_wall_contributors)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.developer_wall_thanks)).assertDoesNotExist()
        compose.onNodeWithContentDescription("Bob").assertHasNoClickAction()
        compose.onAllNodesWithText("Bob").assertCountEquals(1)
        // Use the first role's avatar through the button's localized click label.
        compose.onAllNodes(hasContentDescription("Alice"))[0]
            .assertHasClickAction().performClick()
        assertEquals("https://example.com/alice", opened)
    }

    @Test fun failedOrEmptyWallRendersNoSection() {
        val repository = repository("{invalid")
        compose.setContent { MaterialTheme { DeveloperWallScreen(repository) } }
        compose.onNodeWithText(context.getString(R.string.developer_wall_title)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.developer_wall_developers)).assertDoesNotExist()
    }

    @Test fun capturesThreeColumnAvatarLayout() {
        val json = (1..9).joinToString(prefix = "[", postfix = "]") {
            """{"name":"Developer $it","role":0} """
        }
        val repository = repository(json)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.testTag("wall")) { Surface { DeveloperWallScreen(repository) } }
            }
        }
        compose.onNodeWithText("Developer 9").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.developer_wall_title)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.developer_wall_sync)).assertDoesNotExist()
        val first = compose.onNodeWithText("Developer 1").fetchSemanticsNode().boundsInRoot
        val third = compose.onNodeWithText("Developer 3").fetchSemanticsNode().boundsInRoot
        val fourth = compose.onNodeWithText("Developer 4").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, third.top, 1f)
        assertTrue(fourth.top > first.bottom)
        val bitmap = compose.onNodeWithTag("wall").captureToImage().asAndroidBitmap()
        File(context.externalCacheDir, "developer-wall.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun aboutEntryOpensWallWithoutRenderingThePeopleInline() {
        val repository = repository("""[{"name":"Alice","role":0}]""")
        var opened = false
        compose.setContent {
            MaterialTheme { DeveloperWallEntry(onClick = { opened = true }, repository = repository) }
        }
        compose.onNodeWithText("Alice").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.developer_wall_title)).performClick()
        assertTrue(opened)
    }

    @Test fun unavailableWallHidesAboutEntry() {
        val repository = repository("{invalid")
        compose.setContent {
            MaterialTheme { DeveloperWallEntry(onClick = {}, repository = repository) }
        }
        compose.onNodeWithText(context.getString(R.string.developer_wall_title)).assertDoesNotExist()
    }

    @Test fun longWallScrollsInsideItsOwnSettingsPage() {
        val json = (1..100).joinToString(prefix = "[", postfix = "]") {
            """{"name":"Developer $it","role":0}"""
        }
        val repository = repository(json)
        compose.setContent {
            MaterialTheme { SettingsDetailPage(scroll = false) { DeveloperWallScreen(repository) } }
        }
        compose.onNodeWithText("Developer 1").assertIsDisplayed()
        compose.onNodeWithTag("developer-wall-list").performScrollToNode(hasText("Developer 100"))
        compose.onNodeWithText("Developer 100").assertIsDisplayed()
    }

    @Test fun pullShowsHintOnlyWhileDraggingAndRefreshesOnlyAfterRelease() {
        val calls = AtomicInteger()
        val repository = repository("""[{"name":"Alice","role":0}]""") { calls.incrementAndGet() }
        compose.setContent { MaterialTheme { DeveloperWallScreen(repository) } }
        val hint = context.getString(R.string.developer_wall_release_to_refresh)
        compose.onNodeWithText(hint).assertDoesNotExist()
        val list = compose.onNodeWithTag("developer-wall-list")
        list.performTouchInput {
            down(Offset(centerX, height * 0.1f))
            moveTo(Offset(centerX, height * 0.15f), delayMillis = 100)
            moveTo(Offset(centerX, height * 0.75f), delayMillis = 600)
        }
        compose.onNodeWithText(hint).assertIsDisplayed()
        assertEquals(1, calls.get())
        list.performTouchInput { up() }
        compose.waitUntil(5_000) { calls.get() == 2 && !repository.state.value.syncing }
        compose.waitForIdle()
        compose.onNodeWithText(hint).assertDoesNotExist()
        // A subsequent drag must work even when the previous refresh finished immediately.
        list.performTouchInput { swipeDown(durationMillis = 600) }
        compose.waitUntil(5_000) { calls.get() == 3 && !repository.state.value.syncing }
    }

    @Test fun shortPullDoesNotRefresh() {
        val calls = AtomicInteger()
        val repository = repository("""[{"name":"Alice","role":0}]""") { calls.incrementAndGet() }
        compose.setContent { MaterialTheme { DeveloperWallScreen(repository) } }
        compose.onNodeWithTag("developer-wall-list").performTouchInput {
            swipe(Offset(centerX, height * 0.1f), Offset(centerX, height * 0.1f + 80f), durationMillis = 600)
        }
        compose.waitForIdle()
        assertEquals(1, calls.get())
        compose.onNodeWithText(context.getString(R.string.developer_wall_release_to_refresh)).assertDoesNotExist()
    }

    @Test fun scrollingDownInMiddleOfListDoesNotRefreshOrShowHint() {
        val calls = AtomicInteger()
        val json = (1..100).joinToString(prefix = "[", postfix = "]") {
            """{"name":"Developer $it","role":0}"""
        }
        val repository = repository(json) { calls.incrementAndGet() }
        compose.setContent { MaterialTheme { DeveloperWallScreen(repository) } }
        val list = compose.onNodeWithTag("developer-wall-list")
        list.performScrollToNode(hasText("Developer 60"))
        list.performTouchInput {
            swipe(Offset(centerX, height * 0.2f), Offset(centerX, height * 0.5f), durationMillis = 800)
        }
        compose.waitForIdle()
        assertEquals(1, calls.get())
        compose.onNodeWithText(context.getString(R.string.developer_wall_release_to_refresh)).assertDoesNotExist()
    }

    @Test fun informationDialogExplainsMaintenanceAndLinksToIssueForm() {
        var opened = ""
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) { opened = uri }
                }) { DeveloperWallInformationAction() }
            }
        }
        val body = context.getString(R.string.developer_wall_information_body)
        val information = context.getString(R.string.developer_wall_information)
        compose.onNodeWithText(body).assertDoesNotExist()
        compose.onNodeWithContentDescription(information).performClick()
        compose.onNodeWithText(body).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.action_close)).performClick()
        compose.onNodeWithText(body).assertDoesNotExist()
        compose.onNodeWithContentDescription(information).performClick()
        compose.onNodeWithText(context.getString(R.string.developer_wall_report_issue)).performClick()
        assertEquals("https://github.com/lyracode-app/Lyra-Code/issues/new", opened)
        compose.onNodeWithText(body).assertDoesNotExist()
    }
}
