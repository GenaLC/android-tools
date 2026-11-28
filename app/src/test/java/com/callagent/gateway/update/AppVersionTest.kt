package com.callagent.gateway.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One version against another.
 *
 * The release feed says which version is newest, but the decision — is that
 * newer than the one running here? — has to be made without a phone in hand,
 * so it lives in plain code that can be tested.
 */
class AppVersionTest {

    @Test
    fun `the tag's v is not part of the version`() {
        assertEquals("1.4.27", AppVersion.normalize("v1.4.27"))
        assertEquals("1.4.27", AppVersion.normalize(" 1.4.27 "))
    }

    @Test
    fun `a suffix is not part of the version either`() {
        assertEquals("1.4.27", AppVersion.normalize("1.4.27-beta"))
        assertEquals("1.4.27", AppVersion.normalize("v1.4.27+build2"))
    }

    @Test
    fun `a version is its numbers, most significant first`() {
        assertEquals(listOf(1, 4, 27), AppVersion.parts("v1.4.27"))
        assertEquals(listOf(2, 0), AppVersion.parts("2.0"))
    }

    @Test
    fun `a higher patch is a newer version`() {
        assertTrue(AppVersion.isNewer("v1.4.27", "1.4.26"))
    }

    @Test
    fun `the same version is not a newer one`() {
        assertFalse(AppVersion.isNewer("1.4.26", "1.4.26"))
        assertFalse(AppVersion.isNewer("v1.4.26", "1.4.26"))
    }

    @Test
    fun `a lower version is not a newer one`() {
        assertFalse(AppVersion.isNewer("1.4.25", "1.4.26"))
    }

    @Test
    fun `the numbers are compared by place, not by length`() {
        assertTrue(AppVersion.isNewer("1.5.0", "1.4.26"))
        assertTrue(AppVersion.isNewer("2.0", "1.9.9"))
        assertFalse(AppVersion.isNewer("1.4", "1.4.1"))
    }

    @Test
    fun `a version that is not there is never newer`() {
        // Nothing to compare against means nothing to install.
        assertFalse(AppVersion.isNewer("", "1.4.26"))
        assertFalse(AppVersion.isNewer("1.4.27", ""))
        assertFalse(AppVersion.isNewer("v", "1.4.26"))
    }

    @Test
    fun `a pre-release of the version running is not newer`() {
        assertFalse(AppVersion.isNewer("1.4.26-beta", "1.4.26"))
    }

    @Test
    fun `the application is the apk, not the module`() {
        assertTrue(AppVersion.isApkAsset("android-tools-1.4.27.apk"))
        assertFalse(AppVersion.isApkAsset("android-tools-module-1.4.27.zip"))
        assertFalse(AppVersion.isApkAsset("android-tools-1.4.27.zip"))
    }
}
