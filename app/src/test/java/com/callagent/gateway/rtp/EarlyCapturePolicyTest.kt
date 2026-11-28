package com.callagent.gateway.rtp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture policy for the phase before the cellular leg is up.
 *
 * The operator's announcement is played by the network while the leg is
 * still alerting, so the downlink has to be read in that phase — the RTP
 * session used to start only once the leg was answered, and by then the
 * announcement was over.
 */
class EarlyCapturePolicyTest {

    @Test
    fun `the digital sources come first and the room is the last resort`() {
        val sources = EarlyCapturePolicy.sources(downlinkCapturable = true)
        assertEquals(EarlyCapturePolicy.SOURCE_VOICE_CALL, sources.first())
        assertEquals(EarlyCapturePolicy.SOURCE_MIC, sources.last())
        assertTrue(sources.contains(EarlyCapturePolicy.SOURCE_VOICE_DOWNLINK))
    }

    @Test
    fun `the downlink alone is not offered where the profile says it cannot be read`() {
        val sources = EarlyCapturePolicy.sources(downlinkCapturable = false)
        assertFalse(sources.contains(EarlyCapturePolicy.SOURCE_VOICE_DOWNLINK))
        assertEquals(
            listOf(EarlyCapturePolicy.SOURCE_VOICE_CALL, EarlyCapturePolicy.SOURCE_MIC),
            sources
        )
    }

    @Test
    fun `the early phase retires a silent source faster than the rest of the call`() {
        val normal = 300
        assertEquals(normal, EarlyCapturePolicy.silenceLimitFrames(early = false, normal))
        assertEquals(
            EarlyCapturePolicy.SILENCE_LIMIT_FRAMES,
            EarlyCapturePolicy.silenceLimitFrames(early = true, normal)
        )
        // The announcement is short: the usual six seconds would be most of it.
        assertTrue(
            "the early limit must be well under the announcement",
            EarlyCapturePolicy.SILENCE_LIMIT_FRAMES * EarlyCapturePolicy.FRAME_MS <= 2_000
        )
    }
}
