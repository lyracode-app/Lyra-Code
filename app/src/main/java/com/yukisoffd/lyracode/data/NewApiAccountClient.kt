package com.yukisoffd.lyracode.data

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
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
    baseUrl: String? = null,
    transport: OkHttpClient = OkHttpClient(),
) {
    private var session = runCatching { JSONObject(store.state) }.getOrDefault(JSONObject())
    private val storedSite = when {
        session.has("site_url") -> parseSiteUrl(session.optString("site_url"))
        session.length() > 0 -> LEGACY_SITE
        else -> null
    }
    @Volatile private var origin = baseUrl?.let { requireNotNull(parseSiteUrl(it)) } ?: storedSite
    init {
        if (origin == null || origin != storedSite) session = JSONObject()
    }
    @Volatile private var userSnapshot = session.optJSONObject("user")?.toString()
    @Volatile private var loggedInSnapshot = hasSession()
    @Volatile private var savedLoginSnapshot = session.optJSONObject("saved_login")?.toString()
    private data class PendingLogin(
        val flowToken: String,
        val serverToken: String?,
        val savedLogin: NewApiSavedLogin?,
    )
    @Volatile private var pendingLogin: PendingLogin? = null
    private val cookies = mutableListOf<Cookie>().apply {
        val saved = session.optJSONArray("cookies") ?: JSONArray()
        origin?.let { site ->
            for (i in 0 until saved.length()) Cookie.parse(site, saved.optString(i))?.let(::add)
        }
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
    val siteUrl: String? get() = origin?.toString()?.trimEnd('/')
    val profileId: String? get() {
        val site = origin ?: return null
        val userId = user?.optLong("id")?.takeIf { it > 0 } ?: return null
        val siteId = if (site == LEGACY_SITE) site.host else site.toString().trimEnd('/')
        return "newapi:$siteId:$userId"
    }

    @Synchronized fun <T> useSite(site: HttpUrl, action: () -> T): T {
        if (site != origin) {
            check(!isLoggedIn) { "Sign out before changing the New API site" }
            val next = JSONObject().put("site_url", site.toString())
            store.state = next.toString()
            session = next
            pendingLogin = null
            cookies.clear()
            publishSession()
            origin = site
        }
        return action()
    }

    @Synchronized fun sendVerification(email: String) {
        exchange(url("/api/verification").newBuilder().addQueryParameter("email", email.trim()).build(), "GET")
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
        if (!isLoggedIn) cookies.clear()
        val saved = if (rememberCredentials) NewApiSavedLogin(username.trim(), password, autoLogin) else null
        val data = exchange(url("/api/user/login"), "POST",
            JSONObject().put("username", username.trim()).put("password", password)).getJSONObject("data")
        val requiresVerification = data.optBoolean("require_verification")
        if (data.optBoolean("require_2fa") || requiresVerification) {
            val serverToken = data.optString("flow_token").takeIf(String::isNotBlank)
            if (requiresVerification) {
                checkNotNull(serverToken) { "Missing two-factor flow token" }
                val methods = data.optJSONArray("methods")
                if (methods != null && (0 until methods.length()).none { index ->
                    methods.optJSONObject(index)?.let { it.optString("method") == "2fa" && it.optBoolean("available") } == true
                }) {
                    throw NewApiException(200, "Complete the required verification on the New API site.")
                }
            }
            val flow = serverToken ?: UUID.randomUUID().toString()
            pendingLogin = PendingLogin(flow, serverToken, saved)
            return NewApiLoginResult(flow)
        }
        acceptAuthentication(data, newLogin = true, saved = saved)
        return NewApiLoginResult()
    }

    @Synchronized fun verifyTwoFactor(flowToken: String, code: String) {
        val pending = checkNotNull(pendingLogin?.takeIf { it.flowToken == flowToken }) { "Sign in again to request a new code" }
        val body = JSONObject().put("code", code.trim())
        pending.serverToken?.let { body.put("flow_token", it) }
        acceptAuthentication(exchange(url("/api/user/login/2fa"), "POST", body).getJSONObject("data"),
            newLogin = true, saved = pending.savedLogin)
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
        try {
            if (session.optBoolean("legacy_session")) {
                exchange(url("/api/user/logout"), "GET")
            } else {
                exchange(url("/api/user/auth/logout"), "POST", accessToken = session.optString("access_token").takeIf(String::isNotBlank),
                    sessionCookieRequest = true)
            }
        }
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
                val listedKey = token.optString("key").takeIf { session.optBoolean("legacy_session") && isPlaintextKey(it) }
                listedKey ?: try {
                    authenticated("/api/token/$id/key", "POST", JSONObject()).getJSONObject("data").getString("key")
                } catch (error: NewApiException) {
                    if (!session.optBoolean("legacy_session") || error.status !in listOf(404, 405)) throw error
                    authenticated("/api/token/$id").getJSONObject("data").getString("key")
                }
            }.trim()
            check(isPlaintextKey(key)) { "Platform returned a masked API key" }
            val normalized = if (key.startsWith("sk-")) key else "sk-$key"
            currentKeys.put(id, normalized)
            // Save each revealed key so resuming after rate limiting does not repeat plaintext requests.
            previousKeys.put(id, normalized)
            session.put("token_keys", previousKeys)
            persist()
            return normalized
        }
        val serviceKey = reveal(serviceToken)
        val id = checkNotNull(profileId)
        val baseUrl = url("/v1").toString()
        val currentProfile = existing?.takeIf { it.id == id && it.baseUrl.trimEnd('/') == baseUrl }
        val site = checkNotNull(origin)
        val siteName = site.toString().substringAfter("://").trimEnd('/')
        var profile = currentProfile ?: ApiProfile(
            id = id, name = "New API · $siteName · ${account.optString("username")}", apiKey = serviceKey,
            baseUrl = baseUrl, selectedModel = "", savedModels = emptyList(),
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
            val enabled = if (currentProfile == null || currentProfile.savedModels.isEmpty()) models else profile.enabledModels.filter { it in models }
            profile = profile.copy(savedModels = models, enabledModels = enabled,
                selectedModel = profile.selectedModel.takeIf { it in enabled } ?: enabled.firstOrNull().orEmpty())
        } catch (error: Exception) { warnings += error.message.orEmpty() }
        save(profile)
        return NewApiSyncResult(profile, warnings)
    }

    private fun isPlaintextKey(key: String): Boolean = key.isNotBlank() && '*' !in key && '…' !in key

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
        val token = data.optString("access_token").takeIf(String::isNotBlank)
            ?: data.optString("token").takeIf(String::isNotBlank)
        val incomingUser = data.optJSONObject("user") ?: data.takeIf { it.optLong("id") > 0 }
        val legacySession = !data.has("access_token") && incomingUser != null
        check(token != null || (newLogin && legacySession && cookies.isNotEmpty())) { "Platform did not return an account session" }
        if (newLogin && (incomingUser == null || incomingUser.optLong("id") != user?.optLong("id"))) {
            session.remove("managed_name")
            session.remove("token_keys")
            session.remove("user")
        }
        session.put("access_token", token).put("access_expires_at", data.optLong("access_expires_at"))
            .put("legacy_session", legacySession)
            .put("session_id", data.optJSONObject("session")?.optString("sid"))
        if (incomingUser != null) session.put("user", incomingUser)
        if (newLogin) writeSavedLogin(saved)
        persist()
    }

    private fun refresh() {
        try {
            acceptAuthentication(exchange(url("/api/user/auth/refresh"), "POST", sessionCookieRequest = true).getJSONObject("data"))
        }
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
            val legacySession = session.optBoolean("legacy_session")
            try {
                return exchange(url(path), method, body, session.optString("access_token").takeIf(String::isNotBlank),
                    legacyUserId = user?.optLong("id")?.takeIf { legacySession && it > 0 })
            }
            catch (error: NewApiException) {
                if (error.status != 401) throw error
                if (!legacySession && !refreshed && !signedInAgain) {
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
        session.remove("legacy_session")
        session.remove("session_id")
        session.remove("access_expires_at")
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

    private fun exchange(
        url: HttpUrl,
        method: String,
        body: JSONObject? = null,
        accessToken: String? = null,
        legacyUserId: Long? = null,
        sessionCookieRequest: Boolean = false,
    ): JSONObject {
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .apply {
                if (accessToken != null) header("Authorization", "Bearer $accessToken")
                if (legacyUserId != null) header("New-Api-User", legacyUserId.toString())
                if (sessionCookieRequest) {
                    header("Origin", url.newBuilder().encodedPath("/").query(null).build().toString().trimEnd('/'))
                    session.optString("session_id").takeIf(String::isNotBlank)?.let { header("X-Auth-Session", it) }
                }
            }
            .method(method, if (method == "GET") null else (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(request).execute().use { response ->
            val json = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
            if (!response.isSuccessful || json?.optBoolean("success") != true) {
                throw NewApiException(response.code, json?.optString("message")?.takeIf(String::isNotBlank)
                    ?: "New API: HTTP ${response.code}")
            }
            json
        }
    }

    private fun url(path: String): HttpUrl =
        checkNotNull(origin) { "Enter a New API site URL" }.resolve(path.removePrefix("/")) ?: error("Invalid platform path")

    private fun hasSession(): Boolean =
        session.optBoolean("legacy_session") || session.optString("access_token").isNotBlank()

    private fun persist() {
        session.put("site_url", origin?.toString())
        session.put("cookies", JSONArray(cookies.map(Cookie::toString)))
        store.state = session.toString()
        publishSession()
    }

    private fun publishSession() {
        userSnapshot = session.optJSONObject("user")?.toString()
        loggedInSnapshot = hasSession()
        savedLoginSnapshot = session.optJSONObject("saved_login")?.toString()
    }

    companion object {
        private val APP_TOKEN_NAME = Regex("Lyra-[0-9a-f]{8}-[0-9a-f]{3}", RegexOption.IGNORE_CASE)
        private val LEGACY_SITE = "https://api.huanyan.fun/".toHttpUrl()

        fun parseSiteUrl(value: String): HttpUrl? {
            val text = value.trim()
            val uri = runCatching { URI(text) }.getOrNull() ?: return null
            val authority = uri.rawAuthority ?: return null
            if ('@' in authority || uri.rawQuery != null || uri.rawFragment != null) return null
            val url = text.toHttpUrlOrNull() ?: return null
            val path = url.encodedPath.trimEnd('/')
            return url.newBuilder().encodedPath("$path/").build()
        }
    }
}
