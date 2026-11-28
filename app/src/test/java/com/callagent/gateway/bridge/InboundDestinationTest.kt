package com.callagent.gateway.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where an incoming GSM call is sent.
 *
 * The SIM card's own number is not a routing key: a server that routes by it
 * needs a dialplan entry for every line.  The gateway names the destination
 * itself instead.
 */
class InboundDestinationTest {

    @Test
    fun `the configured destination wins over the SIM's own number`() {
        assertEquals(
            "40181",
            InboundDestination.resolve(configured = " 40181 ", fallback = "+79177454214")
        )
    }

    @Test
    fun `an extension and a full number are both usable`() {
        assertTrue(InboundDestination.isValid("40181"))
        assertTrue(InboundDestination.isValid("+79001234567"))
        assertTrue(InboundDestination.isValid(" 40181 "))
    }

    @Test
    fun `a value that cannot be dialled is refused`() {
        assertFalse(InboundDestination.isValid("agent one"))
        assertFalse(InboundDestination.isValid("40181@pbx"))
        assertFalse(InboundDestination.isValid(""))
    }

    @Test
    fun `an unusable destination falls back to the SIM's own number`() {
        // A typo in the field must not drop the call: the old routing key
        // stays in place.
        assertEquals(
            "+79177454214",
            InboundDestination.resolve(configured = "agent one", fallback = "+79177454214")
        )
    }

    @Test
    fun `with no destination the SIM's own number is used`() {
        assertEquals(
            "+79177454214",
            InboundDestination.resolve(configured = "", fallback = "+79177454214")
        )
        assertEquals(
            "+79177454214",
            InboundDestination.resolve(configured = null, fallback = "+79177454214")
        )
    }

    @Test
    fun `nothing to route to is reported as nothing`() {
        assertNull(InboundDestination.resolve(configured = "", fallback = ""))
        assertNull(InboundDestination.resolve(configured = null, fallback = null))
    }
}
