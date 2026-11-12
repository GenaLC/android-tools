package com.callagent.gateway.mediatek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which platform strings count as MediaTek.
 *
 * The two Cubot phones report themselves in several ways depending on the
 * ROM, and the matcher has to recognise them all — without taking a
 * Qualcomm, Exynos or Tensor phone with it.
 */
class MediaTekPlatformTest {

    @Test
    fun theCubotX100IsRecognised() {
        // Dimensity 7025 (MT6855V), by SoC name and by product codename.
        assertTrue(MediaTekPlatform.matches("mt6855"))
        assertTrue(MediaTekPlatform.matches("MT6855V"))
        assertTrue(MediaTekPlatform.matches("mt6855v"))
        assertTrue(MediaTekPlatform.matches("aza"))
    }

    @Test
    fun theCubotX90IsRecognised() {
        // Helio G99 (MT6789), by SoC name and by marketing name.
        assertTrue(MediaTekPlatform.matches("mt6789"))
        assertTrue(MediaTekPlatform.matches("MT6789"))
        assertTrue(MediaTekPlatform.matches("helio g99"))
        assertTrue(MediaTekPlatform.matches("Helio G99"))
    }

    @Test
    fun theRestOfTheFamilyIsRecognised() {
        // MT6768 / Helio G96, MT6767 / G70, MT6833, MT6853 — accepted so a
        // slightly different string still lands on a MediaTek profile.
        assertTrue(MediaTekPlatform.matches("mt6768"))
        assertTrue(MediaTekPlatform.matches("helio g96"))
        assertTrue(MediaTekPlatform.matches("mt6767"))
        assertTrue(MediaTekPlatform.matches("helio g70"))
        assertTrue(MediaTekPlatform.matches("mt6833"))
        assertTrue(MediaTekPlatform.matches("mt6853"))
    }

    @Test
    fun otherVendorsAreNotHijacked() {
        // Qualcomm.
        assertFalse(MediaTekPlatform.matches("sm6150"))
        assertFalse(MediaTekPlatform.matches("sm7150"))
        assertFalse(MediaTekPlatform.matches("sunfish"))
        // Samsung Exynos.
        assertFalse(MediaTekPlatform.matches("exynos9820"))
        assertFalse(MediaTekPlatform.matches("universal7420"))
        // Google Tensor.
        assertFalse(MediaTekPlatform.matches("gs201"))
        assertFalse(MediaTekPlatform.matches("panther"))
    }

    @Test
    fun aPropertyAssignmentIsRecognised() {
        // getprop and Build.MODEL can both arrive wrapped.
        assertTrue(MediaTekPlatform.matches("ro.board.platform=mt6855"))
        assertTrue(MediaTekPlatform.matches("MT6855V"))
        assertFalse(MediaTekPlatform.matches("ro.board.platform=sm6150"))
    }

    @Test
    fun anEmptyOrMissingStringIsNotAPlatform() {
        assertFalse(MediaTekPlatform.matches(""))
        assertFalse(MediaTekPlatform.matches("   "))
        assertFalse(MediaTekPlatform.isMediaTek("", ""))
    }

    @Test
    fun severalStringsAreConsideredAtOnce() {
        assertTrue(MediaTekPlatform.isMediaTek("sunfish", "sunfish", "aza", "sunfish"))
        assertFalse(MediaTekPlatform.isMediaTek("sunfish", "sm6150", "gs201"))
    }

    @Test
    fun theLabelPrefersTheSoCName() {
        assertEquals("MT6855", MediaTekPlatform.label("mt6855", "aza"))
        assertEquals("MT6789", MediaTekPlatform.label("helio g99", "mt6789"))
        assertEquals("AZA", MediaTekPlatform.label("aza"))
        assertEquals("MediaTek", MediaTekPlatform.label("unknown", "thing"))
    }
}
