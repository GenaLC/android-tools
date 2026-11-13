package com.callagent.gateway.mediatek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the mixer's control list.
 *
 * The listing in this file is a real one — the output of `tinymix` on a Cubot
 * X100 (MT6855V, Android 15), trimmed to the lines that matter.  The X100 is
 * the phone this gateway has to work on, so what its mixer says is not a
 * hypothetical: writing a control it does not have leaves the handset's own
 * microphone in the call, and the shell swallows the complaint.
 */
class MediaTekMixerTest {

    /** Lines exactly as the X100's tinymix prints them. */
    private val x100Listing = """
        Number of controls: 435
        1    	INT 	1 	Audio IRQ1 CNT                              	0
        354 	BOOL	1 	Speech_Mic_Mute                             	On
        355 	BOOL	1 	Speech_DL_Mute                              	Off
        356 	BOOL	1 	Speech_UL_Mute                              	Off
        388 	ENUM	1 	BTCVSD Band                                 	NB
        396 	BYTES	16	BTCVSD Rx Timestamp                         	(read error)
    """.trimIndent()

    @Test
    fun theControlNamesAreReadFromTheFourthField() {
        val names = MediaTekMixer.controlNames(x100Listing)
        assertTrue(names.contains("Speech_UL_Mute"))
        assertTrue(names.contains("Speech_DL_Mute"))
        assertTrue(names.contains("Speech_Mic_Mute"))
    }

    @Test
    fun theHeaderAndTheLinesWithoutTabsAreNotControls() {
        val names = MediaTekMixer.controlNames(x100Listing)
        assertFalse(names.contains("Number of controls: 435"))
        assertFalse(names.contains("On"))
        assertFalse(names.contains("Off"))
    }

    @Test
    fun theX100IsAModernMixerPhone() {
        assertEquals(
            SpeechMuteFamily.MODERN_MIXER,
            MediaTekMixer.muteFamily(MediaTekMixer.controlNames(x100Listing)),
        )
    }

    /** The older generation, as upstream's MT6753 had it. */
    @Test
    fun theOlderGenerationIsRecognisedByItsOwnNames() {
        val legacy = """
            1    	INT 	1 	Audio IRQ1 CNT                              	0
            120 	BOOL	1 	Set_BGS_UL_Mute                             	Off
            121 	BOOL	1 	Set_BGS_DL_Mute                             	Off
            122 	BOOL	1 	Set_SpeechCall_UL_Mute                      	Off
            123 	BOOL	1 	Set_SpeechCall_DL_Mute                      	Off
        """.trimIndent()
        assertEquals(
            SpeechMuteFamily.LEGACY_HAL,
            MediaTekMixer.muteFamily(MediaTekMixer.controlNames(legacy)),
        )
    }

    /**
     * A mixer with neither family has no way to take the handset out of the
     * call — the plan has to say so rather than pretend otherwise.
     */
    @Test
    fun aMixerWithoutEitherFamilyHasNoMutes() {
        val other = """
            1    	INT 	1 	Audio IRQ1 CNT                              	0
            354 	BOOL	1 	Something_Else                             	On
        """.trimIndent()
        assertEquals(
            SpeechMuteFamily.NONE,
            MediaTekMixer.muteFamily(MediaTekMixer.controlNames(other)),
        )
    }

    /** An empty listing — no root, no tinymix — is no family, not a crash. */
    @Test
    fun anEmptyListingIsNoMutes() {
        assertEquals(SpeechMuteFamily.NONE, MediaTekMixer.muteFamily(MediaTekMixer.controlNames("")))
    }
}
