package com.callagent.gateway.mediatek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The profile that follows from the measurements.
 *
 * What matters here is not the mode but the commands: a profile that writes a
 * control the phone does not have leaves the handset's own microphone in the
 * call, and the only sign of it is a complaint in a log file nobody reads.
 */
class MediaTekProfilesTest {

    private fun plan(
        injection: InjectionMode = InjectionMode.BGS,
        mutes: SpeechMuteFamily = SpeechMuteFamily.MODERN_MIXER,
    ) = MediaTekPlan(
        injection = injection,
        capture = CaptureMethod.VOICE_DOWNLINK,
        telephonyRxPcm = null,
        speechMutes = mutes,
        notes = emptyList(),
    )

    @Test
    fun theModernMixerControlsAreWrittenWhenTheyAreWhatThePhoneHas() {
        val profile = MediaTekProfiles.build(plan(mutes = SpeechMuteFamily.MODERN_MIXER), "MT6855V")
        assertTrue(
            "the uplink mute must be written",
            profile.mixerSetupCmd.contains("Speech_UL_Mute"),
        )
        assertFalse(
            // Measured 2026-11-19 17:50: with the downlink muted in the mixer
            // the caller's voice did not reach the agent's capture at all —
            // the control takes the modem's downlink out of the voice path,
            // not just out of the loudspeaker.
            "the downlink mute must not be written — it takes the caller's voice out of the capture",
            profile.mixerSetupCmd.contains("Speech_DL_Mute"),
        )
        assertFalse(
            "the legacy parameter must not be written when the mixer has no such control",
            profile.halCallParams.any { it.contains("Set_SpeechCall") },
        )
    }

    /**
     * The handset's loudspeaker is silenced at the output device, not in the
     * voice path: the codec's receiver and speaker paths carry the caller's
     * voice out of the phone, and muting them cannot touch what the agent
     * captures.
     */
    @Test
    fun theLoudspeakerIsMutedAtTheOutputDeviceAndLate() {
        val profile = MediaTekProfiles.build(plan(mutes = SpeechMuteFamily.MODERN_MIXER), "MT6855V")
        assertTrue(
            "the receiver path must be silenced",
            profile.mixerHandsetOutputMuteCmd.contains("RCV Mux"),
        )
        assertTrue(
            "the speaker path must be silenced",
            profile.mixerHandsetOutputMuteCmd.contains("LOL Mux"),
        )
        assertTrue(
            // Measured 2026-11-27 by diffing the whole mixer, idle against a
            // live call: the HAL opens the I2S link to the speaker amplifier
            // while the call is up, and the far end's voice reaches the room
            // through it — `RCV Mux` and `LOL Mux` do not gate it.  With the
            // link open the caller is heard in the room, the microphone picks
            // that up, and the caller hears their own voice back.
            "the link that carries the modem's voice to the amplifier must be broken",
            profile.mixerHandsetOutputMuteCmd.contains("I2S3_CH1 HW_SRC_3_OUT_CH1"),
        )
        assertTrue(
            // The sidetone is the handset's own microphone played back
            // locally — the room's voice in the earpiece.
            "the sidetone must be broken",
            profile.mixerHandsetOutputMuteCmd.contains("STF_CH1 ADDA_UL_CH1"),
        )
        assertFalse(
            "the voice path must not be muted to silence the loudspeaker",
            profile.mixerHandsetMuteCmd.contains("Speech_DL_Mute"),
        )
    }

    /** The output path has to be given back when the call is over. */
    @Test
    fun theOutputPathIsGivenBackAfterTheCall() {
        val profile = MediaTekProfiles.build(plan(mutes = SpeechMuteFamily.MODERN_MIXER), "MT6855V")
        assertTrue(profile.mixerRestoreCmd.contains("I2S3_CH1 HW_SRC_3_OUT_CH1"))
        assertTrue(profile.mixerRestoreCmd.contains("STF_CH1 ADDA_UL_CH1"))
    }

    /**
     * The room is cut at the analogue stage, where it is still separable
     * from the caller's voice.
     *
     * Measured 2026-11-27 13:10, in one live call: with the microphone
     * disconnected from the preamp (`PGA_L_Mux` = None) the caller's voice
     * stayed in the capture at 3043 RMS, and the agent stopped hearing the
     * room.  Every other lever that was tried — the source muxes, the AIF
     * node the microphone and the downlink share — took the caller's voice
     * with it, which is why the room was in the capture for so long.
     */
    @Test
    fun theMicrophoneIsCutAtTheAnalogueStage() {
        // Both digital routes have to carry the cut: which one a phone ends
        // up on is decided by the probe, not by us.  The Cubot X100 is
        // measured on INCALL_MUSIC (log of 2026-11-27 13:18).
        for (injection in listOf(InjectionMode.BGS, InjectionMode.INCALL_MUSIC)) {
            val profile = MediaTekProfiles.build(plan(injection = injection), "MT6855V")
            assertTrue(
                "the microphone must be disconnected from the preamp ($injection)",
                profile.mixerHandsetMicCutCmd.contains("PGA_L_Mux"),
            )
            assertTrue(
                "the microphone must be disconnected, not muted ($injection)",
                profile.mixerHandsetMicCutCmd.contains("PGA_L_Mux' None"),
            )
            assertTrue(
                "the cut has to be checked, the HAL puts the microphone back ($injection)",
                profile.mixerHandsetMicCutVerifyCmd.contains("PGA_L_Mux"),
            )
            assertTrue(
                "the marker says what a disconnected microphone looks like ($injection)",
                profile.mixerHandsetMicCutOffMarker.contains("PGA_L_Mux"),
            )
        }
    }

    /** The microphone has to be given back when the call is over. */
    @Test
    fun theMicrophoneIsGivenBackAfterTheCall() {
        for (injection in listOf(InjectionMode.BGS, InjectionMode.INCALL_MUSIC)) {
            val profile = MediaTekProfiles.build(plan(injection = injection), "MT6855V")
            assertTrue(
                "the microphone must be connected again after the call ($injection)",
                profile.mixerRestoreCmd.contains("PGA_L_Mux' AIN0"),
            )
        }
    }

    /**
     * The vendor's own uplink mute is not applied by the profile.
     *
     * Measured 2026-11-19 19:12, in one call, with the parameter switched off
     * and on again while the call was up: the agent's capture went from
     * `capRMS=13` to `capRMS=1` and back again with it.  The parameter does
     * take the handset's microphone out of the cellular uplink — and it takes
     * the modem's downlink tap with it, so the agent stops hearing the
     * caller.  That is the same rule every other voice-path mute on this
     * chipset follows, and the reason none of them may be written by the
     * profile.
     */
    @Test
    fun theVendorUplinkMuteIsNotAppliedByTheProfile() {
        val profile = MediaTekProfiles.build(plan(mutes = SpeechMuteFamily.MODERN_MIXER), "MT6855V")
        assertFalse(
            "the vendor uplink mute must not be written — it takes the caller's voice out of the capture",
            profile.halCallParamsLate.any { it.contains("Set_SpeechCall_UL_Mute") },
        )
        assertTrue(
            // It still has to be undone at the end of the call: the override
            // file can put it there during a call.
            profile.halCallParamsRestore.any { it.startsWith("Set_SpeechCall_UL_Mute=0") },
        )
    }

    @Test
    fun theHandsetIsGivenItsVoiceBackAfterTheCall() {
        val profile = MediaTekProfiles.build(plan(mutes = SpeechMuteFamily.MODERN_MIXER), "MT6855V")
        assertTrue(profile.mixerRestoreCmd.contains("Speech_UL_Mute"))
        assertTrue(profile.mixerRestoreCmd.contains("Speech_DL_Mute"))
    }

    @Test
    fun theLegacyParametersAreWrittenOnTheOlderGeneration() {
        val profile = MediaTekProfiles.build(plan(mutes = SpeechMuteFamily.LEGACY_HAL), "MT6753")
        assertTrue(profile.halCallParams.any { it.startsWith("Set_SpeechCall_UL_Mute=1") })
        assertTrue(profile.halCallParamsRestore.any { it.startsWith("Set_SpeechCall_UL_Mute=0") })
        assertEquals(
            "no mixer command is written when the mixer has no such control",
            "",
            profile.mixerSetupCmd,
        )
    }

    @Test
    fun withoutAnyMuteNothingIsWritten() {
        val profile = MediaTekProfiles.build(plan(mutes = SpeechMuteFamily.NONE), "MT6855V")
        assertEquals("", profile.mixerSetupCmd)
        assertEquals("", profile.mixerRestoreCmd)
        assertFalse(profile.halCallParams.any { it.contains("Set_SpeechCall") })
    }

    /** The BGS parameters and the speech mutes are separate questions. */
    @Test
    fun theBgsParametersAreAlwaysWrittenOnTheBgsRoute() {
        val profile = MediaTekProfiles.build(plan(injection = InjectionMode.BGS), "MT6855V")
        assertTrue(profile.halCallParams.any { it.startsWith("Set_BGS_UL_Mute=0") })
    }

    /** The incall_music route does not use the BGS player — but it still mutes. */
    @Test
    fun theIncallMusicRouteMutesTheHandsetWithoutTheBgsParameters() {
        val profile = MediaTekProfiles.build(
            plan(injection = InjectionMode.INCALL_MUSIC, mutes = SpeechMuteFamily.MODERN_MIXER),
            "MT6855V",
        )
        assertFalse(profile.halCallParams.any { it.contains("Set_BGS") })
        assertTrue(profile.mixerSetupCmd.contains("Speech_UL_Mute"))
    }
}
