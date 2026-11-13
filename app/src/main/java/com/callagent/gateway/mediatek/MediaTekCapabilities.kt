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
     * The mixer commands that mute the handset during the call.
     *
     * `Speech_Mic_Mute` is deliberately left alone: it is the voice-recording
     * mute and its polarity differs between builds, so touching it could
     * mute the agent's own microphone.
     */
    fun modernSetupCmd(): String =
        "tinymix '$MODERN_UL' 1 2>/dev/null; tinymix '$MODERN_DL' 1 2>/dev/null"

    /** The mixer commands that give the handset back its voice. */
    fun modernRestoreCmd(): String =
        "tinymix '$MODERN_UL' 0 2>/dev/null; tinymix '$MODERN_DL' 0 2>/dev/null"
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

        val injection = bgs ?: incallMusic ?: InjectionMode.ACOUSTIC.also {
            notes += "no digital injection route found — falling back to speaker and microphone"
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
