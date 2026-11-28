package com.callagent.gateway.update

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the update check found, in words.
 *
 * The same words go to the operator's screen and into the gateway's log, so
 * they are said in one place.
 */
class AppUpdaterTest {

    @Test
    fun `an application that is up to date says so`() {
        assertTrue(
            AppUpdater.Outcome.UpToDate.describe().contains("up to date", ignoreCase = true)
        )
    }

    @Test
    fun `a build that is there is named`() {
        assertTrue(AppUpdater.Outcome.Available("v1.4.27").describe().contains("1.4.27"))
    }

    @Test
    fun `a build that is over the old one is named`() {
        assertTrue(AppUpdater.Outcome.Installed("v1.4.27").describe().contains("1.4.27"))
    }

    @Test
    fun `a build waiting for a tap is named`() {
        assertTrue(
            AppUpdater.Outcome.InstallerOpened("v1.4.27").describe().contains("1.4.27")
        )
    }

    @Test
    fun `a failure says what went wrong`() {
        assertTrue(AppUpdater.Outcome.Failed("no network").describe().contains("no network"))
    }

    @Test
    fun `every outcome says something`() {
        val outcomes = listOf(
            AppUpdater.Outcome.UpToDate,
            AppUpdater.Outcome.Available("v1.4.27"),
            AppUpdater.Outcome.Installed("v1.4.27"),
            AppUpdater.Outcome.InstallerOpened("v1.4.27"),
            AppUpdater.Outcome.Failed("no network")
        )
        for (outcome in outcomes) {
            assertTrue(outcome.describe().isNotBlank())
        }
    }
}
