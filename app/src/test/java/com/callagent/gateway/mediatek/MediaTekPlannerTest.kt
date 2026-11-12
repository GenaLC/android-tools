package com.callagent.gateway.mediatek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mode selection, checked on its own.
 *
 * The planner is a pure function of the probe result, so every case here is
 * a question of the form "given this phone, what would the app do?" — no
 * phone needed.
 */
class MediaTekPlannerTest {

    /** A phone whose HAL took the background-sound parameters. */
    private fun bgsPhone() = MediaTekCapabilities(
        rootAvailable = true,
        bgsInHal = true,
        bgsParamResult = BgsParamResult.ACCEPTED,
        bgsParamsConfirmed = true,
    )

    /** A phone with no BGS parameters but an incall_music route in the policy. */
    private fun incallMusicPhone() = MediaTekCapabilities(
        rootAvailable = true,
        bgsInHal = false,
        bgsParamResult = BgsParamResult.REJECTED,
        incallMusicMixPort = true,
        telephonyTxDevicePort = true,
    )

    @Test
    fun bgsAvailableMeansBgs() {
        assertEquals(InjectionMode.BGS, MediaTekPlanner.plan(bgsPhone()).injection)
    }

    @Test
    fun bgsIsPreferredOverIncallMusic() {
        val both = bgsPhone().copy(
            incallMusicMixPort = true,
            telephonyTxDevicePort = true,
        )
        assertEquals(InjectionMode.BGS, MediaTekPlanner.plan(both).injection)
    }

    @Test
    fun withoutBgsAnIncallMusicRouteIsUsed() {
        assertEquals(InjectionMode.INCALL_MUSIC, MediaTekPlanner.plan(incallMusicPhone()).injection)
    }

    @Test
    fun withoutEitherRouteTheCallFallsBackToTheAcousticPath() {
        val neither = MediaTekCapabilities(
            rootAvailable = true,
            bgsInHal = false,
            bgsParamResult = BgsParamResult.REJECTED,
        )
        assertEquals(InjectionMode.ACOUSTIC, MediaTekPlanner.plan(neither).injection)
    }

    /** A mixPort with nowhere to go is not a route. */
    @Test
    fun anIncallMusicMixPortWithoutATelephonyTxPortIsNotARoute() {
        val halfRoute = MediaTekCapabilities(
            incallMusicMixPort = true,
            telephonyTxDevicePort = false,
        )
        assertEquals(InjectionMode.ACOUSTIC, MediaTekPlanner.plan(halfRoute).injection)
    }

    /** The HAL's own copy of the parameters is evidence when it cannot be asked. */
    @Test
    fun aVendorHalWithTheParametersCountsAsBgs() {
        val halOnly = MediaTekCapabilities(bgsInHal = true)
        assertEquals(InjectionMode.BGS, MediaTekPlanner.plan(halOnly).injection)
    }

    /**
     * Taken without complaint is taken: setParameters() throws on a
     * parameter the HAL does not know, so acceptance is the real signal.
     * A HAL that does not answer a read-back is noted, not vetoed.
     */
    @Test
    fun acceptedBgsParametersAreUsedEvenWhenTheyCannotBeReadBack() {
        val half = MediaTekCapabilities(
            bgsInHal = true,
            bgsParamResult = BgsParamResult.ACCEPTED,
            bgsParamsConfirmed = false,
        )
        val plan = MediaTekPlanner.plan(half)
        assertEquals(InjectionMode.BGS, plan.injection)
        assertTrue(
            "the plan must record that the parameters were not read back",
            plan.notes.any { it.contains("did not read them back") },
        )
    }

    /** A HAL that refuses the parameters is not a BGS phone. */
    @Test
    fun refusedBgsParametersAreNotUsed() {
        val refused = MediaTekCapabilities(
            bgsInHal = true,
            bgsParamResult = BgsParamResult.REJECTED,
        )
        assertEquals(InjectionMode.ACOUSTIC, MediaTekPlanner.plan(refused).injection)
    }

    // ── the capture side ─────────────────────────────────────────────────

    @Test
    fun theDownlinkIsCapturedDigitallyWheneverThePolicyHasARoute() {
        val policyRoute = MediaTekCapabilities(telephonyRxInPolicy = true)
        assertEquals(CaptureMethod.VOICE_DOWNLINK, MediaTekPlanner.plan(policyRoute).capture)

        val devicePortRoute = MediaTekCapabilities(telephonyRxDevicePort = true)
        assertEquals(CaptureMethod.VOICE_DOWNLINK, MediaTekPlanner.plan(devicePortRoute).capture)
    }

    @Test
    fun aTelephonyCapturePcmIsUsedWhenThePolicyHasNoRoute() {
        val pcm = AlsaPcm(card = 0, device = 5, name = "voice_rec", rate = 16000)
        val caps = MediaTekCapabilities(telephonyRxPcm = pcm)
        val plan = MediaTekPlanner.plan(caps)
        assertEquals(CaptureMethod.TINYCAP_PCM, plan.capture)
        assertEquals(pcm, plan.telephonyRxPcm)
    }

    @Test
    fun withNeitherTheMicrophoneIsTheLastResort() {
        assertEquals(CaptureMethod.ACOUSTIC, MediaTekPlanner.plan(MediaTekCapabilities()).capture)
    }

    /**
     * VOICE_CALL mixes the uplink into the downlink, and the uplink carries
     * the agent's own injected audio — so the agent would hear itself.  It
     * must not be reachable through any combination of the probe results.
     */
    @Test
    fun voiceCallIsNeverChosenAsTheCaptureSource() {
        assertFalse("VOICE_CALL must not exist as a capture method",
            CaptureMethod.values().any { it.name == "VOICE_CALL" })

        val pcm = AlsaPcm(card = 0, device = 5, name = "voice_rec", rate = 16000)
        // Every combination of the flags that decide the capture.
        for (policy in listOf(false, true)) {
            for (devicePort in listOf(false, true)) {
                for (hasPcm in listOf(false, true)) {
                    val caps = MediaTekCapabilities(
                        telephonyRxInPolicy = policy,
                        telephonyRxDevicePort = devicePort,
                        telephonyRxPcm = if (hasPcm) pcm else null,
                    )
                    val capture = MediaTekPlanner.plan(caps).capture
                    assertFalse("VOICE_CALL chosen for $caps", capture.name == "VOICE_CALL")
                    if (policy || devicePort) {
                        assertEquals(
                            "a telephony RX route in the policy must be used",
                            CaptureMethod.VOICE_DOWNLINK, capture,
                        )
                    }
                }
            }
        }
    }

    /** The reasoning belongs in the plan, not only in the mode. */
    @Test
    fun everyPlanExplainsItself() {
        val plan = MediaTekPlanner.plan(bgsPhone())
        assertTrue("the plan must say why", plan.notes.isNotEmpty())
        assertTrue(
            "the plan must mention the mode it chose",
            plan.notes.any { it.contains("BGS") },
        )
    }
}
