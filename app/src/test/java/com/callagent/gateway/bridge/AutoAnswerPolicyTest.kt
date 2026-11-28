package com.callagent.gateway.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the cellular leg is answered on our own.
 *
 * The answer has to be early enough to catch the beginning of the operator's
 * announcement and late enough for the network to have set the channel up.
 * The window is narrow and differs per network, so the delay is a setting —
 * these tests pin the bounds that setting is held to.
 */
class AutoAnswerPolicyTest {

    @Test
    fun `the delay is taken as it is asked for`() {
        assertEquals(1200, AutoAnswerPolicy.delayMs(1200))
        assertEquals(0, AutoAnswerPolicy.delayMs(0))
        assertEquals(5000, AutoAnswerPolicy.delayMs(5000))
    }

    @Test
    fun `the delay is held inside the usable window`() {
        assertEquals(AutoAnswerPolicy.MIN_DELAY_MS, AutoAnswerPolicy.delayMs(-1))
        assertEquals(AutoAnswerPolicy.MIN_DELAY_MS, AutoAnswerPolicy.delayMs(-100_000))
        assertEquals(AutoAnswerPolicy.MAX_DELAY_MS, AutoAnswerPolicy.delayMs(999_999))
    }

    @Test
    fun `a call that is there is answered straight away`() {
        assertTrue(AutoAnswerPolicy.shouldAttempt(callObjectReady = true, attempts = 0))
        assertTrue(AutoAnswerPolicy.shouldAttempt(callObjectReady = true, attempts = 99))
    }

    @Test
    fun `a call that is not there yet is waited for`() {
        assertTrue(AutoAnswerPolicy.shouldAttempt(callObjectReady = false, attempts = 0))
        assertTrue(
            AutoAnswerPolicy.shouldAttempt(
                callObjectReady = false,
                attempts = AutoAnswerPolicy.MAX_ATTEMPTS - 1
            )
        )
    }

    @Test
    fun `a call that never appears is given up on`() {
        assertFalse(
            AutoAnswerPolicy.shouldAttempt(
                callObjectReady = false,
                attempts = AutoAnswerPolicy.MAX_ATTEMPTS
            )
        )
        assertFalse(
            AutoAnswerPolicy.shouldAttempt(
                callObjectReady = false,
                attempts = AutoAnswerPolicy.MAX_ATTEMPTS + 5
            )
        )
    }

    @Test
    fun `the default delay is inside the window`() {
        assertTrue(AutoAnswerPolicy.DEFAULT_DELAY_MS >= AutoAnswerPolicy.MIN_DELAY_MS)
        assertTrue(AutoAnswerPolicy.DEFAULT_DELAY_MS <= AutoAnswerPolicy.MAX_DELAY_MS)
    }
}
