package com.yukisoffd.lyracode

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class KeyboardOutputLiftInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun keyboardLiftHoldsWhenAConversationStartsWithTheKeyboardOpen() {
        // Sending the first message creates the conversation while the keyboard is open.
        var conversation by mutableStateOf<Long?>(null)
        var lift = false
        compose.setContent {
            lift = rememberKeyboardOutputLift(conversationKey = conversation, liftPx = 300, nearOutputEnd = true)
        }
        compose.runOnIdle { assertTrue("lift before the conversation exists", lift) }

        compose.runOnIdle { conversation = 42L }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("lift after the conversation is created", lift) }
    }

    @Test
    fun keyboardLiftDropsWhenTheKeyboardClosesAwayFromTheEnd() {
        var liftPx by mutableStateOf(300)
        var nearEnd by mutableStateOf(true)
        var lift = false
        compose.setContent {
            lift = rememberKeyboardOutputLift(conversationKey = 1L, liftPx = liftPx, nearOutputEnd = nearEnd)
        }
        compose.runOnIdle { assertTrue(lift) }

        compose.runOnIdle { nearEnd = false }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("an open keyboard keeps the lift", lift) }

        compose.runOnIdle { liftPx = 0 }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse("a closed keyboard away from the end drops the lift", lift) }
    }
}
