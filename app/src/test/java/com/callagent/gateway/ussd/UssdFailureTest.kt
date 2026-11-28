package com.callagent.gateway.ussd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Why a USSD request came back without an answer.
 *
 * The platform reports failures as numbers that mean nothing to a person, and
 * the codes it defines have very different causes: one says the system
 * refused the request, one says the telephony service was not there to take
 * it, one says the network itself would not answer.
 */
class UssdFailureTest {

    @Test
    fun `a refused request says so`() {
        val text = UssdFailure.NOT_ALLOWED.describe()
        assertTrue(text.contains("not allowed", ignoreCase = true))
    }

    @Test
    fun `an unavailable service says so`() {
        val text = UssdFailure.SERVICE_UNAVAILABLE.describe()
        assertTrue(text.contains("telephony", ignoreCase = true))
    }

    @Test
    fun `a network that would not answer says so`() {
        val text = UssdFailure.NETWORK_FAILED.describe()
        assertTrue(text.contains("network", ignoreCase = true))
    }

    @Test
    fun `any other failure is reported as a failure`() {
        val text = UssdFailure.UNKNOWN.describe()
        assertTrue(text.isNotBlank())
    }

    @Test
    fun `every failure has something to say`() {
        for (failure in UssdFailure.values()) {
            assertTrue(failure.describe().isNotBlank())
        }
    }

    @Test
    fun `the codes are told apart`() {
        assertEquals(UssdFailure.NETWORK_FAILED, UssdFailure.fromCode(-1))
        assertEquals(UssdFailure.SERVICE_UNAVAILABLE, UssdFailure.fromCode(-2))
        assertEquals(UssdFailure.NOT_ALLOWED, UssdFailure.fromCode(-3))
    }

    @Test
    fun `a code nobody knows is reported as unknown`() {
        assertEquals(UssdFailure.UNKNOWN, UssdFailure.fromCode(42))
    }

    @Test
    fun `a request the permission stopped is told so`() {
        assertEquals(UssdFailure.NOT_PERMITTED, UssdFailure.fromCode(-100))
        assertTrue(
            UssdFailure.NOT_PERMITTED.describe().contains("permission", ignoreCase = true)
        )
    }

    @Test
    fun `a request that could not be made at all is told so`() {
        assertEquals(UssdFailure.NOT_SENT, UssdFailure.fromCode(-101))
        assertTrue(UssdFailure.NOT_SENT.describe().isNotBlank())
    }
}
