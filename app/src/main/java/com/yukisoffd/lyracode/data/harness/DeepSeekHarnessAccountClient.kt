package com.yukisoffd.lyracode.data

import android.content.Context
import android.os.Build
import com.yukisoffd.lyracode.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.math.BigDecimal
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class HarnessBalanceAlert(val currency: HarnessCurrency, val threshold: BigDecimal, val notified: Boolean = false) {
    fun next(balance: HarnessBalance, notify: (BigDecimal) -> Boolean): HarnessBalanceAlert {
        val amount = balance.recharge[currency] ?: return this
        return when {
            amount >= threshold -> if (notified) copy(notified = false) else this
            !notified && notify(amount) -> copy(notified = true)
            else -> this
        }
    }
}

internal data class DeepSeekHarnessAccountView(
    val profileId: String? = null,
    val user: HarnessUser? = null,
    val balance: HarnessBalance? = null,
    val alert: HarnessBalanceAlert? = null,
)

internal class DeepSeekHarnessAccountClient(
    private val store: AccountStateStore,
    private val http: DeepSeekHarnessHttp,
    private val deviceModel: String,
    private val osVersion: String,
    private val notifyLow: (HarnessBalanceAlert, BigDecimal) -> Boolean = { _, _ -> false },
    private val clearNotice: () -> Unit = {},
) {
    private class Grant(val id: String, val token: String, val deviceId: String)
    private val lock = Any()
    private val balanceLock = Any()
    private val signInLock = Mutex()
    private var grant: Grant?
    private val mutableView: MutableStateFlow<DeepSeekHarnessAccountView>
    init {
        val initial = runCatching { store.transaction { JSONObject(it.toString()) } }.getOrDefault(JSONObject())
        grant = readGrant(initial)
        val alert = initial.optJSONObject("alert")?.takeIf { grant != null }?.let(::readAlert)
        mutableView = MutableStateFlow(DeepSeekHarnessAccountView(profileId = grant?.id, alert = alert))
    }
    val view = mutableView.asStateFlow()
    private var lastBalanceNanos: Long? = null

    suspend fun signIn(darkTheme: Boolean, openBrowser: (String) -> Unit): String = withContext(Dispatchers.IO) {
        check(signInLock.tryLock()) { "DeepSeek Harness sign-in is already running" }
        try {
            check(view.value.profileId == null) { "Sign out of DeepSeek Harness first" }
            signInWithBrowser(darkTheme, openBrowser)
        } finally {
            signInLock.unlock()
        }
    }

    private suspend fun signInWithBrowser(darkTheme: Boolean, openBrowser: (String) -> Unit): String {
        val verifier = randomSecret()
        val state = randomSecret()
        val challenge = base64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val limit = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DeepSeekHarnessProtocol.ATTEMPT_TIMEOUT_MS)
        var authorizationId: String? = null
        var committed = false
        try {
            DeepSeekHarnessCallback().use { callback ->
                val init = http.platform("/auth-api/v0/dsh/auth_init", JSONObject()
                    .put("code_challenge", challenge).put("code_challenge_method", "S256").put("state", state)
                    .put("redirect_uri", callback.redirectUri).put("locale", http.locale())
                    .put("login_source", DeepSeekHarnessProtocol.LOGIN_SOURCE))
                authorizationId = (init.opt("authorize_id") as? String)?.takeIf(String::isNotBlank)
                    ?: throw HarnessInvalidAuthorizationPayload()
                val expiresIn = init.optDouble("expires_in")
                if (!expiresIn.isFinite() || expiresIn <= 0) throw HarnessInvalidAuthorizationPayload()
                val ttlMillis = (expiresIn * 1000.0)
                    .coerceAtMost(DeepSeekHarnessProtocol.ATTEMPT_TIMEOUT_MS.toDouble()).toLong()
                if (ttlMillis <= 0) throw HarnessSignInTimedOut()
                val deadline = minOf(limit, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ttlMillis))
                val authorizeUrl = DeepSeekHarnessProtocol.browserUrl(init.optString("authorize_url"), "/dsh/authorize")
                    .newBuilder().setQueryParameter("theme", if (darkTheme) "dark" else "light").build()
                if (System.nanoTime() >= deadline) throw HarnessSignInTimedOut()
                withContext(Dispatchers.Main) { openBrowser(authorizeUrl.toString()) }
                callback.await(state, deadline).use { browser ->
                    val deviceId = store.transaction { data ->
                        data.optString("device_id").ifBlank { UUID.randomUUID().toString().also { data.put("device_id", it) } }
                    }
                    val result = http.platform("/auth-api/v0/dsh/auth_exchange", JSONObject()
                        .put("code", browser.code).put("code_verifier", verifier).put("redirect_uri", callback.redirectUri)
                        .put("device_id", deviceId).put("device_model", deviceModel).put("os_version", osVersion))
                    val completion = DeepSeekHarnessProtocol.browserUrl(result.optString("authorized_url"), "/dsh/authorized")
                        .newBuilder().setQueryParameter("login_source", DeepSeekHarnessProtocol.LOGIN_SOURCE).build()
                    val token = (result.opt("token") as? String)?.takeIf(::validToken)
                        ?: throw HarnessInvalidAuthorizationPayload()
                    val newGrant = Grant(DeepSeekHarnessProtocol.PROFILE_PREFIX + UUID.randomUUID(), token, deviceId)
                    val user = DeepSeekHarnessProtocol.user(result.optJSONObject("user"))
                    currentCoroutineContext().ensureActive()
                    if (System.nanoTime() >= deadline) throw HarnessSignInTimedOut()
                    synchronized(lock) {
                        store.transaction {
                            it.put("grant", JSONObject().put("id", newGrant.id).put("token", newGrant.token))
                            it.remove("alert")
                        }
                        grant = newGrant
                        lastBalanceNanos = null
                        mutableView.value = DeepSeekHarnessAccountView(profileId = newGrant.id, user = user)
                    }
                    committed = true
                    browser.complete(completion)
                    return newGrant.id
                }
            }
        } finally {
            if (!committed) authorizationId?.let { runCatching { http.cancel(it, verifier) } }
        }
    }

    fun signOut() {
        val removed = synchronized(lock) { grant?.also { clearGrant() } } ?: return
        http.platform("/auth-api/v0/users/logout", token = removed.token)
    }

    fun reject(profileId: String) {
        synchronized(lock) { if (grant?.id == profileId) clearGrant() }
    }

    fun authorize(profileId: String, request: Request): Request {
        DeepSeekHarnessProtocol.requireModelRoute(request)
        val current = requireGrant(profileId)
        return request.newBuilder().removeHeader("Authorization").removeHeader("x-api-key")
            .removeHeader("x-goog-api-key").removeHeader("Cookie")
            .header(DeepSeekHarnessProtocol.TOKEN_HEADER, current.token)
            .header("User-Agent", "LyraCode/${BuildConfig.VERSION_NAME}")
            .apply {
                if (request.method == "POST") {
                    header("anthropic-version", "2023-06-01")
                    if (request.url.toString() == DeepSeekHarnessProtocol.FILES) {
                        header("anthropic-beta", DeepSeekHarnessProtocol.FILES_BETA)
                    } else {
                        header("x-deepseek-harness-user-id", current.deviceId)
                    }
                }
            }.build()
    }

    fun fetchModels(profileId: String): List<String> = authorized(profileId) { http.models(it.token) }

    fun refreshUser(profileId: String) {
        val user = authorized(profileId) {
            DeepSeekHarnessProtocol.user(http.platform("/auth-api/v0/users/current", token = it.token, get = true))
        } ?: return
        synchronized(lock) {
            if (grant?.id == profileId) mutableView.value = mutableView.value.copy(user = user)
        }
    }

    fun refreshBalance(profileId: String, maxAgeMillis: Long = 0) = synchronized(balanceLock) {
        val shouldFetch = synchronized(lock) {
            grant?.id == profileId && (lastBalanceNanos == null ||
                System.nanoTime() - lastBalanceNanos!! >= TimeUnit.MILLISECONDS.toNanos(maxAgeMillis))
        }
        if (!shouldFetch) return@synchronized
        val balance = authorized(profileId) {
            DeepSeekHarnessProtocol.balance(http.platform("/api/v0/users/get_user_summary", token = it.token, get = true))
        }
        synchronized(lock) {
            if (grant?.id == profileId) {
                val current = mutableView.value
                mutableView.value = current.copy(balance = balance)
                applyAlert(balance)
                lastBalanceNanos = System.nanoTime()
            }
        }
    }

    fun configureAlert(profileId: String, currency: HarnessCurrency?, threshold: BigDecimal?) {
        synchronized(lock) {
            requireGrant(profileId)
            val previous = view.value.alert
            val next = if (currency != null && threshold != null) {
                require(threshold.signum() > 0 && threshold.scale() in 0..6 && threshold.precision() <= 15)
                if (previous?.currency == currency && previous.threshold.compareTo(threshold) == 0) previous
                else HarnessBalanceAlert(currency, threshold)
            } else null
            if (next != previous) {
                saveAlert(next)
                clearNotice()
            }
        }
    }

    private fun applyAlert(balance: HarnessBalance) {
        val current = view.value.alert ?: return
        val next = current.next(balance) { notifyLow(current, it) }
        if (next != current) {
            saveAlert(next)
            if (!next.notified) clearNotice()
        }
    }

    private fun saveAlert(alert: HarnessBalanceAlert?) {
        store.transaction { data ->
            if (alert == null) data.remove("alert")
            else data.put("alert", JSONObject().put("currency", alert.currency.name)
                .put("threshold", alert.threshold.toPlainString()).put("notified", alert.notified))
        }
        mutableView.value = mutableView.value.copy(alert = alert)
    }

    private fun clearGrant() {
        store.transaction { it.remove("grant"); it.remove("alert") }
        grant = null
        lastBalanceNanos = null
        mutableView.value = DeepSeekHarnessAccountView()
        clearNotice()
    }

    private fun requireGrant(id: String): Grant = synchronized(lock) {
        grant?.takeIf { it.id == id } ?: throw HarnessAuthExpired()
    }

    private fun <T> authorized(profileId: String, action: (Grant) -> T): T {
        val current = requireGrant(profileId)
        val result = try { action(current) }
        catch (error: HarnessAuthExpired) { reject(current.id); throw error }
        requireGrant(profileId)
        return result
    }

    private fun readGrant(state: JSONObject): Grant? {
        val value = state.optJSONObject("grant") ?: return null
        val id = value.optString("id")
        val token = value.optString("token")
        val device = state.optString("device_id")
        return if (id.startsWith(DeepSeekHarnessProtocol.PROFILE_PREFIX) && validToken(token) && device.isNotBlank()) Grant(id, token, device)
        else null
    }

    private fun readAlert(value: JSONObject): HarnessBalanceAlert? = runCatching {
        val threshold = value.getString("threshold").toBigDecimal()
        require(threshold.signum() > 0 && threshold.scale() in 0..6 && threshold.precision() <= 15)
        HarnessBalanceAlert(HarnessCurrency.valueOf(value.getString("currency")), threshold, value.optBoolean("notified"))
    }.getOrNull()

    companion object {
        @Volatile private var instance: DeepSeekHarnessAccountClient? = null

        fun get(context: Context): DeepSeekHarnessAccountClient = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val notifications = DeepSeekHarnessBalanceNotifications(app)
                DeepSeekHarnessAccountClient(
                    EncryptedAccountStore(app, "deepseek-harness", "lyra_deepseek_harness_v1", "LyraCode.DeepSeekHarness.v1"),
                    DeepSeekHarnessHttp(OkHttpClient(), BuildConfig.VERSION_NAME) {
                        AppSettings(app).languageMode.takeUnless { it == AppSettings.LANGUAGE_SYSTEM }
                            ?: Locale.getDefault().toLanguageTag()
                    },
                    "android-${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}",
                    "android ${Build.VERSION.RELEASE}",
                    notifications::post,
                    notifications::clear,
                ).also { instance = it }
            }
        }

        private fun validToken(token: String): Boolean =
            token.isNotEmpty() && token.all { it in '!'..'~' } && !token.startsWith("dsh_mock_")

        private fun randomSecret(): String = base64(ByteArray(32).also { SecureRandom().nextBytes(it) })

        private fun base64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
