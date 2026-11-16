package com.callagent.gateway.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which number an incoming INVITE asks the gateway to dial.
 *
 * The cases mirror what res_pjsip actually puts on the wire: the Request-URI
 * carries the AOR, the To header carries the dialled part.
 */
class CallDestinationTest {

    private val account = "1001"
    private val own = listOf("+4915112345678")

    private fun resolve(
        forward: String? = null,
        requestUri: String? = null,
        to: String? = null,
        ownNumbers: List<String> = own,
    ) = CallDestination.resolve(forward, requestUri, to, account, ownNumbers)

    @Test
    fun `x-gsm-forward wins over everything`() {
        assertEquals(
            "+4915110000000",
            resolve(
                forward = "+4915110000000",
                requestUri = "sip:1001@pbx",
                to = "sip:1001@pbx",
            )
        )
    }

    @Test
    fun `x-gsm-forward is trimmed`() {
        assertEquals("+4915110000000", resolve(forward = "  +4915110000000  "))
    }

    @Test
    fun `empty x-gsm-forward is ignored`() {
        assertEquals(
            "+4915110000000",
            resolve(forward = "   ", requestUri = "sip:+4915110000000@pbx")
        )
    }

    @Test
    fun `number is taken from the request uri`() {
        assertEquals(
            "+4915110000000",
            resolve(requestUri = "sip:+4915110000000@pbx", to = "sip:1001@pbx")
        )
    }

    @Test
    fun `number is taken from the to header when the request uri names the peer`() {
        // What res_pjsip sends for Dial(PJSIP/1001/+4915110000000).
        assertEquals(
            "+4915110000000",
            resolve(requestUri = "sip:1001@pbx", to = "sip:+4915110000000@pbx")
        )
    }

    @Test
    fun `the account name is never a destination`() {
        assertNull(resolve(requestUri = "sip:1001@pbx", to = "sip:1001@pbx"))
    }

    @Test
    fun `our own msisdn is never a destination`() {
        assertNull(
            resolve(requestUri = "sip:1001@pbx", to = "sip:+4915112345678@pbx")
        )
    }

    @Test
    fun `our own msisdn is compared by digits only`() {
        // Same digits as +4915112345678, written with separators.
        assertNull(
            resolve(
                requestUri = "sip:1001@pbx",
                to = "sip:+49-151-1234-5678@pbx",
                ownNumbers = listOf("+4915112345678"),
            )
        )
    }

    @Test
    fun `a number that is too short is not a destination`() {
        assertNull(resolve(requestUri = "sip:12@pbx", to = "sip:12@pbx"))
    }

    @Test
    fun `an account name with letters is not a destination`() {
        assertNull(resolve(requestUri = "sip:1001@pbx", to = "sip:gsmgw@pbx"))
    }

    @Test
    fun `nothing to dial gives null`() {
        assertNull(resolve())
    }

    @Test
    fun `a uri without a user part is skipped`() {
        assertNull(resolve(requestUri = "tel:+4915110000000", to = "tel:+4915110000000"))
    }
}
