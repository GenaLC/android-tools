package com.callagent.gateway.sip

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * SIP Digest Authentication (RFC 2617).
 * Ported from the Python SIP implementation.
 *
 * The digest follows the server's challenge: when the challenge offers
 * `qop`, the response carries a client nonce and a nonce count and hashes
 * them into the response, as RFC 2617 §3.2.1 describes.  A server that
 * offers `MD5-sess` gets the session variant of HA1, which folds the server
 * nonce and the client nonce into it.
 *
 * Asterisk's res_pjsip offers `qop="auth"`, and pjproject verifies the
 * response against the CLIENT's qop fields — so the old qop-less response
 * still authenticated there.  It did not against FreeSWITCH, Kamailio or an
 * SBC, which check that the qop, nc and cnonce are present and consistent.
 */
object SipAuth {

    /** Random source for client nonces. */
    private val random = SecureRandom()

    /** How many requests have been made with each server nonce. */
    private val nonceCounters = ConcurrentHashMap<String, AtomicInteger>()

    /** Parse WWW-Authenticate header parameters */
    fun parseChallenge(msg: SipMessage): AuthParams? {
        val authHeader = msg.header("www-authenticate") ?: msg.header("proxy-authenticate")
            ?: return null

        if (!authHeader.lowercase().startsWith("digest")) return null

        // Strip "Digest " prefix (case-insensitive)
        val digestIdx = authHeader.lowercase().indexOf("digest")
        val paramStr = if (digestIdx >= 0) authHeader.substring(digestIdx + 6).trim() else return null
        val params = parseAuthParams(paramStr)

        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        return AuthParams(
            realm = realm,
            nonce = nonce,
            opaque = params["opaque"],
            qop = selectQop(params["qop"]),
            algorithm = params["algorithm"] ?: "MD5"
        )
    }

    /**
     * Build Authorization header for REGISTER
     *
     * @param cnonce the client nonce to use.  Null takes a fresh random one,
     *   which is what a real request wants; a fixed value is what a test
     *   needs to compare the response against a published vector.
     * @param nonceCount the nonce count to send.  Null takes the next count
     *   for this server nonce.
     */
    fun buildAuthHeader(
        method: String,
        uri: String,
        username: String,
        password: String,
        params: AuthParams,
        cnonce: String? = null,
        nonceCount: String? = null
    ): String {
        val qop = params.qop
        val sessionAlgorithm = params.algorithm.equals("MD5-sess", ignoreCase = true)

        // A client nonce is needed for qop and for MD5-sess: the first counts
        // the request, the second is hashed into HA1.
        val clientNonce = if (qop != null || sessionAlgorithm) cnonce ?: newCnonce() else null
        val nc = if (qop != null) nonceCount ?: nextNonceCount(params.nonce) else null

        var ha1 = md5("$username:${params.realm}:$password")
        if (sessionAlgorithm) {
            // MD5-sess: HA1 is the plain hash with the server nonce and the
            // client nonce folded in, so a captured response cannot be
            // replayed against another nonce.
            ha1 = md5("$ha1:${params.nonce}:$clientNonce")
        }
        val ha2 = md5("$method:$uri")
        val response = if (qop != null) {
            md5("$ha1:${params.nonce}:$nc:$clientNonce:$qop:$ha2")
        } else {
            md5("$ha1:${params.nonce}:$ha2")
        }

        return buildString {
            append("Authorization: Digest username=\"$username\", ")
            append("realm=\"${params.realm}\", ")
            append("nonce=\"${params.nonce}\", ")
            append("uri=\"$uri\", ")
            append("response=\"$response\", ")
            append("algorithm=${if (sessionAlgorithm) "MD5-sess" else "MD5"}")
            if (qop != null) {
                append(", qop=$qop")
                append(", nc=$nc")
                append(", cnonce=\"$clientNonce\"")
            }
            if (params.opaque != null) append(", opaque=\"${params.opaque}\"")
            append("\r\n")
        }
    }

    /** Build Authorization header for INVITE */
    fun buildInviteAuthHeader(
        uri: String,
        username: String,
        password: String,
        params: AuthParams,
        cnonce: String? = null,
        nonceCount: String? = null
    ): String = buildAuthHeader("INVITE", uri, username, password, params, cnonce, nonceCount)

    /**
     * The qop to answer with, out of the server's list.
     *
     * A server may offer several: "auth,auth-int".  Only "auth" can be
     * answered here — auth-int authenticates the entity body, which this
     * client does not send — so the token has to be picked out of the list
     * rather than the list being taken wholesale.
     */
    private fun selectQop(qop: String?): String? {
        if (qop == null) return null
        val tokens = qop.split(",").map { it.trim().lowercase() }
        return if ("auth" in tokens) "auth" else null
    }

    /**
     * The challenge's parameters, as name/value pairs.
     *
     * The list is split on commas that are not inside a quoted string: a
     * realm may well contain one ("My Company, Inc") and a qop list is
     * comma-separated by definition, so splitting blindly tore both apart
     * and lost the value.
     */
    private fun parseAuthParams(paramStr: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        for (part in splitOutsideQuotes(paramStr, ',')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val key = part.substring(0, eq).trim().lowercase()
            if (key.isEmpty()) continue
            params[key] = unquote(part.substring(eq + 1).trim())
        }
        return params
    }

    /** Split on a separator that is not inside a quoted string. */
    private fun splitOutsideQuotes(value: String, separator: Char): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var escaped = false
        for (ch in value) {
            when {
                escaped -> {
                    current.append(ch)
                    escaped = false
                }
                ch == '\\' && inQuotes -> {
                    current.append(ch)
                    escaped = true
                }
                ch == '"' -> {
                    inQuotes = !inQuotes
                    current.append(ch)
                }
                ch == separator && !inQuotes -> {
                    parts.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
        }
        parts.add(current.toString())
        return parts
    }

    /** The value of a parameter, unquoted and with escapes resolved. */
    private fun unquote(value: String): String {
        if (value.length < 2 || !value.startsWith('"') || !value.endsWith('"')) return value
        val inner = value.substring(1, value.length - 1)
        return buildString {
            var escaped = false
            for (ch in inner) {
                when {
                    escaped -> {
                        append(ch)
                        escaped = false
                    }
                    ch == '\\' -> escaped = true
                    else -> append(ch)
                }
            }
        }
    }

    /**
     * The nonce count for the next request made with this server nonce.
     *
     * RFC 2617 counts the requests sent with one nonce, starting at
     * 00000001.  A count that goes backwards reads as a replay.
     */
    private fun nextNonceCount(nonce: String): String {
        val counter = nonceCounters.getOrPut(nonce) { AtomicInteger(0) }
        return "%08x".format(counter.incrementAndGet())
    }

    /** A fresh client nonce: 16 random bytes as hex. */
    private fun newCnonce(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    data class AuthParams(
        val realm: String,
        val nonce: String,
        val opaque: String?,
        /** The qop token to answer with — "auth" — or null when the server
         *  offered none this client can use. */
        val qop: String?,
        /** The algorithm the server asked for: "MD5" or "MD5-sess". */
        val algorithm: String = "MD5"
    )
}
