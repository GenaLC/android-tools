package com.callagent.gateway.mediatek

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.callagent.gateway.RootShell
import java.io.File

/**
 * Measures what the phone in front of us can actually do.
 *
 * Everything here is a measurement, not a decision: the planner in
 * [MediaTekPlanner] turns the result into a mode.  The measurements use only
 * what the project already has — a root shell, the vendor's own audio policy
 * file, and the public AudioManager API — and every one of them degrades to
 * "not found" rather than throwing, because a probe that fails must still
 * leave a usable profile behind.
 *
 * The probe runs once per process and its result is cached: it costs a root
 * shell round trip, and the answer cannot change while the app runs.
 */
object MediaTekCapabilityProbe {
    private const val TAG = "MediaTekProbe"

    /** Where the audio policy lives on every Android since 7. */
    private const val POLICY_FILE = "/vendor/etc/audio_policy_configuration.xml"

    /** The Magisk module's tinymix, or the vendor's own. */
    private const val TINYMIX_CANDIDATES =
        "/data/local/tmp/tinymix /vendor/bin/tinymix /system/bin/tinymix /system/xbin/tinymix"

    /** Everything that needs root, in one shell round trip. */
    private val ROOT_PROBE = """
echo '=== MTK_HAL ==='
grep -rl 'Set_BGS_UL_Mute' /vendor/lib64/hw /vendor/lib32/hw 2>/dev/null | head -3
echo '=== MTK_POLICY ==='
dumpsys media.audio_policy 2>/dev/null | grep -iE 'TELEPHONY_TX|TELEPHONY_RX' | head -20
echo '=== MTK_CARDS ==='
cat /proc/asound/cards 2>/dev/null
echo '=== MTK_PCMS ==='
cat /proc/asound/pcm 2>/dev/null
echo '=== MTK_TINYMIX ==='
for p in $TINYMIX_CANDIDATES; do
  if [ -x "${'$'}p" ]; then
    echo "TINYMIX:${'$'}p"
    echo '=== MTK_MIXER ==='
    "${'$'}p" 2>/dev/null
    echo '=== MTK_MIXER_END ==='
  fi
done
echo '=== MTK_END ==='
""".trimIndent()

    @Volatile private var cached: MediaTekCapabilities? = null

    /** The result of the last probe, or null if it has not run yet. */
    val lastResult: MediaTekCapabilities? get() = cached

    /**
     * Probe the device, once.  Later calls return the cached answer.
     *
     * [context] is optional: it is only used for the AudioManager half of
     * the BGS check, and the check is skipped without it rather than
     * guessed at.
     */
    @Synchronized
    fun probe(context: Context? = null): MediaTekCapabilities {
        cached?.let { return it }
        val caps = runProbe(context)
        cached = caps
        Log.i(TAG, "MediaTek capabilities: $caps")
        return caps
    }

    /** Forget the cached answer — for tests and for a re-probe. */
    fun reset() {
        cached = null
    }

    private fun runProbe(context: Context?): MediaTekCapabilities {
        // ── the audio policy file: no root, no latency ──────────────────
        val policy = readPolicy()
        val incallMusicMixPort = MIXPORT_INCALL.containsMatchIn(policy)
        val telephonyTxDevicePort = DEVICEPORT_TX.containsMatchIn(policy)
        val telephonyRxDevicePort = DEVICEPORT_RX.containsMatchIn(policy)
        val telephonyTxRouted = SINK_TX.containsMatchIn(policy)

        // ── the HAL: does this build have the background-sound player? ───
        val bgs = probeBgs(context)

        // ── root: the running policy, the PCMs, tinymix ─────────────────
        val root = try {
            RootShell.execForOutput(ROOT_PROBE, timeoutMs = 5000)
        } catch (e: Exception) {
            Log.w(TAG, "Root probe failed: ${e.message}")
            ""
        }
        val rootAvailable = root.contains("=== MTK_END ===")
        val telephonyRxInPolicy = root.contains("TELEPHONY_RX")
        val telephonyTxInPolicy = root.contains("TELEPHONY_TX")
        val bgsInHal = root.contains("=== MTK_HAL ===") &&
            root.substringAfter("=== MTK_HAL ===").substringBefore("=== MTK_POLICY ===")
                .contains("Set_BGS_UL_Mute")
        val tinymixAvailable = root.contains("TINYMIX:")
        val pcm = findTelephonyRxPcm(
            root.substringAfter("=== MTK_PCMS ===").substringBefore("=== MTK_TINYMIX ==="),
            telephonyRxSampleRate(policy),
        )

        // Which mute controls this mixer actually has.  A control that is not
        // listed cannot be written, and writing it fails quietly — the shell
        // swallows "control not found" — so the list is the evidence.
        val mixerControls = mixerControlNames(
            root.substringAfter("=== MTK_MIXER ===").substringBefore("=== MTK_MIXER_END ===")
        )
        val muteFamily = when {
            mixerControls.contains(MediaTekSpeechMutes.MODERN_UL) ||
                mixerControls.contains(MediaTekSpeechMutes.MODERN_DL) -> SpeechMuteFamily.MODERN_MIXER
            mixerControls.contains(LEGACY_UL_CONTROL) ||
                mixerControls.contains(LEGACY_DL_CONTROL) -> SpeechMuteFamily.LEGACY_HAL
            else -> SpeechMuteFamily.NONE
        }

        // ── the HAL's own speech-mute parameters, for the older generation ──
        val legacyMutes = probeLegacySpeechMutes(context)

        return MediaTekCapabilities(
            rootAvailable = rootAvailable,
            tinymixAvailable = tinymixAvailable,
            bgsInHal = bgsInHal,
            bgsParamResult = bgs.result,
            bgsParamsConfirmed = bgs.confirmed,
            incallMusicMixPort = incallMusicMixPort,
            telephonyTxDevicePort = telephonyTxDevicePort,
            telephonyTxRouted = telephonyTxRouted || telephonyTxInPolicy,
            telephonyRxDevicePort = telephonyRxDevicePort,
            telephonyRxInPolicy = telephonyRxInPolicy,
            telephonyRxPcm = pcm,
            speechMuteFamily = muteFamily,
            legacySpeechMuteAccepted = legacyMutes,
        )
    }

    /**
     * The names of the mixer controls this build has.
     *
     * tinymix prints one control per line, tab separated: index, type,
     * channel, name, value.  The name is the fourth field.
     */
    private fun mixerControlNames(section: String): Set<String> {
        val names = HashSet<String>()
        for (line in section.lines()) {
            val fields = line.split('\t')
            if (fields.size < 4) continue
            val name = fields[3].trim()
            if (name.isNotEmpty()) names += name
        }
        return names
    }

    // ── the BGS half ─────────────────────────────────────────────────────

    /**
     * Ask the audio HAL whether it has the background-sound parameters.
     *
     * Two independent pieces of evidence, because either alone can mislead:
     *  - the vendor HAL binary carries the parameter strings, which says the
     *    build knows the parameter but not that it acts on it;
     *  - the HAL itself takes the parameters, which says it parsed them —
     *    setParameters() throws on a parameter the HAL does not know, so
     *    surviving the call is the signal.  What the parameter did is a
     *    second question, answered by reading the state back.
     *
     * The parameters are put to the HAL one at a time, the way the MT6753
     * profile sets them.
     */
    private fun probeBgs(context: Context?): BgsProbe {
        val am = audioManager(context)
        if (am == null) {
            Log.i(TAG, "BGS: no AudioManager reachable — the HAL cannot be asked directly")
            return BgsProbe(BgsParamResult.NOT_TESTED, false)
        }
        try {
            return probeBgsParams(am)
        } finally {
            // The parameters the probe puts to the HAL include the two that
            // mute the handset's own speech path.  Leaving them set would mute
            // the phone's microphone and earpiece with no call in progress,
            // and nothing else would undo that: the profile restores them when
            // a bridged call is torn down, which never happens if no call
            // ever comes.  So the HAL is put back the way it was found,
            // whether the probe succeeded or not.
            restoreBgsParams(am)
        }
    }

    /**
     * Put the parameters the probe touched back the way the phone keeps them
     * when it is an ordinary phone and not a gateway.
     */
    private fun restoreBgsParams(am: AudioManager) {
        for (p in MediaTekBgs.RESTORE_PARAMS) {
            try {
                am.setParameters(p)
            } catch (e: Exception) {
                Log.i(TAG, "BGS: '$p' could not be restored: ${e.message}")
            }
        }
    }

    private fun probeBgsParams(am: AudioManager): BgsProbe {
        // The parameters that must be refused for the route to be unusable.
        for (p in MediaTekBgs.CALL_PARAMS) {
            try {
                // setParameters() returns nothing on Android 15, so the
                // HAL's answer has to be asked for separately.
                am.setParameters(p)
            } catch (e: Exception) {
                Log.i(TAG, "BGS: '$p' refused by the HAL: ${e.message}")
                return BgsProbe(BgsParamResult.REJECTED, false)
            }
            // Read the state back: the HAL's own answer to "what is this
            // parameter set to?".  A parameter that was stored but never
            // took effect reads back as something else.
            val read = try {
                am.getParameters(p.substringBefore('='))
            } catch (e: Exception) {
                ""
            }
            if (!confirms(read, p)) {
                Log.i(TAG, "BGS: '$p' accepted but not confirmed (read='$read')")
                return BgsProbe(BgsParamResult.ACCEPTED, false)
            }
        }
        Log.i(TAG, "BGS: the HAL took and confirmed all ${MediaTekBgs.CALL_PARAMS.size} parameters")
        return BgsProbe(BgsParamResult.ACCEPTED, true)
    }

    /**
     * Ask the HAL whether it takes the older generation's speech-mute
     * parameters.
     *
     * The X100's mixer has no such control, and its HAL refused them in the
     * same breath — but on the MT6753 these were the only way to take the
     * handset out of the call, so they are still asked about.
     */
    private fun probeLegacySpeechMutes(context: Context?): Boolean {
        val am = audioManager(context) ?: return false
        return try {
            for (p in MediaTekSpeechMutes.LEGACY_CALL_PARAMS) {
                am.setParameters(p)
            }
            Log.i(TAG, "Mutes: the HAL took the Set_SpeechCall_* parameters")
            true
        } catch (e: Exception) {
            Log.i(TAG, "Mutes: the HAL refused the Set_SpeechCall_* parameters: ${e.message}")
            false
        } finally {
            // A mute left on is a phone that cannot hear its owner.
            for (p in MediaTekSpeechMutes.LEGACY_RESTORE_PARAMS) {
                try {
                    am.setParameters(p)
                } catch (e: Exception) {
                    Log.i(TAG, "Mutes: '$p' could not be restored: ${e.message}")
                }
            }
        }
    }

    /**
     * Does [returned] say that [expected] ("key=value") is in force?
     *
     * The comparison is on the whole key=value pair, not on a substring:
     * "Set_BGS_UL_Mute=10" contains "Set_BGS_UL_Mute=1", and a mute that
     * reads as ten is not a mute that reads as one.
     */
    private fun confirms(returned: String, expected: String): Boolean {        if (returned.isEmpty()) return false
        val key = expected.substringBefore('=')
        val value = expected.substringAfter('=')
        return returned.split(',').map { it.trim() }.any { pair ->
            pair.substringBefore('=').trim().equals(key, ignoreCase = true) &&
                pair.substringAfter('=').trim() == value
        }
    }

    /**
     * An AudioManager, if one can be had.
     *
     * The caller of [probe] has no Context to hand over — the profile is
     * detected before anything is started — so the application context is
     * fetched the way the rest of the project reaches hidden APIs.  A
     * failure here is not fatal: the BGS check falls back to the vendor HAL
     * binary as its evidence.
     */
    private fun audioManager(context: Context?): AudioManager? {
        val ctx = context ?: appContext() ?: return null
        return try {
            ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        } catch (e: Exception) {
            null
        }
    }

    private fun appContext(): Context? = try {
        val at = Class.forName("android.app.ActivityThread")
        val app = at.getDeclaredMethod("currentApplication").invoke(null) as? Context
        app?.applicationContext
    } catch (e: Throwable) {
        Log.i(TAG, "No application context: ${e.message}")
        null
    }

    // ── the PCM half ─────────────────────────────────────────────────────

    /**
     * Find a capture PCM that carries the call's downlink.
     *
     * The names are matched by shape rather than by an exact string: a
     * telephony PCM is called "telephony", "voice", "incall", "tdm", "apd",
     * "smartphone" or "pcm_tel" depending on the vendor and the generation,
     * and the one that carries the *downlink* is usually the one with "rx",
     * "rec" or "downlink" in it.  A PCM with none of those words in its name
     * is not touched: reading the wrong one would put the room, or the
     * agent's own voice, on the line.
     */
    private fun findTelephonyRxPcm(pcmSection: String, rate: Int): AlsaPcm? {
        val candidates = parsePcms(pcmSection).filter { it.capture }
        if (candidates.isEmpty()) return null

        val named = candidates.filter { c -> RX_NAMES.any { c.name.contains(it) } }
        if (named.isEmpty()) return null

        // The downlink leg first: a phone with both a "voice tx" and a
        // "voice rx" PCM must give us the rx one.
        val preferred = named.filter { c -> RX_PREFERRED.any { c.name.contains(it) } }
        val chosen = (preferred.ifEmpty { named }).minByOrNull { it.device } ?: return null

        Log.i(TAG, "Telephony capture PCM: ${chosen.name} (card ${chosen.card}, device ${chosen.device}, $rate Hz)")
        return chosen.copy(rate = rate)
    }

    /**
     * The sample rate the policy declares for the telephony RX mixPort.
     *
     * tinycap has to be told a rate, and the wrong one either fails to open
     * the PCM or resamples it.  The policy file is the only place on the
     * device that states the rate a telephony port runs at; when it is not
     * there, 16 kHz is assumed, which is what a voice path runs at.
     */
    private fun telephonyRxSampleRate(policy: String): Int {
        val line = policy.lines().firstOrNull { l ->
            val t = l.lowercase()
            "mixport" in t && ("telephony" in t || "voice" in t || "incall" in t) &&
                ("rx" in t || "downlink" in t || "rec" in t)
        }
        val rate = SAMPLE_RATE.find(line ?: "")?.groupValues?.get(1)?.toIntOrNull()
        return rate ?: 16000
    }

    /** Parse /proc/asound/pcm into the PCMs it lists. */
    private fun parsePcms(section: String): List<AlsaPcm> {
        val out = ArrayList<AlsaPcm>()
        for (line in section.lines()) {
            val t = line.trim()
            if (!t.contains('-') || !t.contains(':')) continue
            val m = PCM_LINE.find(t) ?: continue
            val card = m.groupValues[1].toIntOrNull() ?: continue
            val device = m.groupValues[2].toIntOrNull() ?: continue
            val name = m.groupValues[3].trim()
            if (name.isEmpty()) continue
            out += AlsaPcm(
                card = card,
                device = device,
                name = name,
                // The rate is a guess until the policy says otherwise; the
                // caller passes it to the policy reader below.
                rate = 0,
                // /proc/asound/pcm lists "playback 1 : capture 1" for a PCM
                // that does both, and only "playback 1" for one that cannot
                // record.
                capture = line.contains("capture"),
            )
        }
        return out
    }

    // ── small helpers ────────────────────────────────────────────────────

    private fun readPolicy(): String = try {
        File(POLICY_FILE).readText()
    } catch (e: Throwable) {
        Log.i(TAG, "No audio policy at $POLICY_FILE: ${e.message}")
        ""
    }

    /** The result of the BGS half of the probe. */
    private data class BgsProbe(val result: BgsParamResult, val confirmed: Boolean)

    /** "00-03: name : driver : playback 1 : capture 1" */
    private val PCM_LINE = Regex("""^(\d+)-(\d+):\s*(.+?)\s*:""")

    /** A mixPort whose name mentions incall. */
    private val MIXPORT_INCALL = Regex("""mixPort name="[^"]*incall[^"]*"""")

    /** The telephony device ports, as the policy names them. */
    private val DEVICEPORT_TX = Regex("""devicePort name="Telephony Tx"""")
    private val DEVICEPORT_RX = Regex("""devicePort name="Telephony Rx"""")

    /** A mixPort that sinks into the telephony TX device. */
    private val SINK_TX = Regex("""sink="Telephony Tx"""")

    /** The sample rate a mixPort declares. */
    private val SAMPLE_RATE = Regex("""sampleRate="(\d+)""")

    /** Names a telephony PCM may carry. */
    private val RX_NAMES = listOf(
        "telephony", "tel", "voice", "incall", "tdm", "apd", "smartphone", "voip",
    )

    /** Names that mark the downlink leg of a telephony pair. */
    private val RX_PREFERRED = listOf("_rx", " rx", "rx_", "rec", "capture", "downlink", "_dl", " dl")

    /** The mixer controls of the older generation, by name. */
    private const val LEGACY_UL_CONTROL = "Set_SpeechCall_UL_Mute"
    private const val LEGACY_DL_CONTROL = "Set_SpeechCall_DL_Mute"
}
