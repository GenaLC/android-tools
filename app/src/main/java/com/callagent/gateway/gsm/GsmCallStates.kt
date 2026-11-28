package com.callagent.gateway.gsm

import android.telecom.Call

/**
 * When the cellular call may still be answered.
 *
 * The answer is given to the modem, and the modem only takes it when the
 * network has set the channel up.  A call that has not come up yet can
 * therefore be answered again — that is not a second answer, it is the same
 * one being offered until it lands.
 *
 * The states that count as "not up yet" are not only RINGING: a call the
 * phone placed itself goes DIALING → CONNECTING → ACTIVE and never enters
 * RINGING at all.  Judging that call by RINGING alone meant the early
 * answer had exactly one chance to land, and it landed before the network
 * was ready for it.
 */
object GsmCallStates {

    /**
     * True while the call has not come up yet: the answer may still be given.
     *
     * Once the call is active, or on its way down, there is nothing left to
     * answer.
     */
    fun isUpYet(state: Int): Boolean = when (state) {
        Call.STATE_ACTIVE, Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> false
        else -> true
    }
}
