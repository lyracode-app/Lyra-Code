package com.yukisoffd.lyracode.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class DeepSeekHarnessAccountClientTest {
    private val profileId = "deepseek-harness:test"
    private val verifierToken = "fixture-authorized-grant"

    private class Store : AccountStateStore {
        private val data = JSONObject()
        override fun <T> transaction(block: (JSONObject) -> T): T = synchronized(data) { block(data) }
    }

    private fun storeWithGrant() = Store().apply {
        transaction {
            it.put("device_id", "dev-fixture").put("grant", JSONObject().put("id", profileId).put("token", verifierToken))
        }
    }

    private fun client(
        store: Store,
        post: (HarnessBalanceAlert, java.math.BigDecimal) -> Boolean = { _, _ -> false },
        clear: () -> Unit = {},
        handle: (Request) -> Response = { error("Unexpected request") },
    ): DeepSeekHarnessAccountClient {
        val transport = OkHttpClient.Builder().addInterceptor { handle(it.request()) }.build()
        return DeepSeekHarnessAccountClient(store, DeepSeekHarnessHttp(transport, "test") { "zh-TW" },
            "android-test", "android test", post, clear)
    }

    private fun payload(data: JSONObject) =
        JSONObject().put("code", 0).put("data", JSONObject().put("biz_code", 0).put("biz_data", data))

    private fun reply(request: Request, value: JSONObject, status: Int = 200) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(status).message("Test")
        .body(value.toString().toResponseBody("application/json".toMediaType())).build()

    private fun Request.json(): JSONObject = JSONObject(Buffer().also { body!!.writeTo(it) }.readUtf8())

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun browserAuthorizationValidatesStateExchangesPkceAndPersistsDedicatedGrant() = runTest {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val store = Store()
            var initial: JSONObject? = null
            var callback: FutureTask<String>? = null
            val client = client(store) { request ->
                assertEquals("platform.deepseek.com", request.url.host)
                assertNull(request.header(DeepSeekHarnessProtocol.TOKEN_HEADER))
                assertEquals("zh_CN", request.header("x-client-locale"))
                assertEquals("LyraCode/test", request.header("User-Agent"))
                assertEquals("application/json", request.body?.contentType().toString())
                when (request.url.encodedPath) {
                    "/auth-api/v0/dsh/auth_init" -> {
                        initial = request.json()
                        assertEquals("S256", initial!!.getString("code_challenge_method"))
                        assertEquals("web", initial!!.getString("login_source"))
                        reply(request, payload(JSONObject().put("authorize_id", "fixture")
                            .put("expires_in", 60).put("authorize_url", "https://platform.deepseek.com/dsh/authorize?keep=1")))
                    }
                    "/auth-api/v0/dsh/auth_exchange" -> {
                        val body = request.json()
                        val digest = MessageDigest.getInstance("SHA-256").digest(body.getString("code_verifier").toByteArray())
                        assertEquals(initial!!.getString("code_challenge"), Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
                        assertEquals(initial!!.getString("redirect_uri"), body.getString("redirect_uri"))
                        assertEquals("fixture-code", body.getString("code"))
                        assertEquals("android-test", body.getString("device_model"))
                        reply(request, payload(JSONObject().put("token", verifierToken)
                            .put("authorized_url", "https://platform.deepseek.com/dsh/authorized?result=ok")
                            .put("user", JSONObject().put("mobile", "138****0000"))))
                    }
                    else -> error("Unexpected request")
                }
            }
            val id = client.signIn(darkTheme = true) { url ->
                assertEquals("dark", url.toHttpUrl().queryParameter("theme"))
                assertEquals("1", url.toHttpUrl().queryParameter("keep"))
                callback = FutureTask {
                    val browser = OkHttpClient.Builder().followRedirects(false).callTimeout(10, TimeUnit.SECONDS).build()
                    val init = initial!!
                    val redirect = init.getString("redirect_uri").toHttpUrl()
                    val validState = init.getString("state")
                    val invalid = redirect.newBuilder().addQueryParameter("state", validState)
                        .addQueryParameter("state", "duplicate").addQueryParameter("code", "rejected").build()
                    browser.newCall(Request.Builder().url(invalid).build()).execute().use { assertEquals(400, it.code) }
                    val valid = redirect.newBuilder().addQueryParameter("state", validState)
                        .addQueryParameter("code", "fixture-code").build()
                    browser.newCall(Request.Builder().url(valid).build()).execute().use {
                        assertEquals(303, it.code)
                        it.header("Location").orEmpty()
                    }
                }.also { Thread(it).start() }
            }
            assertEquals("https://platform.deepseek.com/dsh/authorized?result=ok&login_source=web",
                callback!!.get(10, TimeUnit.SECONDS))
            assertEquals(id, client.view.value.profileId)
            assertEquals("138****0000", client.view.value.user?.contact)
            val resumed = client(store)
            assertEquals(id, resumed.view.value.profileId)
            val modelRequest = Request.Builder().url(DeepSeekHarnessProtocol.MESSAGES)
                .header("Authorization", "Bearer generic-key").header("x-api-key", "generic-key")
                .post("{}".toRequestBody()).build()
            val authorized = resumed.authorize(id, modelRequest)
            assertEquals(verifierToken, authorized.header(DeepSeekHarnessProtocol.TOKEN_HEADER))
            assertNull(authorized.header("Authorization"))
            assertNull(authorized.header("x-api-key"))
            assertTrue(runCatching { resumed.authorize(id, modelRequest.newBuilder().url("https://example.org").build()) }.isFailure)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun alertPersistsAcrossRestartsAndRearmsOnlyAfterRechargeWalletRecovers() {
        val store = storeWithGrant()
        var recharge: String? = "4.99"
        var bonus = "100.0"
        var posts = 0
        var cleared = 0
        var permission = false
        fun newClient() = client(store, post = { alert, amount ->
            assertEquals(HarnessCurrency.CNY, alert.currency)
            assertTrue(amount < alert.threshold)
            if (permission) posts++
            permission
        }, clear = { cleared++ }) { request ->
            assertEquals("/api/v0/users/get_user_summary", request.url.encodedPath)
            assertEquals("GET", request.method)
            assertEquals(verifierToken, request.header(DeepSeekHarnessProtocol.TOKEN_HEADER))
            val wallets = JSONArray().put(JSONObject().put("currency", "USD").put("balance", "900"))
            recharge?.let { wallets.put(JSONObject().put("currency", "CNY").put("balance", it)) }
            reply(request, payload(JSONObject().put("normal_wallets", wallets)
                .put("bonus_wallets", JSONArray().put(JSONObject().put("currency", "CNY").put("balance", bonus)))))
        }
        var client = newClient()
        client.configureAlert(profileId, HarnessCurrency.CNY, "5.00".toBigDecimal())
        client.refreshBalance(profileId)
        assertFalse(client.view.value.alert!!.notified)
        permission = true
        client.refreshBalance(profileId)
        assertEquals(1, posts)
        client = newClient()
        assertTrue(client.view.value.alert!!.notified)
        bonus = "10000000"
        recharge = null
        client.refreshBalance(profileId)
        assertTrue(client.view.value.alert!!.notified)
        recharge = "4.50"
        client.refreshBalance(profileId)
        assertEquals(1, posts)
        recharge = "5.0000000000000000"
        client.refreshBalance(profileId)
        assertFalse(client.view.value.alert!!.notified)
        recharge = "0E-16"
        client.refreshBalance(profileId)
        assertEquals(2, posts)
        assertEquals(2, cleared)
    }

    @Test fun failedBalanceReadDoesNotAlertAndAuthorizationRejectionClearsSession() {
        val store = storeWithGrant()
        var status = 503
        val client = client(store, post = { _, _ -> error("No balance was read") }) { request ->
            reply(request, JSONObject(), status)
        }
        client.configureAlert(profileId, HarnessCurrency.CNY, "5".toBigDecimal())
        assertTrue(runCatching { client.refreshBalance(profileId) }.isFailure)
        assertEquals(profileId, client.view.value.profileId)
        assertFalse(client.view.value.alert!!.notified)
        status = 401
        assertTrue(runCatching { client.refreshBalance(profileId) }.exceptionOrNull() is HarnessAuthExpired)
        assertNull(client.view.value.profileId)
        assertNull(client.view.value.alert)
        assertNull(client(store).view.value.profileId)
    }

    @Test fun blockedInitializationReportsPlatformStatusBeforeOpeningBrowser() = runTest {
        val cases = listOf(
            Triple(429, null, HarnessPlatformFailure.RATE_LIMITED),
            Triple(202, "challenge", HarnessPlatformFailure.BLOCKED),
            Triple(403, null, HarnessPlatformFailure.BLOCKED),
            Triple(200, null, HarnessPlatformFailure.INVALID_RESPONSE),
        )
        for ((status, wafAction, failure) in cases) {
            var requests = 0
            val account = client(Store()) { request ->
                assertEquals("/auth-api/v0/dsh/auth_init", request.url.encodedPath)
                requests++
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Test")
                    .apply { if (wafAction != null) header("x-amzn-waf-action", wafAction) }
                    .body("<html><title>Request Blocked</title></html>".toResponseBody("text/html".toMediaType()))
                    .build()
            }
            val error = runCatching {
                account.signIn(darkTheme = false) { fail("Browser must not open without a valid authorization") }
            }.exceptionOrNull() as HarnessPlatformException
            assertEquals(failure, error.failure)
            assertEquals(status, error.status)
            assertEquals(1, requests)
            assertNull(account.view.value.profileId)
        }
    }
}
