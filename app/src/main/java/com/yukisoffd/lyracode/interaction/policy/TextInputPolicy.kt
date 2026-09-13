package com.yukisoffd.lyracode.interaction.policy

import android.text.InputType
import java.security.MessageDigest

/** Alpha only fills bounded ordinary text, with an exact, single-use foreground confirmation. */
internal object TextInputPolicy {
    const val MAX_TEXT_LENGTH = 500

    fun isValidText(text: String?): Boolean = text != null && text.isNotEmpty() &&
        text.length <= MAX_TEXT_LENGTH && text.none {
            (it.isISOControl() && it != '\n' && it != '\t') ||
                it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069'
        }

    fun isOrdinaryText(inputType: Int): Boolean =
        inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            inputType and InputType.TYPE_MASK_VARIATION in setOf(
                InputType.TYPE_TEXT_VARIATION_NORMAL,
                InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
                InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
                InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,
            )

    fun isSensitive(inputType: Int, vararg labels: String?): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val kind = inputType and InputType.TYPE_MASK_CLASS
        if (kind == InputType.TYPE_CLASS_TEXT && variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) return true
        if (kind == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return true
        return labels.filterNotNull().any { raw ->
            val label = raw.replace('_', ' ').replace('-', ' ').lowercase()
            SENSITIVE_ENGLISH.containsMatchIn(label) || SENSITIVE_CJK.any(label::contains)
        }
    }

    // Compare exact source text after observation, including whitespace and content beyond the
    // display preview's truncation limit. Hashes stay in the transient runtime snapshot.
    fun fingerprint(text: CharSequence?): String = MessageDigest.getInstance("SHA-256")
        .digest((text?.toString().orEmpty()).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private val SENSITIVE_ENGLISH = Regex(
        "password|passcode|verification|security.?code|one.?time|\\botp\\b|\\bpin\\b|credit.?card|card.?number|\\bcvv\\b|\\bcvc\\b|\\bssn\\b|social.?security|passport|identity|api.?key|access.?token|secret|private.?key|seed.?phrase|recovery.?phrase|amount|payment|transfer|wallet|bank.?account|checkout|purchase|\\bpay\\b",
        RegexOption.IGNORE_CASE,
    )
    private val SENSITIVE_CJK = setOf("密码", "密碼", "口令", "验证码", "驗證碼", "校验码", "校驗碼", "银行卡", "銀行卡", "信用卡", "身份证", "身份證", "护照", "護照", "密钥", "金鑰", "私钥", "私鑰", "助记词", "助記詞", "金额", "金額", "支付", "付款", "转账", "轉帳", "钱包", "錢包", "购买", "購買", "结算", "結算")
}
