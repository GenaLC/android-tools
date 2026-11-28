package com.callagent.gateway.rtp

/**
 * The capture policy for the phase before the cellular leg is up.
 *
 * The operator's announcement is played by the network while the leg is still
 * alerting — before the subscriber picks up and before the agent's phone
 * stops ringing.  It is on the cellular downlink, and the only way it can
 * reach the agent is if that downlink is being captured at that moment: the
 * RTP session used to start only once the leg was answered, and by then the
 * announcement was over.
 *
 * Two things make this phase different from the rest of the call:
 *
 *  * the voice path does not exist yet, so the digital sources may well be
 *    silent.  A silent source is therefore retired after a second and a half
 *    instead of the usual six, so the room is picked up while the
 *    announcement is still being played out loud;
 *  * the room must not reach the agent as if it were the caller, so the
 *    digital sources come first and the handset's microphone is only the
 *    last resort.
 *
 * The injection and the mutes are applied when the leg comes up, not here:
 * the HAL rewrites the voice path when it programs the call, so anything
 * written before that is lost.
 */
object EarlyCapturePolicy {

    /** AudioSource.VOICE_CALL — the digital mix of the cellular uplink and downlink. */
    const val SOURCE_VOICE_CALL = 4

    /** AudioSource.VOICE_DOWNLINK — the cellular downlink on its own. */
    const val SOURCE_VOICE_DOWNLINK = 3

    /** AudioSource.MIC — the room, and with it the announcement the handset is playing. */
    const val SOURCE_MIC = 8

    /** One capture frame is 20 ms. */
    const val FRAME_MS = 20L

    /**
     * How long a source may stay silent in the early phase before the next one
     * is tried: a second and a half.  The usual six seconds would be most of
     * the announcement.
     */
    const val SILENCE_LIMIT_FRAMES = 75

    /**
     * The sources the early phase may use, in order.
     *
     * [downlinkCapturable] says whether the profile believes the modem's
     * downlink can be read on its own; where it cannot, the digital mix is
     * the only digital source there is.
     */
    fun sources(downlinkCapturable: Boolean): List<Int> = buildList {
        add(SOURCE_VOICE_CALL)
        if (downlinkCapturable) add(SOURCE_VOICE_DOWNLINK)
        add(SOURCE_MIC)
    }

    /**
     * The silence limit for a phase.
     *
     * The early one is in a hurry — the announcement is short — while the
     * rest of the call keeps the profile's own limit, which is deliberately
     * patient: a working source retired there costs the agent the caller.
     */
    fun silenceLimitFrames(early: Boolean, normal: Int): Int =
        if (early) SILENCE_LIMIT_FRAMES else normal
}
