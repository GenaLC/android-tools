package com.callagent.gateway.rtp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cadence at which the handset's loudspeaker is kept closed.
 *
 * The HAL rewrites the output controls every time it programs the call path
 * and every time it switches the output device, so the interval between two
 * writes *is* the window in which the far end's voice is in the room.  That
 * interval used to be the statistics tick — fifteen seconds, so a quarter of
 * a minute of every call could be heard in the room.  These tests pin the
 * interval to the few seconds it is now, so that nobody widens it again by
 * accident.
 */
class OutputMuteCadenceTest {

    @Test
    fun theMuteIsReassertedEveryFewSeconds() {
        assertTrue(
            "the output mute must be re-asserted every 3–5 seconds, " +
                "the interval is ${OutputMuteCadence.INTERVAL_MS} ms",
            OutputMuteCadence.INTERVAL_MS >= OutputMuteCadence.MIN_INTERVAL_MS &&
                OutputMuteCadence.INTERVAL_MS <= OutputMuteCadence.MAX_INTERVAL_MS
        )
    }

    @Test
    fun theReadBackIsNotDoneOnEveryPass() {
        assertFalse(
            "the first pass is the write itself — there is nothing to read back yet",
            OutputMuteCadence.shouldVerify(1)
        )
        assertTrue(
            "the read-back must happen every ${OutputMuteCadence.VERIFY_EVERY} passes",
            OutputMuteCadence.shouldVerify(OutputMuteCadence.VERIFY_EVERY)
        )
        assertFalse(
            "the read-back must not happen twice in a row",
            OutputMuteCadence.shouldVerify(OutputMuteCadence.VERIFY_EVERY + 1)
        )
    }

    @Test
    fun everyWindowOfPassesContainsAReadBack() {
        // A HAL that undoes the mute must be seen within one verify period,
        // whichever pass the keeper happens to be on.
        for (start in 1..OutputMuteCadence.VERIFY_EVERY) {
            val window = (start until start + OutputMuteCadence.VERIFY_EVERY)
            assertTrue(
                "no read-back in the passes $window",
                window.any { OutputMuteCadence.shouldVerify(it) }
            )
        }
    }
}
