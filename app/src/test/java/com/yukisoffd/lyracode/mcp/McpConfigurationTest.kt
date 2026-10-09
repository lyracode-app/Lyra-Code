package com.yukisoffd.lyracode.mcp

import com.yukisoffd.lyracode.data.McpServerConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class McpConfigurationTest {
    private fun existing(raw: String = "{}") = McpServerConfig("saved", "Old", "https://old.example/mcp", "old-key", "streamable_http", 30, true, raw, emptyList())

    @Test fun rawUpdatePreservesDocumentAndNeverInheritsOldCredentials() {
        val raw = """{
          "mcpServers": {"special": {"url":"https://new.example/mcp", "headers":{"authorization":"Token EXACT", "X-Custom":"custom"}, "vendor":{"option":true}}},
          "protocolVersion":"2026-07-28", "extension":[1,2,3]
        }"""
        val saved = mcpConfigurationUpdate(existing(), JSONObject().put("raw_json", raw).put("url", "https://ignored.example").put("auth_key", "ignored"))
        assertEquals(raw, saved.rawJson)
        assertEquals("https://new.example/mcp", saved.url)
        assertEquals("Token EXACT", saved.authKey)
        assertEquals("2026-07-28", McpJsonConfig(saved.rawJson).protocolVersion)
        val cleared = mcpConfigurationUpdate(saved, JSONObject().put("raw_json", """{"url":"https://new.example/mcp","headers":{}}"""))
        assertEquals("", cleared.authKey)
        assertTrue(McpJsonConfig(cleared.rawJson).headers.isEmpty())
    }

    @Test fun basicFieldEditLeavesAuthenticationAndExtensionsUntouched() {
        val raw = """{"url":"https://old.example/mcp","headers":{"Authorization":"raw-key","X-API-Key":"another"},"vendor":{"keep":"yes"}}"""
        val saved = mcpConfigurationUpdate(existing(raw), JSONObject().put("url", "https://new.example/mcp"))
        val node = JSONObject(saved.rawJson)
        assertEquals("raw-key", node.getJSONObject("headers").getString("Authorization"))
        assertEquals("yes", node.getJSONObject("vendor").getString("keep"))
        assertFalse(node.has("mcpServers"))
        val cleared = mcpConfigurationUpdate(saved, JSONObject().put("auth_key", ""))
        assertFalse(JSONObject(cleared.rawJson).getJSONObject("headers").has("Authorization"))
        assertEquals("another", McpJsonConfig(cleared.rawJson).headers["X-API-Key"])
    }

    @Test fun nestedJsonObjectIsAcceptedForAiConfiguration() {
        val config = JSONObject("""{"mcpServers":{"service":{"url":"https://example.com/mcp","type":"http","headers":{"Authorization":"key"},"special":false}}}""")
        val saved = mcpConfigurationUpdate(null, JSONObject().put("id", "new").put("config_json", config).put("enabled", false))
        assertEquals(config.toString(), JSONObject(saved.rawJson).toString())
        assertEquals("service", saved.name)
        assertEquals("key", saved.authKey)
        assertFalse(saved.enabled)
    }

    @Test fun invalidJsonAndAmbiguousEndpointAreRejectedWithoutFallback() {
        listOf("{invalid", "{}", """{"url":"https://a.example","baseUrl":"https://b.example"}""",
            """{"mcpServers":{"a":{"url":"https://a.example"},"b":{"url":"https://b.example"}}}""",
            """{"url":"https://a.example","headers":{"Authorization":42}}""",
            """{"url":"https://a.example","type":"stdio","command":"node"}""").forEach { raw ->
            assertTrue(raw, runCatching { mcpConfigurationUpdate(existing(), JSONObject().put("raw_json", raw)) }.isFailure)
        }
    }

    @Test fun nodeProtocolAndCaseInsensitiveHeaderAreReadWithoutRewriting() {
        val parsed = McpJsonConfig("""{"mcpServers":{"server":{"url":"https://example.com","protocolVersion":"2026-07-28","headers":{"mcp-protocol-version":"2026-07-28","AUTHORIZATION":"key-without-prefix"}}}}""")
        assertEquals("2026-07-28", parsed.protocolVersion)
        assertEquals("key-without-prefix", parsed.authKey)
        val auto = McpJsonConfig(parsed.patch("protocolVersion", ""))
        assertEquals("", auto.protocolVersion)
        assertEquals("key-without-prefix", auto.authKey)
    }
}
