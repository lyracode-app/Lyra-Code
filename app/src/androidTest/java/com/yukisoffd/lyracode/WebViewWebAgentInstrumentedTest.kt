package com.yukisoffd.lyracode

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.ai.WebViewWebAgent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebViewWebAgentInstrumentedTest {
    @Test
    fun searchReturnsWithoutTimingOut() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = withTimeout(45_000L) {
            WebViewWebAgent(context, com.yukisoffd.lyracode.data.AppSettings(context)).search("OpenAI", limit = 2)
        }
        Log.d("LyraWebAgentTest", result.take(1_000))
        assertTrue(result.isNotBlank())
    }
}
