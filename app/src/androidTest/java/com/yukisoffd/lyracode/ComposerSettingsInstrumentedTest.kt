package com.yukisoffd.lyracode

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.data.AppSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ComposerSettingsInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun show(
        enabled: Boolean = true,
        onEnabledChange: (Boolean) -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                ComposerSettingsSection(
                    enabled = enabled,
                    onEnabledChange = onEnabledChange,
                )
            }
        }
    }

    @Test
    fun defaultsShowTheFeatureOn() {
        show()

        compose.onNodeWithText(context.getString(R.string.composer_delivery_enabled_title))
            .assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNodeWithText(context.getString(R.string.composer_delivery_enabled_desc))
            .assertIsDisplayed()
    }

    @Test
    fun toggleReportsTheNewValue() {
        var changed: Boolean? = null
        show(onEnabledChange = { changed = it })

        compose.onNode(isToggleable()).performClick()

        compose.runOnIdle { assertEquals(false, changed) }
    }

    @Test
    fun featureOffShowsTheExplanation() {
        show(enabled = false)

        compose.onNode(isToggleable()).assertIsOff()
        compose.onNodeWithText(context.getString(R.string.composer_delivery_disabled_hint))
            .assertIsDisplayed()
    }

    @Test
    fun freshSettingsDefaultToEnabled() {
        val prefs = context.getSharedPreferences("lyra_settings", Context.MODE_PRIVATE)
        val hadEnabled = prefs.contains("composer_delivery_enabled")
        val previousEnabled = prefs.getBoolean("composer_delivery_enabled", true)
        try {
            prefs.edit().remove("composer_delivery_enabled").commit()

            val settings = AppSettings(context)

            assertTrue(settings.composerDeliveryEnabled)
        } finally {
            if (hadEnabled) {
                prefs.edit().putBoolean("composer_delivery_enabled", previousEnabled).commit()
            } else {
                prefs.edit().remove("composer_delivery_enabled").commit()
            }
        }
    }

    @Test
    fun settingsBackupCarriesTheComposerSetting() {
        val settings = AppSettings(context)
        val previousEnabled = settings.composerDeliveryEnabled
        try {
            settings.composerDeliveryEnabled = false
            val exported = settings.exportSettingsJson(includeSecrets = false)
            assertFalse(exported.getBoolean("composerDeliveryEnabled"))

            settings.composerDeliveryEnabled = true
            settings.importSettingsJson(
                JSONObject().put("composerDeliveryEnabled", false),
                "replace",
            )

            assertFalse(settings.composerDeliveryEnabled)
        } finally {
            settings.composerDeliveryEnabled = previousEnabled
        }
    }

    @Test
    fun changesPersistInTheAppSettingsStore() {
        val settings = AppSettings(context)
        val previousEnabled = settings.composerDeliveryEnabled
        try {
            settings.composerDeliveryEnabled = true
            compose.setContent {
                MaterialTheme { ComposerSettingsScreen(settings) }
            }

            compose.onNode(isToggleable()).performClick()

            compose.runOnIdle {
                val reloaded = AppSettings(context)
                assertFalse(reloaded.composerDeliveryEnabled)
            }
        } finally {
            settings.composerDeliveryEnabled = previousEnabled
        }
    }
}
