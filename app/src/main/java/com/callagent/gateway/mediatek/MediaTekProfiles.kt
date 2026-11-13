package com.callagent.gateway.mediatek

import android.media.AudioAttributes
import com.callagent.gateway.DeviceProfile
import com.callagent.gateway.RootCapture

/**
 * Builds the [DeviceProfile] the gateway runs on, from what the device was
 * found to support.
 *
 * The fields used here are the ones the profile model already has — the
 * mixer commands, the HAL parameters, the gains, the capture and playback
 * usage, the flags.  No parallel mechanism is introduced: the MediaTek
 * route is the same route the MT6753 profile uses, only chosen by
 * measurement rather than by assumption.
 */
object MediaTekProfiles {

    /**
     * The profile for a measured MediaTek device.
     *
     * [label] is the device's own name for its SoC ("MT6855V", "MT6789") and
     * goes into the profile's name, which is what the gateway logs and what
     * the operator sees: the mode is in the name, so a call that does not
     * sound right can be traced back to the mode that was chosen for it.
     */
    fun build(plan: MediaTekPlan, label: String): DeviceProfile = when (plan.injection) {
        InjectionMode.BGS -> bgs(label, plan)
        InjectionMode.INCALL_MUSIC -> incallMusic(label, plan)
        InjectionMode.ACOUSTIC -> acoustic(label, plan)
    }

    /**
     * Whether the modem's downlink is capturable through AudioRecord.
     *
     * The plan decides it, not the injection mode: only the VOICE_DOWNLINK
     * capture says the downlink can be read that way.  With the tinycap PCM
     * the caller's voice comes from an ALSA device instead, and with the
     * acoustic capture it comes from the microphone — in neither case is the
     * AudioRecord downlink the right source, and on some devices it opens
     * successfully and returns silence, which would hold the bridge hostage
     * until the silence detector retires it.
     */
    private fun voiceDownlinkWorks(plan: MediaTekPlan): Boolean =
        plan.capture == CaptureMethod.VOICE_DOWNLINK

    /**
     * The background-sound player: the modem's own path into the uplink.
     *
     * This is the route upstream verified on the MT6753, and it is the
     * route these phones are most likely to keep — but it is only used when
     * the HAL was actually found to have the parameters, because a HAL
     * without them would leave the caller hearing nothing.
     */
    private fun bgs(label: String, plan: MediaTekPlan) = DeviceProfile(
        name = "$label (MediaTek) ${plan.injection} / ${plan.capture}",
        // The MediaTek ALSA card has no routing matrix the bridge can drive
        // — the HAL does the in-call routing itself.  What the mixer does
        // have is the speech mutes, on some generations under one name and
        // on others under another; the probe found out which.
        mixerSetupCmd = modernMuteCmd(plan),
        mixerRestoreCmd = modernRestoreCmd(plan),
        mixerIncallMusicCmd = "",
        mixerVerifyCmd = "",
        halCallParams = MediaTekBgs.CALL_PARAMS + legacyMuteParams(plan),
        halCallParamsRestore = MediaTekBgs.RESTORE_PARAMS + legacyRestoreParams(plan),
        musicVolPercent = 20,
        captureGain = 1,
        playbackGain = 1,
        noiseGateThreshold = 0,
        echoGateThreshold = 300,
        doubleTalkRatio = 1.5f,
        requireSpeakerMode = false,
        incallMusicParam = "",
        voiceDownlinkWorks = voiceDownlinkWorks(plan),
        voiceCallVolPercent = 100,
        // STREAM_VOICE_CALL: the stream type that reaches the BGS player.
        playbackUsage = AudioAttributes.USAGE_VOICE_COMMUNICATION,
        answerRetry = true,
        playbackLeaksIntoCapture = false,
        playbackFadeIn = false,
        silenceFallback = false,
        captureSilenceFrames = 300,
        routeChangeDelayMs = 500,
        appopsPropagationMs = 300,
        rootCapture = rootCapture(plan),
    )

    /**
     * The mixer commands that mute the handset, when this build has them.
     *
     * Empty on a build whose mixer has no such control: writing a name that
     * is not there fails quietly, and a command that does nothing is better
     * than a command that pretends.
     */
    private fun modernMuteCmd(plan: MediaTekPlan): String =
        if (plan.speechMutes == SpeechMuteFamily.MODERN_MIXER) MediaTekSpeechMutes.modernSetupCmd() else ""

    /** The mixer commands that give the handset its voice back. */
    private fun modernRestoreCmd(plan: MediaTekPlan): String =
        if (plan.speechMutes == SpeechMuteFamily.MODERN_MIXER) MediaTekSpeechMutes.modernRestoreCmd() else ""

    /** The HAL parameters that mute the handset, when this build takes them. */
    private fun legacyMuteParams(plan: MediaTekPlan): List<String> =
        if (plan.speechMutes == SpeechMuteFamily.LEGACY_HAL) MediaTekSpeechMutes.LEGACY_CALL_PARAMS else emptyList()

    /** The HAL parameters that undo the handset mute. */
    private fun legacyRestoreParams(plan: MediaTekPlan): List<String> =
        if (plan.speechMutes == SpeechMuteFamily.LEGACY_HAL) MediaTekSpeechMutes.LEGACY_RESTORE_PARAMS else emptyList()

    /**
     * The audio policy's incall_music route: the playback track asks the
     * policy manager for the telephony TX device, and the policy routes it
     * into the modem's uplink.
     */
    private fun incallMusic(label: String, plan: MediaTekPlan) = DeviceProfile(
        name = "$label (MediaTek) ${plan.injection} / ${plan.capture}",
        // The speech mutes are not part of the BGS route: they keep the
        // handset out of the call on this route too, and this build has them
        // under the other name.
        mixerSetupCmd = modernMuteCmd(plan),
        mixerRestoreCmd = modernRestoreCmd(plan),
        mixerIncallMusicCmd = "",
        mixerVerifyCmd = "",
        // The BGS parameters are deliberately absent: this route does not use
        // the background-sound player, and a parameter the HAL refuses is
        // only a warning in the log.  The speech mutes stay — they are not
        // BGS parameters.
        halCallParams = legacyMuteParams(plan),
        halCallParamsRestore = legacyRestoreParams(plan),
        musicVolPercent = 20,
        captureGain = 1,
        playbackGain = 1,
        noiseGateThreshold = 0,
        echoGateThreshold = 300,
        doubleTalkRatio = 1.5f,
        requireSpeakerMode = false,
        incallMusicParam = "incall_music_enabled",
        voiceDownlinkWorks = voiceDownlinkWorks(plan),
        voiceCallVolPercent = 100,
        // STREAM_MUSIC: the usecase the incall_music mixPort is declared for.
        playbackUsage = -1,
        answerRetry = true,
        playbackLeaksIntoCapture = false,
        playbackFadeIn = false,
        silenceFallback = false,
        captureSilenceFrames = 300,
        // The policy only offers the incall_music mixPort while the flag is
        // already on, and binds the track when it is created.
        incallMusicBeforeTrack = true,
        playbackToTelephonyTx = true,
        // The incall_music mixPort declares stereo and nothing else.
        playbackStereo = true,
        routeChangeDelayMs = 500,
        appopsPropagationMs = 300,
        rootCapture = rootCapture(plan),
    )

    /**
     * Nothing digital was found: the agent talks to the caller through the
     * speaker and the caller is heard through the microphone.
     *
     * It is a poor way to hold a conversation, but it is the one way that
     * does not depend on anything the vendor may or may not have built, so
     * it is the floor the gateway falls back to rather than failing a call.
     */
    private fun acoustic(label: String, plan: MediaTekPlan) = DeviceProfile(
        name = "$label (MediaTek) ${plan.injection} / ${plan.capture}",
        mixerSetupCmd = "",
        mixerRestoreCmd = "",
        mixerIncallMusicCmd = "",
        mixerVerifyCmd = "",
        halCallParams = emptyList(),
        halCallParamsRestore = emptyList(),
        // The caller has to be loud on the speaker for the microphone to
        // pick it out of the room.
        musicVolPercent = 60,
        captureGain = 2,
        playbackGain = 2,
        // An acoustic capture carries the room with it, so the gate has to
        // stay in place here.
        noiseGateThreshold = 300,
        echoGateThreshold = 300,
        doubleTalkRatio = 1.5f,
        requireSpeakerMode = true,
        incallMusicParam = "",
        voiceDownlinkWorks = voiceDownlinkWorks(plan),
        voiceCallVolPercent = 100,
        playbackUsage = -1,
        answerRetry = true,
        // The agent's own audio plays on the speaker and the microphone
        // hears it, so the echo gate is needed here — the opposite of the
        // digital profiles.
        playbackLeaksIntoCapture = true,
        playbackFadeIn = true,
        // The downlink source may not exist at all on this device; let the
        // silence detector retire it and fall through to the microphone.
        silenceFallback = true,
        captureSilenceFrames = 100,
        // Raw microphone ahead of the voice-tuned sources: their echo
        // canceller is built to remove the loudspeaker signal we are
        // trying to record.
        preferUnprocessedMic = true,
        preferVoiceRecognition = true,
        routeChangeDelayMs = 700,
        appopsPropagationMs = 300,
        rootCapture = rootCapture(plan),
    )

    /**
     * Read the caller from a telephony-rx PCM over root, when that is the
     * capture the plan chose.
     *
     * No mixer command is issued: the control that routes a telephony PCM
     * to the call's downlink is vendor- and generation-specific, and a
     * guessed control name would be worse than none — the PCM then opens
     * un-routed and reads zeros, which the capture loop notices and falls
     * back from.  The mixer dump in the diagnostic script is where the
     * right control is found.
     */
    private fun rootCapture(plan: MediaTekPlan): RootCapture? {
        val pcm = plan.telephonyRxPcm ?: return null
        return RootCapture(
            card = pcm.card,
            device = pcm.device,
            rate = pcm.rate,
            setupCmd = "",
            restoreCmd = "",
        )
    }
}
