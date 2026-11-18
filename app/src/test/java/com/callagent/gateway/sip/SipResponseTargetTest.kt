package com.callagent.gateway.sip

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a SIP response has to be sent.
 *
 * A response to a request goes back to where the request came from, not to
 * the address in the request's Contact header.  That distinction is what
 * keeps a call from hanging: a PBX whose Contact names a host it does not
 * send from (behind NAT, or with an `external_signaling_address` left over
 * from another machine) would never see the final answer, and the caller
 * would wait out the whole transaction timeout before the call dropped.
 */
class SipResponseTargetTest {

    private val source = "203.0.113.10" to 5062
    private val contact = "198.51.100.7" to 6064
    private val fallback = "192.0.2.1" to 5060

    @Test
    fun answersToTheSourceOfTheRequest() {
        assertEquals(
            source,
            SipCall.responseTarget(source, contact, fallback)
        )
    }

    @Test
    fun ignoresAContactThatNamesADifferentHost() {
        // The Contact names the address for in-dialog requests.  It is not
        // where a response to the initial request belongs.
        assertEquals(
            source,
            SipCall.responseTarget(source = source, contact = contact, fallback = fallback)
        )
    }

    @Test
    fun fallsBackToTheContactWhenThereIsNoSourceAddress() {
        // An outbound call has no request of its own: the server's 200 OK
        // carried the contact to answer.
        assertEquals(
            contact,
            SipCall.responseTarget(source = null, contact = contact, fallback = fallback)
        )
    }

    @Test
    fun fallsBackToTheServerWhenNeitherIsKnown() {
        assertEquals(
            fallback,
            SipCall.responseTarget(source = null, contact = null, fallback = fallback)
        )
    }
}
