package com.callagent.gateway.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the gateway introduces itself to the server.
 *
 * The rule the server applies is simple: an incoming request is matched to an
 * endpoint by the user in the From header.  A phone's number is not an
 * extension, so putting the caller there means the request can only be
 * matched by its source address — which needs an identify section with a
 * literal address, and a static IP on the phone.  A phone behind a mobile
 * carrier's NAT has neither.
 *
 * So the gateway states who is on the line in P-Asserted-Identity and
 * identifies itself by its own account name in From.  The match then works
 * from any address.
 */
class SipIdentityTest {

    private fun invite(
        callerIdNumber: String? = null,
        callerIdName: String? = null,
        assertedIdentity: String? = null,
        assertedIdentityName: String? = null,
        fromHeader: String? = null,
    ): String = SipBuilder.invite(
        targetUri = "sip:+4915112345678@pbx.example.com",
        username = "1001",
        domain = "pbx.example.com",
        localIp = "198.51.100.7",
        localPort = 5060,
        callId = "call-1",
        cseq = 1,
        localRtpPort = 40000,
        fromTag = "tag-1",
        callerIdNumber = callerIdNumber,
        callerIdName = callerIdName,
        fromHeader = fromHeader,
        assertedIdentity = assertedIdentity,
        assertedIdentityName = assertedIdentityName,
    )

    /** The user part of the From header. */
    private fun fromUser(invite: String): String {
        val from = invite.lines().first { it.startsWith("From:") }
        return from.substringAfter("<sip:").substringBefore('@')
    }

    private fun header(invite: String, name: String): String? =
        invite.lines().firstOrNull { it.startsWith("$name:") }

    // ── the gateway asserting an identity ─────────────────────────────────

    @Test
    fun theFromHeaderCarriesTheAccountNameWhenAnIdentityIsAsserted() {
        val invite = invite(
            callerIdNumber = "+4915112345678",
            callerIdName = "Anna",
            assertedIdentity = "+4915112345678",
            assertedIdentityName = "Anna",
        )
        assertEquals("1001", fromUser(invite))
    }

    @Test
    fun theCallersNumberTravelsInTheAssertedIdentity() {
        val invite = invite(
            callerIdNumber = "+4915112345678",
            callerIdName = "Anna",
            assertedIdentity = "+4915112345678",
            assertedIdentityName = "Anna",
        )
        val pai = header(invite, "P-Asserted-Identity")
        assertTrue("the header must be there: $pai", pai != null)
        assertTrue("the number must be in it: $pai", pai!!.contains("+4915112345678"))
        assertTrue("the name must be in it: $pai", pai.contains("\"Anna\""))
    }

    @Test
    fun withoutAnAssertedIdentityTheOldBehaviourIsKept() {
        // The server→gateway direction still puts the caller's number in
        // From: there the number is the extension's caller ID and the server
        // is the one matching the request.
        val invite = invite(callerIdNumber = "+4915112345678", callerIdName = "Anna")
        assertEquals("+4915112345678", fromUser(invite))
        assertFalse(
            "no identity is asserted, so there is no header",
            header(invite, "P-Asserted-Identity") != null,
        )
    }

    @Test
    fun theAssertedIdentityIsNotSentWithoutANumber() {
        val invite = invite(callerIdNumber = "1001")
        assertEquals("1001", fromUser(invite))
        assertTrue(header(invite, "P-Asserted-Identity") == null)
    }

    /**
     * The authenticated re-INVITE is the same request with credentials on
     * it: the From has to be byte for byte the same, tag included, or the
     * server treats it as a different dialog.
     */
    @Test
    fun aFromHeaderPassedInIsUsedWhole() {
        val from = "<sip:1001@pbx.example.com>;tag=tag-9"
        val invite = invite(fromHeader = from, assertedIdentity = "+4915112345678")
        assertTrue(
            "the From must be the one that was passed in",
            header(invite, "From")!!.contains("tag=tag-9"),
        )
        assertEquals("1001", fromUser(invite))
    }

    /** The header has to survive a parse: the server reads it from the wire. */
    @Test
    fun theAssertedIdentitySurvivesParsing() {
        val invite = invite(
            callerIdNumber = "+4915112345678",
            assertedIdentity = "+4915112345678",
            assertedIdentityName = "Anna",
        )
        val parsed = SipMessage.parse(invite) ?: error("the INVITE must parse")
        assertEquals("+4915112345678", parsed.header("p-asserted-identity")?.let {
            it.substringAfter("<sip:").substringBefore('@')
        })
    }
}
