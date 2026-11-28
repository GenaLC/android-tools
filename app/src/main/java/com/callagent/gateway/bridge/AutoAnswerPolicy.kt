package com.callagent.gateway.bridge

/**
 * When the cellular leg is answered on our own, and how early.
 *
 * The cellular network says things of its own before the far end picks up:
 * "this number is not available", a billing prompt, a queue announcement.
 * That sound is only on the cellular downlink, and the downlink opens only
 * once the leg is answered — so without an early answer it is never heard at
 * all, and the agent gets a silent call.
 *
 * The early answer therefore has to be early enough to catch the beginning
 * of the announcement and late enough for the network to have set the
 * channel up.  The window is narrow, and it differs per network, so the
 * delay is a setting rather than a constant.
 *
 * Kept free of Android types so it can be tested on its own.
 */
object AutoAnswerPolicy {

    /** How long to wait before answering the cellular leg ourselves. */
    const val DEFAULT_DELAY_MS = 1200

    /** Zero is allowed: answer the moment the call appears. */
    const val MIN_DELAY_MS = 0

    /** Beyond this the announcement has long since ended. */
    const val MAX_DELAY_MS = 15_000

    /** How often the answer is retried while the call object is missing. */
    const val RETRY_MS = 300L

    /** How many times the answer is retried before it is given up on. */
    const val MAX_ATTEMPTS = 10

    /** The delay to wait, from whatever the settings hold. */
    fun delayMs(configured: Int): Int = configured.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)

    /**
     * Whether the answer should be attempted now.
     *
     * [callObjectReady] says whether Telecom has created the call yet: it
     * appears a moment after the ringing callback, and answering a call
     * that does not exist is a no-op.
     *
     * [attempts] counts the tries already made — a call object that never
     * shows up must not be retried for ever.
     */
    fun shouldAttempt(callObjectReady: Boolean, attempts: Int): Boolean =
        callObjectReady || attempts < MAX_ATTEMPTS
}
