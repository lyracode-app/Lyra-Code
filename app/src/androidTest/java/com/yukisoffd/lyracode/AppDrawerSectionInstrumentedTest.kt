package com.yukisoffd.lyracode

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class AppDrawerSectionInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sectionTitle = context.getString(R.string.label_functions)

    private fun showSection() {
        compose.setContent {
            MaterialTheme {
                var expanded by rememberSaveable { mutableStateOf(true) }
                DrawerSectionHeader(
                    title = sectionTitle,
                    expanded = expanded,
                    onToggle = { expanded = !expanded },
                )
            }
        }
    }

    @Test
    fun expandedHeaderExposesCollapseActionWithSectionName() {
        showSection()

        compose.onNodeWithContentDescription(
            context.getString(R.string.action_collapse_section, sectionTitle),
        ).assertIsDisplayed()
    }

    @Test
    fun headerExposesClickAction() {
        showSection()

        compose.onNodeWithContentDescription(
            context.getString(R.string.action_collapse_section, sectionTitle),
        ).assertHasClickAction()
    }

    @Test
    fun tappingHeaderCollapsesThenExpandsAndUpdatesActionLabel() {
        showSection()
        val collapseLabel = context.getString(R.string.action_collapse_section, sectionTitle)
        val expandLabel = context.getString(R.string.action_expand_section, sectionTitle)

        compose.onNodeWithContentDescription(collapseLabel).performClick()
        compose.onNodeWithContentDescription(expandLabel).assertIsDisplayed()

        compose.onNodeWithContentDescription(expandLabel).performClick()
        compose.onNodeWithContentDescription(collapseLabel).assertIsDisplayed()
    }

    @Test
    fun historyHeaderUsesItsOwnSectionNameInActionLabel() {
        val historyTitle = context.getString(R.string.label_history_sessions)
        compose.setContent {
            MaterialTheme {
                DrawerSectionHeader(title = historyTitle, expanded = true, onToggle = {})
            }
        }

        compose.onNodeWithContentDescription(
            context.getString(R.string.action_collapse_section, historyTitle),
        ).assertIsDisplayed()
    }
}
