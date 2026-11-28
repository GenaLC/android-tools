package com.callagent.gateway.ussd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A USSD code as it was typed, and whether it can be sent.
 *
 * A code is not a phone number: it is a command for the network, written as
 * `*100#`.  The rules here exist so a typo is refused before it reaches the
 * modem, where it would come back as an unknown-code answer nobody can act on.
 */
class UssdRequestTest {

    @Test
    fun `a code is cleaned of the spaces around and inside it`() {
        assertEquals("*100#", UssdRequest.normalize(" * 100 # "))
    }

    @Test
    fun `a well formed code is ready to send`() {
        val prepared = UssdRequest.prepare("*100#")
        assertTrue(prepared is UssdRequest.Prepared.Ready)
        assertEquals("*100#", (prepared as UssdRequest.Prepared.Ready).code)
    }

    @Test
    fun `a code that starts with a hash is a code too`() {
        val prepared = UssdRequest.prepare("#100#")
        assertEquals("#100#", (prepared as UssdRequest.Prepared.Ready).code)
    }

    @Test
    fun `the trailing hash is kept as it was typed`() {
        assertEquals("*100#", UssdRequest.normalize("*100#"))
    }

    @Test
    fun `a code without the closing hash is given one`() {
        // A USSD session is closed by the hash.  Without it the network keeps
        // the session open, waiting for more digits, and the answer never
        // comes — the request would simply hang.
        assertEquals(
            "*100#",
            (UssdRequest.prepare("*100") as UssdRequest.Prepared.Ready).code
        )
    }

    @Test
    fun `nothing typed is refused as empty`() {
        assertEquals(
            UssdRequest.Rejection.EMPTY,
            (UssdRequest.prepare("") as UssdRequest.Prepared.Refused).reason
        )
        assertEquals(
            UssdRequest.Rejection.EMPTY,
            (UssdRequest.prepare("   ") as UssdRequest.Prepared.Refused).reason
        )
    }

    @Test
    fun `a code without the star or the hash is refused`() {
        // Without the prefix the network has no way of telling a code from a
        // number, and a number that reaches the modem is a call.
        assertEquals(
            UssdRequest.Rejection.NO_PREFIX,
            (UssdRequest.prepare("100#") as UssdRequest.Prepared.Refused).reason
        )
        assertEquals(
            UssdRequest.Rejection.NO_PREFIX,
            (UssdRequest.prepare("+100#") as UssdRequest.Prepared.Refused).reason
        )
    }

    @Test
    fun `letters in a code are refused`() {
        assertEquals(
            UssdRequest.Rejection.UNSUPPORTED_CHARACTERS,
            (UssdRequest.prepare("*100#abc") as UssdRequest.Prepared.Refused).reason
        )
    }

    @Test
    fun `a code without a single digit is refused`() {
        assertEquals(
            UssdRequest.Rejection.NO_DIGITS,
            (UssdRequest.prepare("*#") as UssdRequest.Prepared.Refused).reason
        )
    }

    @Test
    fun `a code of the longest length the network carries is sent`() {
        val code = "*" + "1".repeat(UssdRequest.MAX_LENGTH - 2) + "#"
        assertEquals(UssdRequest.MAX_LENGTH, code.length)
        assertTrue(UssdRequest.prepare(code) is UssdRequest.Prepared.Ready)
    }

    @Test
    fun `a code longer than the network carries is refused`() {
        val code = "*" + "1".repeat(UssdRequest.MAX_LENGTH - 1) + "#"
        assertEquals(
            UssdRequest.Rejection.TOO_LONG,
            (UssdRequest.prepare(code) as UssdRequest.Prepared.Refused).reason
        )
    }

    @Test
    fun `the spaces are cleared before the code is judged`() {
        // "*1 0 0#" is one code typed with the fingers spread out; it must not
        // be refused for the spaces.
        assertEquals(
            "*100#",
            (UssdRequest.prepare("*1 0 0#") as UssdRequest.Prepared.Ready).code
        )
    }

    @Test
    fun `every refusal has something to say`() {
        for (reason in UssdRequest.Rejection.values()) {
            assertTrue(reason.describe().isNotBlank())
        }
    }

    @Test
    fun `a code without a prefix is told what a code looks like`() {
        val text = UssdRequest.Rejection.NO_PREFIX.describe()
        assertTrue(text.contains("*"))
        assertTrue(text.contains("#"))
    }

    @Test
    fun `a code with the wrong characters is told what a code may hold`() {
        assertTrue(UssdRequest.Rejection.UNSUPPORTED_CHARACTERS.describe().contains("digits"))
    }

    @Test
    fun `a code that is too long says how long a code may be`() {
        assertTrue(UssdRequest.Rejection.TOO_LONG.describe().contains("${UssdRequest.MAX_LENGTH}"))
    }
}
