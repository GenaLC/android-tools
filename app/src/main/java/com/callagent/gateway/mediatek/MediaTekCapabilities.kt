package com.callagent.gateway.mediatek

/**
 * The MediaTek capability layer.
 *
 * Everything in this file is pure Kotlin — no Android APIs, no root, no I/O —
 * so the decision of *which* mechanism a phone uses is a function that can be
 * tested on the JVM.  The measurements it decides on are taken on the device
 * by [MediaTekCapabilityProbe]; the [com.callagent.gateway.DeviceProfile] that
 * follows from the decision is built by [MediaTekProfiles].
 *
 * Why the layer exists at all: the MediaTek route upstream verified was
 * measured on an MT6735/MT6753 running Android 8.1 (madOS, Ulefone Metal).
 * The phones this gateway is being built for run a much newer HAL generation
 * — MT6789 (Helio G99) and MT6855V (Dimensity 7025) on Android 15 — where
 * the modem's background-sound player may not exist any more, and where the
 * audio policy may or may not offer an incall_music route.  None of that can
 * be known from a desk, so the app measures the device and picks, rather
 * than assuming the MT6753 answer still holds.
 */

/**
 * How the agent's audio reaches the cellular uplink.  Ordered by preference:
 * the first mode the device is found to support is the one used.
 */
enum class InjectionMode {
    /**
     * The MediaTek HAL's background-sound player.  During MODE_IN_CALL the
     * HAL hands the call's output streams to the modem's BGS player, which
     * opens with its uplink gain at 0 — the earpiece plays the agent and the
     * caller hears nothing.  `Set_BGS_UL_Mute=0` turns the uplink gain on and
     * `Set_BGS_DL_Mute=1` takes the agent off the earpiece.  This is the
     * route upstream verified on the MT6753.
     */
    BGS,

    /**
     * The audio policy's incall_music mixPort, routed to the "Telephony Tx"
     * device port — the route the Qualcomm profiles use.  The playback track
     * asks the policy manager for the telephony device explicitly, which is
     * what makes the mixPort eligible at all.
     */
    INCALL_MUSIC,

    /**
     * Nothing digital: the agent's audio plays on the speaker and the caller
     * is picked up by the handset's own microphone.  Loud, echo-prone and
     * second best, but it always works, so a call is never lost because the
     * digital route turned out to be missing.
     */
    ACOUSTIC,
}

/**
 * Where the caller's voice is read from.
 *
 * There is deliberately no VOICE_CALL here.  AudioSource.VOICE_CALL mixes
 * the uplink into the downlink, and the uplink is where the agent's own
 * injected audio goes — so the agent would hear itself as if the caller had
 * spoken, and its barge-in detector would cut every phrase in half.  The
 * upstream README documents this; the capture side of this gateway is
 * downlink-only by construction.
 */
enum class CaptureMethod {
    /** AudioSource.VOICE_DOWNLINK through AudioRecord. */
    VOICE_DOWNLINK,

    /** A telephony-rx ALSA PCM read with tinycap under root. */
    TINYCAP_PCM,

    /** The handset's own microphone. */
    ACOUSTIC,
}

/** What happened when the BGS parameters were put to the audio HAL. */
enum class BgsParamResult {
    /** No AudioManager was reachable, so the HAL was never asked. */
    NOT_TESTED,

    /** The HAL took the parameters without complaining. */
    ACCEPTED,

    /** The HAL refused them — this build has no such parameter. */
    REJECTED,
}

/**
 * Which mute controls keep the handset itself out of the bridged call.
 *
 * The names changed between MediaTek generations, and the gateway has to use
 * the ones the phone actually has:
 *
 *  - the MT6753 generation (upstream's phone, Android 8.1) took them as HAL
 *    parameters: Set_SpeechCall_UL_Mute / Set_SpeechCall_DL_Mute;
 *  - the MT6855V generation (Cubot X100, Android 15) has no such parameter —
 *    tinymix answers "control not found" — but offers mixer controls of its
 *    own: Speech_UL_Mute / Speech_DL_Mute.
 *
 * Writing a name the build does not have is not an error: the shell swallows
 * the complaint.  It is a mute that never happens, with the handset's own
 * microphone still in the call.  So the family is measured, not guessed.
 */
enum class SpeechMuteFamily {
    /** No mute of any kind was found — the handset stays in the call. */
    NONE,

    /** The HAL parameters of the older generation. */
    LEGACY_HAL,

    /** The mixer controls of the newer generation. */
    MODERN_MIXER,
}

/**
 * An ALSA PCM found on the device.
 *
 * [card] and [device] are the numbers `tinycap -- -D <card> -d <device>`
 * wants; [name] is the name the kernel gave the PCM, kept for the log and
 * for the diagnostic script to match against.  [capture] says the PCM has a
 * capture side at all — a playback-only PCM cannot carry the caller.
 */
data class AlsaPcm(
    val card: Int,
    val device: Int,
    val name: String,
    val rate: Int,
    val capture: Boolean = true,
)

/**
 * What the device was found to support.  One instance per probe run; the
 * planner turns it into a [MediaTekPlan].
 */
data class MediaTekCapabilities(
    /** Root shell answered — without it none of the HAL-side checks ran. */
    val rootAvailable: Boolean = false,

    /** A runnable tinymix was found. */
    val tinymixAvailable: Boolean = false,

    /** The vendor audio HAL carries the BGS parameter strings. */
    val bgsInHal: Boolean = false,

    /** What the HAL said when the BGS parameters were put to it. */
    val bgsParamResult: BgsParamResult = BgsParamResult.NOT_TESTED,

    /** The HAL echoed the BGS parameters back, or read them back for us. */
    val bgsParamsConfirmed: Boolean = false,

    /** The audio policy declares an incall_music mixPort. */
    val incallMusicMixPort: Boolean = false,

    /** The audio policy declares a "Telephony Tx" device port. */
    val telephonyTxDevicePort: Boolean = false,

    /** Some mixPort sinks into "Telephony Tx". */
    val telephonyTxRouted: Boolean = false,

    /** The audio policy declares a "Telephony Rx" device port. */
    val telephonyRxDevicePort: Boolean = false,

    /** `dumpsys media.audio_policy` mentions TELEPHONY_RX. */
    val telephonyRxInPolicy: Boolean = false,

    /** A telephony-rx capture PCM that tinycap could read, if one exists. */
    val telephonyRxPcm: AlsaPcm? = null,

    /**
     * Which mute controls the mixer of this build was found to have.
     *
     * Measured by listing the mixer controls: a control that is not there
     * cannot be written, and the write fails quietly.
     */
    val speechMuteFamily: SpeechMuteFamily = SpeechMuteFamily.NONE,

    /**
     * The HAL took the older generation's speech-mute parameters.
     *
     * The evidence for [SpeechMuteFamily.LEGACY_HAL] when the mixer does not
     * list the controls: the parameter was put to the HAL and came back.
     */
    val legacySpeechMuteAccepted: Boolean = false,
) {
    /**
     * Whether AudioSource.VOICE_DOWNLINK has a route to read from.
     *
     * The source exists on every Android; what it needs is a telephony RX
     * route the policy will hand it, which is either a "Telephony Rx" device
     * port in the policy file or a TELEPHONY_RX device in the running policy.
     */
    val voiceDownlinkSupported: Boolean
        get() = telephonyRxDevicePort || telephonyRxInPolicy
}

/**
 * The decision: which mechanism to use, and what it was decided on.
 *
 * [notes] carries the reasoning in the operator's words — it is what the log
 * shows when a call does not sound right, and it is why the mode is in the
 * profile's name.
 */
data class MediaTekPlan(
    val injection: InjectionMode,
    val capture: CaptureMethod,
    /** The PCM to read the caller from, when [capture] is TINYCAP_PCM. */
    val telephonyRxPcm: AlsaPcm?,
    /** Which mute controls the profile uses to keep the handset out of the call. */
    val speechMutes: SpeechMuteFamily = SpeechMuteFamily.NONE,
    val notes: List<String>,
)

/**
 * The HAL parameters of the background-sound player.
 *
 * They are the ones the MT6753 profile sets during a call, and the ones the
 * probe puts to the HAL to find out whether this build has them at all.
 *
 * The speech mutes that go with them live in [MediaTekSpeechMutes]: they are
 * a separate question, because a build can have the BGS parameters and no
 * speech mute at all — the Cubot X100 is exactly that case.
 */
object MediaTekBgs {
    /** Set once the call is up: agent into the uplink, handset off the earpiece. */
    val CALL_PARAMS = listOf(
        "Set_BGS_UL_Mute=0",         // injected audio reaches the caller
        "Set_BGS_DL_Mute=1",         // ... and no longer plays on the earpiece
    )

    /** The other way round, for ordinary phone use after the call. */
    val RESTORE_PARAMS = listOf(
        "Set_BGS_UL_Mute=1",
        "Set_BGS_DL_Mute=0",
    )
}

/**
 * Reading the mixer's control list.
 *
 * Pure on purpose: the listing is text, and the question "which mute controls
 * does this build have?" is a question about that text.  The answer decides
 * whether the handset's own microphone stays in the call, so it is worth
 * testing on its own.
 *
 * The measurement that produced this: a Cubot X100 (MT6855V, Android 15)
 * answered "control not found" for Set_BGS_UL_Mute, Set_SpeechCall_UL_Mute
 * and incall_music_enabled, and offered Speech_UL_Mute / Speech_DL_Mute
 * instead.
 */
object MediaTekMixer {

    /**
     * The control names in a `tinymix` listing.
     *
     * tinymix prints one control per line, tab separated: index, type,
     * channel, name, value.  The name is the fourth field; the lines that
     * are not controls are skipped.
     */
    fun controlNames(listing: String): Set<String> {
        val names = HashSet<String>()
        for (line in listing.lines()) {
            val fields = line.split('\t')
            if (fields.size < 4) continue
            val name = fields[3].trim()
            if (name.isNotEmpty()) names += name
        }
        return names
    }

    /** Which mute family the given controls make possible. */
    fun muteFamily(controls: Set<String>): SpeechMuteFamily = when {
        controls.contains(MediaTekSpeechMutes.MODERN_UL) ||
            controls.contains(MediaTekSpeechMutes.MODERN_DL) -> SpeechMuteFamily.MODERN_MIXER
        controls.contains(LEGACY_UL) || controls.contains(LEGACY_DL) -> SpeechMuteFamily.LEGACY_HAL
        else -> SpeechMuteFamily.NONE
    }

    /** The controls of the older generation, by name. */
    const val LEGACY_UL = "Set_SpeechCall_UL_Mute"
    const val LEGACY_DL = "Set_SpeechCall_DL_Mute"
}

/**
 * The controls that take the handset itself out of the call.
 *
 * Two generations, two spellings, and they are not interchangeable: the
 * X100's mixer has no Set_SpeechCall_* control at all.
 */
object MediaTekSpeechMutes {

    /** The HAL parameters of the older generation. */
    val LEGACY_CALL_PARAMS = listOf(
        "Set_SpeechCall_UL_Mute=1",  // handset microphone out of the call
        "Set_SpeechCall_DL_Mute=1",  // caller no longer plays on the earpiece
    )

    /** The other way round, for ordinary phone use after the call. */
    val LEGACY_RESTORE_PARAMS = listOf(
        "Set_SpeechCall_UL_Mute=0",
        "Set_SpeechCall_DL_Mute=0",
    )

    /** The mixer control of the newer generation for the uplink leg. */
    const val MODERN_UL = "Speech_UL_Mute"

    /** The mixer control of the newer generation for the downlink leg. */
    const val MODERN_DL = "Speech_DL_Mute"

    /**
     * The mixer control that takes the handset's microphone out of the voice
     * path.
     *
     * `Speech_UL_Mute` mutes the microphone in the *cellular* uplink, so the
     * room does not reach the caller.  This one mutes it in the *voice* path,
     * which is where the app's AudioRecord taps the caller — without it the
     * handset's microphone still ends up in what the agent hears.
     *
     * The polarity follows the same convention as the other two (1 = muted);
     * the command is written with a readback so a build that disagrees shows
     * up in the log instead of quietly doing the opposite. */
    const val MODERN_MIC = "Speech_Mic_Mute"

    /**
     * The mixer control that picks the analogue input of the left PGA — the
     * preamp the handset's own microphone (AIN0) comes in through.
     *
     * `Speech_Mic_Mute` above is the vendor's own mute of the microphone in
     * the *voice path*: the HAL sets it to `On` by itself when the call is up,
     * and the room still reaches the agent through it.  This control is one
     * stage earlier — the microphone is not muted there, it is *not connected*.
     *
     * The two legs of the conversation live on different wires at this point:
     * the caller's voice is the modem's downlink and arrives in the CPU
     * digitally, while the room is the handset's microphone and has to be
     * converted and amplified first.  Disconnecting the microphone at the
     * analogue stage therefore takes the room out of the capture and leaves
     * the caller's voice alone — the one thing every other lever on this
     * chipset could not do (measured 2026-11-27 13:10).
     */
    const val MODERN_MIC_ANALOG = "PGA_L_Mux"

    /** The value that disconnects the microphone from the preamp. */
    const val MODERN_MIC_ANALOG_OFF = "None"

    /** The value that connects the built-in microphone (AIN0) to the preamp. */
    const val MODERN_MIC_ANALOG_ON = "AIN0"

    /**
     * The mixer commands that mute the handset during the call.
     *
     * `Speech_Mic_Mute` is deliberately left alone here: it is the voice-recording
     * mute and its polarity differs between builds, so touching it could
     * mute the agent's own microphone.  The digital bridge writes it
     * separately, once the HAL has settled — see [modernHandsetMuteCmd].
     *
     * `Speech_DL_Mute` is **not** written.  Measured 2026-11-19 17:50: with
     * the downlink muted in the mixer, the caller's voice did not reach the
     * agent's capture at all — the control takes the modem's downlink out of
     * the voice path, not just out of the loudspeaker.  The same goes for the
     * vendor's own HAL parameter (`Set_SpeechCall_DL_Mute=1`) and for the
     * framework's voice-stream mute: every mute of the *voice* path on this
     * chipset takes the downlink with it.  The handset's loudspeaker is
     * silenced at the **output device** instead — see
     * [modernOutputMuteCmd].
     */
    fun modernSetupCmd(): String =
        "tinymix '$MODERN_UL' 1 2>/dev/null"

    /** The mixer commands that give the handset back its voice. */
    fun modernRestoreCmd(): String =
        "tinymix '$MODERN_UL' 0 2>/dev/null; " +
            "tinymix '$MODERN_DL' 0 2>/dev/null; " +
            "tinymix '$MODERN_MIC' 0 2>/dev/null; " +
            "tinymix '$MODERN_I2S_VOICE_CH1' 0 2>/dev/null; " +
            "tinymix '$MODERN_I2S_VOICE_CH2' 0 2>/dev/null; " +
            "tinymix '$MODERN_SIDETONE_CH1' 0 2>/dev/null; " +
            "tinymix '$MODERN_SIDETONE_CH2' 0 2>/dev/null; " +
            // The microphone is given back connected rather than in the
            // device's own idle state: leaving it disconnected would be a
            // dead microphone if the HAL did not program it again for the
            // next call, and a connected microphone costs nothing while no
            // call is up.
            "tinymix '$MODERN_MIC_ANALOG' $MODERN_MIC_ANALOG_ON 2>/dev/null"

    /**
     * Disconnect the handset's microphone from the analogue preamp.
     *
     * Applied late — the HAL puts the microphone back (AIN0) when it programs
     * the call path — and with a readback, so a build that does not have the
     * control shows up in the log instead of quietly doing nothing.
     */
    fun modernMicCutCmd(): String =
        "tinymix '$MODERN_MIC_ANALOG' $MODERN_MIC_ANALOG_OFF 2>/dev/null; " +
            "echo '$MODERN_MIC_ANALOG:'; tinymix '$MODERN_MIC_ANALOG' 2>/dev/null"

    /** The microphone input as it is *now*, without writing anything. */
    fun modernMicCutVerifyCmd(): String =
        "echo '$MODERN_MIC_ANALOG:'; tinymix '$MODERN_MIC_ANALOG' 2>/dev/null"

    /**
     * The commands that take the handset out of the call for good: the
     * cellular uplink (the room does not reach the caller) and the voice path
     * (the room does not reach the agent either).
     *
     * Written after the HAL has programmed the call path, because the HAL
     * rewrites the voice mutes when it does — a mute written before that is
     * discarded, which is exactly why the handset ended up in the call.
     *
     * The downlink is deliberately **not** muted here: that control takes the
     * modem's downlink out of the voice path, and with it the caller's voice
     * out of the agent's capture (measured 2026-11-19 17:50).  The handset's
     * loudspeaker is silenced at the output device instead — see
     * [modernOutputMuteCmd]. */
    fun modernHandsetMuteCmd(): String =
        "tinymix '$MODERN_UL' 1 2>/dev/null; tinymix '$MODERN_MIC' 1 2>/dev/null"

    /**
     * The same commands with a readback of the controls, so the log
     * shows what the mixer actually holds instead of what was asked for.
     *
     * The bare 'tinymix' is resolved to the full path by
     * [DeviceProfile.resolveCmd], which replaces every occurrence. */
    fun modernHandsetMuteCmdWithReadback(): String =
        "tinymix '$MODERN_UL' 1 2>/dev/null; tinymix '$MODERN_MIC' 1 2>/dev/null; " +
            "echo 'Speech_UL_Mute:'; tinymix '$MODERN_UL' 2>/dev/null; " +
            "echo 'Speech_Mic_Mute:'; tinymix '$MODERN_MIC' 2>/dev/null"

    /**
     * The handset's loudspeaker, muted at the output device.
     *
     * This is the one mute that cannot hurt the bridge: `RCV Mux` and
     * `LOL Mux` are the codec's receiver and speaker paths — the place where
     * the caller's voice leaves the phone — and not the voice path the agent
     * captures from.  `/vendor/etc/audio_device.xml` shows what the HAL does
     * with them:
     *
     * ```xml
     * <path name="receiver_output" value="turnon">
     *     <kctl name="RCV Mux" value="Voice Playback" />   ← абонент в ухе
     * <path name="receiver_output" value="turnoff">
     *     <kctl name="RCV Mux" value="Open" />              ← тишина
     * <path name="dual_speaker_output" value="turnon">
     *     <kctl name="LOL Mux" value="Playback" />          ← абонент в динамике
     * ```
     *
     * So the same control that carries the caller's voice out of the phone
     * carries it out of silence when set to `Open` — and the modem's voice
     * path, which the agent reads, is not touched.
     *
     * Applied late, once the route has settled: the HAL rewrites these
     * controls when it programs the call path and when it switches the
     * output device.
     */
    fun modernOutputMuteCmd(): String =
        "tinymix '$MODERN_RCV_MUX' Open 2>/dev/null; tinymix '$MODERN_LOL_MUX' Open 2>/dev/null"

    /** The same with a readback, so the log shows what the mixer holds. */
    fun modernOutputMuteCmdWithReadback(): String =
        "tinymix '$MODERN_RCV_MUX' Open 2>/dev/null; tinymix '$MODERN_LOL_MUX' Open 2>/dev/null; " +
            "tinymix '$MODERN_I2S_VOICE_CH1' 0 2>/dev/null; tinymix '$MODERN_I2S_VOICE_CH2' 0 2>/dev/null; " +
            "tinymix '$MODERN_SIDETONE_CH1' 0 2>/dev/null; tinymix '$MODERN_SIDETONE_CH2' 0 2>/dev/null; " +
            "echo '$MODERN_RCV_MUX:'; tinymix '$MODERN_RCV_MUX' 2>/dev/null; " +
            "echo '$MODERN_LOL_MUX:'; tinymix '$MODERN_LOL_MUX' 2>/dev/null; " +
            "echo '$MODERN_I2S_VOICE_CH1:'; tinymix '$MODERN_I2S_VOICE_CH1' 2>/dev/null; " +
            "echo '$MODERN_SIDETONE_CH1:'; tinymix '$MODERN_SIDETONE_CH1' 2>/dev/null"

    /**
     * The output path as it is *now*, without writing anything.
     *
     * The codec's DAC input mux and the two output muxes are what decide
     * whether the caller is audible in the room; the HAL rewrites them when
     * it programs the call path, so the only honest answer comes from a
     * read-back taken after the write.
     */
    fun modernOutputMuteVerifyCmd(): String =
        "echo '$MODERN_RCV_MUX:'; tinymix '$MODERN_RCV_MUX' 2>/dev/null; " +
            "echo '$MODERN_LOL_MUX:'; tinymix '$MODERN_LOL_MUX' 2>/dev/null; " +
            "echo 'DAC In Mux:'; tinymix 'DAC In Mux' 2>/dev/null; " +
            "echo 'Handset Volume:'; tinymix 'Handset Volume' 2>/dev/null; " +
            "echo '$MODERN_I2S_VOICE_CH1:'; tinymix '$MODERN_I2S_VOICE_CH1' 2>/dev/null; " +
            "echo '$MODERN_I2S_VOICE_CH2:'; tinymix '$MODERN_I2S_VOICE_CH2' 2>/dev/null; " +
            "echo '$MODERN_SIDETONE_CH1:'; tinymix '$MODERN_SIDETONE_CH1' 2>/dev/null; " +
            "echo '$MODERN_SIDETONE_CH2:'; tinymix '$MODERN_SIDETONE_CH2' 2>/dev/null"

    /** The codec's receiver path — the earpiece. */
    const val MODERN_RCV_MUX = "RCV Mux"

    /** The codec's speaker path — the loudspeaker. */
    const val MODERN_LOL_MUX = "LOL Mux"

    /**
     * The modem's voice on its way to the speaker amplifier.
     *
     * Measured 2026-11-27 by diffing the whole mixer, idle against a live
     * call: the HAL opens exactly this chain while the call is up —
     *
     * ```
     * PCM_2_CAP → HW_SRC_3 → I2S3 → усилитель (профиль Receiver)
     * ```
     *
     * and closes it again when the call ends.  `RCV Mux` and `LOL Mux` do
     * **not** gate it: they are the codec's analog output muxes, and the
     * voice of the far end reaches the room through the I2S link.  With that
     * link open the room hears the caller, the handset's microphone picks it
     * up again, and the caller hears their own voice back — the "extra call"
     * that made the gateway unpleasant to sit next to.
     */
    const val MODERN_I2S_VOICE_CH1 = "I2S3_CH1 HW_SRC_3_OUT_CH1"
    const val MODERN_I2S_VOICE_CH2 = "I2S3_CH2 HW_SRC_3_OUT_CH2"

    /**
     * The sidetone: the handset's own microphone played back locally.
     *
     * The same diff shows the HAL opening `STF_CH1/CH2 ADDA_UL_CH*` for the
     * duration of the call — the room's own voice in the earpiece, which the
     * microphone then picks up a second time.
     */
    const val MODERN_SIDETONE_CH1 = "STF_CH1 ADDA_UL_CH1"
    const val MODERN_SIDETONE_CH2 = "STF_CH2 ADDA_UL_CH2"

    /**
     * The vendor's own uplink mute, as a HAL parameter.
     *
     * The mixer control `Speech_UL_Mute` does not take the handset's
     * microphone out of the cellular uplink on this build — measured
     * 2026-11-19 18:03: it was written and read back as `On`, and the
     * microphone was still in the call.  The HAL keeps the real mute state
     * itself (`mMicMute`, `mUplinkMuteOn`, `mUplinkSourceMuteOn` in
     * `audio.primary.mediatek.so`), and only its own parameters change it;
     * `Set_SpeechCall_UL_Mute` is the key the upstream project uses on
     * MediaTek for exactly this.
     *
     * Applied late, because it may mute the whole uplink rather than the
     * microphone source — and the agent's voice is injected into that same
     * uplink (`incall_music_uplink → Telephony Tx`).  If it turns out to take
     * the injection with it, the log shows the capture level dropping right
     * after it, and the parameter comes back out.
     */
    val MODERN_HAL_LATE_PARAMS = listOf(
        "Set_SpeechCall_UL_Mute=1",
    )

    /** The same, undone: the phone gets its microphone back. */
    val MODERN_HAL_RESTORE_PARAMS = listOf(
        "Set_SpeechCall_UL_Mute=0",
    )
}

/**
 * Turns measurements into a decision.
 *
 * Pure on purpose: the ordering below is the whole of the policy, and a
 * policy that cannot be tested is a policy that is quietly wrong.
 */
object MediaTekPlanner {

    /**
     * Pick the injection mode and the capture method.
     *
     * Injection, in order of preference:
     *  1. BGS — the HAL was found to have the background-sound parameters
     *     and either took them or was never asked (the vendor HAL carries
     *     the strings, which is the same evidence in a different form).
     *  2. INCALL_MUSIC — the audio policy declares an incall_music mixPort
     *     *and* a "Telephony Tx" device port to route it to.
     *  3. ACOUSTIC — neither, so the speaker and the microphone it is.
     *
     * Capture, in order of preference:
     *  1. VOICE_DOWNLINK — the policy has a telephony RX route.
     *  2. TINYCAP_PCM — no such route in the policy, but a telephony-rx
     *     capture PCM exists that tinycap can read over root.
     *  3. ACOUSTIC — the microphone.
     */
    fun plan(caps: MediaTekCapabilities): MediaTekPlan {
        val notes = ArrayList<String>()

        val bgs = when {
            caps.bgsParamResult == BgsParamResult.REJECTED -> {
                notes += "BGS: the audio HAL refused the background-sound parameters"
                null
            }
            caps.bgsParamResult == BgsParamResult.ACCEPTED -> {
                // Accepted is the signal that matters: setParameters() only
                // survives a parameter the HAL parsed, and throws on one it
                // does not know.  The read-back is the second half of the
                // question — whether the parameter changed anything — but a
                // HAL that stores a parameter without answering a query
                // about it is still a HAL that has the parameter, so the
                // route is kept and the doubt is written down.
                notes += if (caps.bgsParamsConfirmed) {
                    "BGS: the audio HAL took and confirmed the background-sound parameters"
                } else {
                    "BGS: the audio HAL took the background-sound parameters but did not " +
                        "read them back — if the caller hears nothing, this is the first " +
                        "thing to check"
                }
                InjectionMode.BGS
            }
            caps.bgsInHal -> {
                notes += "BGS: the vendor audio HAL carries the background-sound parameters " +
                    "(the HAL could not be asked directly)"
                InjectionMode.BGS
            }
            else -> {
                notes += "BGS: no background-sound parameters in the vendor audio HAL"
                null
            }
        }

        val incallMusic = when {
            caps.incallMusicMixPort && caps.telephonyTxDevicePort -> {
                notes += "incall_music: the audio policy declares an incall_music mixPort " +
                    "and a Telephony Tx device port"
                InjectionMode.INCALL_MUSIC
            }
            caps.incallMusicMixPort -> {
                notes += "incall_music: the mixPort is declared but there is no Telephony Tx " +
                    "device port to route it to"
                null
            }
            else -> {
                notes += "incall_music: no incall_music mixPort in the audio policy"
                null
            }
        }

        val injection = when {
            // The background-sound route depends on a HAL parameter that the
            // newer generations take without ever confirming it.  Where the
            // audio policy declares the incall_music route together with a
            // telephony TX device port, that route is the one to use: it is
            // declared in the policy file itself, it puts the agent into the
            // modem's uplink, and it does not need the phone's speaker or
            // microphone at all.
            incallMusic != null && caps.bgsParamsConfirmed.not() &&
                caps.bgsParamResult != BgsParamResult.REJECTED -> {
                notes += "injection: the background-sound parameters were never confirmed, so " +
                    "the policy's incall_music route is used instead"
                incallMusic
            }
            bgs != null -> bgs
            incallMusic != null -> incallMusic
            else -> {
                notes += "no digital injection route found — falling back to speaker and microphone"
                InjectionMode.ACOUSTIC
            }
        }

        val capture = when {
            caps.voiceDownlinkSupported -> {
                notes += "capture: the audio policy has a telephony RX route — " +
                    "AudioSource.VOICE_DOWNLINK"
                CaptureMethod.VOICE_DOWNLINK
            }
            caps.telephonyRxPcm != null -> {
                notes += "capture: no telephony RX route in the policy, but ${caps.telephonyRxPcm.name} " +
                    "is a capture PCM tinycap can read over root"
                CaptureMethod.TINYCAP_PCM
            }
            else -> {
                notes += "capture: no telephony RX route and no capture PCM — the microphone it is"
                CaptureMethod.ACOUSTIC
            }
        }

        // Which mute controls to write.  The mixer listing is the evidence:
        // a control that is not listed cannot be written, and writing it
        // fails quietly.  The HAL parameters of the older generation are the
        // fallback, but only when the HAL actually took them.
        val mutes = when {
            caps.speechMuteFamily == SpeechMuteFamily.MODERN_MIXER -> {
                notes += "mutes: the mixer has ${MediaTekSpeechMutes.MODERN_UL} / " +
                    "${MediaTekSpeechMutes.MODERN_DL} — the handset is taken out of the call " +
                    "through them"
                SpeechMuteFamily.MODERN_MIXER
            }
            caps.speechMuteFamily == SpeechMuteFamily.LEGACY_HAL || caps.legacySpeechMuteAccepted -> {
                notes += "mutes: the HAL takes the Set_SpeechCall_* parameters — the handset is " +
                    "taken out of the call through them"
                SpeechMuteFamily.LEGACY_HAL
            }
            else -> {
                notes += "mutes: no usable mute control was found — the handset's own " +
                    "microphone stays in the call"
                SpeechMuteFamily.NONE
            }
        }

        return MediaTekPlan(
            injection = injection,
            capture = capture,
            telephonyRxPcm = if (capture == CaptureMethod.TINYCAP_PCM) caps.telephonyRxPcm else null,
            speechMutes = mutes,
            notes = notes,
        )
    }
}

/**
 * Which platform strings mean MediaTek.
 *
 * The two Cubot phones report themselves in more than one way, and which
 * way depends on the ROM: LineageOS puts the SoC name in
 * `ro.board.platform`, stock ROMs tend to put the product codename there
 * ("aza" for the X100), and `Build.SOC_MODEL` (Android 12+) gives the SoC
 * name on both.  So all of them are looked at.
 *
 * The MT67xx/MT68xx families are accepted as a whole, so a phone whose
 * strings differ slightly from the ones listed here is still recognised as
 * MediaTek rather than falling through to the generic profile — which for
 * these devices means no digital path at all.
 *
 * The matcher is deliberately narrow: nothing here can match a Qualcomm
 * (sm6xxx), an Exynos or a Tensor (gsxxx) string, so those devices keep the
 * profiles they had.
 */
object MediaTekPlatform {

    /** Strings seen on the Cubot phones and on their close relatives. */
    private val KNOWN = setOf(
        // Cubot X100 — Dimensity 7025 (MT6855V), codename "aza".
        "mt6855", "mt6855v", "mt6855t", "aza",
        // Cubot X90 — Helio G99 (MT6789).
        "mt6789", "helio g99", "g99",
        // The rest of the family, accepted so a slightly different string
        // still lands on a MediaTek profile.
        "mt6768", "helio g96", "mt6767", "helio g70",
        "mt6833", "mt6853",
        "dimensity", "helio",
    )

    /** MT67xx and MT68xx — the families the two Cubots belong to. */
    private val FAMILY = Regex("""mt6[78]\d{2}""")

    /** True if any of [strings] identifies a MediaTek platform. */
    fun isMediaTek(vararg strings: String): Boolean = strings.any { matches(it) }

    /** True if [raw] identifies a MediaTek platform. */
    fun matches(raw: String): Boolean {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return false
        // "helio g99" and "dimensity 7025" span two words.
        if (s.contains("helio g99") || s.contains("dimensity 7025")) return true
        for (token in s.split(TOKEN_SPLIT)) {
            if (token.isEmpty()) continue
            if (token in KNOWN) return true
            if (FAMILY.containsMatchIn(token)) return true
        }
        return false
    }

    /**
     * A human-readable label for the profile name: the SoC name when the
     * device gives one, the platform name otherwise.
     */
    fun label(vararg strings: String): String {
        // The SoC name first: "mt6855" says more than the codename does.
        for (s in strings) {
            val t = s.trim().lowercase()
            if (FAMILY.containsMatchIn(t)) return t.uppercase()
        }
        for (s in strings) {
            val t = s.trim().lowercase()
            if (t.isEmpty() || t == "helio" || t == "dimensity") continue
            if (t in KNOWN) return t.uppercase()
        }
        return "MediaTek"
    }

    /** Split on anything that is not a letter or a digit. */
    private val TOKEN_SPLIT = Regex("""[^a-z0-9]+""")
}
