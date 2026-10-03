package com.yukisoffd.lyracode

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.ai.UserQuestionRequest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UserQuestionDialogInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var pending by mutableStateOf(
        PendingUserQuestion(1L, UserQuestionRequest(7L, "Travel preference", "Choose transport",
            listOf("Train", "Plane"), recommendedOptions = listOf("Train"))),
    )
    private var submitted: Pair<List<String>, String>? = null

    private fun showQuestion() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    Text("Earlier AI explanation", modifier = Modifier.align(Alignment.TopStart))
                    UserQuestionDialog(
                        pending = pending,
                        onActivity = {},
                        onMinimize = { pending = pending.copy(isMinimized = true) },
                        onSubmit = { options, text -> submitted = options to text },
                    )
                    if (pending.isMinimized) {
                        MinimizedUserQuestionButton(
                            onRestore = { pending = pending.copy(isMinimized = false) },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun recommendationDisplaysLabelWithoutSelectingAnAnswer() {
        showQuestion()
        compose.onAllNodesWithText(context.getString(R.string.ask_user_recommended)).assertCountEquals(1)
        compose.onNodeWithText(context.getString(R.string.ask_user_submit)).assertIsNotEnabled()
        compose.onNodeWithText("Train").performClick()
        compose.onNodeWithText(context.getString(R.string.ask_user_submit)).assertIsEnabled()
    }

    @Test
    fun minimizingRevealsEarlierOutputAndPreservesDraftThroughReopening() {
        showQuestion()
        compose.onNodeWithText("Train").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Prefer a window seat")
        compose.onNodeWithText(context.getString(R.string.ask_user_minimize)).performClick()
        compose.onNodeWithText("Earlier AI explanation").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.ask_user_restore)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.ask_user_restore)).performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("Prefer a window seat")
        compose.onNodeWithText(context.getString(R.string.ask_user_submit)).performClick()
        compose.onNodeWithText(context.getString(R.string.ask_user_confirm_selected, "Train")).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.ask_user_confirm_submit)).performClick()
        compose.runOnIdle {
            assertEquals(listOf("Train") to "Prefer a window seat", submitted)
        }
    }
}
