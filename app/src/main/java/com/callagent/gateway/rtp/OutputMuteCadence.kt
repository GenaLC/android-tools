package com.callagent.gateway.rtp

/**
 * How often the handset's own loudspeaker is kept closed during a call.
 *
 * The HAL owns the output controls: it rewrites `RCV Mux`, `LOL Mux` and the
 * I2S link every time it programs the call path and every time it switches the
 * output device.  A mute written once is therefore undone within a second —
 * and until the next write, the far end's voice is in the room.  The cadence
 * *is* the size of that window: the shorter it is, the less of the call the
 * room hears.
 *
 * Four seconds is the measured cost of one round trip on this device (six
 * tinymix writes and six read-backs in a single root shell) without crowding
 * it: at three seconds the mixer work would take a quarter of the device's
 * attention, at ten the audible window would be too long.
 *
 * The read-back — the check that the HAL has not undone the mute — costs a
 * second shell call, so it runs every [VERIFY_EVERY] passes rather than every
 * pass.  The mute itself is written every pass, so the audible window never
 * grows beyond [INTERVAL_MS] whichever way the HAL behaves.
 */
object OutputMuteCadence {

    /** Between two writes of the output mute. */
    const val INTERVAL_MS = 4_000L

    /** How many passes between two read-back checks. */
    const val VERIFY_EVERY = 3

    /** The shortest interval that still counts as "every few seconds". */
    const val MIN_INTERVAL_MS = 3_000L

    /** The longest interval that still counts as "every few seconds". */
    const val MAX_INTERVAL_MS = 5_000L

    /**
     * Whether this pass should also read the controls back.
     *
     * @param pass the 1-based number of the pass.
     */
    fun shouldVerify(pass: Int): Boolean = pass % VERIFY_EVERY == 0
}
