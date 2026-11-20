package com.yukisoffd.lyracode.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** Public SIWC endpoints only. Never send these credentials to ChatGPT backend-api or a proxy. */
internal object ChatGptProtocol {
    const val ISSUER = "https://auth.openai.com"
    const val RESOURCE = "https://api.openai.com/v1"
    const val AUTHORIZE = "$ISSUER/api/accounts/authorize"
    const val TOKEN = "$ISSUER/api/accounts/oauth/token"
    const val DISCOVERY = "$ISSUER/.well-known/openid-configuration"
    const val JWKS = "$ISSUER/.well-known/jwks.json"
    const val USAGE_URL = "https://chatgpt.com/settings/usage"
    const val DIRECT_SCOPE = "chatgpt.tokens.use.direct"
    const val SCOPES = "openid profile email offline_access resource.invoke $DIRECT_SCOPE"
    const val DYNAMIC_CLIENT = "dynamic_agent_client"

    fun randomValue(): String = encode(ByteArray(32).also(SecureRandom()::nextBytes))
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
    fun challenge(verifier: String): String = encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun authorizationUrl(attempt: ChatGptSignInAttempt, hostId: String, account: ChatGptAccount?): HttpUrl =
        if (attempt.codex) ChatGptCodexProtocol.authorizationUrl(attempt) else AUTHORIZE.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", account?.clientId ?: attempt.clientId.ifBlank { DYNAMIC_CLIENT })
            .apply {
                if (account == null && attempt.clientId.isBlank()) addQueryParameter("agent_name_hint", "Lyra Code")
                if (account != null) {
                    account.idToken.takeIf(String::isNotBlank)?.let { addQueryParameter("id_token_hint", it) }
                    account.email.takeIf(String::isNotBlank)?.let { addQueryParameter("login_hint", it) }
                    if (!account.planEnabled) addQueryParameter("prompt", "consent")
                }
            }
            .addQueryParameter("ext_agent_host_id", hostId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", attempt.redirectUri)
            .addQueryParameter("scope", SCOPES)
            .addQueryParameter("resource", RESOURCE)
            .addQueryParameter("state", attempt.state)
            .addQueryParameter("nonce", attempt.nonce)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", challenge(attempt.verifier))
            .build()

    fun callback(attempt: ChatGptSignInAttempt, url: HttpUrl, now: Long): Pair<String, String> {
        val redirect = attempt.redirectUri.toHttpUrl()
        require(url.scheme == redirect.scheme && url.host == redirect.host && url.port == redirect.port && url.encodedPath == redirect.encodedPath)
        require(now < attempt.expiresAt) { "ChatGPT sign-in expired. Please try again." }
        require(url.queryParameterValues("state").size == 1 &&
            MessageDigest.isEqual(url.queryParameter("state").orEmpty().toByteArray(), attempt.state.toByteArray())) { "ChatGPT sign-in could not be verified." }
        require(url.queryParameter("error") == null) { "ChatGPT authorization was declined or could not be completed." }
        val returned = url.queryParameter("client_id")
        val clientId = if (attempt.codex) {
            require(returned == null || returned == ChatGptCodexProtocol.CLIENT_ID)
            ChatGptCodexProtocol.CLIENT_ID
        } else if (attempt.clientId.isBlank()) returned.orEmpty() else {
            require(returned == null || returned == attempt.clientId) { "ChatGPT registration changed during sign-in." }
            attempt.clientId
        }
        require(clientId.isNotBlank() && clientId != DYNAMIC_CLIENT) { "ChatGPT registration did not return an issued client ID." }
        require(url.queryParameterValues("code").size == 1 && url.queryParameterValues("client_id").size <= 1)
        val code = requireNotNull(url.queryParameter("code")?.takeIf(String::isNotBlank)) { "Missing ChatGPT authorization code." }
        attempt.issuedClientId = clientId
        return clientId to code
    }

    /** Production discovery advertises RS256. Reject algorithm substitution and all unsigned JWTs. */
    fun validateIdToken(token: String, clientId: String, nonce: String?, jwks: JSONObject, nowSeconds: Long): JSONObject {
        val parts = token.split('.')
        require(parts.size == 3) { "Invalid ChatGPT ID token." }
        val header = JSONObject(String(decode(parts[0]), Charsets.UTF_8))
        require(header.optString("alg") == "RS256" && !header.has("crit")) { "Unsupported ChatGPT ID token signature." }
        val keys = jwks.getJSONArray("keys")
        val key = (0 until keys.length()).map { keys.getJSONObject(it) }.firstOrNull {
            it.optString("kid") == header.optString("kid") && it.optString("kty") == "RSA" &&
                it.optString("use", "sig") == "sig" && it.optString("alg", "RS256") == "RS256"
        } ?: error("ChatGPT signing key was not found.")
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(
            BigInteger(1, decode(key.getString("n"))), BigInteger(1, decode(key.getString("e"))),
        ))
        val verified = Signature.getInstance("SHA256withRSA").run {
            initVerify(publicKey)
            update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
            verify(decode(parts[2]))
        }
        require(verified) { "ChatGPT ID token signature did not verify." }
        val claims = JSONObject(String(decode(parts[1]), Charsets.UTF_8))
        val aud = claims.opt("aud")
        val audiences = if (aud is JSONArray) (0 until aud.length()).map(aud::getString) else listOf(aud as? String)
        require(claims.optString("iss") == ISSUER && clientId in audiences) { "ChatGPT ID token issuer or audience did not match." }
        require(audiences.size <= 1 || claims.optString("azp") == clientId)
        require(claims.getLong("exp") > nowSeconds - 5 && claims.getLong("iat") <= nowSeconds + 5 &&
            claims.optLong("nbf", 0) <= nowSeconds + 5 && claims.optString("sub").isNotBlank()) { "ChatGPT ID token is expired or invalid." }
        if (nonce != null) require(claims.optString("nonce") == nonce) { "ChatGPT ID token nonce did not match." }
        return claims
    }

    fun models(root: JSONObject): List<ChatGptModel> {
        val models = root.getJSONArray("models")
        return (0 until models.length()).mapNotNull { index ->
            val item = models.getJSONObject(index)
            val slug = item.optString("slug")
            if (item.optString("visibility") == "list" && slug.isNotBlank())
                ChatGptModel(slug, item.optString("display_name").ifBlank { slug }) else null
        }.distinctBy { it.slug }
    }
}

internal class ChatGptSignInAttempt(val redirectUri: String, val clientId: String = "", now: Long = System.currentTimeMillis(), val codex: Boolean = false) {
    var issuedClientId: String = clientId
        internal set
    internal var consumed = false
    val state = ChatGptProtocol.randomValue()
    val nonce = ChatGptProtocol.randomValue()
    val verifier = ChatGptProtocol.randomValue()
    val expiresAt = now + 5 * 60_000
    override fun toString() = "ChatGptSignInAttempt([redacted])"
}

internal data class ChatGptModel(val slug: String, val displayName: String)

internal fun ApiProfile.withChatGptModels(models: List<String>, names: Map<String, String>): ApiProfile {
    require(isChatGpt)
    val available = models.filter(String::isNotBlank).distinct()
    val enabled = enabledModels.filter { it in available }.ifEmpty { available.take(1) }
    return copy(savedModels = available, enabledModels = enabled,
        selectedModel = selectedModel.takeIf { it in enabled } ?: enabled.firstOrNull().orEmpty(),
        modelDisplayNames = names.filterKeys { it in available })
}

internal class ChatGptAccount(private val value: JSONObject) {
    val clientId: String get() = value.getString("client_id")
    val subject: String get() = value.getString("subject")
    val email: String get() = value.optString("email")
    val plan: String get() = value.optString("plan")
    val isCodex: Boolean get() = clientId.startsWith("codex:")
    internal val accountId: String get() = value.optString("account_id")
    val label: String get() = "${email.ifBlank { "ChatGPT" }} · ${clientId.takeLast(8)}"
    val isLoggedIn: Boolean get() = value.optString("id_token").isNotBlank()
    val planEnabled: Boolean get() = if (isCodex) isLoggedIn && accountId.isNotBlank() && value.optString("access_token").isNotBlank()
        else value.optString("scope").split(' ').contains(ChatGptProtocol.DIRECT_SCOPE)
    fun hasNoFiveHourLimit(windows: List<ChatGptUsageWindow>): Boolean = isLoggedIn && windows.none { it.minutes == 300 } &&
        (plan.startsWith("Pro") || (isCodex && value.optLong("usage_checked_at") > 0 && windows.any { it.minutes == 10080 }))
    internal val idToken: String get() = value.optString("id_token")
    internal fun json(): JSONObject = JSONObject(value.toString())
    override fun toString() = "ChatGptAccount([redacted])"
}

internal data class ChatGptComposerAllowance(val minutes: Int, val remainingPercent: Double?)

internal fun chatGptComposerAllowance(account: ChatGptAccount?, windows: List<ChatGptUsageWindow>, now: Long): ChatGptComposerAllowance {
    val minutes = if (account?.hasNoFiveHourLimit(windows) == true) 10080 else 300
    return ChatGptComposerAllowance(minutes, if (account?.isLoggedIn == true) windows.firstOrNull { it.minutes == minutes }?.remaining(now) else null)
}

/** State changes are serialized, including refresh token rotation, across every app process. */
