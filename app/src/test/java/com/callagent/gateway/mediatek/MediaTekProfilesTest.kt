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
        assertTrue(
            "the downlink mute must be written",
            profile.mixerSetupCmd.contains("Speech_DL_Mute"),
        )
        assertFalse(
            "the legacy parameter must not be written when the mixer has no such control",
            profile.halCallParams.any { it.contains("Set_SpeechCall") },
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
