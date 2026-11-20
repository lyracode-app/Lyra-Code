package com.yukisoffd.lyracode.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

internal open class ChatGptServiceException(val errorCode: String, val statusCode: Int? = null, val requestId: String = "", val responseBody: String = "") :
    IllegalStateException("ChatGPT: $errorCode${statusCode?.let { " (HTTP $it)" }.orEmpty()}${requestId.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}")

internal class ChatGptUsageLimitException(code: String, status: Int? = null, requestId: String = "", responseBody: String = "") :
    ChatGptServiceException(code, status, requestId, responseBody)

/** A notice is published only for an exhausted window or a rejected request. */
internal data class ChatGptUsageNotice(val clientId: String, val windowMinutes: Int? = null, val remainingPercent: Double? = null)

internal data class ChatGptUsageWindow(val minutes: Int, val usedPercent: Double, val resetsAt: Long?, val observedAt: Long) {
    fun remaining(now: Long = System.currentTimeMillis()): Double? =
        if (now - observedAt !in 0..300_000 || (resetsAt != null && resetsAt <= now)) null else (100.0 - usedPercent).coerceIn(0.0, 100.0)
    fun json() = JSONObject().put("minutes", minutes).put("used", usedPercent).put("reset", resetsAt).put("observed", observedAt)
}

/** All HTTP methods run on an IO dispatcher; credentials never enter ApiProfile or exported settings. */
internal class ChatGptAccountClient(
    private val store: AccountStateStore,
    private val http: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build(),
) {
    private val modelNames = ConcurrentHashMap<String, Map<String, String>>()
    private val usageAttempts = ConcurrentHashMap<String, Long>()
    private val mutableNotice = MutableStateFlow<ChatGptUsageNotice?>(null)
    val notice = mutableNotice.asStateFlow()
    private val mutableAccountsRevision = MutableStateFlow(0)
    val accountsRevision = mutableAccountsRevision.asStateFlow()
    private val mutableUsageRevision = MutableStateFlow(0)
    val usageRevision = mutableUsageRevision.asStateFlow()

    fun accounts(): List<ChatGptAccount> = store.transaction { root ->
        val values = root.optJSONObject("accounts") ?: return@transaction emptyList()
        values.keys().asSequence().map { ChatGptAccount(JSONObject(values.getJSONObject(it).toString())) }.toList()
    }

    fun hostId(): String = store.transaction { root ->
        root.optString("host_id").ifBlank { "urn:uuid:${UUID.randomUUID()}".also { root.put("host_id", it) } }
    }

    fun completeSignIn(attempt: ChatGptSignInAttempt, callback: HttpUrl, selected: ChatGptAccount?): ChatGptAccount {
        val (clientId, code) = ChatGptProtocol.callback(attempt, callback, System.currentTimeMillis())
        synchronized(attempt) {
            require(!attempt.consumed) { "ChatGPT authorization callback was already used." }
            attempt.consumed = true
        }
        val tokens = tokenRequest(FormBody.Builder().add("grant_type", "authorization_code")
            .add("client_id", clientId).add("code", code).add("code_verifier", attempt.verifier)
            .add("redirect_uri", attempt.redirectUri).apply { if (!attempt.codex) add("resource", ChatGptProtocol.RESOURCE) }.build(),
            if (attempt.codex) ChatGptCodexProtocol.TOKEN else ChatGptProtocol.TOKEN)
        val identity = validate(tokens.getString("id_token"), clientId, if (attempt.codex) null else attempt.nonce)
        val workspace = identity.optJSONObject("https://api.openai.com/auth")?.optString("chatgpt_account_id").orEmpty()
        if (attempt.codex) require(workspace.isNotBlank()) { "ChatGPT did not provide a Codex workspace." }
        val storageId = if (attempt.codex) ChatGptCodexProtocol.storageId(identity.getString("sub"), workspace) else clientId
        if (selected != null) require(identity.getString("sub") == selected.subject && storageId == selected.clientId) {
            "ChatGPT sign-in returned a different account. Add it as a separate account instead."
        }
        return store.transaction { root ->
            val values = root.optJSONObject("accounts") ?: JSONObject().also { root.put("accounts", it) }
            val previous = values.optJSONObject(storageId)
            require(previous == null || previous.optString("subject") == identity.getString("sub"))
            val account = JSONObject().put("client_id", storageId).put("subject", identity.getString("sub"))
                .put("account_id", workspace).put("email", identity.optString("email").ifBlank {
                    identity.optJSONObject("https://api.openai.com/profile")?.optString("email").orEmpty()
                }).put("plan", plan(identity, attempt.codex))
            saveTokens(account, tokens)
            values.put(storageId, account)
            ChatGptAccount(JSONObject(account.toString()))
        }.also { usageAttempts.remove(storageId); mutableAccountsRevision.update { it + 1 } }
    }

    fun accessToken(clientId: String, rejectedAccessToken: String? = null): String = store.transaction { root -> runCatching {
        val saved = root.optJSONObject("accounts")?.optJSONObject(clientId)
            ?: throw ChatGptServiceException("sign_in_required")
        val account = JSONObject(saved.toString())
        requirePermission(account)
        val codex = clientId.startsWith("codex:")
        val rejected = codex && rejectedAccessToken != null && account.optString("access_token") == rejectedAccessToken
        if (rejected || account.optLong("expires_at") <= System.currentTimeMillis() + 60_000) {
            val refresh = account.optString("refresh_token").takeIf(String::isNotBlank)
                ?: throw ChatGptServiceException("sign_in_required")
            if (account.optLong("earliest_refresh_at") > System.currentTimeMillis()) {
                if (account.optLong("expires_at") <= System.currentTimeMillis()) throw ChatGptServiceException("refresh_not_yet_available")
            } else {
                val tokens = try {
                    if (codex) tokenRequest(JSONObject().put("grant_type", "refresh_token").put("client_id", ChatGptCodexProtocol.CLIENT_ID)
                        .put("refresh_token", refresh).toString().toRequestBody("application/json".toMediaType()), ChatGptCodexProtocol.TOKEN)
                    else tokenRequest(FormBody.Builder().add("grant_type", "refresh_token").add("client_id", clientId)
                        .add("refresh_token", refresh).add("resource", ChatGptProtocol.RESOURCE).build())
                } catch (error: ChatGptServiceException) {
                    if (error.errorCode in setOf("invalid_grant", "invalid_client", "unauthorized_client", "refresh_token_expired", "refresh_token_reused", "refresh_token_invalidated")) {
                        listOf("access_token", "refresh_token", "id_token", "scope", "expires_at", "earliest_refresh_at",
                            "usage", "warnings", "usage_checked_at", "usage_limit_reached").forEach(saved::remove)
                        mutableAccountsRevision.update { it + 1 }
                    }
                    throw error
                }
                tokens.optString("id_token").takeIf(String::isNotBlank)?.let { token ->
                    val identity = validate(token, if (codex) ChatGptCodexProtocol.CLIENT_ID else clientId, null)
                    require(identity.getString("sub") == account.getString("subject")) { "ChatGPT account changed during refresh." }
                    if (codex) require(identity.optJSONObject("https://api.openai.com/auth")?.optString("chatgpt_account_id") == account.optString("account_id")) {
                        "ChatGPT workspace changed during refresh."
                    }
                    account.put("email", identity.optString("email", account.optString("email")))
                    account.put("plan", plan(identity, codex).ifBlank { account.optString("plan") })
                }
                // Commit the rotated refresh token, access token, scopes and expiry as one encrypted record.
                saveTokens(account, tokens, refreshing = true)
                root.getJSONObject("accounts").put(clientId, account)
            }
        }
        requirePermission(account)
        account.optString("access_token").takeIf(String::isNotBlank) ?: throw ChatGptServiceException("sign_in_required")
    } }.getOrThrow()

    fun fetchModels(clientId: String): List<ChatGptModel> {
        val request = Request.Builder().url(if (clientId.startsWith("codex:")) ChatGptCodexProtocol.MODELS else "${ChatGptProtocol.RESOURCE}/models").build()
        val models = executeAccountRequest(clientId, request).use { response ->
            observeUsage(clientId, response.headers.toMultimap().mapValues { it.value.last() })
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) fail(clientId, response.code, body, response.header("x-request-id").orEmpty())
            ChatGptProtocol.models(JSONObject(body))
        }
        store.transaction { root ->
            root.getJSONObject("accounts").getJSONObject(clientId).put("models", JSONObject().apply {
                models.forEach { put(it.slug, it.displayName) }
            })
        }
        modelNames[clientId] = models.associate { it.slug to it.displayName }
        return models
    }

    /** Called only for pinned account endpoints; credentials never follow a caller-supplied base URL. */
    fun authorize(clientId: String, request: Request, rejectedAccessToken: String? = null): Request {
        val codex = clientId.startsWith("codex:")
        val expected = if (codex) setOf(ChatGptCodexProtocol.MODELS, ChatGptCodexProtocol.USAGE, "${ChatGptCodexProtocol.BASE}/responses")
            else setOf("${ChatGptProtocol.RESOURCE}/models", "${ChatGptProtocol.RESOURCE}/responses")
        require(request.url.toString() in expected && request.method == (if (request.url.encodedPath.endsWith("/responses")) "POST" else "GET"))
        val token = accessToken(clientId, rejectedAccessToken)
        val account = accounts().firstOrNull { it.clientId == clientId && it.isLoggedIn } ?: throw ChatGptServiceException("sign_in_required")
        return request.newBuilder().header("Authorization", "Bearer $token").removeHeader("ChatGPT-Account-ID").removeHeader("originator")
            .apply { if (codex) { header("ChatGPT-Account-ID", account.accountId); header("originator", "codex_cli_rs") } }.build()
    }

    private fun executeAccountRequest(clientId: String, request: Request): Response {
        val authorized = authorize(clientId, request)
        var response = http.newCall(authorized).execute()
        if (clientId.startsWith("codex:") && response.code == 401) {
            response.close()
            val retry = authorize(clientId, request, authorized.header("Authorization")!!.removePrefix("Bearer "))
            response = http.newCall(retry).execute()
        }
        return response
    }

    fun refreshUsage(clientId: String) {
        if (!clientId.startsWith("codex:")) return
        val updated = executeAccountRequest(clientId, Request.Builder().url(ChatGptCodexProtocol.USAGE).build()).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) fail(clientId, response.code, body, response.header("x-request-id").orEmpty())
            val now = System.currentTimeMillis()
            val snapshot = ChatGptCodexProtocol.usage(JSONObject(body), now)
            val token = response.request.header("Authorization")?.removePrefix("Bearer ")
            store.transaction { root ->
                val account = root.optJSONObject("accounts")?.optJSONObject(clientId) ?: return@transaction false
                // An old request may finish after logout, reauthorization, or account switching.
                if (account.optString("access_token") != token || !ChatGptAccount(account).isLoggedIn) return@transaction false
                val previouslyLimited = account.optBoolean("usage_limit_reached")
                account.put("usage", JSONObject())
                account.put("usage_checked_at", now).put("usage_limit_reached", snapshot.limitReached)
                if (snapshot.planType.isNotBlank()) account.put("plan", ChatGptCodexProtocol.planName(snapshot.planType))
                recordWindows(clientId, account, snapshot.windows, now)
                if (snapshot.limitReached && !previouslyLimited && mutableNotice.value?.clientId != clientId)
                    mutableNotice.value = ChatGptUsageNotice(clientId)
                true
            }
        }
        if (updated) mutableUsageRevision.update { it + 1 }
    }

    /** Best-effort preflight, at most once a minute (20 seconds after a failure). Never guess missing usage. */
    fun refreshUsageIfStale(clientId: String) {
        if (!clientId.startsWith("codex:")) return
        val now = System.currentTimeMillis()
        val lastChecked = store.transaction { it.optJSONObject("accounts")?.optJSONObject(clientId)?.optLong("usage_checked_at") ?: 0 }
        val resetPassed = usage(clientId).any { it.resetsAt != null && it.resetsAt <= now && it.observedAt < it.resetsAt }
        if (!resetPassed && now - lastChecked in 0..59_999) return
        synchronized(usageAttempts) {
            if (now - (usageAttempts[clientId] ?: 0) in 0..19_999) return
            usageAttempts[clientId] = now
        }
        runCatching { refreshUsage(clientId) }
    }

    fun cachedModelNames(clientId: String): Map<String, String> = modelNames[clientId].orEmpty()

    /** Retain identity/client mapping and host ID, even after a local sign-out. */
    fun signOut(clientId: String): Boolean = store.transaction { root ->
        val account = root.optJSONObject("accounts")?.optJSONObject(clientId) ?: return@transaction true
        val refresh = account.optString("refresh_token")
        val revoked = if (refresh.isBlank()) true else runCatching {
            val discovery = getJson(ChatGptProtocol.DISCOVERY)
            require(discovery.getString("issuer") == ChatGptProtocol.ISSUER)
            val endpoint = discovery.getString("revocation_endpoint")
            require(endpoint.startsWith("${ChatGptProtocol.ISSUER}/"))
            http.newCall(Request.Builder().url(endpoint).post(FormBody.Builder().add("token", refresh)
                .add("token_type_hint", "refresh_token").add("client_id", if (clientId.startsWith("codex:")) ChatGptCodexProtocol.CLIENT_ID else clientId).build()).build())
                .execute().use { it.code == 200 }
        }.getOrDefault(false)
        listOf("access_token", "refresh_token", "id_token", "scope", "expires_at", "earliest_refresh_at", "usage", "warnings", "usage_checked_at", "usage_limit_reached").forEach(account::remove)
        modelNames.remove(clientId)
        usageAttempts.remove(clientId)
        if (mutableNotice.value?.clientId == clientId) mutableNotice.value = null
        mutableAccountsRevision.update { it + 1 }
        revoked
    }

    fun usage(clientId: String): List<ChatGptUsageWindow> = store.transaction { root ->
        val usage = root.optJSONObject("accounts")?.optJSONObject(clientId)?.optJSONObject("usage") ?: return@transaction emptyList()
        usage.keys().asSequence().mapNotNull { key ->
            usage.optJSONObject(key)?.let { item -> ChatGptUsageWindow(item.getInt("minutes"), item.getDouble("used"),
                item.optLong("reset").takeIf { it > 0 }, item.getLong("observed")) }
        }.toList()
    }

    /** Optional server headers, when supplied. They are NOT a documented SIWC usage-query API.
     * Missing values remain unknown; never reinterpret token counters as subscription percentages. */
    fun observeUsage(clientId: String, headers: Map<String, String>, now: Long = System.currentTimeMillis()) {
        val windows = chatGptUsageHeaders(headers, now)
        if (windows.isEmpty()) return
        val updated = store.transaction { root ->
            val account = root.optJSONObject("accounts")?.optJSONObject(clientId) ?: return@transaction false
            if (!ChatGptAccount(account).isLoggedIn) return@transaction false
            recordWindows(clientId, account, windows, now)
            true
        }
        if (updated) mutableUsageRevision.update { it + 1 }
    }

    private fun recordWindows(clientId: String, account: JSONObject, windows: List<ChatGptUsageWindow>, now: Long) {
        val usage = account.optJSONObject("usage") ?: JSONObject().also { account.put("usage", it) }
        val warnings = account.optJSONObject("warnings") ?: JSONObject().also { account.put("warnings", it) }
        var candidate: ChatGptUsageNotice? = null
        windows.forEach { window ->
            usage.put(window.minutes.toString(), window.json())
            val remaining = window.remaining(now) ?: return@forEach
            val warningKey = "${window.minutes}:${window.resetsAt ?: "unknown"}"
            if (remaining > 0) warnings.remove(warningKey)
            else if (!warnings.optBoolean(warningKey)) {
                warnings.put(warningKey, true)
                if (candidate == null) candidate = ChatGptUsageNotice(clientId, window.minutes, 0.0)
            }
        }
        candidate?.let { mutableNotice.value = it }
    }

    fun checkUsage(clientId: String) {
        val windows = usage(clientId)
        val exhausted = windows.firstOrNull { it.remaining() == 0.0 }
        val resetPassed = windows.any { it.resetsAt != null && it.resetsAt <= System.currentTimeMillis() && it.observedAt < it.resetsAt }
        val limited = store.transaction { root -> root.optJSONObject("accounts")?.optJSONObject(clientId)?.let {
            !resetPassed && it.optBoolean("usage_limit_reached") && System.currentTimeMillis() - it.optLong("usage_checked_at") in 0..300_000
        } == true }
        if (exhausted == null && !limited) return
        mutableNotice.value = ChatGptUsageNotice(clientId, exhausted?.minutes, exhausted?.let { 0.0 })
        throw ChatGptUsageLimitException("subscription_sharing_usage_limit_exceeded")
    }

    fun dismissNotice() { mutableNotice.value = null }

    fun fail(clientId: String, status: Int?, body: String, requestId: String = ""): Nothing {
        val root = runCatching { JSONObject(body) }.getOrNull()
        val error = root?.optJSONObject("error") ?: root?.optJSONObject("response")?.optJSONObject("error") ?: root
        val code = error?.optString("code").orEmpty().ifBlank { error?.optString("type").orEmpty() }.ifBlank { "request_failed" }
        if (code in setOf("subscription_sharing_usage_limit_exceeded", "usage_limit_reached", "usage_limit_exceeded") || (status == 429 && code == "request_failed")) {
            mutableNotice.value = ChatGptUsageNotice(clientId)
            throw ChatGptUsageLimitException(code, status, requestId, body)
        }
        throw ChatGptServiceException(code, status, requestId, body)
    }

    private fun validate(idToken: String, clientId: String, nonce: String?): JSONObject =
        ChatGptProtocol.validateIdToken(idToken, clientId, nonce, getJson(ChatGptProtocol.JWKS), System.currentTimeMillis() / 1000)

    private fun getJson(url: String): JSONObject = http.newCall(Request.Builder().url(url).build()).execute().use {
        check(it.isSuccessful) { "ChatGPT identity service is unavailable (HTTP ${it.code})." }
        JSONObject(it.body?.string().orEmpty())
    }

    private fun tokenRequest(body: RequestBody, endpoint: String = ChatGptProtocol.TOKEN): JSONObject = http.newCall(Request.Builder().url(endpoint).post(body).build())
        .execute().use {
            val result = runCatching { JSONObject(it.body?.string().orEmpty()) }.getOrDefault(JSONObject())
            if (!it.isSuccessful) throw ChatGptServiceException((result.optJSONObject("error")?.optString("code")
                ?: result.optString("error")).ifBlank { "token_exchange_failed" }, it.code)
            result
        }

    private fun requirePermission(account: JSONObject) {
        if (account.optString("client_id").startsWith("codex:")) {
            if (!ChatGptAccount(account).planEnabled) throw ChatGptServiceException("sign_in_required")
            return
        }
        if (!account.optString("scope").split(' ').contains(ChatGptProtocol.DIRECT_SCOPE)) throw ChatGptServiceException("chatgpt_plan_use_not_enabled")
    }

    private fun saveTokens(account: JSONObject, tokens: JSONObject, refreshing: Boolean = false) {
        if (account.optString("client_id").startsWith("codex:")) {
            val access = tokens.optString("access_token")
            val refresh = tokens.optString("refresh_token").ifBlank { if (refreshing) account.optString("refresh_token") else "" }
            require(access.isNotBlank() && refresh.isNotBlank()) { "ChatGPT returned incomplete Codex credentials." }
            require(tokens.optString("token_type", "Bearer").equals("Bearer", true))
            // Expiry alone can be read from the TLS-authenticated token response's access JWT.
            // Identity and workspace always come from the signature-validated ID token.
            val expiry = if (tokens.has("expires_in")) System.currentTimeMillis() + tokens.getLong("expires_in") * 1000
                else runCatching { JSONObject(String(ChatGptProtocol.decode(access.split('.')[1]), Charsets.UTF_8)).getLong("exp") * 1000 }.getOrDefault(0)
            require(expiry > System.currentTimeMillis() && expiry <= System.currentTimeMillis() + 31L * 86_400_000) { "Invalid ChatGPT credential expiry." }
            account.put("access_token", access).put("refresh_token", refresh).put("expires_at", expiry)
                .put("scope", tokens.optString("scope", ChatGptCodexProtocol.SCOPES))
            tokens.optString("id_token").takeIf(String::isNotBlank)?.let { account.put("id_token", it) }
            return
        }
        val scope = if (tokens.has("scope")) tokens.getString("scope") else if (refreshing) account.optString("scope") else ""
        // A verified identity without direct-plan consent is still a signed-in account.
        if (!scope.split(' ').contains(ChatGptProtocol.DIRECT_SCOPE)) {
            account.put("scope", scope)
            tokens.optString("id_token").takeIf(String::isNotBlank)?.let { account.put("id_token", it) }
            listOf("access_token", "refresh_token", "expires_at", "earliest_refresh_at").forEach(account::remove)
            return
        }
        require(tokens.optString("token_type").equals("Bearer", true)) { "Unsupported ChatGPT token type." }
        require(tokens.optString("access_token").isNotBlank() && tokens.optString("refresh_token").isNotBlank()) { "ChatGPT returned incomplete credentials." }
        val expiresIn = tokens.getLong("expires_in")
        require(expiresIn in 1..86_400)
        account.put("access_token", tokens.getString("access_token")).put("refresh_token", tokens.getString("refresh_token"))
            .put("expires_at", System.currentTimeMillis() + expiresIn * 1000)
        tokens.optString("id_token").takeIf(String::isNotBlank)?.let { account.put("id_token", it) }
        account.put("scope", scope)
        val earliest = tokens.opt("earliest_refresh_at")
        account.put("earliest_refresh_at", when (earliest) {
            is Number -> earliest.toLong() * 1000
            is String -> earliest.toLongOrNull()?.times(1000) ?: runCatching { Instant.parse(earliest).toEpochMilli() }.getOrDefault(0)
            else -> 0
        })
    }

    private fun plan(identity: JSONObject, codex: Boolean = false): String = identity.optJSONObject("https://api.openai.com/auth")?.optString("chatgpt_plan_type")
        .orEmpty().ifBlank { identity.optString("chatgpt_plan_type") }.lowercase().let {
            if (codex) ChatGptCodexProtocol.planName(it) else when (it) { "free" -> "Free"; "go" -> "Go"; "plus" -> "Plus"; "pro" -> "Pro"; else -> "" }
        }
}

internal fun chatGptUsageHeaders(headers: Map<String, String>, now: Long): List<ChatGptUsageWindow> {
    val values = headers.mapKeys { it.key.lowercase() }
    return listOf("primary", "secondary").mapNotNull { slot ->
        val prefix = "x-codex-$slot"
        val minutes = values["$prefix-window-minutes"]?.toIntOrNull() ?: return@mapNotNull null
        val used = values["$prefix-used-percent"]?.toDoubleOrNull() ?: return@mapNotNull null
        if (minutes !in setOf(300, 10080) || !used.isFinite() || used !in 0.0..100.0) return@mapNotNull null
        val reset = values["$prefix-reset-at"]?.toLongOrNull()?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000)
        ChatGptUsageWindow(minutes, used, reset, now)
    }
}
