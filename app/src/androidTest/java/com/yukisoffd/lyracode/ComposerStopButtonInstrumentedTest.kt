package com.yukisoffd.lyracode

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ComposerStopButtonInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun visibleStopIsActionable() {
        var stops = 0
        compose.setContent {
            MaterialTheme {
                ComposerStopButton(visible = true, accent = Color.Red, onStop = { stops++ })
            }
        }

        compose.onNodeWithContentDescription(context.getString(R.string.cd_stop))
            .assertIsDisplayed()
            .performClick()

        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test
    fun hiddenStopIsNotComposed() {
        compose.setContent {
            MaterialTheme {
                ComposerStopButton(visible = false, accent = Color.Red, onStop = {})
            }
        }

        compose.onNodeWithContentDescription(context.getString(R.string.cd_stop)).assertDoesNotExist()
    }
}
