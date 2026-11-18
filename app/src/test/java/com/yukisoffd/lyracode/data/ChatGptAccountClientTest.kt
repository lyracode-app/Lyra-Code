package com.yukisoffd.lyracode.data

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal class MemoryChatGptStore : AccountStateStore {
    var state = JSONObject()
    @Synchronized override fun <T> transaction(block: (JSONObject) -> T): T {
        val copy = JSONObject(state.toString())
        return block(copy).also { state = JSONObject(copy.toString()) }
    }
}

internal fun chatGptFixtureStore(): MemoryChatGptStore = MemoryChatGptStore().apply {
    state = JSONObject().put("host_id", "urn:uuid:host").put("accounts", JSONObject().put("oaiapp_fixture", JSONObject()
        .put("client_id", "oaiapp_fixture").put("subject", "fixture-user").put("email", "user@example.test")
        .put("access_token", "fixture-access").put("refresh_token", "fixture-refresh").put("id_token", "fixture-id")
        .put("scope", ChatGptProtocol.SCOPES).put("expires_at", System.currentTimeMillis() + 3600_000)))
}

internal fun chatGptHttp(handler: (Request) -> Pair<Int, String>): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
    val (status, body) = handler(chain.request())
    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture").body(body.toResponseBody()).build()
}.build()

class ChatGptAccountClientTest {
    @Test fun registrationExchangesIssuedClientPkceAndValidatedIdentityOnlyOnce() {
        val store = MemoryChatGptStore()
        val attempt = ChatGptSignInAttempt("http://127.0.0.1:45001/auth/callback")
        val callback = attempt.redirectUri.toHttpUrl().newBuilder().addQueryParameter("state", attempt.state).addQueryParameter("code", "code")
            .addQueryParameter("client_id", "oaiapp_fixture").build()
        var exchanges = 0
        val http = chatGptHttp { request ->
            when (request.url.toString()) {
                ChatGptProtocol.JWKS -> 200 to ChatGptTestIdentity.jwks.toString()
                ChatGptProtocol.TOKEN -> {
                    exchanges++
                    val form = request.body as FormBody
                    val params = (0 until form.size).associate { form.name(it) to form.value(it) }
                    assertEquals("oaiapp_fixture", params["client_id"])
                    assertEquals(attempt.verifier, params["code_verifier"])
                    assertEquals(attempt.redirectUri, params["redirect_uri"])
                    assertFalse(params.containsKey("client_secret"))
                    200 to tokens().put("id_token", ChatGptTestIdentity.token(ChatGptTestIdentity.claims(nonce = attempt.nonce))).toString()
                }
                else -> error("Unexpected fixture request")
            }
        }
        val client = ChatGptAccountClient(store, http)
        val host = client.hostId()
        val account = client.completeSignIn(attempt, callback, null)
        assertEquals(host, client.hostId())
        assertEquals("user@example.test", account.email)
        assertEquals("Plus", account.plan)
        assertEquals("fixture-access", client.accessToken(account.clientId))
        assertFalse(account.toString().contains("fixture-access"))
        assertThrows(IllegalArgumentException::class.java) { client.completeSignIn(attempt, callback, null) }
        assertEquals(1, exchanges)
    }

    @Test fun rotatingRefreshIsSerializedAndUsesTheLatestReplacement() {
        val store = chatGptFixtureStore()
        store.state.getJSONObject("accounts").getJSONObject("oaiapp_fixture").put("expires_at", 1)
        val refreshes = AtomicInteger()
        val client = ChatGptAccountClient(store, chatGptHttp { request ->
            assertEquals(ChatGptProtocol.TOKEN, request.url.toString())
            refreshes.incrementAndGet()
            val form = request.body as FormBody
            val params = (0 until form.size).associate { form.name(it) to form.value(it) }
            assertEquals("fixture-refresh", params["refresh_token"])
            assertEquals("oaiapp_fixture", params["client_id"])
            assertEquals(ChatGptProtocol.RESOURCE, params["resource"])
            assertFalse(params.containsKey("scope"))
            200 to tokens().put("access_token", "rotated-access").put("refresh_token", "rotated-refresh").toString()
        })
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = executor.invokeAll((0..7).map { Callable { client.accessToken("oaiapp_fixture") } }).map { it.get() }
            assertTrue(results.all { it == "rotated-access" })
            assertEquals(1, refreshes.get())
            assertEquals("rotated-refresh", store.state.getJSONObject("accounts").getJSONObject("oaiapp_fixture").getString("refresh_token"))
        } finally { executor.shutdownNow() }
    }

    @Test fun permanentRefreshFailureClearsCredentialsButRetainsRegistrationAndHost() {
        val store = chatGptFixtureStore()
        store.state.getJSONObject("accounts").getJSONObject("oaiapp_fixture").put("expires_at", 1)
        val client = ChatGptAccountClient(store, chatGptHttp { 400 to """{"error":"invalid_grant"}""" })
        assertThrows(ChatGptServiceException::class.java) { client.accessToken("oaiapp_fixture") }
        assertFalse(client.accounts().single().isLoggedIn)
        assertEquals("oaiapp_fixture", client.accounts().single().clientId)
        assertEquals("urn:uuid:host", client.hostId())
        assertFalse(store.state.toString().contains("fixture-refresh"))
    }

    @Test fun revokedPlanScopeCommitsRotationAndPreventsInference() {
        val store = chatGptFixtureStore()
        store.state.getJSONObject("accounts").getJSONObject("oaiapp_fixture").put("expires_at", 1)
        var calls = 0
        val client = ChatGptAccountClient(store, chatGptHttp { calls++; 200 to tokens().put("scope", "openid email").toString() })
        assertThrows(ChatGptServiceException::class.java) { client.accessToken("oaiapp_fixture") }
        assertFalse(client.accounts().single().planEnabled)
        assertTrue(client.accounts().single().isLoggedIn)
        assertThrows(ChatGptServiceException::class.java) { client.accessToken("oaiapp_fixture") }
        assertEquals(1, calls)
    }

    @Test fun signOutUsesDiscoveredRevocationEndpointAndClearsOnlyTheSelectedSession() {
        for (revokeStatus in listOf(200, 503)) {
            val store = chatGptFixtureStore()
            store.state.getJSONObject("accounts").put("another", JSONObject(store.state.getJSONObject("accounts").getJSONObject("oaiapp_fixture").toString()).put("client_id", "another"))
            val client = ChatGptAccountClient(store, chatGptHttp { request ->
                if (request.url.toString() == ChatGptProtocol.DISCOVERY) 200 to JSONObject().put("issuer", ChatGptProtocol.ISSUER)
                    .put("revocation_endpoint", "${ChatGptProtocol.ISSUER}/api/accounts/oauth/revoke").toString()
                else {
                    assertEquals("${ChatGptProtocol.ISSUER}/api/accounts/oauth/revoke", request.url.toString())
                    val form = request.body as FormBody
                    assertEquals("fixture-refresh", form.value(0))
                    revokeStatus to ""
                }
            })
            assertEquals(revokeStatus == 200, client.signOut("oaiapp_fixture"))
            assertFalse(client.accounts().first { it.clientId == "oaiapp_fixture" }.isLoggedIn)
            assertTrue(client.accounts().first { it.clientId == "another" }.isLoggedIn)
            assertEquals("urn:uuid:host", client.hostId())
        }
    }

    private fun tokens() = JSONObject().put("access_token", "fixture-access").put("refresh_token", "fixture-refresh")
        .put("token_type", "Bearer").put("expires_in", 3600).put("scope", ChatGptProtocol.SCOPES)
}
