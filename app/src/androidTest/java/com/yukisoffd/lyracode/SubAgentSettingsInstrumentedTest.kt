package com.yukisoffd.lyracode

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.data.AppSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SubAgentSettingsInstrumentedTest {
    private fun withSettings(block: (Context, AppSettings) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "pr60-${UUID.randomUUID()}-"
        val files = File(base.cacheDir, prefix).apply { mkdirs() }
        val names = mutableSetOf<String>()
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                names += prefix + name
                return base.getSharedPreferences(prefix + name, mode)
            }
        }
        try { block(isolated, AppSettings(isolated)) }
        finally {
            names.forEach { base.deleteSharedPreferences(it) }
            files.deleteRecursively()
        }
    }

    @Test fun limitsPersistAndLoweringTaskLimitDoesNotResurrectOldConcurrency() = withSettings { context, settings ->
        assertEquals(6, settings.subAgentMaxTasks)
        assertEquals(1, settings.subAgentMaxConcurrency)
        settings.subAgentMaxTasks = 99
        settings.subAgentMaxConcurrency = 99
        assertTrue(context.getSharedPreferences("lyra_settings", Context.MODE_PRIVATE).edit().commit())
        AppSettings(context).let {
            assertEquals(12, it.subAgentMaxTasks)
            assertEquals(12, it.subAgentMaxConcurrency)
            it.subAgentMaxTasks = 2
            it.subAgentMaxTasks = 10
        }
        AppSettings(context).let {
            assertEquals(10, it.subAgentMaxTasks)
            assertEquals(2, it.subAgentMaxConcurrency)
            it.subAgentMaxTasks = 0
            assertEquals(1, it.subAgentMaxTasks)
            assertEquals(1, it.subAgentMaxConcurrency)
        }
    }

    @Test fun settingsBackupRoundTripClampsLimitsAndLegacyBackupPreservesThem() = withSettings { context, settings ->
        settings.subAgentMaxTasks = 10
        settings.subAgentMaxConcurrency = 3
        val exported = settings.exportSettingsJson(includeSecrets = false)
        assertEquals(10, exported.getInt("subAgentMaxTasks"))
        assertEquals(3, exported.getInt("subAgentMaxConcurrency"))
        settings.subAgentMaxTasks = 2
        settings.importSettingsJson(exported, "replace")
        AppSettings(context).let {
            assertEquals(10, it.subAgentMaxTasks)
            assertEquals(3, it.subAgentMaxConcurrency)
            it.importSettingsJson(JSONObject(), "replace")
            assertEquals(10, it.subAgentMaxTasks)
            assertEquals(3, it.subAgentMaxConcurrency)
            it.importSettingsJson(JSONObject().put("subAgentMaxTasks", 2).put("subAgentMaxConcurrency", 99), "replace")
            assertEquals(2, it.subAgentMaxTasks)
            assertEquals(2, it.subAgentMaxConcurrency)
        }
    }
}
