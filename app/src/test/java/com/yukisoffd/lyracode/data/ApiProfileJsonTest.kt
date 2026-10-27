package com.yukisoffd.lyracode.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ApiProfileJsonTest {
    private val profile = ApiProfile("p", "Provider", "main-secret", "https://example.com/v1", chatPath = "/custom-chat",
        selectedModel = "a", savedModels = listOf("a", "b"), enabledModels = listOf("a"),
        secondaryApiKeys = listOf("backup-secret", "main-secret", "", "backup-secret"))
    private fun decode(json: JSONObject) = ApiProfileJson.decode(json, "https://default.example/v1", "default")

    @Test fun encryptedStorageAndSecretBackupRoundTripAllKeysAndCustomPath() {
        val restored = decode(ApiProfileJson.encode(profile))
        assertEquals(listOf("main-secret", "backup-secret"), restored.allApiKeys)
        assertEquals("/custom-chat", restored.chatPath)
        assertEquals(listOf("a"), restored.enabledModels)
    }

    @Test fun backupWithoutSecretsRemovesBothPrimaryAndSecondaryKeys() {
        val json = ApiProfileJson.encode(profile, includeSecrets = false)
        assertFalse(json.toString().contains("main-secret"))
        assertFalse(json.toString().contains("backup-secret"))
        assertTrue(decode(json).allApiKeys.isEmpty())
    }

    @Test fun legacyBackupsRemainCompatible() {
        val restored = decode(JSONObject("""{"id":"old","apiKey":"legacy","savedModels":["a"]}"""))
        assertEquals("legacy", restored.apiKey)
        assertTrue(restored.secondaryApiKeys.isEmpty())
        assertEquals(listOf("a"), restored.enabledModels)
        assertFalse(restored.gzipRequestBodies)
    }

    @Test fun disabledRequestCompressionSurvivesStorageAndBackup() {
        assertFalse(decode(ApiProfileJson.encode(profile.copy(gzipRequestBodies = false), includeSecrets = false)).gzipRequestBodies)
    }

    @Test fun missingCompressionSettingDefaultsToAnthropicOnly() {
        for (format in listOf(ApiProfile.API_FORMAT_OPENAI, ApiProfile.API_FORMAT_ANTHROPIC, ApiProfile.API_FORMAT_GEMINI)) {
            val json = ApiProfileJson.encode(profile.copy(apiFormat = format)).apply { remove("gzipRequestBodies") }
            assertEquals(format == ApiProfile.API_FORMAT_ANTHROPIC, decode(json).gzipRequestBodies)
        }
    }

    @Test fun explicitCompressionChoicesSurviveStorageAndBackupForEveryFormat() {
        for (format in listOf(ApiProfile.API_FORMAT_OPENAI, ApiProfile.API_FORMAT_ANTHROPIC, ApiProfile.API_FORMAT_GEMINI)) {
            for (enabled in listOf(true, false)) {
                assertEquals(enabled, decode(ApiProfileJson.encode(profile.copy(apiFormat = format, gzipRequestBodies = enabled))).gzipRequestBodies)
            }
        }
    }

    @Test fun incompleteModelSyncDoesNotInventASelectedModel() {
        assertEquals("", decode(ApiProfileJson.encode(profile.copy(selectedModel = "", savedModels = emptyList(), enabledModels = emptyList()))).selectedModel)
    }

    @Test fun mergingBackupsKeepsBothOldAndNewPrimaryKeys() {
        val incoming = profile.copy(apiKey = "new-primary", secondaryApiKeys = listOf("new-backup"))
        val merged = ApiProfileJson.merge(profile, incoming)
        assertEquals("new-primary", merged.apiKey)
        assertEquals(listOf("new-primary", "new-backup", "main-secret", "backup-secret"), merged.allApiKeys)
        assertEquals(profile.allApiKeys, ApiProfileJson.merge(profile, decode(ApiProfileJson.encode(profile, false))).allApiKeys)
    }
}
