package com.yukisoffd.lyracode

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MinimizedUserQuestionSlotInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun buttonStaysAboveTheComposerWhenTheKeyboardLiftsIt() {
        var lifted by mutableStateOf(false)
        var liftPx = 0
        compose.setContent {
            liftPx = with(LocalDensity.current) { 240.dp.roundToPx() }
            val lift = if (lifted) liftPx else 0
            MaterialTheme {
                // The same shape as the chat screen: the list box above the composer, and
                // the composer drawn over the list's bottom by the keyboard lift.
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        MinimizedUserQuestionSlot(liftPx = lift, onRestore = {})
                    }
                    Box(Modifier.fillMaxWidth().height(120.dp).keyboardAwareInputOffset(lift).testTag("composer"))
                }
            }
        }
        val description = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.ask_user_restore)
        val button = compose.onNodeWithContentDescription(description, useUnmergedTree = true)
        val composer = compose.onNodeWithTag("composer")

        val buttonBefore = button.fetchSemanticsNode().boundsInRoot
        assertTrue(buttonBefore.bottom <= composer.fetchSemanticsNode().boundsInRoot.top)

        compose.runOnIdle { lifted = true }
        compose.waitForIdle()
        val buttonAfter = button.fetchSemanticsNode().boundsInRoot
        val composerAfter = composer.fetchSemanticsNode().boundsInRoot
        assertTrue("button bottom ${buttonAfter.bottom} under composer top ${composerAfter.top}",
            buttonAfter.bottom <= composerAfter.top)
        assertEquals(liftPx.toFloat(), buttonBefore.top - buttonAfter.top, 0.5f)
    }
}
