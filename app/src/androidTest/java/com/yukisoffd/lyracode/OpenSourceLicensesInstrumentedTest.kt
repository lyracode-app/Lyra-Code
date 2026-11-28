package com.yukisoffd.lyracode

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OpenSourceLicensesInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun entryOpensOriginalOfflineAndExternalButtonOpensSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val openedUrls = mutableListOf<String>()
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                openedUrls.add(uri)
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                MaterialTheme { OpenSourceLicensesScreen() }
            }
        }

        val dialog = isDialog()
        val externalLabel = context.getString(R.string.license_open_externally, "Lyra Code")
        compose.onNodeWithText("Lyra Code").performClick()
        compose.onNode(dialog).assertExists()
        compose.onNode(hasAnyAncestor(dialog) and hasText("GNU AFFERO GENERAL PUBLIC LICENSE", substring = true))
            .assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.cd_close)).performClick()
        compose.runOnIdle { assertTrue(openedUrls.isEmpty()) }

        compose.onNodeWithContentDescription(externalLabel).performClick()
        compose.onNode(dialog).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("https://github.com/lyracode-app/Lyra-Code/blob/main/LICENSE"), openedUrls)
        }
    }
}
