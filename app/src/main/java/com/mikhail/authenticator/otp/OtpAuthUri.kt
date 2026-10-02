package com.mikhail.authenticator.otp

import com.mikhail.authenticator.crypto.OtpAlgorithm
import com.mikhail.authenticator.crypto.Totp

/**
 * A parsed otpauth:// URI, or a manually entered account.
 *
 * Reference for the scheme: the Key URI Format used by Google Authenticator and read by
 * Aegis (`OtpInfoParser`). Only the fields this app stores are modelled.
 */
data class OtpAccount(
    val issuer: String,
    val account: String,
    val secret: String,
    val algorithm: OtpAlgorithm = OtpAlgorithm.SHA1,
    val digits: Int = Totp.DEFAULT_DIGITS,
    val period: Int = Totp.DEFAULT_PERIOD,
)

object OtpAuthUri {

    private const val SCHEME = "otpauth"
    private const val TYPE_TOTP = "totp"

    /**
     * Parses `otpauth://totp/Issuer:account?secret=…&issuer=…&digits=…&period=…&algorithm=…`.
     *
     * Returns null when the text is not a TOTP URI (HOTP and unknown schemes are rejected
     * rather than guessed at), so callers can show "Not a TOTP code" instead of adding junk.
     */
    fun parse(raw: String): OtpAccount? {
        val text = raw.trim()
        val schemeEnd = text.indexOf("://")
        if (schemeEnd <= 0) return null
        if (!text.substring(0, schemeEnd).equals(SCHEME, ignoreCase = true)) return null

        val rest = text.substring(schemeEnd + 3)
        val queryStart = rest.indexOf('?')
        val pathPart = if (queryStart >= 0) rest.substring(0, queryStart) else rest
        val queryPart = if (queryStart >= 0) rest.substring(queryStart + 1) else ""

        val slash = pathPart.indexOf('/')
        if (slash < 0) return null
        val type = pathPart.substring(0, slash)
        if (!type.equals(TYPE_TOTP, ignoreCase = true)) return null

        val label = decode(pathPart.substring(slash + 1))
        val params = parseQuery(queryPart)

        val secret = params["secret"]?.trim().orEmpty()
        if (secret.isEmpty()) return null

        var issuer = params["issuer"]?.trim().orEmpty()
        var account = label
        // The label is conventionally "Issuer:account"; the explicit issuer parameter wins
        // when both are present, and the prefix is stripped from the account either way.
        val colon = label.indexOf(':')
        if (colon >= 0) {
            val prefix = label.substring(0, colon).trim()
            account = label.substring(colon + 1).trim()
            if (issuer.isEmpty()) issuer = prefix
        }
        if (issuer.isEmpty()) issuer = account

        val digits = params["digits"]?.toIntOrNull()?.takeIf { it in 6..8 } ?: Totp.DEFAULT_DIGITS
        val period = params["period"]?.toIntOrNull()?.takeIf { it > 0 } ?: Totp.DEFAULT_PERIOD

        return OtpAccount(
            issuer = issuer,
            account = account,
            secret = secret.uppercase().filter { !it.isWhitespace() },
            algorithm = OtpAlgorithm.fromOtpAuth(params["algorithm"]),
            digits = digits,
            period = period,
        )
    }

    /** Serialises back to an otpauth URI — used when exporting a vault. */
    fun build(account: OtpAccount): String {
        val label = "${encode(account.issuer)}:${encode(account.account)}"
        val params = buildList {
            add("secret=" + encode(account.secret))
            add("issuer=" + encode(account.issuer))
            if (account.algorithm != OtpAlgorithm.SHA1) add("algorithm=" + account.algorithm.otpauthName)
            if (account.digits != Totp.DEFAULT_DIGITS) add("digits=${account.digits}")
            if (account.period != Totp.DEFAULT_PERIOD) add("period=${account.period}")
        }.joinToString("&")
        return "$SCHEME://$TYPE_TOTP/$label?$params"
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val key = decode(pair.substring(0, eq)).lowercase()
            map[key] = decode(pair.substring(eq + 1))
        }
        return map
    }

    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
