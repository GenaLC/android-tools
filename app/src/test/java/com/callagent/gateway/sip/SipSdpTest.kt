package com.callagent.gateway.sip

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The SDP the gateway offers and answers with.
 *
 * The payload list is the whole negotiation: what it offers is what the
 * server may pick, and what the answer claims is what the RTP session
 * will actually use.
 */
class SipSdpTest {

    private var savedMode: String = ""

    @Before
    fun rememberMode() {
        savedMode = SipBuilder.codecMode
    }

    @After
    fun restoreMode() {
        SipBuilder.codecMode = savedMode
    }

    /** An INVITE from the server, with the given media line. */
    private fun incomingInvite(mediaLine: String): SipMessage {
        val rtpmaps = mediaLine.substringAfter("RTP/AVP ").split(" ").joinToString("") { pt ->
            when (pt) {
                "9" -> "a=rtpmap:9 G722/8000\r\n"
                "8" -> "a=rtpmap:8 PCMA/8000\r\n"
                "0" -> "a=rtpmap:0 PCMU/8000\r\n"
                "101" -> "a=rtpmap:101 telephone-event/8000\r\n"
                else -> ""
            }
        }
        val sdp = "v=0\r\n" +
            "o=pbx 1 1 IN IP4 10.0.0.1\r\n" +
            "s=call\r\n" +
            "c=IN IP4 10.0.0.1\r\n" +
            "t=0 0\r\n" +
            "$mediaLine\r\n" +
            rtpmaps
        val raw = "INVITE sip:1001@10.0.0.1 SIP/2.0\r\n" +
            "Via: SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK1\r\n" +
            "To: <sip:1001@10.0.0.1>\r\n" +
            "From: <sip:2002@10.0.0.1>;tag=abc\r\n" +
            "Call-ID: test-call\r\n" +
            "CSeq: 1 INVITE\r\n" +
            "X-GSM-Forward: +4915112345678\r\n" +
            "Content-Type: application/sdp\r\n" +
            "Content-Length: ${sdp.toByteArray().size}\r\n\r\n" +
            sdp
        return SipMessage.parse(raw)!!
    }

    /** The media line of an SDP body. */
    private fun mediaLine(sdp: String): String =
        sdp.lineSequence().first { it.startsWith("m=audio") }

    /** An offer built by the gateway, in the given codec mode. */
    private fun offer(mode: String): String {
        SipBuilder.codecMode = mode
        return SipBuilder.invite(
            "sip:2002@10.0.0.1", "1001", "10.0.0.1",
            "192.168.1.10", 5060, "call-id", 1, 4000
        )
    }

    // ── The offer ───────────────────────────────────────────────────

    /**
     * The default offer names G.722, both G.711 codecs and
     * telephone-event.
     *
     * An offer of G.722 alone is answered with 488 by every endpoint that
     * only allows G.711, which is the common case.
     */
    @Test
    fun theDefaultOfferCarriesG722BothG711sAndTelephoneEvent() {
        val invite = offer("both")
        assertTrue(
            "offer was: $invite",
            invite.contains("m=audio 4000 RTP/AVP 9 8 0 101")
        )
        assertTrue(invite.contains("a=rtpmap:9 G722/8000"))
        assertTrue(invite.contains("a=rtpmap:8 PCMA/8000"))
        assertTrue(invite.contains("a=rtpmap:0 PCMU/8000"))
        assertTrue(invite.contains("a=rtpmap:101 telephone-event/8000"))
    }

    @Test
    fun theDefaultCodecModeIsBoth() {
        assertTrue(
            "SipBuilder.codecMode was ${SipBuilder.codecMode}",
            SipBuilder.codecMode == "both"
        )
    }

    @Test
    fun thePerModePayloadListsAreUnchanged() {
        assertTrue(offer("g711").contains("m=audio 4000 RTP/AVP 8 0 101"))
        assertFalse(offer("g711").contains("a=rtpmap:9 G722/8000"))
        assertTrue(offer("g722").contains("m=audio 4000 RTP/AVP 9 101"))
        assertFalse(offer("g722").contains("a=rtpmap:8 PCMA/8000"))
        assertFalse(offer("g722").contains("a=rtpmap:0 PCMU/8000"))
    }

    // ── The answer ──────────────────────────────────────────────────

    /**
     * The answer names the codec that was negotiated, not everything the
     * gateway could do.
     *
     * Advertising the whole list leaves the choice to the server, which may
     * pick a different codec than the one the RTP session was set up for —
     * and then both ends hear noise.
     */
    @Test
    fun theAnswerNamesTheNegotiatedPayloadOnly() {
        SipBuilder.codecMode = "both"
        val invite = incomingInvite("m=audio 4000 RTP/AVP 8 0 101")
        // Only G.711 is on offer, so G.711 is what gets negotiated.
        assertTrue(invite.sdpPreferredPayloadType == 8)

        val ok = SipBuilder.ok200(
            invite, "1001", "192.168.1.10", 5060, localRtpPort = 5000
        )
        val sdp = ok.substringAfter("\r\n\r\n")
        assertTrue("answer was: $sdp", mediaLine(sdp).endsWith("RTP/AVP 8 101"))
        assertTrue(sdp.contains("a=rtpmap:8 PCMA/8000"))
        assertFalse(sdp.contains("a=rtpmap:9 G722/8000"))
        assertFalse(sdp.contains("a=rtpmap:0 PCMU/8000"))
        assertTrue(sdp.contains("a=rtpmap:101 telephone-event/8000"))
    }

    @Test
    fun theAnswerNamesG722WhenTheServerOffersIt() {
        SipBuilder.codecMode = "both"
        val invite = incomingInvite("m=audio 4000 RTP/AVP 9 8 0 101")
        assertTrue(invite.sdpPreferredPayloadType == 9)

        val ok = SipBuilder.ok200(
            invite, "1001", "192.168.1.10", 5060, localRtpPort = 5000
        )
        val sdp = ok.substringAfter("\r\n\r\n")
        assertTrue("answer was: $sdp", mediaLine(sdp).endsWith("RTP/AVP 9 101"))
        assertTrue(sdp.contains("a=rtpmap:9 G722/8000"))
        assertFalse(sdp.contains("a=rtpmap:8 PCMA/8000"))
    }

    @Test
    fun theAnswerNamesMuLawWhenThatIsAllTheServerOffers() {
        SipBuilder.codecMode = "both"
        val invite = incomingInvite("m=audio 4000 RTP/AVP 0 101")
        assertTrue(invite.sdpPreferredPayloadType == 0)

        val ok = SipBuilder.ok200(
            invite, "1001", "192.168.1.10", 5060, localRtpPort = 5000
        )
        val sdp = ok.substringAfter("\r\n\r\n")
        assertTrue("answer was: $sdp", mediaLine(sdp).endsWith("RTP/AVP 0 101"))
        assertTrue(sdp.contains("a=rtpmap:0 PCMU/8000"))
    }

    /**
     * A server that offers nothing this build has gets the configured
     * list rather than a codec it never offered.
     */
    @Test
    fun theAnswerKeepsTheConfiguredListWhenNothingCanBeNegotiated() {
        SipBuilder.codecMode = "both"
        val invite = incomingInvite("m=audio 4000 RTP/AVP 101")
        assertTrue(invite.sdpNegotiatedPayloadType == null)

        val ok = SipBuilder.ok200(
            invite, "1001", "192.168.1.10", 5060, localRtpPort = 5000
        )
        val sdp = ok.substringAfter("\r\n\r\n")
        assertTrue("answer was: $sdp", mediaLine(sdp).endsWith("RTP/AVP 9 8 0 101"))
    }
}
