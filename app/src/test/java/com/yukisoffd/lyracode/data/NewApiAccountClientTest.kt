package com.yukisoffd.lyracode.data

import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Collections

class NewApiAccountClientTest {
    private class Store : NewApiAccountStore {
        override var state = ""
        override val supportsRememberedLogin = true
    }
    private data class Reply(val body: String, val status: Int = 200, val cookie: String? = null)
    private data class Seen(val path: String, val method: String, val auth: String?, val cookie: String?, val body: String)
    private class Server(val handler: (Seen) -> Reply) : AutoCloseable {
        val requests = Collections.synchronizedList(mutableListOf<Seen>())
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                try {
                    val seen = Seen(exchange.requestURI.toString(), exchange.requestMethod, exchange.requestHeaders.getFirst("Authorization"),
                        exchange.requestHeaders.getFirst("Cookie"), exchange.requestBody.readBytes().toString(Charsets.UTF_8))
                    requests += seen
                    val reply = handler(seen)
                    reply.cookie?.let { exchange.responseHeaders.add("Set-Cookie", it) }
                    val bytes = reply.body.toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } catch (error: Throwable) {
                    failures += error
                    exchange.sendResponseHeaders(500, -1)
                    exchange.close()
                }
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
        override fun close() { server.stop(0); failures.firstOrNull()?.let { throw AssertionError("Server assertion failed", it) } }
    }
    private fun auth(token: String = "access", expiry: Long = System.currentTimeMillis() / 1000 + 3600): String =
        JSONObject().put("success", true).put("data", JSONObject().put("access_token", token).put("access_expires_at", expiry)
            .put("user", JSONObject().put("id", 7).put("username", "tester"))).toString()
    private fun success(data: Any? = null) = JSONObject().put("success", true).apply { if (data != null) put("data", data) }.toString()

    @Test fun rememberedCredentialsSurviveRestartAndAnUnrememberedLoginRemovesThem() {
        Server { Reply(auth()) }.use { server ->
            val store = Store()
            NewApiAccountClient(store, server.url).login(" tester ", "remembered-password", rememberCredentials = true)
            val restored = NewApiAccountClient(store, server.url)
            assertEquals(NewApiSavedLogin("tester", "remembered-password", false), restored.savedLogin)
            restored.login("other", "other-password", autoLogin = true)
            assertNull(NewApiAccountClient(store, server.url).savedLogin)
            assertFalse(store.state.contains("remembered-password"))
            assertFalse(store.state.contains("other-password"))
        }
    }

    @Test fun unavailableEncryptedStorageNeverSavesOrSendsRememberedCredentials() {
        Server { Reply(auth()) }.use { server ->
            val store = object : NewApiAccountStore {
                override var state = ""
                override val supportsRememberedLogin = false
            }
            val client = NewApiAccountClient(store, server.url)
            assertFalse(client.canRememberCredentials)
            assertTrue(runCatching { client.login("u", "secret", true, true) }.isFailure)
            assertTrue(server.requests.isEmpty())
            client.login("u", "secret")
            assertTrue(client.isLoggedIn)
            assertFalse(store.state.contains("secret"))
        }
    }

    @Test fun expiredRefreshAutomaticallySignsInOnceUsingRestoredCredentials() {
        var logins = 0
        Server { request ->
            when (request.path) {
                "/api/user/login" -> {
                    assertEquals("tester", JSONObject(request.body).getString("username"))
                    assertEquals("remembered-password", JSONObject(request.body).getString("password"))
                    logins++
                    Reply(auth(if (logins == 1) "old" else "new", expiry = if (logins == 1) 1 else Long.MAX_VALUE))
                }
                "/api/user/auth/refresh" -> Reply("""{"success":false,"message":"expired"}""", 401)
                "/api/user/self" -> {
                    assertEquals("Bearer new", request.auth)
                    Reply(success(JSONObject().put("id", 7).put("username", "tester")))
                }
                else -> error(request.path)
            }
        }.use { server ->
            val store = Store()
            NewApiAccountClient(store, server.url).login("tester", "remembered-password", true, true)
            val restored = NewApiAccountClient(store, server.url)
            assertEquals(7, restored.refreshUser().getInt("id"))
            assertTrue(restored.isLoggedIn)
            assertTrue(restored.savedLogin!!.autoLogin)
            assertEquals(2, logins)
            assertEquals(1, server.requests.count { it.path == "/api/user/auth/refresh" })
        }
    }

    @Test fun rememberingCredentialsAloneDoesNotAutomaticallySignIn() {
        var logins = 0
        Server { request ->
            if (request.path == "/api/user/login") { logins++; Reply(auth(expiry = 1)) }
            else Reply("""{"success":false,"message":"expired"}""", 401)
        }.use { server ->
            val store = Store()
            val client = NewApiAccountClient(store, server.url)
            client.login("tester", "remembered-password", true, false)
            assertTrue(runCatching { client.refreshUser() }.isFailure)
            assertFalse(client.isLoggedIn)
            assertEquals(1, logins)
            assertEquals("remembered-password", NewApiAccountClient(store, server.url).savedLogin!!.password)
        }
    }

    @Test fun manualLogoutDisablesAutoLoginEvenWhenRemoteLogoutFails() {
        Server { request ->
            if (request.path == "/api/user/login") Reply(auth())
            else Reply("""{"success":false,"message":"unavailable"}""", 503)
        }.use { server ->
            val store = Store()
            val client = NewApiAccountClient(store, server.url)
            client.login("tester", "remembered-password", true, true)
            assertTrue(runCatching { client.logout() }.isFailure)
            val restored = NewApiAccountClient(store, server.url)
            assertFalse(restored.isLoggedIn)
            assertNull(restored.user)
            assertEquals(NewApiSavedLogin("tester", "remembered-password", false), restored.savedLogin)
            val requestCount = server.requests.size
            assertTrue(runCatching { restored.refreshUser() }.isFailure)
            assertEquals(requestCount, server.requests.size)
        }
    }

    @Test fun twoFactorCredentialsAreOnlyRememberedAfterSuccessfulVerification() {
        Server { request ->
            if (request.path == "/api/user/login") Reply(success(JSONObject().put("require_2fa", true).put("flow_token", "flow")))
            else Reply(auth())
        }.use { server ->
            val store = Store()
            val client = NewApiAccountClient(store, server.url)
            val result = client.login("tester", "remembered-password", true, true)
            assertNull(client.savedLogin)
            assertFalse(store.state.contains("remembered-password"))
            client.verifyTwoFactor(result.flowToken!!, "123456")
            assertEquals(NewApiSavedLogin("tester", "remembered-password", true), NewApiAccountClient(store, server.url).savedLogin)
        }
    }

    @Test fun autoLoginRequiringTwoFactorOrRejectingCredentialsStopsWithoutLooping() {
        for (twoFactor in listOf(false, true)) {
            var logins = 0
            Server { request ->
                when (request.path) {
                    "/api/user/login" -> {
                        logins++
                        if (logins == 1) Reply(auth(expiry = 1))
                        else if (twoFactor) Reply(success(JSONObject().put("require_2fa", true).put("flow_token", "flow")))
                        else Reply("""{"success":false,"message":"invalid credentials"}""")
                    }
                    "/api/user/auth/refresh" -> Reply("""{"success":false,"message":"expired"}""", 401)
                    else -> error(request.path)
                }
            }.use { server ->
                val store = Store()
                val client = NewApiAccountClient(store, server.url)
                client.login("tester", "remembered-password", true, true)
                assertTrue(runCatching { client.refreshUser() }.isFailure)
                val restored = NewApiAccountClient(store, server.url)
                assertFalse(restored.isLoggedIn)
                assertFalse(restored.savedLogin!!.autoLogin)
                assertTrue(runCatching { restored.refreshUser() }.isFailure)
                assertEquals(2, logins)
            }
        }
    }

    @Test fun temporaryRefreshFailuresDoNotResubmitCredentials() {
        for (status in listOf(429, 503)) {
            Server { request ->
                if (request.path == "/api/user/login") Reply(auth(expiry = 1))
                else Reply("""{"success":false,"message":"try later"}""", status)
            }.use { server ->
                val client = NewApiAccountClient(Store(), server.url)
                client.login("tester", "remembered-password", true, true)
                assertTrue(runCatching { client.refreshUser() }.isFailure)
                assertTrue(client.isLoggedIn)
                assertTrue(client.savedLogin!!.autoLogin)
                assertEquals(1, server.requests.count { it.path == "/api/user/login" })
            }
        }
    }

    @Test fun cookiesSurviveRestartAndRotateWhenAccessExpires() {
        val store = Store()
        Server { request ->
            when (request.path) {
                "/api/user/login" -> Reply(auth(expiry = 1), cookie = "refresh=first; Path=/api; HttpOnly; Max-Age=3600")
                "/api/user/auth/refresh" -> {
                    assertEquals("refresh=first", request.cookie)
                    assertNull(request.auth)
                    Reply(auth("renewed"), cookie = "refresh=rotated; Path=/api; HttpOnly; Max-Age=3600")
                }
                "/api/user/self" -> { assertEquals("Bearer renewed", request.auth); Reply(success(JSONObject().put("id", 7))) }
                "/api/user/auth/logout" -> { assertEquals("refresh=rotated", request.cookie); Reply(success()) }
                else -> error(request.path)
            }
        }.use { server ->
            NewApiAccountClient(store, server.url).login("tester", "password")
            val restored = NewApiAccountClient(store, server.url)
            restored.refreshUser()
            assertTrue(store.state.contains("refresh=rotated"))
            restored.logout()
            assertFalse(restored.isLoggedIn)
            assertNull(restored.user)
            assertFalse(store.state.contains("rotated"))
            assertFalse(store.state.contains("password"))
        }
    }

    @Test fun registrationDoesNotFilterEmailDomainsOrAliasesAndDoesNotPretendToLogIn() {
        Server { Reply(success()) }.use { server ->
            val client = NewApiAccountClient(Store(), server.url)
            client.sendVerification("name+alias@example.org")
            client.register(" tester ", "password", "name+alias@example.org", "123456", "invite")
            assertTrue(server.requests.first().path.contains("name%2Balias%40example.org"))
            val body = JSONObject(server.requests.last().body)
            assertEquals("name+alias@example.org", body.getString("email"))
            assertEquals("tester", body.getString("username"))
            assertEquals("invite", body.getString("aff_code"))
            assertFalse(client.isLoggedIn)
        }
    }

    @Test fun twoFactorFlowStaysLoggedOutUntilVerified() {
        Server { request ->
            if (request.path == "/api/user/login") Reply(success(JSONObject().put("require_2fa", true).put("flow_token", "flow")))
            else {
                assertEquals("/api/user/login/2fa", request.path)
                assertEquals("flow", JSONObject(request.body).getString("flow_token"))
                Reply(auth(), cookie = "refresh=second; Path=/api; HttpOnly")
            }
        }.use { server ->
            val client = NewApiAccountClient(Store(), server.url)
            val flow = client.login("tester", "password")
            assertFalse(client.isLoggedIn)
            client.verifyTwoFactor(flow.flowToken!!, "123456")
            assertTrue(client.isLoggedIn)
        }
    }

    @Test fun http200BusinessFailureAndRateLimitAreNotRetried() {
        for (status in listOf(200, 429)) {
            Server { Reply("""{"success":false,"message":"platform failure"}""", status) }.use { server ->
                val failure = runCatching { NewApiAccountClient(Store(), server.url).login("u", "p") }.exceptionOrNull()
                assertTrue(failure is NewApiException)
                assertEquals("platform failure", failure!!.message)
                assertEquals(1, server.requests.size)
            }
        }
    }

    @Test fun synchronizationCreatesOneUnlimitedAutoGroupKeyAndImportsModelsAndOtherKeys() {
        var created: JSONObject? = null
        val store = Store()
        Server { request ->
            when {
                request.path == "/api/user/login" -> Reply(auth(), cookie = "refresh=local; Path=/; HttpOnly")
                request.path == "/api/user/auth/logout" -> Reply(success())
                request.path == "/api/user/self" -> Reply(success(JSONObject().put("id", 7).put("username", "tester").put("quota", 500000)))
                request.path.startsWith("/api/token/?") -> {
                    val items = JSONArray().put(JSONObject().put("id", 10).put("name", "existing").put("key", "sk-***"))
                    created?.let { items.put(JSONObject(it.toString()).put("id", 11)) }
                    Reply(success(JSONObject().put("items", items).put("total", items.length())))
                }
                request.path == "/api/token/" -> { created = JSONObject(request.body); Reply(success()) }
                request.path == "/api/token/11/key" -> Reply(success(JSONObject().put("key", "generated")))
                request.path == "/api/token/10/key" -> Reply(success(JSONObject().put("key", "sk-other")))
                request.path == "/v1/models" -> {
                    assertEquals("Bearer sk-generated", request.auth)
                    assertNull(request.cookie)
                    assertEquals("GET", request.method)
                    assertEquals("", request.body)
                    Reply("""{"data":[{"id":"model-b"},{"id":"model-a"}]}""")
                }
                else -> error(request.path)
            }
        }.use { server ->
            val client = NewApiAccountClient(store, server.url)
            client.login("tester", "password")
            var saved: ApiProfile? = null
            val sync = client.synchronizeService(null) { saved = it }
            assertEquals(listOf("sk-generated", "sk-other"), sync.profile.allApiKeys)
            assertEquals(listOf("model-a", "model-b"), saved!!.enabledModels)
            assertEquals("auto", created!!.getString("group"))
            assertTrue(created!!.getBoolean("unlimited_quota"))
            assertFalse(created!!.getBoolean("model_limits_enabled"))
            assertEquals(-1, created!!.getInt("expired_time"))
            val restarted = NewApiAccountClient(store, server.url)
            restarted.synchronizeService(saved) { saved = it }
            assertEquals(1, server.requests.count { it.path == "/api/token/" })
            assertEquals(2, server.requests.count { it.path.endsWith("/key") })
            assertTrue(server.requests.filter { it.path.startsWith("/api/token/") }.all { it.auth == "Bearer access" })
            assertFalse(server.requests.any { it.path.contains("chat") || it.path.contains("config") })

            // Logout must still clear credentials; deduplication cannot depend on retaining the session.
            var signedIn = restarted
            repeat(2) {
                signedIn.logout()
                assertFalse(store.state.contains("managed_name"))
                assertFalse(store.state.contains("token_keys"))
                signedIn = NewApiAccountClient(store, server.url)
                signedIn.login("tester", "password")
                val result = signedIn.synchronizeService(saved) { saved = it }
                assertEquals(listOf("sk-generated", "sk-other"), result.profile.allApiKeys)
                assertEquals(listOf("model-a", "model-b"), result.profile.savedModels)
                assertEquals(1, server.requests.count { request -> request.path == "/api/token/" })
            }
        }
    }

    @Test fun freshInstallReusesAnExistingAppKeyAndSkipsAnUnavailableOrSimilarlyNamedKey() {
        Server { request ->
            when {
                request.path == "/api/user/login" -> Reply(auth())
                request.path == "/api/user/self" -> Reply(success(JSONObject().put("id", 7).put("username", "tester")))
                request.path.startsWith("/api/token/?") -> Reply(success(JSONArray()
                    .put(JSONObject().put("id", 10).put("name", "Lyra-ffffffff-fff").put("status", 2))
                    .put(JSONObject().put("id", 11).put("name", "Lyra-personal"))
                    .put(JSONObject().put("id", 12).put("name", "Lyra-12345678-abc"))))
                request.path == "/api/token/12/key" -> Reply(success(JSONObject().put("key", "sk-managed")))
                request.path == "/api/token/11/key" -> Reply(success(JSONObject().put("key", "sk-personal")))
                request.path == "/v1/models" -> {
                    assertEquals("Bearer sk-managed", request.auth)
                    Reply("""{"data":[{"id":"a"}]}""")
                }
                else -> error("Unexpected request: ${request.method} ${request.path}")
            }
        }.use { server ->
            val store = Store()
            val client = NewApiAccountClient(store, server.url)
            client.login("u", "p")
            val result = client.synchronizeService(null) {}
            assertEquals(listOf("sk-managed", "sk-personal"), result.profile.allApiKeys)
            assertEquals("Lyra-12345678-abc", JSONObject(store.state).getString("managed_name"))
            assertFalse(server.requests.any { it.method == "POST" && it.path == "/api/token/" })
        }
    }

    @Test fun appKeyOnALaterPageIsReusedBeforeAnyCreation() {
        Server { request ->
            when {
                request.path == "/api/user/login" -> Reply(auth())
                request.path == "/api/user/self" -> Reply(success(JSONObject().put("id", 7).put("username", "tester")))
                request.path.startsWith("/api/token/?p=1&") -> Reply(success(JSONObject().put("total", 101).put("items", JSONArray().apply {
                    repeat(100) { put(JSONObject().put("id", it + 1).put("name", "personal-$it").put("status", 2)) }
                })))
                request.path.startsWith("/api/token/?p=2&") -> Reply(success(JSONObject().put("total", 101).put("items", JSONArray()
                    .put(JSONObject().put("id", 101).put("name", "Lyra-12345678-abc")))))
                request.path == "/api/token/101/key" -> Reply(success(JSONObject().put("key", "sk-existing")))
                request.path == "/v1/models" -> Reply("""{"data":[{"id":"a"}]}""")
                else -> error("Unexpected request: ${request.method} ${request.path}")
            }
        }.use { server ->
            val client = NewApiAccountClient(Store(), server.url)
            client.login("u", "p")
            assertEquals("sk-existing", client.synchronizeService(null) {}.profile.apiKey)
            assertEquals(2, server.requests.count { it.path.startsWith("/api/token/?") })
            assertFalse(server.requests.any { it.method == "POST" && it.path == "/api/token/" })
        }
    }

    @Test fun unavailableAppKeysDoNotCauseReplacementKeysToAccumulate() {
        val unavailable = listOf(
            JSONObject().put("status", 2),
            JSONObject().put("expired_time", 1),
            JSONObject().put("unlimited_quota", false).put("remain_quota", 0),
        )
        for (state in unavailable) for (hasOtherKey in listOf(false, true)) {
            Server { request ->
                when {
                    request.path == "/api/user/login" -> Reply(auth())
                    request.path == "/api/user/self" -> Reply(success(JSONObject().put("id", 7).put("username", "tester")))
                    request.path.startsWith("/api/token/?") -> Reply(success(JSONArray()
                        .put(JSONObject(state.toString()).put("id", 10).put("name", "Lyra-12345678-abc"))
                        .apply { if (hasOtherKey) put(JSONObject().put("id", 11).put("name", "personal")) }))
                    request.path == "/api/token/11/key" -> Reply(success(JSONObject().put("key", "sk-personal")))
                    request.path == "/v1/models" -> Reply("""{"data":[{"id":"a"}]}""")
                    else -> error("Unexpected request: ${request.method} ${request.path}")
                }
            }.use { server ->
                val client = NewApiAccountClient(Store(), server.url)
                client.login("u", "p")
                if (hasOtherKey) {
                    assertEquals("sk-personal", client.synchronizeService(null) {}.profile.apiKey)
                } else {
                    assertTrue(runCatching { client.synchronizeService(null) {} }.exceptionOrNull() is IllegalStateException)
                }
                assertFalse(server.requests.any { it.method == "POST" && it.path == "/api/token/" })
            }
        }
    }

    @Test fun unauthorizedRequestRefreshesOnlyOnce() {
        var selfCalls = 0
        Server { request ->
            when (request.path) {
                "/api/user/login" -> Reply(auth(), cookie = "refresh=valid; Path=/api")
                "/api/user/auth/refresh" -> Reply(auth("new-token"))
                "/api/user/self" -> { selfCalls++; Reply("""{"success":false,"message":"unauthorized"}""", 401) }
                else -> error(request.path)
            }
        }.use { server ->
            val client = NewApiAccountClient(Store(), server.url)
            client.login("u", "p")
            assertTrue(runCatching { client.refreshUser() }.exceptionOrNull() is NewApiException)
            assertEquals(2, selfCalls)
            assertEquals(1, server.requests.count { it.path == "/api/user/auth/refresh" })
            assertFalse(client.isLoggedIn)
        }
    }

    @Test fun rejectedRefreshClearsTheSessionAndDoesNotLoop() {
        Server { request ->
            if (request.path == "/api/user/login") Reply(auth(expiry = 1), cookie = "refresh=expired; Path=/api")
            else Reply("""{"success":false,"message":"expired session"}""", 401)
        }.use { server ->
            val client = NewApiAccountClient(Store(), server.url)
            client.login("u", "p")
            assertTrue(runCatching { client.refreshUser() }.exceptionOrNull() is NewApiException)
            assertFalse(client.isLoggedIn)
            assertEquals(1, server.requests.count { it.path == "/api/user/auth/refresh" })
        }
    }

    @Test fun pendingProvisioningRecoversAfterRestartWithoutCreatingAnotherKey() {
        var created: JSONObject? = null
        var interruptList = true
        Server { request ->
            when {
                request.path == "/api/user/login" -> Reply(auth())
                request.path == "/api/user/self" -> Reply(success(JSONObject().put("id", 7).put("username", "tester")))
                request.path == "/api/token/" -> { created = JSONObject(request.body); Reply(success()) }
                request.path.startsWith("/api/token/?") -> {
                    if (created != null && interruptList) {
                        interruptList = false
                        Reply("""{"success":false,"message":"temporary failure"}""", 503)
                    } else Reply(success(JSONArray().apply { created?.let { put(JSONObject(it.toString()).put("id", 11)) } }))
                }
                request.path == "/api/token/11/key" -> Reply(success(JSONObject().put("key", "sk-created")))
                request.path == "/v1/models" -> Reply("""{"data":[{"id":"a"}]}""")
                else -> error(request.path)
            }
        }.use { server ->
            val store = Store()
            val client = NewApiAccountClient(store, server.url)
            client.login("u", "p")
            assertTrue(runCatching { client.synchronizeService(null) {} }.exceptionOrNull() is NewApiException)
            assertTrue(client.isLoggedIn)
            val restored = NewApiAccountClient(store, server.url)
            assertEquals("sk-created", restored.synchronizeService(null) {}.profile.apiKey)
            assertEquals(1, server.requests.count { it.path == "/api/token/" })
        }
    }

    @Test fun plaintextRateLimitKeepsTheUsableServiceAndCanResumeWithNoDuplicateKeys() {
        var created: JSONObject? = null
        var rateLimit = true
        Server { request ->
            when {
                request.path == "/api/user/login" -> Reply(auth())
                request.path == "/api/user/self" -> Reply(success(JSONObject().put("id", 7).put("username", "tester")))
                request.path == "/api/token/" -> { created = JSONObject(request.body); Reply(success()) }
                request.path.startsWith("/api/token/?") -> Reply(success(JSONArray()
                    .put(JSONObject().put("id", 10).put("name", "other"))
                    .apply { created?.let { put(JSONObject(it.toString()).put("id", 11)) } }))
                request.path == "/api/token/11/key" -> Reply(success(JSONObject().put("key", "sk-created")))
                request.path == "/api/token/10/key" -> if (rateLimit) Reply("""{"success":false,"message":"rate limited"}""", 429)
                    else Reply(success(JSONObject().put("key", "sk-other")))
                request.path == "/v1/models" -> Reply("""{"data":[{"id":"a"}]}""")
                else -> error(request.path)
            }
        }.use { server ->
            val client = NewApiAccountClient(Store(), server.url)
            client.login("u", "p")
            var saved: ApiProfile? = null
            val first = client.synchronizeService(null) { saved = it }
            assertEquals(listOf("sk-created"), saved!!.allApiKeys)
            assertEquals(listOf("a"), saved!!.enabledModels)
            assertEquals(listOf("rate limited"), first.warnings)
            assertEquals(1, server.requests.count { it.path == "/api/token/10/key" })
            rateLimit = false
            val second = client.synchronizeService(saved) { saved = it }
            assertTrue(second.warnings.isEmpty())
            assertEquals(listOf("sk-created", "sk-other"), saved!!.allApiKeys)
            assertEquals(1, server.requests.count { it.path == "/api/token/11/key" })
            assertEquals(1, server.requests.count { it.path == "/api/token/" })
        }
    }
}
