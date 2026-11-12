package com.yukisoffd.lyracode

import android.content.Intent
import androidx.browser.customtabs.CustomTabsService
import androidx.compose.ui.platform.UriHandler
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.browser.AppUriHandler
import com.yukisoffd.lyracode.data.ChatGptCodexProtocol
import com.yukisoffd.lyracode.data.ChatGptLoopbackSignIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Requires an initialized Chrome on the test device. No OpenAI account or token exchange. */
@RunWith(AndroidJUnit4::class)
class CustomTabsBrowserSmokeTest {
    @Test fun browserDeliversBothSiwcAndCodexLoopbackCallbacks() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeNotNull(context.packageManager.resolveService(
            Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION).setPackage("com.android.chrome"), 0,
        ))
        val handler = AppUriHandler(context, object : UriHandler {
            override fun openUri(uri: String) { fail("Expected a Custom Tab") }
        }, false, 0xffffffff.toInt())
        withTimeout(30_000) {
            for (codex in listOf(false, true)) {
                val clientId = if (codex) ChatGptCodexProtocol.CLIENT_ID else "oaiapp_fixture"
                ChatGptLoopbackSignIn(clientId, codex).use { listener ->
                    val callback = listener.attempt.redirectUri.toHttpUrl().newBuilder()
                        .addQueryParameter("state", listener.attempt.state)
                        .addQueryParameter("code", "fixture-code").build()
                    val waiting = async(Dispatchers.IO) { listener.awaitCallback() }
                    instrumentation.runOnMainSync { handler.openUri(callback.toString()) }
                    assertEquals(callback, waiting.await())
                    assertEquals(clientId, listener.attempt.issuedClientId)
                }
            }
        }
        Unit
    }
}
