package com.yukisoffd.lyracode

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConversationDockInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun statusAndComposerMoveTogetherWithoutOverlap() {
        var lifted by mutableStateOf(false)
        var liftPx = 0
        var spacingPx = 0
        compose.setContent {
            val density = LocalDensity.current
            liftPx = with(density) { 96.dp.roundToPx() }
            spacingPx = with(density) { 8.dp.roundToPx() }
            MaterialTheme {
                Box(Modifier.fillMaxSize().testTag("root"), contentAlignment = Alignment.BottomCenter) {
                    ConversationDock(status = "Working", liftPx = if (lifted) liftPx else 0) {
                        Box(Modifier.fillMaxWidth().height(56.dp).testTag("composer"))
                    }
                }
            }
        }

        val status = compose.onNodeWithTag("conversationStatus")
        val composer = compose.onNodeWithTag("composer")
        status.assertIsDisplayed()
        composer.assertIsDisplayed()
        val statusBefore = status.fetchSemanticsNode().boundsInRoot
        val composerBefore = composer.fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag("root").fetchSemanticsNode().boundsInRoot
        assertEquals(root.bottom, composerBefore.bottom, 0.5f)
        assertEquals(spacingPx.toFloat(), composerBefore.top - statusBefore.bottom, 0.5f)

        compose.runOnIdle { lifted = true }
        status.assertIsDisplayed()
        composer.assertIsDisplayed()
        val statusAfter = status.fetchSemanticsNode().boundsInRoot
        val composerAfter = composer.fetchSemanticsNode().boundsInRoot
        assertTrue(statusAfter.bottom <= composerAfter.top)
        assertEquals(root.bottom - liftPx, composerAfter.bottom, 0.5f)
        assertEquals(liftPx.toFloat(), statusBefore.top - statusAfter.top, 0.5f)
        assertEquals(liftPx.toFloat(), composerBefore.top - composerAfter.top, 0.5f)
    }

    @Test
    fun blankStatusIsNotRendered() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    ConversationDock(status = "   ", liftPx = 0) {
                        Box(Modifier.fillMaxWidth().height(56.dp).testTag("composer"))
                    }
                }
            }
        }

        compose.onNodeWithTag("conversationStatus").assertDoesNotExist()
        compose.onNodeWithTag("composer").assertIsDisplayed()
    }
}
