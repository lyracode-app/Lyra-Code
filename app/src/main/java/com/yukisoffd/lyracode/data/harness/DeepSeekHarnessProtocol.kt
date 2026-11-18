package com.yukisoffd.lyracode.data

import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.time.ZonedDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit

internal class HarnessAuthExpired : IOException("DeepSeek Harness authorization expired")
internal class HarnessSignInTimedOut : IOException("DeepSeek Harness sign-in timed out")
internal class HarnessInvalidBrowserDestination : IOException("Invalid DeepSeek Harness browser destination")
internal class HarnessInvalidAuthorizationPayload : IOException("Invalid DeepSeek Harness authorization data")

internal enum class HarnessPlatformFailure { RATE_LIMITED, BLOCKED, HTTP_ERROR, INVALID_RESPONSE, REJECTED }

internal class HarnessPlatformException(
    val failure: HarnessPlatformFailure,
    val status: Int,
    val platformCode: Int? = null,
    val businessCode: Int? = null,
) : IOException("DeepSeek Harness ${failure.name} (HTTP $status)")

internal enum class HarnessCurrency { CNY, USD }

internal data class HarnessBalance(
    val recharge: Map<HarnessCurrency, BigDecimal>,
    val bonus: Map<HarnessCurrency, BigDecimal>,
)

internal data class HarnessUser(val name: String, val contact: String)

internal object DeepSeekHarnessProtocol {
    const val NAME = "DeepSeek Harness"
    const val PROFILE_PREFIX = "deepseek-harness:"
    const val TOKEN_HEADER = "x-dsh-auth-token"
    const val PLATFORM = "https://platform.deepseek.com"
    const val INFERENCE = "https://api.deepseek.com"
    const val MODELS = "$INFERENCE/models"
    const val BASE = "$INFERENCE/anthropic/v1"
    const val MESSAGES = "$BASE/messages"
    const val FILES = "$BASE/files"
    const val FILES_BETA = "files-api-2025-04-14"
    const val CALLBACK_PATH = "/oauth/callback"
    const val LOGIN_SOURCE = "web"
    const val ATTEMPT_TIMEOUT_MS = 5 * 60_000L
    val bundledModels = listOf("deepseek-flash", "deepseek-v4-pro")

    fun locale(language: String): String = if (language.startsWith("zh", ignoreCase = true)) "zh_CN" else "en_US"

    fun browserUrl(value: String, path: String): HttpUrl {
        val url = value.toHttpUrlOrNull()
        if (url == null || url.scheme != "https" || url.host != "platform.deepseek.com" || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null || url.encodedPath != path
        ) throw HarnessInvalidBrowserDestination()
        return url
    }

    fun requireModelRoute(request: Request) {
        val url = request.url.toString()
        val allowed = when (request.method) {
            "GET" -> url == MODELS
            "POST" -> url == MESSAGES || url == FILES
            else -> false
        }
        if (!allowed) throw IOException("Invalid DeepSeek Harness model endpoint")
    }

    fun user(value: JSONObject?): HarnessUser? {
        value ?: return null
        return HarnessUser(
            value.optJSONObject("id_profile")?.text("name").orEmpty(),
            value.text("mobile").ifBlank { value.text("mobile_number") }.ifBlank { value.text("email") },
        ).takeUnless { it.name.isBlank() && it.contact.isBlank() }
    }

    fun balance(data: JSONObject): HarnessBalance = HarnessBalance(
        wallets(data.getJSONArray("normal_wallets")),
        wallets(data.getJSONArray("bonus_wallets")),
    )

    private fun wallets(data: JSONArray): Map<HarnessCurrency, BigDecimal> = buildMap {
        for (index in 0 until data.length()) {
            val wallet = data.getJSONObject(index)
            val currency = HarnessCurrency.entries.firstOrNull { it.name == wallet.getString("currency") }
                ?: throw IOException("Unsupported DeepSeek wallet currency")
            val amount = (wallet.get("balance") as? String)?.toBigDecimalOrNull()
                ?: throw IOException("Invalid DeepSeek wallet balance")
            if (amount.scale() !in -100..100 || amount.precision() > 100) throw IOException("Invalid DeepSeek wallet balance")
            put(currency, getOrDefault(currency, BigDecimal.ZERO) + amount)
        }
    }

    private fun JSONObject.text(name: String): String = if (isNull(name)) "" else optString(name)
}

internal class DeepSeekHarnessHttp(
    transport: OkHttpClient,
    private val version: String,
    private val language: () -> String = { Locale.getDefault().toLanguageTag() },
) {
    private val http = transport.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun locale(): String = DeepSeekHarnessProtocol.locale(language())

    fun platform(path: String, body: JSONObject? = null, token: String? = null, get: Boolean = false): JSONObject =
        http.newCall(platformRequest(path, body, token, get)).execute().use { response ->
            val data = readJson(response, authenticated = token != null)
            val envelope = data.optJSONObject("data")
            val code = (data.opt("code") as? Number)?.toInt()
            val businessCode = (envelope?.opt("biz_code") as? Number)?.toInt()
            if (code == null || (code == 0 && businessCode == null)) {
                throw HarnessPlatformException(HarnessPlatformFailure.INVALID_RESPONSE, response.code)
            }
            if (code != 0 || businessCode != 0) {
                throw HarnessPlatformException(HarnessPlatformFailure.REJECTED, response.code, code, businessCode)
            }
            envelope?.optJSONObject("biz_data") ?: JSONObject()
        }

    fun cancel(authorizationId: String, verifier: String) {
        val body = JSONObject().put("authorize_id", authorizationId).put("code_verifier", verifier)
        http.newCall(platformRequest("/auth-api/v0/dsh/auth_cancel", body, null, false)).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) = Unit
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) = response.close()
        })
    }

    fun models(token: String): List<String> {
        val request = Request.Builder().url(DeepSeekHarnessProtocol.MODELS)
            .header(DeepSeekHarnessProtocol.TOKEN_HEADER, token)
            .header("User-Agent", "LyraCode/$version").build()
        return http.newCall(request).execute().use { response ->
            val rows = readJson(response, authenticated = true).getJSONArray("data")
            buildList {
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    val capabilities = row.optJSONObject("api_capabilities")
                    if (capabilities != null && capabilities.optJSONObject("anthropic_messages") == null) continue
                    row.optString("id").takeIf(String::isNotBlank)?.let(::add)
                }
            }.distinct().ifEmpty { throw IOException("DeepSeek Harness returned no compatible models") }
        }
    }

    private fun platformRequest(path: String, body: JSONObject?, token: String?, get: Boolean): Request {
        val builder = Request.Builder().url(DeepSeekHarnessProtocol.PLATFORM + path)
            .header("User-Agent", "LyraCode/$version")
            .header("x-client-bundle-id", "")
            .header("x-client-platform", DeepSeekHarnessProtocol.LOGIN_SOURCE)
            .header("x-client-version", version)
            .header("x-client-locale", locale())
            .header("x-client-timezone-offset", ZonedDateTime.now().offset.totalSeconds.toString())
        if (token != null) builder.header(DeepSeekHarnessProtocol.TOKEN_HEADER, token)
        if (get) builder.get()
        else builder.post((body ?: JSONObject()).toString().toByteArray(Charsets.UTF_8)
            .toRequestBody("application/json".toMediaType()))
        return builder.build()
    }

    private fun readJson(response: Response, authenticated: Boolean): JSONObject {
        val contentType = response.body?.contentType()
        val html = contentType?.type == "text" && contentType.subtype == "html"
        val failure = when {
            response.code == 429 -> HarnessPlatformFailure.RATE_LIMITED
            response.header("x-amzn-waf-action").equals("challenge", ignoreCase = true) ||
                response.header("x-amzn-waf-action").equals("captcha", ignoreCase = true) ||
                (html && response.code in setOf(202, 403)) -> HarnessPlatformFailure.BLOCKED
            else -> null
        }
        if (failure != null) throw HarnessPlatformException(failure, response.code)
        if (authenticated && response.code == 401) throw HarnessAuthExpired()
        if (!response.isSuccessful) throw HarnessPlatformException(HarnessPlatformFailure.HTTP_ERROR, response.code)
        val source = response.body?.source()
        if (html || source == null || source.request(256 * 1024L + 1)) {
            throw HarnessPlatformException(HarnessPlatformFailure.INVALID_RESPONSE, response.code)
        }
        val data = try { JSONObject(source.readUtf8()) }
        catch (_: JSONException) { throw HarnessPlatformException(HarnessPlatformFailure.INVALID_RESPONSE, response.code) }
        if (authenticated && data.optInt("code") == 40003) throw HarnessAuthExpired()
        return data
    }
}
