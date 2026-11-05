package com.yukisoffd.lyracode.data

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

internal interface NewApiAccountStore {
    var state: String
    val supportsRememberedLogin: Boolean
}
internal class NewApiException(val status: Int, message: String) : IOException(message)
internal data class NewApiLoginResult(val flowToken: String? = null)
internal data class NewApiSavedLogin(val username: String, val password: String, val autoLogin: Boolean)
internal data class NewApiSyncResult(val profile: ApiProfile, val warnings: List<String>)

/** Only account endpoints and /v1/models are used here. No local chat/configuration data is accepted. */
internal class NewApiAccountClient(
    private val store: NewApiAccountStore,
    baseUrl: String = PLATFORM_URL,
    transport: OkHttpClient = OkHttpClient(),
) {
    private val origin = baseUrl.trimEnd('/').toHttpUrl()
    private var session = runCatching { JSONObject(store.state) }.getOrDefault(JSONObject())
    @Volatile private var userSnapshot = session.optJSONObject("user")?.toString()
    @Volatile private var loggedInSnapshot = session.optString("access_token").isNotBlank()
    @Volatile private var savedLoginSnapshot = session.optJSONObject("saved_login")?.toString()
    private data class PendingLogin(val flowToken: String, val savedLogin: NewApiSavedLogin?)
    @Volatile private var pendingLogin: PendingLogin? = null
    private val cookies = mutableListOf<Cookie>().apply {
        val saved = session.optJSONArray("cookies") ?: JSONArray()
        for (i in 0 until saved.length()) Cookie.parse(origin, saved.optString(i))?.let(::add)
    }
    private val client = transport.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false) // A lost creation response must not silently repeat a POST.
        .followRedirects(false).followSslRedirects(false)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = synchronized(this@NewApiAccountClient) {
                cookies.forEach { incoming ->
                    this@NewApiAccountClient.cookies.removeAll {
                        it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path
                    }
                    if (incoming.expiresAt > System.currentTimeMillis()) this@NewApiAccountClient.cookies.add(incoming)
                }
                persist()
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(this@NewApiAccountClient) {
                cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
            }
        }).build()

    val isLoggedIn: Boolean get() = loggedInSnapshot
    val canRememberCredentials: Boolean get() = store.supportsRememberedLogin
    // The store uses device-local encrypted preferences, excluded from exports and Android backups.
    val savedLogin: NewApiSavedLogin? get() = savedLoginSnapshot?.takeIf { canRememberCredentials }?.let {
        val data = JSONObject(it)
        val username = data.optString("username")
        val password = data.optString("password")
        if (username.isBlank() || password.isBlank()) null
        else NewApiSavedLogin(username, password, data.optBoolean("auto_login"))
    }
    val user: JSONObject? get() = userSnapshot?.let(::JSONObject)
    val profileId: String get() = "newapi:${origin.host}:${user?.optLong("id") ?: 0}"

    @Synchronized fun sendVerification(email: String) {
        val url = origin.newBuilder().encodedPath("/api/verification").addQueryParameter("email", email.trim()).build()
        exchange(url, "GET")
    }

    @Synchronized fun register(username: String, password: String, email: String, code: String, invitation: String) {
        exchange(url("/api/user/register"), "POST", JSONObject()
            .put("username", username.trim()).put("password", password).put("email", email.trim())
            .put("verification_code", code.trim()).put("aff_code", invitation.trim()))
    }

    @Synchronized fun login(
        username: String,
        password: String,
        rememberCredentials: Boolean = false,
        autoLogin: Boolean = false,
    ): NewApiLoginResult {
        pendingLogin = null
        check(!rememberCredentials || canRememberCredentials) { "Encrypted credential storage is unavailable" }
        val saved = if (rememberCredentials) NewApiSavedLogin(username.trim(), password, autoLogin) else null
        val data = exchange(url("/api/user/login"), "POST",
            JSONObject().put("username", username.trim()).put("password", password)).getJSONObject("data")
        if (data.optBoolean("require_2fa")) {
            val flow = data.optString("flow_token")
            check(flow.isNotBlank()) { "Missing two-factor flow token" }
            pendingLogin = PendingLogin(flow, saved)
            return NewApiLoginResult(flow)
        }
        acceptAuthentication(data, newLogin = true, saved = saved)
        return NewApiLoginResult()
    }

    @Synchronized fun verifyTwoFactor(flowToken: String, code: String) {
        acceptAuthentication(exchange(url("/api/user/login/2fa"), "POST",
            JSONObject().put("flow_token", flowToken).put("code", code.trim())).getJSONObject("data"),
            newLogin = true, saved = pendingLogin?.takeIf { it.flowToken == flowToken }?.savedLogin)
        pendingLogin = null
    }

    fun cancelPendingLogin() { pendingLogin = null }

    private fun saveLogin(saved: NewApiSavedLogin?) {
        writeSavedLogin(saved)
        persist()
    }

    private fun writeSavedLogin(saved: NewApiSavedLogin?) {
        if (saved == null) session.remove("saved_login")
        else session.put("saved_login", JSONObject().put("username", saved.username)
            .put("password", saved.password).put("auto_login", saved.autoLogin))
    }

    @Synchronized fun refreshUser(): JSONObject {
        val current = authenticated("/api/user/self").getJSONObject("data")
        session.put("user", current)
        persist()
        return JSONObject(current.toString())
    }

    @Synchronized fun logout() {
        pendingLogin = null
        val remembered = savedLogin?.copy(autoLogin = false)
        // Disable before the request so an interrupted/manual logout cannot sign the user back in.
        saveLogin(remembered)
        try { exchange(url("/api/user/auth/logout"), "POST", JSONObject()) }
        finally {
            cookies.clear()
            session = JSONObject()
            saveLogin(remembered)
        }
    }

    /** Discover app-created keys remotely, including after logout has cleared all local session data. */
    @Synchronized fun synchronizeService(existing: ApiProfile?, save: (ApiProfile) -> Unit): NewApiSyncResult {
        val account = refreshUser()
        check(account.optLong("id") > 0) { "Platform returned an invalid user ID" }
        val warnings = mutableListOf<String>()
        var tokens = listTokens()
        val rememberedName = session.optString("managed_name")
        val appTokens = tokens.filter {
            val name = it.optString("name")
            (rememberedName.isNotBlank() && name == rememberedName) || APP_TOKEN_NAME.matches(name)
        }
        var managed = appTokens.firstOrNull { it.optString("name") == rememberedName && usableToken(it) }
            ?: appTokens.firstOrNull(::usableToken)
            ?: appTokens.firstOrNull()
        if (managed == null) {
            val managedName = rememberedName.ifBlank { "Lyra-${UUID.randomUUID().toString().take(12)}" }
            // Persist before submitting so a lost creation response can be recovered on the next sync.
            session.put("managed_name", managedName)
            persist()
            authenticated("/api/token/", "POST", JSONObject()
                .put("name", managedName).put("unlimited_quota", true).put("remain_quota", 0)
                .put("expired_time", -1).put("model_limits_enabled", false).put("model_limits", "")
                .put("group", "auto"))
            tokens = listTokens()
            managed = tokens.firstOrNull { it.optString("name") == managedName }
                ?: error("Created API key was not returned by the platform. Retry synchronization.")
        }
        session.put("managed_name", managed.optString("name"))
        persist()
        // Never bypass a disabled/expired app key by silently creating another one.
        val serviceToken = managed.takeIf(::usableToken) ?: tokens.firstOrNull(::usableToken)
            ?: error("The existing app API key is unavailable. Enable it on the platform and sync again.")
        val previousKeys = session.optJSONObject("token_keys") ?: JSONObject()
        val currentKeys = JSONObject()
        fun reveal(token: JSONObject): String {
            val id = token.getLong("id").toString()
            val key = previousKeys.optString(id).ifBlank {
                authenticated("/api/token/$id/key", "POST", JSONObject()).getJSONObject("data").getString("key")
            }.trim()
            check(key.isNotBlank() && !key.contains('*') && !key.contains('…')) { "Platform returned a masked API key" }
            val normalized = if (key.startsWith("sk-")) key else "sk-$key"
            currentKeys.put(id, normalized)
            // Save each revealed key so resuming after rate limiting does not repeat plaintext requests.
            previousKeys.put(id, normalized)
            session.put("token_keys", previousKeys)
            persist()
            return normalized
        }
        val serviceKey = reveal(serviceToken)
        var profile = existing?.copy() ?: ApiProfile(
            id = profileId, name = "$PLATFORM_NAME · ${account.optString("username")}", apiKey = serviceKey,
            baseUrl = "${origin.toString().trimEnd('/')}/v1", selectedModel = "", savedModels = emptyList(),
        )
        // Persist the usable service before optional plaintext/model requests which may be rate limited.
        profile = profile.copy(apiKey = profile.apiKey.ifBlank { serviceKey },
            secondaryApiKeys = (profile.secondaryApiKeys + serviceKey).distinct().filter { it != profile.apiKey.ifBlank { serviceKey } })
        save(profile)
        val oldRemoteKeys = previousKeys.keys().asSequence().map { previousKeys.optString(it) }.toSet()
        var complete = true
        for (token in tokens.filter(::usableToken).filter { it.getLong("id") != serviceToken.getLong("id") }) {
            try { reveal(token) } catch (error: Exception) {
                warnings += error.message.orEmpty()
                complete = false
                break // Respect plaintext endpoint rate limits; the user can resume explicitly.
            }
        }
        val syncedKeys = currentKeys.keys().asSequence().map { currentKeys.getString(it) }.toList()
        val retained = profile.allApiKeys.filter { !complete || it !in oldRemoteKeys || it in syncedKeys }
        val keys = (retained + serviceKey + syncedKeys).distinct()
        val primary = profile.apiKey.takeIf { it in keys } ?: serviceKey
        profile = profile.copy(apiKey = primary, secondaryApiKeys = keys.filter { it != primary })
        if (complete) { session.put("token_keys", currentKeys); persist() }
        try {
            val models = fetchModels(serviceKey)
            val enabled = if (existing == null || existing.savedModels.isEmpty()) models else profile.enabledModels.filter { it in models }
            profile = profile.copy(savedModels = models, enabledModels = enabled,
                selectedModel = profile.selectedModel.takeIf { it in enabled } ?: enabled.firstOrNull().orEmpty())
        } catch (error: Exception) { warnings += error.message.orEmpty() }
        save(profile)
        return NewApiSyncResult(profile, warnings)
    }

    private fun usableToken(token: JSONObject): Boolean = token.optInt("status", 1) == 1 &&
        (token.optLong("expired_time", -1) == -1L || token.optLong("expired_time") > System.currentTimeMillis() / 1000) &&
        (token.optBoolean("unlimited_quota", true) || token.optLong("remain_quota") > 0)

    private fun listTokens(): List<JSONObject> = buildList {
        var page = 1
        while (true) {
            val data = authenticated("/api/token/?p=$page&page_size=100").opt("data")
            val items = when (data) {
                is JSONArray -> data
                is JSONObject -> data.optJSONArray("items") ?: data.optJSONArray("data") ?: JSONArray()
                else -> error("Invalid API key list response")
            }
            for (i in 0 until items.length()) add(items.getJSONObject(i))
            val total = (data as? JSONObject)?.optInt("total", -1) ?: -1
            if (items.length() == 0 || (if (total >= 0) size >= total else items.length() < 100)) break
            check(page < 100) { "Too many API key pages" }
            page++
        }
    }

    private fun fetchModels(key: String): List<String> {
        val request = Request.Builder().url(url("/v1/models")).header("Authorization", "Bearer $key").build()
        // Model calls never carry account refresh cookies or the account access token.
        val modelClient = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        return modelClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw NewApiException(response.code, "Model list: HTTP ${response.code}")
            val data = JSONObject(response.body?.string().orEmpty()).getJSONArray("data")
            (0 until data.length()).map { data.getJSONObject(it).getString("id") }.filter(String::isNotBlank).distinct().sorted()
        }
    }

    private fun acceptAuthentication(data: JSONObject, newLogin: Boolean = false, saved: NewApiSavedLogin? = null) {
        val token = data.getString("access_token")
        check(token.isNotBlank()) { "Missing account access token" }
        val incomingUser = data.optJSONObject("user")
        if (newLogin && (incomingUser == null || incomingUser.optLong("id") != user?.optLong("id"))) {
            session.remove("managed_name")
            session.remove("token_keys")
            session.remove("user")
        }
        session.put("access_token", token).put("access_expires_at", data.optLong("access_expires_at"))
        if (incomingUser != null) session.put("user", incomingUser)
        if (newLogin) writeSavedLogin(saved)
        persist()
    }

    private fun refresh() {
        try { acceptAuthentication(exchange(url("/api/user/auth/refresh"), "POST", JSONObject()).getJSONObject("data")) }
        catch (error: NewApiException) {
            if (error.status in listOf(200, 401, 403)) clearAuthentication()
            throw error
        }
    }

    private fun authenticated(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        var refreshed = false
        var signedInAgain = false
        while (true) {
            if (!isLoggedIn) {
                check(!signedInAgain && savedLogin?.autoLogin == true) { "Please sign in again" }
                signedInAgain = true
                automaticLogin()
            }
            val expiry = session.optLong("access_expires_at")
            if (!refreshed && !signedInAgain && expiry > 0 && expiry <= System.currentTimeMillis() / 1000 + 30) {
                refreshed = true
                try { refresh() }
                catch (error: NewApiException) {
                    if (!isLoggedIn && savedLogin?.autoLogin == true) continue
                    throw error
                }
            }
            try { return exchange(url(path), method, body, session.getString("access_token")) }
            catch (error: NewApiException) {
                if (error.status != 401) throw error
                if (!refreshed && !signedInAgain) {
                    refreshed = true
                    try { refresh() }
                    catch (refreshError: NewApiException) {
                        if (!isLoggedIn && savedLogin?.autoLogin == true) continue
                        throw refreshError
                    }
                } else {
                    clearAuthentication()
                    if (signedInAgain || savedLogin?.autoLogin != true) throw error
                }
            }
        }
    }

    private fun clearAuthentication() {
        session.remove("access_token")
        cookies.clear()
        persist()
    }

    private fun automaticLogin() {
        val saved = checkNotNull(savedLogin?.takeIf { it.autoLogin }) { "Please sign in again" }
        try {
            val result = login(saved.username, saved.password, rememberCredentials = true, autoLogin = true)
            if (result.flowToken != null) {
                pendingLogin = null
                throw NewApiException(401, "Two-factor verification is required. Please sign in again.")
            }
        } catch (error: NewApiException) {
            if (error.status in listOf(200, 401, 403)) {
                clearAuthentication()
                saveLogin(saved.copy(autoLogin = false))
            }
            throw error
        }
    }

    private fun exchange(url: HttpUrl, method: String, body: JSONObject? = null, accessToken: String? = null): JSONObject {
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .apply { if (accessToken != null) header("Authorization", "Bearer $accessToken") }
            .method(method, if (method == "GET") null else (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(request).execute().use { response ->
            val json = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
            if (!response.isSuccessful || json?.optBoolean("success") != true) {
                throw NewApiException(response.code, json?.optString("message")?.takeIf(String::isNotBlank)
                    ?: "$PLATFORM_NAME: HTTP ${response.code}")
            }
            json
        }
    }

    private fun url(path: String): HttpUrl = origin.resolve(path) ?: error("Invalid platform path")
    private fun persist() {
        session.put("cookies", JSONArray(cookies.map(Cookie::toString)))
        store.state = session.toString()
        userSnapshot = session.optJSONObject("user")?.toString()
        loggedInSnapshot = session.optString("access_token").isNotBlank()
        savedLoginSnapshot = session.optJSONObject("saved_login")?.toString()
    }

    companion object {
        private val APP_TOKEN_NAME = Regex("Lyra-[0-9a-f]{8}-[0-9a-f]{3}", RegexOption.IGNORE_CASE)
        const val PLATFORM_URL = "https://api.huanyan.fun"
        const val PLATFORM_NAME = "梦汐 API"
    }
}
