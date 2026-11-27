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
        // The HAL rewrites the voice mutes when it programs the call path,
        // so the mute written at setup is gone by the time the call is up.
        // Written again here, once the HAL has settled — and only while the
        // digital bridge is on, because the mute is what keeps the handset's
        // own microphone out of the conversation.
        mixerHandsetMuteCmd = modernHandsetMuteCmd(plan),
        mixerVerifyCmd = "",
        halCallParams = MediaTekBgs.CALL_PARAMS + legacyMuteParams(plan),
        halCallParamsRestore = MediaTekBgs.RESTORE_PARAMS + legacyRestoreParams(plan) +
            modernHalRestoreParams(plan),
        halCallParamsLate = modernHalLateParams(plan),
        mixerHandsetOutputMuteCmd = modernOutputMuteCmd(plan),
        mixerHandsetOutputMuteVerifyCmd = modernOutputMuteVerifyCmd(plan),
        // The room's way into the agent's capture: the handset's microphone.
        // It is cut at the analogue stage, where it is still separable from
        // the caller's voice — the caller's voice is digital by then, the
        // microphone is not (measured 2026-11-27 13:10: the caller's voice
        // stayed at 3043 RMS in the capture, and the room was gone from what
        // the agent hears).
        mixerHandsetMicCutCmd = modernMicCutCmd(plan),
        mixerHandsetMicCutVerifyCmd = modernMicCutVerifyCmd(plan),
        mixerHandsetMicCutOffMarker =
            "${MediaTekSpeechMutes.MODERN_MIC_ANALOG}: ${MediaTekSpeechMutes.MODERN_MIC_ANALOG_OFF}",
        // The music stream carries the injection into the modem's uplink, so
        // it is the agent's level to the caller — full, not the 20% that was
        // right when the agent was played out of the speaker instead.
        musicVolPercent = 100,
        captureGain = 1,
        playbackGain = 1,
        // The capture on this chipset is the local mix: the modem's downlink
        // (the caller) *and* the handset's own microphone (the room).  The
        // room must not be merely gated out — the goal is to take the
        // microphone out of the capture altogether, so the capture source
        // has to become a downlink-only path.  Until that path exists the
        // gate stays off: it would only hide quiet room noise while still
        // leaking loud room sounds and clipping the caller's quiet speech.
        noiseGateThreshold = 0,
        echoGateThreshold = 300,
        doubleTalkRatio = 1.5f,
        requireSpeakerMode = false,
        incallMusicParam = "",
        voiceDownlinkWorks = voiceDownlinkWorks(plan),
        voiceCallVolPercent = 100,
        // STREAM_VOICE_CALL: the stream type that reaches the BGS player.
        // With the digital bridge on, the track is moved to the telephony TX
        // device instead (see [DeviceProfile.digitalBridge]) — that is the
        // route the audio policy declares as incall_music_uplink, and it is
        // the only one that puts the agent into the call without the phone
        // speaking it out loud.
        playbackUsage = AudioAttributes.USAGE_VOICE_COMMUNICATION,
        answerRetry = true,
        playbackLeaksIntoCapture = false,
        playbackFadeIn = false,
        // The downlink source may not exist at all on this device — on the
        // MT6855 VOICE_DOWNLINK initialises and then reads nothing but zeros.
        // Let the silence detector retire it and fall through to the next
        // source instead of holding a dead capture for the whole call.
        silenceFallback = true,
        captureSilenceFrames = 300,
        routeChangeDelayMs = 500,
        appopsPropagationMs = 300,
        rootCapture = rootCapture(plan),
        digitalBridge = true,
        // Same as the incall_music route: the injection is digital, so the
        // handset is taken out of the call at the framework level.
        silenceLocalAudio = true,
        // …but not through the framework's microphone mute: on this chipset it
        // takes the whole voice path down, and the agent's voice rides on it.
        frameworkMicMuteSafe = false,
        // …and not through the framework's voice-stream mute either: it takes
        // the modem's downlink out of the capture as well (measured
        // 2026-11-19 17:50).  The vendor's HAL parameter does the silencing.
        frameworkVoiceMuteSafe = false,
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

    /**
     * The commands that take the handset's microphone out of the call, with a
     * readback of the controls they wrote.
     *
     * Empty on a build whose mixer has no such control: writing a name that
     * is not there fails quietly, and a command that does nothing is better
     * than a command that pretends.
     */
    private fun modernHandsetMuteCmd(plan: MediaTekPlan): String =
        if (plan.speechMutes == SpeechMuteFamily.MODERN_MIXER)
            MediaTekSpeechMutes.modernHandsetMuteCmdWithReadback()
        else ""

    /** The HAL parameters that mute the handset, when this build takes them. */
    private fun legacyMuteParams(plan: MediaTekPlan): List<String> =
        if (plan.speechMutes == SpeechMuteFamily.LEGACY_HAL) MediaTekSpeechMutes.LEGACY_CALL_PARAMS else emptyList()

    /** The HAL parameters that undo the handset mute. */
    private fun legacyRestoreParams(plan: MediaTekPlan): List<String> =
        if (plan.speechMutes == SpeechMuteFamily.LEGACY_HAL) MediaTekSpeechMutes.LEGACY_RESTORE_PARAMS else emptyList()

    /**
     * The vendor's uplink mute, applied late — but *not* by default.
     *
     * Measured 2026-11-19 19:12, in one call, with the mute switched off and
     * on again while the call was up: the agent's capture went from
     * `capRMS=13` to `capRMS=1` and back again with it.  So the parameter
     * does take the handset's microphone out of the cellular uplink — and it
     * takes the modem's downlink tap with it, which is the same rule every
     * other voice-path mute on this chipset follows (the HAL does it on
     * purpose: `mMuteDlUlForRoutingCtrl` in
     * `audio.primary.mediatek.so`).  The agent would stop hearing the caller,
     * so the parameter is not applied by the profile any more.
     *
     * The mechanism stays: `/data/local/tmp/gw_hal_param` puts it back for a
     * measurement, and `/data/local/tmp/gw_ul_mute` switches that one
     * parameter off and on during a call.
     */
    private fun modernHalLateParams(plan: MediaTekPlan): List<String> = emptyList()

    /** The same, undone. */
    private fun modernHalRestoreParams(plan: MediaTekPlan): List<String> =
        if (plan.speechMutes == SpeechMuteFamily.MODERN_MIXER) MediaTekSpeechMutes.MODERN_HAL_RESTORE_PARAMS else emptyList()

    /**
     * The handset's loudspeaker, muted at the output device.
     *
     * Applied late: the HAL rewrites these controls when it programs the
     * call path, so they have to go in after the route has settled — and
     * late is also the safe moment, because this mute cannot touch the
     * modem's voice path the agent captures from.
     */
    private fun modernOutputMuteCmd(plan: MediaTekPlan): String =
        if (plan.speechMutes == SpeechMuteFamily.MODERN_MIXER) MediaTekSpeechMutes.modernOutputMuteCmdWithReadback() else ""

    /** The read-only check of the same path — see [DeviceProfile.mixerHandsetOutputMuteVerifyCmd]. */
    private fun modernOutputMuteVerifyCmd(plan: MediaTekPlan): String =
        if (plan.speechMutes == SpeechMuteFamily.MODERN_MIXER) MediaTekSpeechMutes.modernOutputMuteVerifyCmd() else ""

    /**
     * The cut of the handset's microphone at the analogue stage — see
     * [DeviceProfile.mixerHandsetMicCutCmd].
     */
    private fun modernMicCutCmd(plan: MediaTekPlan): String =
        if (plan.speechMutes == SpeechMuteFamily.MODERN_MIXER) MediaTekSpeechMutes.modernMicCutCmd() else ""

    /** The read-only check of the same path — see [DeviceProfile.mixerHandsetMicCutVerifyCmd]. */
    private fun modernMicCutVerifyCmd(plan: MediaTekPlan): String =
        if (plan.speechMutes == SpeechMuteFamily.MODERN_MIXER) MediaTekSpeechMutes.modernMicCutVerifyCmd() else ""

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
        // The HAL rewrites the voice mutes when it programs the call path, so
        // the mute written at setup is gone by the time the call is up.  It
        // is written again here, once the HAL has settled — and only while the
        // digital bridge is on, because the mute is what keeps the handset's
        // own microphone out of the conversation.
        mixerHandsetMuteCmd = modernHandsetMuteCmd(plan),
        mixerVerifyCmd = "",
        // The BGS parameters are deliberately absent: this route does not use
        // the background-sound player, and a parameter the HAL refuses is
        // only a warning in the log.  The speech mutes stay — they are not
        // BGS parameters.
        halCallParams = legacyMuteParams(plan),
        halCallParamsRestore = legacyRestoreParams(plan) + modernHalRestoreParams(plan),
        halCallParamsLate = modernHalLateParams(plan),
        mixerHandsetOutputMuteCmd = modernOutputMuteCmd(plan),
        mixerHandsetOutputMuteVerifyCmd = modernOutputMuteVerifyCmd(plan),
        // The room's way into the agent's capture: the handset's microphone.
        // It is cut at the analogue stage, where it is still separable from
        // the caller's voice — the caller's voice is digital by then, the
        // microphone is not (measured 2026-11-27 13:10: the caller's voice
        // stayed at 3043 RMS in the capture, and the room was gone from what
        // the agent hears).
        mixerHandsetMicCutCmd = modernMicCutCmd(plan),
        mixerHandsetMicCutVerifyCmd = modernMicCutVerifyCmd(plan),
        mixerHandsetMicCutOffMarker =
            "${MediaTekSpeechMutes.MODERN_MIC_ANALOG}: ${MediaTekSpeechMutes.MODERN_MIC_ANALOG_OFF}",
        // The music stream carries the injection into the modem's uplink, so
        // it is the agent's level to the caller — full, not the 20% that was
        // right when the agent was played out of the speaker instead.
        musicVolPercent = 100,
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
        // The downlink source may not exist at all on this device — on the
        // MT6855 VOICE_DOWNLINK initialises and then reads nothing but zeros.
        // Let the silence detector retire it and fall through to the next
        // source instead of holding a dead capture for the whole call.
        silenceFallback = true,
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
        // This is the digital route: the agent goes into the modem's uplink
        // and the handset's microphone is kept out of the call.
        digitalBridge = true,
        // The agent's voice rides into the modem digitally, so the handset
        // itself is not needed in the call: its own downlink must stay out of
        // the earpiece and its microphone out of the call.  The legacy mixer
        // mutes do not reach the VoLTE path on this build — the HAL keeps the
        // mute state itself — so the silence is asked of the framework, which
        // does reach the voice path.
        silenceLocalAudio = true,
        // …but not through the framework's microphone mute: on this chipset it
        // takes the whole voice path down, and the agent's voice rides on it.
        frameworkMicMuteSafe = false,
        // …and not through the framework's voice-stream mute either: measured
        // 2026-11-19 17:50, it takes the modem's downlink out of the capture
        // as well — the caller's voice reached the agent right up to the
        // moment it was applied and was gone afterwards.  The vendor's HAL
        // parameter (Set_SpeechCall_DL_Mute) silences the loudspeaker without
        // touching the capture.
        frameworkVoiceMuteSafe = false,
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
