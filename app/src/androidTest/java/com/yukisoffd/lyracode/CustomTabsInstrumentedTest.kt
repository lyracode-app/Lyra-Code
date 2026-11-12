package com.yukisoffd.lyracode

import android.content.ActivityNotFoundException
import android.app.ActivityOptions
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.ui.platform.UriHandler
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.browser.AppUriHandler
import com.yukisoffd.lyracode.browser.buildCustomTabIntent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomTabsInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun nativeToolbarAndPersistentBrowserStateAreEnabled() {
        val color = 0xff202020.toInt()
        val tab = buildCustomTabIntent(true, color)
        val intent = tab.intent
        assertEquals(CustomTabsIntent.SHARE_STATE_ON, intent.getIntExtra(CustomTabsIntent.EXTRA_SHARE_STATE, -1))
        assertEquals(CustomTabsIntent.SHOW_PAGE_TITLE, intent.getIntExtra(CustomTabsIntent.EXTRA_TITLE_VISIBILITY_STATE, -1))
        assertEquals(CustomTabsIntent.COLOR_SCHEME_DARK, intent.getIntExtra(CustomTabsIntent.EXTRA_COLOR_SCHEME, -1))
        assertTrue(CustomTabsIntent.isCloseButtonEnabled(intent))
        assertTrue(CustomTabsIntent.isBookmarksButtonEnabled(intent))
        assertTrue(CustomTabsIntent.isDownloadButtonEnabled(intent))
        assertFalse(tab.isEphemeralBrowsingEnabled)
        if (Build.VERSION.SDK_INT >= 34) {
            val enabled = ActivityOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
            val disabled = ActivityOptions.makeBasic().setShareIdentityEnabled(false).toBundle()
            @Suppress("DEPRECATION")
            val identityKey = enabled.keySet().single { enabled.get(it) != disabled.get(it) }
            @Suppress("DEPRECATION")
            assertEquals(enabled.get(identityKey), tab.startAnimationBundle?.get(identityKey))
        }
        assertTrue(CustomTabsIntent.isSendToExternalDefaultHandlerEnabled(intent))
        assertFalse(intent.getBooleanExtra(CustomTabsIntent.EXTRA_ENABLE_URLBAR_HIDING, false))
        assertEquals(color, CustomTabsIntent.getColorSchemeParams(intent, CustomTabsIntent.COLOR_SCHEME_DARK).toolbarColor)
        assertEquals(CustomTabsIntent.COLOR_SCHEME_LIGHT,
            buildCustomTabIntent(false, color).intent.getIntExtra(CustomTabsIntent.EXTRA_COLOR_SCHEME, -1))
    }

    @Test fun customTabsLaunchFailureFallsBackWithoutLosingTheAuthorizationUrl() {
        val launches = mutableListOf<Intent>()
        val recordingContext = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent, options: Bundle?) { startActivity(intent) }
            override fun startActivity(intent: Intent) {
                launches.add(Intent(intent))
                if (intent.`package` != null) throw ActivityNotFoundException("Provider became unavailable")
            }
        }
        val url = "https://auth.example.test/authorize?state=fixture&code_challenge=fixture&redirect_uri=http%3A%2F%2F127.0.0.1%3A1455%2Fauth%2Fcallback"
        AppUriHandler(recordingContext, rejectingExternalHandler(), false, 0xffffffff.toInt()).openUri(url)
        assertTrue(launches.isNotEmpty())
        val fallback = launches.last()
        assertNull(fallback.`package`)
        assertTrue(fallback.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        @Suppress("DEPRECATION")
        val target = if (fallback.action == Intent.ACTION_CHOOSER) fallback.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!! else fallback
        assertEquals(Intent.ACTION_VIEW, target.action)
        assertEquals(Uri.parse(url), target.data)
        assertTrue(target.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertFalse(target.hasExtra(CustomTabsIntent.EXTRA_SESSION))
    }

    @Test fun nonWebSchemesKeepTheirExistingHandlers() {
        val seen = mutableListOf<String>()
        val handler = AppUriHandler(context, object : UriHandler {
            override fun openUri(uri: String) { seen.add(uri) }
        }, false, 0xffffffff.toInt())
        val links = listOf("mailto:support@example.test", "tel:12345", "content://example/document/1", "custom-app://callback")
        links.forEach(handler::openUri)
        assertEquals(links, seen)
    }

    @Test fun githubOpensInTheAppFirstAndFallsBackWhenItCannotHandleTheUrl() {
        val launches = mutableListOf<Intent>()
        var githubAvailable = true
        val recordingContext = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent, options: Bundle?) { startActivity(intent) }
            override fun startActivity(intent: Intent) {
                launches.add(Intent(intent))
                if (intent.`package` == "com.github.android" && !githubAvailable) {
                    throw ActivityNotFoundException("GitHub is unavailable")
                }
            }
        }
        val url = "https://github.com/lyracode-app/Lyra-Code/issues/1"
        val handler = AppUriHandler(recordingContext, rejectingExternalHandler(), false, 0xffffffff.toInt())
        handler.openUri(url)
        val github = launches.single()
        assertEquals("com.github.android", github.`package`)
        assertEquals(Uri.parse(url), github.data)
        assertFalse(github.hasExtra(CustomTabsIntent.EXTRA_SESSION))

        launches.clear()
        githubAvailable = false
        handler.openUri(url)
        assertEquals("com.github.android", launches.first().`package`)
        assertNotEquals("com.github.android", launches.last().`package`)
        assertTrue(launches.size > 1)

        launches.clear()
        handler.openUri("https://github.com.example.test/")
        assertTrue(launches.isNotEmpty())
        assertTrue(launches.none { it.`package` == "com.github.android" })
    }

    private fun rejectingExternalHandler() = object : UriHandler {
        override fun openUri(uri: String) { fail("HTTP(S) must use the browser launcher") }
    }
}
