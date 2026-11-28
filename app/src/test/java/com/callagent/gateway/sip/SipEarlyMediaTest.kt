package com.callagent.gateway.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Early media: the sound that exists before the call is answered.
 *
 * The cellular network announces itself before the far end picks up, and that
 * announcement has to reach the agent.  It can only travel over a media
 * stream that is live while the call is still ringing — which is exactly what
 * a provisional response with SDP establishes.
 */
class SipEarlyMediaTest {

    /** A media description offering G.722 and the telephone-event payload. */
    private fun sdp(port: Int, codecs: String = "9 101"): String =
        "v=0\r\n" +
        "o=pbx 1 1 IN IP4 10.0.0.1\r\n" +
        "s=call\r\n" +
        "c=IN IP4 10.0.0.1\r\n" +
        "t=0 0\r\n" +
        "m=audio $port RTP/AVP $codecs\r\n" +
        "a=rtpmap:9 G722/8000\r\n" +
        "a=rtpmap:8 PCMA/8000\r\n" +
        "a=rtpmap:101 telephone-event/8000\r\n"

    /** A provisional response from the server, with or without SDP. */
    private fun provisional(code: Int, body: String?): SipMessage {
        val headers = "SIP/2.0 $code Session Progress\r\n" +
            "Via: SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK1\r\n" +
            "To: <sip:2002@10.0.0.1>;tag=servertag\r\n" +
            "From: <sip:1001@10.0.0.1>;tag=gwtag\r\n" +
            "Call-ID: early-media-call\r\n" +
            "CSeq: 1 INVITE\r\n"
        val raw = if (body != null) {
            headers + "Content-Type: application/sdp\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n\r\n" + body
        } else {
            headers + "Content-Length: 0\r\n\r\n"
        }
        return SipMessage.parse(raw)!!
    }

    /** Records what the dialog reports. */
    private class Recorder : SipCall.Listener {
        var earlyMedia: Triple<String, Int, Int>? = null
        var rtpReady: Triple<String, Int, Int>? = null
        var answered = false

        override fun onCallAnswered(call: SipCall) { answered = true }
        override fun onCallTerminated(call: SipCall) {}
        override fun onRtpReady(call: SipCall, remoteRtpAddr: String, remoteRtpPort: Int, payloadType: Int) {
            rtpReady = Triple(remoteRtpAddr, remoteRtpPort, payloadType)
        }
        override fun onEarlyMedia(call: SipCall, remoteRtpAddr: String, remoteRtpPort: Int, payloadType: Int) {
            earlyMedia = Triple(remoteRtpAddr, remoteRtpPort, payloadType)
        }
    }

    private fun client(): SipClient = SipClient(
        username = "1001",
        password = "secret",
        serverDomain = "10.0.0.1",
        serverPort = 5060,
        localIp = "192.168.1.10",
        localPort = 5060,
        publicIp = "198.51.100.7",
    )

    private fun outboundCall(recorder: Recorder): SipCall {
        val call = SipCall("early-media-call", SipCall.Direction.OUTBOUND, client())
        call.localRtpPort = 30000
        call.listener = recorder
        return call
    }

    @Test
    fun `183 with SDP is reported as early media`() {
        val recorder = Recorder()
        val call = outboundCall(recorder)

        call.handleMessage(provisional(183, sdp(40000)))

        assertTrue("the dialog must know the media is live", call.earlyMediaReceived)
        assertEquals(Triple("10.0.0.1", 40000, 9), recorder.earlyMedia)
        // The media is the only thing the dialog has to send to, so it is
        // remembered even if the answer that follows carries no SDP.
        assertEquals(40000, call.remoteRtpPort)
        assertEquals("10.0.0.1", call.remoteRtpAddress)
    }

    @Test
    fun `180 with SDP is reported as early media too`() {
        val recorder = Recorder()
        val call = outboundCall(recorder)

        call.handleMessage(provisional(180, sdp(40000)))

        assertTrue(call.earlyMediaReceived)
        assertEquals(Triple("10.0.0.1", 40000, 9), recorder.earlyMedia)
    }

    @Test
    fun `a provisional response without SDP is not early media`() {
        val recorder = Recorder()
        val call = outboundCall(recorder)

        call.handleMessage(provisional(183, null))

        assertFalse(call.earlyMediaReceived)
        assertEquals(null, recorder.earlyMedia)
    }

    @Test
    fun `the answer keeps the codec the early media agreed on`() {
        val recorder = Recorder()
        val call = outboundCall(recorder)

        // Early media offering PCMA only: that is the codec the stream is
        // built with.
        call.handleMessage(provisional(183, sdp(40000, codecs = "8 101")))
        assertEquals(8, call.negotiatedPayloadType)

        // The answer that follows carries no SDP of its own.  It must not
        // fall back to the configured codec: the media is already running
        // with the other one.
        val ok = "SIP/2.0 200 OK\r\n" +
            "Via: SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK1\r\n" +
            "To: <sip:2002@10.0.0.1>;tag=servertag\r\n" +
            "From: <sip:1001@10.0.0.1>;tag=gwtag\r\n" +
            "Call-ID: early-media-call\r\n" +
            "CSeq: 1 INVITE\r\n" +
            "Content-Length: 0\r\n\r\n"
        call.handleMessage(SipMessage.parse(ok)!!)

        assertEquals(8, call.negotiatedPayloadType)
    }

    @Test
    fun `the 183 offers our own media`() {
        val invite = SipMessage.parse(
            "INVITE sip:1001@10.0.0.1 SIP/2.0\r\n" +
                "Via: SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK1\r\n" +
                "To: <sip:1001@10.0.0.1>\r\n" +
                "From: <sip:2002@10.0.0.1>;tag=servertag\r\n" +
                "Call-ID: inbound-call\r\n" +
                "CSeq: 1 INVITE\r\n" +
                "Content-Type: application/sdp\r\n" +
                "Content-Length: ${sdp(40000).toByteArray().size}\r\n\r\n" +
                sdp(40000)
        )!!

        val response = SipBuilder.sessionProgress183(
            invite, "1001", "198.51.100.7", 5060,
            localRtpPort = 31000,
            toTag = "gwtag1",
            negotiatedPayloadType = 9
        )

        assertTrue(response.startsWith("SIP/2.0 183 Session Progress\r\n"))
        // The tag identifies the dialog: the answer that ends the call has to
        // carry the same one.
        assertTrue("the To tag must be ours: $response", response.contains(";tag=gwtag1"))
        assertTrue("the offer must name our port: $response",
            response.contains("m=audio 31000 RTP/AVP 9 101"))
        assertTrue("the offer must be a real SDP: $response", response.contains("c=IN IP4 198.51.100.7"))
        val declared = response.substringAfter("Content-Length: ").substringBefore("\r\n").trim().toInt()
        assertEquals(declared, response.substringAfter("\r\n\r\n").toByteArray().size)
    }

    @Test
    fun `the early answer and the final answer share the dialog tag`() {
        val invite = SipMessage.parse(
            "INVITE sip:1001@10.0.0.1 SIP/2.0\r\n" +
                "Via: SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK1\r\n" +
                "To: <sip:1001@10.0.0.1>\r\n" +
                "From: <sip:2002@10.0.0.1>;tag=servertag\r\n" +
                "Call-ID: inbound-call\r\n" +
                "CSeq: 1 INVITE\r\n" +
                "Content-Type: application/sdp\r\n" +
                "Content-Length: ${sdp(40000).toByteArray().size}\r\n\r\n" +
                sdp(40000)
        )!!

        val call = SipCall("inbound-call", SipCall.Direction.INBOUND, client())
        call.originalInvite = invite
        call.remoteContactAddress = "10.0.0.1" to 5060

        assertTrue("the early answer must be sent", call.answerEarly(31000))
        assertEquals(SipCall.State.RINGING, call.state)
        // The call is still not answered: the media is up, the dialog is not.
        assertFalse(call.state == SipCall.State.ANSWERED)

        call.accept(31000)
        assertEquals(SipCall.State.ANSWERED, call.state)
        // Same tag on both responses — the 183 and the 200 OK are one dialog.
        val tag = call.localTag
        assertTrue("the To header must carry the tag: ${call.toHeader}",
            call.toHeader?.contains(";tag=$tag") == true)
    }
}
