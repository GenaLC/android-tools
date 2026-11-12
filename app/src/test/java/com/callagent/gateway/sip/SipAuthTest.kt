package com.callagent.gateway.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SipAuthTest {

    /** A 401 carrying the given challenge header. */
    private fun challenge(header: String): SipMessage {
        val raw = "SIP/2.0 401 Unauthorized\r\n" +
            "Via: SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK1\r\n" +
            "$header\r\n" +
            "Call-ID: test-call\r\n" +
            "CSeq: 1 REGISTER\r\n" +
            "Content-Length: 0\r\n\r\n"
        return SipMessage.parse(raw)!!
    }

    /** The value of one parameter of the built Authorization header. */
    private fun param(header: String, name: String): String? {
        val prefix = "$name="
        return header.substringAfter("Authorization: Digest ")
            .split(", ")
            .firstOrNull { it.trim().startsWith(prefix) }
            ?.trim()
            ?.removePrefix(prefix)
            ?.trim('"')
    }

    // ── The vectors from RFC 2617 §3.5 ──────────────────────────────

    /**
     * The RFC's own example, with qop.
     *
     * The published response is 6629fae49393a05397450978507c4ef1 — the
     * value every RFC 2617 implementation is checked against.
     */
    @Test
    fun rfc2617QopVectorMatches() {
        val params = SipAuth.parseChallenge(
            challenge(
                "WWW-Authenticate: Digest realm=\"testrealm@host.com\", " +
                    "nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", " +
                    "qop=\"auth\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""
            )
        )!!
        assertEquals("auth", params.qop)

        val header = SipAuth.buildAuthHeader(
            "GET", "/dir/index.html", "Mufasa", "Circle Of Life", params,
            cnonce = "0a4f113b", nonceCount = "00000001"
        )
        assertEquals(
            "6629fae49393a05397450978507c4ef1",
            param(header, "response")
        )
        assertEquals("auth", param(header, "qop"))
        assertEquals("00000001", param(header, "nc"))
        assertEquals("0a4f113b", param(header, "cnonce"))
        assertEquals("5ccc069c403ebaf9f0171e9517f40e41", param(header, "opaque"))
        assertEquals("MD5", param(header, "algorithm"))
    }

    /** The same request without qop: the RFC 2069-style response. */
    @Test
    fun rfc2617WithoutQopMatches() {
        val params = SipAuth.parseChallenge(
            challenge(
                "WWW-Authenticate: Digest realm=\"testrealm@host.com\", " +
                    "nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\""
            )
        )!!
        assertNull(params.qop)

        val header = SipAuth.buildAuthHeader(
            "GET", "/dir/index.html", "Mufasa", "Circle Of Life", params
        )
        assertEquals(
            "670fd8c2df070c60b045671b8b24ff02",
            param(header, "response")
        )
        // Without qop there is nothing to count and no client nonce to send.
        assertNull(param(header, "qop"))
        assertNull(param(header, "nc"))
        assertNull(param(header, "cnonce"))
    }

    /**
     * MD5-sess: the nonce and the client nonce are folded into HA1.
     *
     * The value is the one RFC 2617 §3.2.1 describes for the same inputs.
     */
    @Test
    fun md5SessMatches() {
        val params = SipAuth.parseChallenge(
            challenge(
                "WWW-Authenticate: Digest realm=\"testrealm@host.com\", " +
                    "nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", " +
                    "qop=\"auth\", algorithm=MD5-sess"
            )
        )!!
        assertEquals("MD5-sess", params.algorithm)

        val header = SipAuth.buildAuthHeader(
            "GET", "/dir/index.html", "Mufasa", "Circle Of Life", params,
            cnonce = "0a4f113b", nonceCount = "00000001"
        )
        assertEquals(
            "8e3825c57e897f5a0dec6c2d4e5059d0",
            param(header, "response")
        )
        assertEquals("MD5-sess", param(header, "algorithm"))
    }

    // ── Parsing ─────────────────────────────────────────────────────

    /**
     * A realm with a comma in it, and a qop list.
     *
     * Splitting the parameter list on every comma tore the realm in half
     * and left the qop as "auth" plus a stray token.
     */
    @Test
    fun challengeWithCommasInsideQuotesIsParsedWhole() {
        val params = SipAuth.parseChallenge(
            challenge(
                "WWW-Authenticate: Digest realm=\"My, Company Ltd\", " +
                    "nonce=\"abc123\", qop=\"auth,auth-int\", opaque=\"op-1\""
            )
        )!!
        assertEquals("My, Company Ltd", params.realm)
        assertEquals("abc123", params.nonce)
        assertEquals("auth", params.qop)
        assertEquals("op-1", params.opaque)
    }

    @Test
    fun onlyTheAuthTokenIsTakenFromAQopList() {
        val authFirst = SipAuth.parseChallenge(
            challenge("WWW-Authenticate: Digest realm=\"r\", nonce=\"n\", qop=\"auth,auth-int\"")
        )!!
        assertEquals("auth", authFirst.qop)

        val authLast = SipAuth.parseChallenge(
            challenge("WWW-Authenticate: Digest realm=\"r\", nonce=\"n\", qop=\"auth-int,auth\"")
        )!!
        assertEquals("auth", authLast.qop)

        // auth-int alone authenticates the body, which this client does not
        // send — so there is no qop to answer with, and the plain response
        // is what goes back.
        val intOnly = SipAuth.parseChallenge(
            challenge("WWW-Authenticate: Digest realm=\"r\", nonce=\"n\", qop=\"auth-int\"")
        )!!
        assertNull(intOnly.qop)
    }

    @Test
    fun opaqueIsKeptAndEchoed() {
        val params = SipAuth.parseChallenge(
            challenge("WWW-Authenticate: Digest realm=\"r\", nonce=\"n\", opaque=\"keep-me\"")
        )!!
        assertEquals("keep-me", params.opaque)
        val header = SipAuth.buildAuthHeader("REGISTER", "sip:p:5060", "u", "pw", params)
        assertEquals("keep-me", param(header, "opaque"))
    }

    @Test
    fun aChallengeWithoutRealmOrNonceIsRejected() {
        assertNull(
            SipAuth.parseChallenge(
                challenge("WWW-Authenticate: Digest nonce=\"n\"")
            )
        )
        assertNull(
            SipAuth.parseChallenge(
                challenge("WWW-Authenticate: Digest realm=\"r\"")
            )
        )
    }

    @Test
    fun aNonDigestChallengeIsRejected() {
        assertNull(
            SipAuth.parseChallenge(
                challenge("WWW-Authenticate: Basic realm=\"r\"")
            )
        )
    }

    // ── The counters ───────────────────────────────────────────────

    @Test
    fun theNonceCountIncrementsForEachRequestWithThatNonce() {
        val params = SipAuth.parseChallenge(
            challenge("WWW-Authenticate: Digest realm=\"r\", nonce=\"nonce-counter-1\", qop=\"auth\"")
        )!!
        val first = SipAuth.buildAuthHeader("REGISTER", "sip:p:5060", "u", "pw", params)
        val second = SipAuth.buildAuthHeader("REGISTER", "sip:p:5060", "u", "pw", params)
        assertEquals("00000001", param(first, "nc"))
        assertEquals("00000002", param(second, "nc"))
        // And the client nonce differs between the two requests.
        assertNotNull(param(first, "cnonce"))
        assertFalse(param(first, "cnonce") == param(second, "cnonce"))
    }

    @Test
    fun theAuthorizationHeaderIsWellFormed() {
        val params = SipAuth.parseChallenge(
            challenge("WWW-Authenticate: Digest realm=\"r\", nonce=\"n\", qop=\"auth\"")
        )!!
        val header = SipAuth.buildAuthHeader("REGISTER", "sip:p:5060", "u", "pw", params)
        assertTrue(header.startsWith("Authorization: Digest "))
        assertTrue(header.endsWith("\r\n"))
        assertEquals("u", param(header, "username"))
        assertEquals("r", param(header, "realm"))
        assertEquals("n", param(header, "nonce"))
        assertEquals("sip:p:5060", param(header, "uri"))
    }

    /** The INVITE variant has to hash the INVITE method, not REGISTER. */
    @Test
    fun theInviteResponseHashesTheInviteMethod() {
        val params = SipAuth.parseChallenge(
            challenge("WWW-Authenticate: Digest realm=\"r\", nonce=\"n\"")
        )!!
        val register = SipAuth.buildAuthHeader("REGISTER", "sip:p:5060", "u", "pw", params)
        val invite = SipAuth.buildInviteAuthHeader("sip:p:5060", "u", "pw", params)
        assertFalse(param(register, "response") == param(invite, "response"))
    }
}
