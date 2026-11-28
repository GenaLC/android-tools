package com.callagent.gateway.gsm

import android.telecom.Call
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the cellular call may still be answered.
 *
 * The early answer of a call the phone placed itself has to be offered again
 * until the network takes it: the first attempt lands before the channel is
 * up, and one attempt at the wrong moment is one attempt too few.
 */
class GsmCallStatesTest {

    @Test
    fun `an outgoing call is answered again while it dials`() {
        // The states an outgoing call passes through.  It never rings — the
        // network rings the far end, not this phone.
        assertTrue(GsmCallStates.isUpYet(Call.STATE_NEW))
        assertTrue(GsmCallStates.isUpYet(Call.STATE_DIALING))
        assertTrue(GsmCallStates.isUpYet(Call.STATE_CONNECTING))
    }

    @Test
    fun `an incoming call is answered again while it rings`() {
        assertTrue(GsmCallStates.isUpYet(Call.STATE_RINGING))
    }

    @Test
    fun `a call that is up is not answered again`() {
        assertFalse(GsmCallStates.isUpYet(Call.STATE_ACTIVE))
    }

    @Test
    fun `a call that is going down is not answered again`() {
        assertFalse(GsmCallStates.isUpYet(Call.STATE_DISCONNECTING))
        assertFalse(GsmCallStates.isUpYet(Call.STATE_DISCONNECTED))
    }
}
