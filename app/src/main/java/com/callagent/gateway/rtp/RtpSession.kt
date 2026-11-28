package com.callagent.gateway.rtp

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.callagent.gateway.DeviceProfile
import com.callagent.gateway.RootCapture
import com.callagent.gateway.RootPcmRoute
import com.callagent.gateway.RootShell
import com.callagent.gateway.gsm.GsmCallManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RTP session: handles bidirectional audio between speaker/mic and remote RTP endpoint.
 *
 * On S4 Mini (MSM8930), VOICE_DOWNLINK routes through the physical mic
 * (HAL usecase incall-rec-downlink → voice-handset-mic).  Speaker mode
 * plays GSM caller audio through the speaker; the mic picks it up.
 *
 * Audio path:
 * - Capture: VOICE_DOWNLINK via mic → gain boost → encode → RTP → SIP agent.
 * - Playback: RTP → decode → AudioTrack (usage from profile).
 *   MSM8930: USAGE_MEDIA → STREAM_MUSIC.  incall_music_enabled=true
 *   injects STREAM_MUSIC into voice TX, bypassing modem AEC.
 *   Exynos 9820: USAGE_VOICE_COMMUNICATION → STREAM_VOICE_CALL.
 *   Samsung HAL may route this into the modem uplink directly.
 *
 * The Magisk module disables Android's audio concurrency restrictions.
 * Uses G.722 codec for wideband (16 kHz), falls back to the narrowband G.711
 * codecs (A-law, µ-law) when the server does not want G.722.
 */
class RtpSession(
    private val context: Context,
    private val localPort: Int,
    private val remoteAddr: String,
    private val remotePort: Int,
    // G.722 is the default of the configured codec mode, so it is the
    // default here too.  Every call site passes the negotiated payload
    // explicitly; this is only what a session built without one would use.
    private val payloadType: Int = RtpPacket.PT_G722,
    /**
     * The cellular leg was answered on our own, before the far end did.
     *
     * That opens the downlink before the network has anything to say on it,
     * so the first seconds of the capture are legitimately silent.  Without
     * this the silence detector reads that silence as a dead source, retires
     * the capture and falls back to a source that carries the room instead
     * of the call — which loses the very announcement the early answer was
     * there to catch.
     */
    private val allowSilentStart: Boolean = false,
    /**
     * The session was started before the cellular leg was up.
     *
     * The operator's announcement is played while the leg is still alerting,
     * so the downlink has to be captured from that moment on — the RTP
     * session used to start only once the leg was answered, and by then the
     * announcement was over.
     *
     * The early phase is different in three ways: the source list is short
     * (the voice path may not exist yet, so a silent source is retired
     * quickly instead of after the usual six seconds), the injection and the
     * mutes are held back (the HAL rewrites the voice path when it programs
     * the call, so anything written before that is lost — they are applied
     * when the leg comes up), and the capture is opened again at that point,
     * because a source opened before the call was set up can be bound to a
     * path that stays silent.
     */
    private val earlyCapture: Boolean = false
) {
    private val running = AtomicBoolean(false)
    /** Ensures the stop callback is delivered once, including a session that
     * failed during startup and never reached the running state. */
    private val stopNotified = AtomicBoolean(false)
    /** The four loop threads, so [stop] can wait for them to leave the
     *  AudioRecord/AudioTrack before those get released. */
    @Volatile private var workers: List<Thread> = emptyList()
    private var socket: DatagramSocket? = null
    /** Where the caller is read from: an AudioRecord, or a PCM over root. */
    @Volatile private var capture: CaptureSource? = null
    private var audioTrack: AudioTrack? = null
    /** 1 or 2, per [DeviceProfile.playbackStereo]. */
    private var playbackChannels = 1
    /** The tinypcm processes carrying the call when the profile routes it over
     *  raw PCMs ([DeviceProfile.rootPcm]); null on the AudioRecord/AudioTrack
     *  path, which is every other device. */
    @Volatile private var pcmCapture: RootPcm? = null
    @Volatile private var pcmPlayback: RootPcm? = null
    /** When the next playback frame is due, on the root PCM path. */
    private var nextPlayNs = 0L

    // ── Monitor ("snoop") ────────────────────────────────────────────
    // A second track on the phone's speaker carrying both sides of the call
    // mixed together, for listening in without touching the bridge.  It cannot
    // work by unmuting the call: the caller arrives on the modem downlink and
    // the agent goes out to Telephony Tx, so neither passes through the
    // speaker any more.  Both sides are therefore mixed here in software.
    //
    // The microphone stays muted throughout — this only ever plays audio out.
    @Volatile private var monitorTrack: AudioTrack? = null
    /** Most recent caller frame, 16-bit mono at [captureRate], for mixing. */
    @Volatile private var lastCallerFrame: ByteArray? = null

    /** Silence the agent towards the caller without disturbing the bridge.
     *  RTP keeps flowing in both directions and the SIP call stays up; only the
     *  audio written towards the modem is zeroed.  Intended for cutting the
     *  agent off mid-sentence when it is saying something it should not. */
    @Volatile private var agentMuted = false

    // Codec
    private val g722Encoder = G722Codec()
    private val g722Decoder = G722Codec()

    // RTP state
    private var txSequence = 0
    private var txTimestamp = 0L
    private val txSsrc = (Math.random() * 0xFFFFFFFFL).toLong()

    // ── SRTP ────────────────────────────────────────────
    // Set before start() when the call negotiated SDES, left null for plain
    // RTP.  One context per direction: they hold independent keys and their
    // own rollover counters, and each is touched by exactly one thread --
    // send by the capture loop, receive by the receive loop.

    @Volatile var srtpSend: SrtpContext? = null
    @Volatile var srtpRecv: SrtpContext? = null

    /** Packets dropped because they failed authentication. */
    @Volatile var srtpAuthFailures = 0L
        private set

    // ── DTMF (RFC 4733) ──
    // Payload types carrying telephone-event: the one in the peer's SDP plus
    // the 101 this gateway offers itself.  Set before start().
    @Volatile var telephoneEventPts: Set<Int> = setOf(RtpPacket.PT_TELEPHONE_EVENT)
    /** Receives key presses from the SIP side; null drops them as before. */
    @Volatile var dtmfDetector: DtmfDetector? = null
    @Volatile var rxDtmfPackets = 0L
        private set

    /**
     * Encrypt if this call is protected, then send.
     *
     * If protect() cannot produce a packet the packet is dropped, never sent
     * in the clear: falling back would silently downgrade a call the user was
     * told is encrypted, which is worse than losing 20ms of audio.
     */
    private fun sendRtp(data: ByteArray, addr: InetAddress, port: Int) {
        val ctx = srtpSend
        val out = if (ctx == null) data else (ctx.protect(data) ?: return)
        socket?.send(DatagramPacket(out, out.size, addr, port))
    }

    // Symmetric RTP: latch onto the actual source address of received packets
    @Volatile private var latchedAddr: InetAddress? = null
    @Volatile private var latchedPort: Int = 0

    // Jitter buffer for received packets.  Capacity 8 (160ms) absorbs
    // network jitter without audible gaps.  The playback loop drains
    // excess above 5 (100ms) to bound latency — acceptable for a GSM
    // bridge that already has 100-200ms of inherent GSM latency.
    // Previous capacity=3 was too aggressive: any slight jitter caused
    // packet drops and choppy audio.
    /** Jitter buffer, in 20ms frames.
     *
     *  Four frames was too tight to be workable.  Measured over a 33s call it
     *  sat at 3-4 — pinned at capacity — and logged 87 dropped frames *and* 90
     *  underruns out of ~1650: about three audible glitches a second, because
     *  at that depth every burst overflows and every gap empties it.  There is
     *  no depth that both absorbs real network jitter and holds 80ms.
     *
     *  So: room to absorb a burst, and a high-water mark to shed the excess a
     *  frame at a time so the burst does not park permanent latency in the
     *  queue.  Shedding one frame occasionally is the same kind of loss as the
     *  overflow drop, but it happens at a chosen depth instead of at the
     *  ceiling, and it is two orders of magnitude rarer. */
    private val jitterBuffer = ArrayBlockingQueue<ByteArray>(JITTER_CAPACITY)

    // RTP inactivity tracking
    @Volatile private var lastRtpReceivedTime = 0L
    private val RTP_TIMEOUT_MS = 30_000L  // 30 seconds with no RTP = dead call

    /** The last late HAL parameter written, so a tick does not rewrite it. */
    @Volatile private var lastLateHalParam: String? = null

    /** The last result of the diagnostic microphone command. */
    @Volatile private var lastDiagnosticMicOut: String? = null

    // Packet counters and audio diagnostics
    @Volatile var txPacketCount = 0L; private set
    @Volatile var rxPacketCount = 0L; private set
    @Volatile var playbackFrames = 0L; private set
    @Volatile var captureRms = 0; private set
    @Volatile var playbackRms = 0; private set
    @Volatile var rawCaptureRms = 0; private set  // Before echo gate — 0 means source is silent
    /** Frames discarded because the jitter buffer was already full — the far
     *  end is arriving faster than playback drains it, or in bursts. */
    @Volatile private var rxDropped = 0L
    /** Frames of silence written because the jitter buffer was empty — the
     *  audible gaps, i.e. late or lost packets from the far end. */
    @Volatile private var underruns = 0L
    /** Frames shed at the high-water mark to keep the queue from parking
     *  latency after a burst. */
    @Volatile private var trimmedFrames = 0L
    @Volatile var audioSourceName = "none"; private set
    @Volatile private var playbackUsageName = "MEDIA"

    /**
     * Whether the digital bridge is on for this session: the agent's audio
     * goes into the modem's uplink and the handset's microphone is out of the
     * call.  Set in [initAudio], read by the playback loop once the HAL has
     * settled and the handset mutes are written. */
    @Volatile private var digitalBridgeActive = false

    /**
     * The same question, asked before the audio is up.
     *
     * The capture source list is built before the audio is initialised, and
     * the answer decides which sources are worth offering at all: with the
     * digital bridge on, VOICE_CALL is a clean source (the uplink carries no
     * room, because the agent never goes through the microphone), and the
     * devices whose VOICE_DOWNLINK reads silence need it.
     */
    private fun digitalBridgeOn(): Boolean =
        profile.digitalBridge &&
            context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
                .getBoolean("digital_bridge", true)

    // Capture and playback rates may differ.  VOICE_CALL on MSM8930
    // only initializes at 8 kHz; G.722 decoding outputs 16 kHz PCM.
    private var captureRate = 8000
    private var playbackRate = 8000

    // Audio session ID from AudioRecord (for logging/diagnostics)
    private var audioSessionId: Int = AudioManager.AUDIO_SESSION_ID_GENERATE

    // Silence detection: track audio source IDs that produce no audio so we
    // can fall back to alternatives.  E.g., VOICE_CALL initializes on
    // Exynos 9820 but delivers silence — the HAL doesn't route voice data
    // to the capture path.  Falling back to MIC captures the caller's voice
    // acoustically from the speaker.
    private val silentSourceIds = mutableSetOf<Int>()
    @Volatile private var currentSourceId: Int = -1
    private data class SourceConfig(val source: Int, val name: String, val rate: Int)

    /** G.722 carries 16 kHz audio; the G.711 codecs carry 8 kHz. */
    private val wideband get() = payloadType != RtpPacket.PT_PCMA && payloadType != RtpPacket.PT_PCMU
    // Silence detection thresholds — only counted during non-echo periods
    // (when decayingPlaybackRms <= echoGateThreshold) to avoid false resets
    // from incall_music echo leaking back through VOICE_CALL capture.
    private val SILENCE_RMS_THRESHOLD = 10   // Below this = truly dead source (ADC noise floor)

    /** How long to let a source settle before judging its frame rate. */
    private val RATE_CHECK_AFTER_MS = 4000L

    /** Below this share of the expected 50 frames/s, the source is failing.
     *  Generous on purpose: a healthy source sits at ~100%, and the case this
     *  catches measured 70%, so anything near the threshold is unambiguous. */
    private val RATE_MIN_PERCENT = 85L
    private val SILENCE_FRAME_LIMIT get() =
        EarlyCapturePolicy.silenceLimitFrames(earlyCapture, profile.captureSilenceFrames)

    /**
     * How long an unproven source may stay silent at the start of a call
     * that was answered early.
     *
     * The silence detector answers "does this source deliver audio at all",
     * and it can only answer that once there was audio to deliver.  On a leg
     * answered early there is nothing on the downlink yet — the network is
     * still setting the announcement up — so the silence says nothing about
     * the source.
     */
    private val SILENT_START_GRACE_MS = 10_000L

    /**
     * True once the cellular leg is up.
     *
     * Until then the handset's loudspeaker stays open — the announcement is
     * still being played there, and the microphone is the last resort for
     * capturing it — and the injection is not written yet.
     */
    @Volatile private var cellularLegUp = false

    /**
     * Set when the capture has to be opened again.
     *
     * A source opened before the call was set up can be bound to a path that
     * stays silent: the HAL picks the input usecase when the record stream is
     * created, and before the voice path exists there is nothing to bind to.
     */
    @Volatile private var reopenCaptureRequested = false

    /**
     * The injection is waiting for the playback track.
     *
     * The HAL starts the incall-music usecase only when there is an active
     * STREAM_MUSIC output, so the injection cannot be written before the
     * track is playing.  If the cellular leg came up while this session was
     * still starting, the playback loop writes it instead.
     */
    @Volatile private var injectionPending = false

    /** True when this session was started before the cellular leg was up. */
    val startedEarly: Boolean get() = earlyCapture

    var listener: Listener? = null

    interface Listener {
        fun onRtpStarted()
        fun onRtpStopped()
        fun onRtpError(error: String)
        fun onRtpTimeout() {}  // No RTP received for RTP_TIMEOUT_MS
        fun onRtpStats(stats: String) {}  // Periodic detailed stats
    }

    fun start() {
        if (running.getAndSet(true)) return
        stopNotified.set(false)
        Log.i(TAG, "Starting RTP session: local=$localPort remote=$remoteAddr:$remotePort pt=$payloadType")

        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(localPort))
                soTimeout = 100
                receiveBufferSize = 262144
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind RTP socket on port $localPort: ${e.message}")
            running.set(false)
            listener?.onRtpError("Socket bind failed: ${e.message}")
            return
        }

        if (!initAudio()) {
            running.set(false)
            return
        }

        // Send initial RTP keepalive to punch NAT pinhole before audio starts
        try {
            val remoteInet = InetAddress.getByName(remoteAddr)
            val silence = RtpPacket(payloadType, 0, 0, txSsrc, ByteArray(160)).encode()
            sendRtp(silence, remoteInet, remotePort)
            Log.i(TAG, "Sent NAT punch-through packet to $remoteAddr:$remotePort")
        } catch (e: Exception) {
            Log.w(TAG, "NAT punch-through failed: ${e.message}")
        }

        lastRtpReceivedTime = System.currentTimeMillis()
        workers = listOf(
            loopThread("RTP-Recv-$localPort") { receiveLoop() },
            loopThread("RTP-Play-$localPort") { playbackLoop() },
            loopThread("RTP-Capt-$localPort") { captureInitAndLoop() },
            loopThread("RTP-Timeout-$localPort") { timeoutLoop() },
            loopThread("RTP-OutMute-$localPort") { outputMuteKeeperLoop() }
        )
        workers.forEach { it.start() }

        listener?.onRtpStarted()
    }

    /**
     * A loop thread that cannot take the process down with it.
     *
     * [stop] interrupts these threads, and several of them sleep outside a
     * try/catch — an InterruptedException escaping a thread body reaches the
     * default uncaught handler, which on Android kills the process.  Anything
     * thrown after the session has been stopped is expected and just noted.
     */
    private fun loopThread(name: String, body: () -> Unit): Thread =
        Thread({
            try {
                body()
            } catch (e: InterruptedException) {
                Log.d(TAG, "$name interrupted")
            } catch (e: Throwable) {
                if (running.get()) Log.e(TAG, "$name died: ${e.javaClass.simpleName}: ${e.message}", e)
                else Log.d(TAG, "$name ended during shutdown: ${e.message}")
            }
        }, name)

    /**
     * Initialize AudioRecord and AudioTrack.
     *
     * AudioRecord: Tries VOICE_DOWNLINK first (on S4 Mini this routes through
     * the physical mic via incall-rec-downlink HAL usecase).
     * AudioTrack: USAGE_MEDIA (STREAM_MUSIC) so that Qualcomm's
     * incall_music_enabled=true parameter injects it into voice TX (uplink).
     * USAGE_VOICE_COMMUNICATION maps to STREAM_VOICE_CALL which the HAL
     * does NOT inject via incall_music — that's why SIP→GSM was silent.
     */
    // profile.playbackUsage holds an AudioAttributes.USAGE_* constant; it
    // arrives as a plain Int, so Lint cannot see that it is one.
    @SuppressLint("WrongConstant")
    private fun initAudio(): Boolean {
        // Before anything is opened: in-call recording and the per-session
        // voice mutes are gated on the HAL's is_call_active flag, and the HAL
        // chooses the input usecase when the record stream is created.  Telling
        // it after AudioRecord exists is too late — VOICE_CALL is already bound
        // to a plain capture path and returns silence.
        setHalCallState(2)
        applyHalCallParams()

        // Telecom may still be completing the earpiece -> speaker transition
        // when the GSM call first becomes ACTIVE.  On the Pixel 4a, opening an
        // AudioTrack during that window can produce a dead IAudioTrack which
        // AudioFlinger recreates as a normal output instead of the
        // incall_music_uplink output.  The framework can then report
        // routedDevice=TELEPHONY even though the modem injection usecase was
        // never opened.  Let the route settle before creating either stream.
        val settleDelay = profile.audioRouteSettleDelayMs
        if (settleDelay > 0) {
            Log.i(TAG, "Waiting ${settleDelay}ms for audio route to settle")
            try {
                Thread.sleep(settleDelay)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }

        // Playback rate matches codec output rate.  G.722 decodes to 16 kHz.
        playbackRate = when (payloadType) {
            RtpPacket.PT_PCMA, RtpPacket.PT_PCMU -> 8000
            else -> 16000  // G.722
        }

        profile.rootPcm?.let { route ->
            if (startRootPcm(route)) return true
            listener?.onRtpStats("Root PCM path failed — falling back to the audio HAL")
        }

        // Try telephony capture sources, then mic sources.
        // When using G.722 (wideband), prefer 16kHz capture to avoid upsampling
        // artifacts.  HD Voice (AMR-WB/EVS) provides native 16kHz audio.
        val configs = mutableListOf<SourceConfig>()

        // VOICE_CALL (source 4): captures uplink+downlink mixed digitally.
        // Best option on MSM8930 — if it initializes, it provides clean
        // digital capture of the caller's voice.  Requires CAPTURE_AUDIO_OUTPUT.
        if (wideband) {
            // G.722: prefer 16kHz native capture — avoids upsampling artifacts
            // in the 4-8kHz upper band that cause AI agent false interruptions.
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL@16k", 16000))
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL", 8000))
        } else {
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL", 8000))
        }
        // Mic-based sources: in speaker mode, the physical mic picks up the
        // caller's voice from the speaker.  This is acoustic coupling — not
        // ideal but functional when digital capture sources fail.
        // VOICE_RECOGNITION bypasses noise suppression that can mute call audio.
        // VOICE_DOWNLINK ahead of the acoustic sources when the profile says
        // it works here.  It captures the caller's voice digitally, off the
        // modem downlink, which is what lets the physical mic stay muted — on
        // the acoustic path the mic is the capture source, so the caller hears
        // the room and the agent hears itself through the speaker.
        if (profile.voiceDownlinkWorks) {
            if (wideband) {
                configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK@16k", 16000))
            }
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK", 8000))
        }
        if (profile.preferUnprocessedMic) {
            // Raw mic, ahead of the voice-tuned sources: no AEC, no noise
            // suppression, no AGC.  When the caller reaches us as sound out of
            // the phone's own speaker, those are all working against us.
            configs.add(SourceConfig(MediaRecorder.AudioSource.UNPROCESSED, "UNPROCESSED", 8000))
            configs.add(SourceConfig(MediaRecorder.AudioSource.CAMCORDER, "CAMCORDER", 8000))
        }
        configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION", 8000))
        configs.add(SourceConfig(MediaRecorder.AudioSource.MIC, "MIC", 8000))
        configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION", 8000))

        // With the digital bridge on, VOICE_CALL stays in the chain: the
        // agent's voice goes into the modem's uplink digitally and never
        // passes the microphone, so the uplink it mixes in is clean — and on
        // the devices whose VOICE_DOWNLINK delivers nothing but silence it is
        // the only source that carries the caller at all.
        if (profile.voiceDownlinkWorks && !digitalBridgeOn()) {
            // Where the modem downlink is capturable, it is the only source we
            // want: VOICE_CALL mixes uplink AND downlink, and the uplink now
            // carries the agent's own injected voice, so the agent hears itself
            // folded into the caller — which is what the far end perceives as
            // noise.  The downlink alone is the caller.  Acoustic sources stay
            // behind it purely as a last resort.
            configs.removeAll { it.source == MediaRecorder.AudioSource.VOICE_CALL }
            val downlink = configs.filter {
                it.source == MediaRecorder.AudioSource.VOICE_DOWNLINK
            }
            configs.removeAll(downlink)
            configs.addAll(0, downlink)
        }
        // VOICE_DOWNLINK (source 3): DEAD LAST — on MSM8930 it initializes
        // successfully (STATE_INITIALIZED) but captures SILENCE because the
        // Incall_Rec mixer controls don't exist on this SoC.  If it were
        // earlier in the list, it would "win" over mic-based sources that
        // actually work.  Kept only for devices where it genuinely works.
        if (!profile.voiceDownlinkWorks) {
            if (wideband) {
                configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK@16k", 16000))
            }
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK", 8000))
        }

        // Same preference as the fallback list, applied here too -- this is
        // the list that picks the source a call actually opens on, and
        // reordering only the fallback list left every call still starting on
        // VOICE_CALL@16k and discovering it was dead ~11s later.  The agent
        // heard silence for those 11 seconds of every call.
        if (profile.preferVoiceRecognition) {
            val vr = configs.filter { it.source == MediaRecorder.AudioSource.VOICE_RECOGNITION }
            configs.removeAll(vr)
            configs.addAll(0, vr)
        }

        // The early phase — the cellular leg is not up yet — has its own short
        // list: the voice path may not exist, so the digital sources can be
        // silent, and the room must not reach the agent as if it were the
        // caller.  The microphone is the last resort: while the handset plays
        // the announcement out loud, it is the only way to pick it up.
        if (earlyCapture) {
            val allowed = EarlyCapturePolicy.sources(profile.voiceDownlinkWorks)
            configs.removeAll { it.source !in allowed }
            Log.i(TAG, "Early capture sources: ${configs.joinToString { it.name }}")
        }

        var record: AudioRecord? = null
        var usedRate = 8000

        // Where the caller is read over root, no AudioRecord is opened up
        // front: it would only hold a HAL usecase that delivers nothing, to be
        // torn down as soon as tinycap is up.  Verified on the Pixel 7 — the
        // telephony-rx PCM carries the caller with no AudioRecord open.  The
        // search in captureInitAndLoop opens one if root capture fails.
        if (profile.rootCapture != null) configs.clear()

        // No appops assertion and no propagation sleep here.  CallOrchestrator
        // .startRtp() runs the very same grant sequence synchronously
        // immediately before this, so doing it again was a second round of
        // eight root commands — each of pm/appops/cmd forks an app_process —
        // followed by a blind 500ms wait, on the path between answering the
        // call and the first frame of audio.  The wait was there for a retry
        // loop that no longer exists: this only ever made one attempt.
        // captureInitAndLoop() still re-asserts and retries if nothing here
        // initialises, which is the case the retry logic was really for.
        for (cfg in configs) {
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    cfg.rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) {
                    Log.w(TAG, "AudioRecord ${cfg.name}@${cfg.rate}: invalid minBuf=$minBuf")
                    continue
                }
                val bufSize = minBuf.coerceAtLeast(cfg.rate / 50 * 2 * 2) // 40ms (two RTP frames)
                val rec = newAudioRecord(cfg.source, cfg.rate, bufSize)
                if (profile.captureFromTelephonyRx) {
                    routeCaptureToTelephonyRx(rec)
                }
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    record = rec
                    usedRate = cfg.rate
                    audioSourceName = cfg.name
                    currentSourceId = cfg.source
                    Log.i(TAG, "AudioRecord OK: ${cfg.name} @ ${cfg.rate}Hz (buf=$bufSize)")
                    break
                } else {
                    Log.w(TAG, "AudioRecord ${cfg.name}@${cfg.rate}: state=${rec.state}")
                    rec.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord ${cfg.name}@${cfg.rate} failed: ${e.message}")
            }
        }

        if (record != null) {
            capture = AudioRecordSource(record, audioSourceName)
            audioSessionId = record.audioSessionId
            captureRate = usedRate

            // Prime the Qualcomm in-call capture path before creating the
            // playback track.  On Pixel 4a the order matters: creating the
            // incall_music track first can leave the modem TX output waiting
            // on a voice path that has not been opened yet, producing random
            // SIP->GSM one-way calls after the first call.
            if (profile.primeCaptureBeforePlayback) try {
                record.startRecording()
                Log.i(TAG, "AudioRecord primed before AudioTrack: state=${record.recordingState}")
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord prime failed: ${e.message}")
            }

            // Diagnostic sweep — off unless explicitly enabled, see
            // audioDiagnosticsEnabled().
            if (audioDiagnosticsEnabled()) logCaptureDiagnostics(record)
        }

        // The digital bridge: the agent's audio goes into the modem's uplink
        // (the telephony TX device) instead of the phone's speaker, and the
        // handset's microphone is kept out of the call.  Without it the
        // bridge is an acoustic loop — the speaker plays the agent and the
        // phone's own microphone carries him to the caller, so the gateway
        // becomes a participant in the conversation.
        //
        // Settings → "Digital bridge" turns it off to fall back to that loop
        // on a device whose audio policy does not offer the telephony TX
        // route: there the GSM side would go silent once the microphone is
        // muted.
        val digitalBridge = digitalBridgeOn()
        Log.i(TAG, "Digital bridge: $digitalBridge (profile=${profile.name})")
        digitalBridgeActive = digitalBridge

        // Minimum buffer for lowest latency.  incall_music injects
        // AudioTrack output digitally into the modem uplink — there is no
        // acoustic speaker→mic path, so deep-buffer headroom is unnecessary.
        // Writing silence when the jitter buffer is empty prevents underruns.
        //
        // The incall_music mixPort declares stereo and nothing else, so the
        // digital route asks for a stereo track; the acoustic one stays mono.
        val stereo = digitalBridge || profile.playbackStereo
        val playChannelMask = if (stereo)
            AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        playbackChannels = if (stereo) 2 else 1
        val minPlayBuf = AudioTrack.getMinBufferSize(
            playbackRate, playChannelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        // A minimum-sized buffer qualifies for the fast path, which the
        // Qualcomm HAL serves on MultiMedia5 — a front-end whose audio goes to
        // the speaker, not into the voice uplink.  Asking for a larger buffer
        // pushes the HAL to deep-buffer-playback on MultiMedia1, which is the
        // front-end the Incall_Music mixer injects from.
        val playBufSize = if (profile.playbackBufferMs > 0) {
            maxOf(minPlayBuf, playbackRate * 2 * playbackChannels * profile.playbackBufferMs / 1000)
        } else {
            minPlayBuf
        }

        // Profile-controlled playback usage:
        // USAGE_MEDIA (default / MSM8930): Maps to STREAM_MUSIC.  Qualcomm's
        // incall_music_enabled=true injects STREAM_MUSIC into voice TX.
        // USAGE_VOICE_COMMUNICATION (Exynos 9820): Maps to STREAM_VOICE_CALL.
        // Samsung's HAL may route this into the modem uplink when a voice
        // call is active.  On Exynos, no incall_music mixer exists, so
        // USAGE_MEDIA only plays on the speaker without reaching the modem.
        //
        // IMPORTANT: Do NOT use PERFORMANCE_MODE_LOW_LATENCY here.
        // Low-latency forces the HAL to use "low-latency-playback" usecase
        // which maps to MultiMedia5.  On MSM8930 (Galaxy S4 Mini), only
        // MultiMedia1 and MultiMedia2 have Incall_Music mixer controls.
        // MultiMedia5 has no incall_music mixer, so audio plays on the
        // earpiece but is NEVER injected into the modem uplink.
        // Using default (deep-buffer-playback → MultiMedia1) ensures the
        // Incall_Music Audio Mixer MultiMedia1 routes audio to the caller.
        val usage = when {
            // The digital route asks the policy for the telephony TX device,
            // and that mixPort is declared for STREAM_MUSIC.
            digitalBridge -> AudioAttributes.USAGE_MEDIA
            profile.playbackUsage >= 0 -> profile.playbackUsage
            else -> AudioAttributes.USAGE_MEDIA
        }
        val contentType = if (usage == AudioAttributes.USAGE_VOICE_COMMUNICATION)
            AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC
        playbackUsageName = when (usage) {
            AudioAttributes.USAGE_MEDIA -> "MEDIA"
            AudioAttributes.USAGE_VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            else -> "usage=$usage"
        }
        // Must happen before the track exists on HALs that pick the output
        // usecase at creation time — see DeviceProfile.incallMusicBeforeTrack.
        //
        // Not in the early phase: the HAL rewrites the voice path when it
        // programs the call, so an injection written before that is gone with
        // it.  It is written when the leg comes up instead — see
        // applyForActiveLeg().
        if (profile.incallMusicBeforeTrack && !earlyCapture) {
            enableIncallMusic()
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(contentType)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(playbackRate)
                    .setChannelMask(playChannelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .apply {
                if (profile.playbackLowLatency) {
                    // Deliberately no setBufferSizeInBytes here: asking for
                    // low latency and then handing the framework a large
                    // buffer is contradictory, and the request is silently
                    // ignored.  Let it size the buffer itself.
                    //
                    // setPerformanceMode arrived in Android 8 (API 26).  Below
                    // that the call is a NoSuchMethodError -- an Error, so the
                    // catch around the track creation does not catch it.
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    } else {
                        setBufferSizeInBytes(playBufSize)
                    }
                } else {
                    setBufferSizeInBytes(playBufSize)
                }
            }
            .build()
        audioTrack = track

        if (digitalBridge || profile.playbackToTelephonyTx) {
            routeToTelephonyTx(track)
            try {
                Log.i(TAG, "Track routed: device=${track.routedDevice?.type}/${track.routedDevice?.productName} " +
                    "rate=${track.sampleRate} channels=${track.channelCount}")
            } catch (_: Exception) {}
        }

        // No platform AEC — AudioTrack is on USAGE_MEDIA (different stream
        // from AudioRecord), so platform AEC can't reference it anyway.
        // For VOICE_DOWNLINK, AEC was over-canceling (capRMS dropped from 252 to ~5).
        // Echo cancellation is handled by Asterisk on the server side.

        // Log what was actually granted, not what was asked for: the
        // low-latency request can be refused silently.
        val grantedFrames = try { track.bufferSizeInFrames } catch (_: Exception) { -1 }
        val grantedMs = if (grantedFrames > 0) grantedFrames * 1000 / playbackRate else -1
        Log.i(TAG, "Audio init: playRate=$playbackRate playUsage=$playbackUsageName " +
            "playBuf=${grantedFrames}f/${grantedMs}ms (asked=$playBufSize min=$minPlayBuf " +
            "lowLatency=${profile.playbackLowLatency}) ch=$playbackChannels " +
            "capture=${if (capture != null) audioSourceName else "deferred"} profile=${profile.name}")

        // The handset's loudspeaker is closed from the first moment of the
        // bridge.  It used to wait for the late pass three seconds in, and the
        // far end's voice was in the room for those three seconds — the
        // framework's own voice-stream mute cannot close that window here,
        // because on this chipset it takes the modem's downlink out of the
        // agent's capture with it.
        //
        // This write is best effort: the HAL rewrites these controls when it
        // programs the call path, which is why the keeper re-asserts them
        // every few seconds for as long as the call lasts.
        //
        // Not in the early phase: the handset is still playing the operator's
        // announcement out loud there, and the microphone is the last resort
        // for capturing it — muting the loudspeaker first would throw it away.
        if (!earlyCapture) {
            applyHandsetOutputMute(captureRms)
        }
        return true
    }

    /**
     * Open the call over raw PCMs: route, start both tinypcm processes, then
     * switch the call over to them.  See [DeviceProfile.rootPcm].
     *
     * Both directions run at the codec's rate; the DSP converts to and from
     * the call's own.  Undoes its routing and returns false if either process
     * does not come up, so the caller can fall back to the HAL.
     */
    private fun startRootPcm(route: RootPcmRoute): Boolean = synchronized(ROOT_PCM_LOCK) {
        val setup = DeviceProfile.resolveCmd(route.setupCmd)
        if (setup.isEmpty()) {
            listener?.onRtpStats("Root PCM: tinymix not found")
            return false
        }
        listener?.onRtpStats("Root PCM setup: ${RootShell.execForOutput(setup, timeoutMs = 8000).trim()}")

        val rate = playbackRate
        // 10ms periods; the pipe is mono, the PCMs stereo.
        val common = "-D ${route.card} -c ${route.channels} -r $rate -b 16 -p ${rate / 100} -m -w 3000"
        val cap = RootPcm.start(TINYPCM, "cap $common -d ${route.captureDevice} -n 4 -q 10", "cap")
        val pacer = if (route.pacerDevice >= 0) " -P ${route.pacerDevice}" else ""
        val play = cap?.let {
            RootPcm.start(TINYPCM, "play $common -d ${route.playDevice} -n 3 -q 6$pacer", "play",
                banners = if (pacer.isEmpty()) 1 else 2)
        }
        if (cap == null || play == null) {
            val why = (play ?: cap)?.recentMessages() ?: ""
            cap?.stop()
            play?.stop()
            RootShell.exec(DeviceProfile.resolveCmd(route.teardownCmd), timeoutMs = 8000)
            listener?.onRtpStats("Root PCM did not start: ${why.ifEmpty { "see logcat RootPcm" }}")
            return false
        }

        pcmCapture = cap
        pcmPlayback = play
        captureRate = rate
        playbackChannels = 1
        nextPlayNs = 0L
        audioSourceName = "PCM C${route.card}D${route.captureDevice}"
        playbackUsageName = "PCM C${route.card}D${route.playDevice}"
        val connect = RootShell.execForOutput(DeviceProfile.resolveCmd(route.connectCmd), timeoutMs = 8000)
        Log.i(TAG, "Root PCM up at $rate Hz: capture=$audioSourceName playback=$playbackUsageName")
        listener?.onRtpStats("Root PCM up at $rate Hz, call switched over: ${connect.trim()}")
        return true
    }

    /**
     * Shut the raw PCM path down in the order the DSP needs: the call gets its
     * own audio back while our streams still run, the streams close, the
     * routes they needed go.
     */
    private fun stopRootPcm(): Unit = synchronized(ROOT_PCM_LOCK) {
        val route = profile.rootPcm ?: return
        val cap = pcmCapture
        val play = pcmPlayback
        if (cap == null && play == null) return
        RootShell.exec(DeviceProfile.resolveCmd(route.disconnectCmd), timeoutMs = 8000)
        pcmCapture = null
        pcmPlayback = null
        cap?.stop()
        play?.stop()
        RootShell.exec(DeviceProfile.resolveCmd(route.teardownCmd), timeoutMs = 8000)
        Log.i(TAG, "Root PCM closed")
    }

    /**
     * Initialize AudioRecord with retries, then run the capture loop.
     *
     * On cold boot, AudioFlinger refuses to create record tracks for
     * 10-30+ seconds after the voice call starts ("could not create record
     * track, status: -1").  The audio HAL needs time to fully initialize
     * the recording infrastructure after the modem voice path starts.
     *
     * Playback (SIP→GSM via incall_music) runs in a separate thread and
     * starts immediately.  This method retries capture init for up to 30s
     * so the caller hears the agent right away, even if the reverse
     * direction (GSM→SIP) takes longer to come up.
     */
    private fun captureInitAndLoop() {
        pcmCapture?.let { rootCaptureLoop(it); return }

        val defaultRemoteInet = InetAddress.getByName(remoteAddr)

        // Root PCM capture first where the profile asks for it — see
        // DeviceProfile.rootCapture.  tinycap can exit mid-call (the HAL
        // re-opens the in-call usecases on hold/resume or a RAT handover), and
        // on the devices that need this the AudioRecord sources below are the
        // ones that read silence, so a fresh tinycap gets a few tries before
        // they get a turn.
        profile.rootCapture?.let { cfg ->
            for (attempt in 1..ROOT_CAPTURE_ATTEMPTS) {
                if (!running.get()) return
                if (!startRootCapture(cfg)) break   // could not launch: retrying will not help
                if (captureLoop() || !running.get()) return
                Log.w(TAG, "Root PCM capture ended (attempt $attempt/$ROOT_CAPTURE_ATTEMPTS) — restarting")
                sendSilencePackets(500, defaultRemoteInet)
            }
            Log.w(TAG, "Root PCM capture unavailable — trying the AudioRecord sources")
        }

        // Fast path: the source was already opened in initAudio (warm boot)
        if (capture != null) {
            if (captureLoop() || !running.get()) return
            // Source produced silence for 3s — fall through to try alternatives
            Log.w(TAG, "Primary source $audioSourceName silent, searching for working source (skip: $silentSourceIds)")
        }

        // Deferred/fallback source finding + capture loop.
        // Rebuilds the source list each iteration, excluding sources detected
        // as silent.  This handles both cold-boot AudioRecord unavailability
        // AND sources that initialize but deliver no audio (e.g., VOICE_CALL
        // on Exynos 9820 where the HAL doesn't route voice data to capture).
        val maxFallbacks = 5  // Maximum silent-source fallback cycles

        for (fallback in 0 until maxFallbacks) {
            if (!running.get()) return

            // Build source list, filtering out sources that produced silence
            val configs = buildCaptureConfigs(wideband)
            if (configs.isEmpty()) {
                Log.e(TAG, "All capture sources produced silence — no working source found")
                break
            }

            if (fallback > 0 || capture == null) {
                Log.w(TAG, "Trying capture sources (fallback=$fallback): ${configs.joinToString { it.name }} (silent: $silentSourceIds)")
            }

            // Try to initialize AudioRecord with one of the remaining sources
            val maxAttempts = if (fallback == 0 && capture == null) 15 else 3
            var sourceFound = false

            for (attempt in 1..maxAttempts) {
                if (!running.get()) return

                reAssertAppOps()
                sendSilencePackets(500, defaultRemoteInet)

                for (cfg in configs) {
                    if (cfg.source in silentSourceIds) continue
                    try {
                        val minBuf = AudioRecord.getMinBufferSize(
                            cfg.rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                        )
                        if (minBuf <= 0) continue
                        val bufSize = minBuf.coerceAtLeast(cfg.rate / 50 * 2 * 2)
                        val rec = newAudioRecord(cfg.source, cfg.rate, bufSize)
                        if (profile.captureFromTelephonyRx) {
                        routeCaptureToTelephonyRx(rec)
                    }
                    if (rec.state == AudioRecord.STATE_INITIALIZED) {
                            if (!running.get()) { rec.release(); return }
                            capture = AudioRecordSource(rec, cfg.name)
                            audioSessionId = rec.audioSessionId
                            captureRate = cfg.rate
                            audioSourceName = cfg.name
                            currentSourceId = cfg.source
                            Log.i(TAG, "AudioRecord OK: ${cfg.name} @ ${cfg.rate}Hz (buf=$bufSize, fallback=$fallback attempt=$attempt)")
                            sourceFound = true
                            break
                        } else {
                            Log.w(TAG, "AudioRecord ${cfg.name}@${cfg.rate}: state=${rec.state}")
                            rec.release()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "AudioRecord ${cfg.name}@${cfg.rate} failed: ${e.message}")
                    }
                }

                if (sourceFound) break

                if (attempt < maxAttempts && running.get()) {
                    Log.w(TAG, "All remaining sources failed (fallback=$fallback attempt=$attempt/$maxAttempts), retrying in 2s")
                    sendSilencePackets(2000, defaultRemoteInet)
                }
            }

            if (!sourceFound) {
                Log.e(TAG, "Could not initialize any remaining capture source")
                break
            }

            // Run capture loop — returns false if silence detected
            Log.i(TAG, "Capture ready (fallback=$fallback), starting capture loop with $audioSourceName")
            if (captureLoop() || !running.get()) return
            // Silence detected — silentSourceIds updated, loop again with remaining sources
            Log.w(TAG, "Source $audioSourceName silent after 3s (skip: $silentSourceIds), trying next fallback")
        }

        // All sources exhausted — DON'T tear down the call.  Playback
        // (SIP→GSM via incall_music) still works if we keep NAT alive.
        // Continue sending silence RTP so the caller at least hears the agent.
        Log.e(TAG, "All capture sources exhausted — capture disabled, keeping NAT alive for playback")
        while (running.get()) {
            sendSilencePackets(5000, defaultRemoteInet)
        }
    }

    /**
     * Start reading the caller from [cfg] over root.
     *
     * False — with nothing changed — if the mixer command it needs cannot
     * be resolved, or tinycap could not be launched or produced nothing.
     */
    private fun startRootCapture(cfg: RootCapture): Boolean {
        val setup = DeviceProfile.resolveCmd(cfg.setupCmd)
        if (cfg.setupCmd.isNotEmpty() && setup.isEmpty()) {
            // Without the routing command the PCM opens un-routed and reads
            // zeros for the whole call — not something to find out slowly.
            Log.w(TAG, "Root PCM capture: tinymix NOT FOUND, cannot route the PCM")
            return false
        }
        val rate = if (wideband) 16000 else 8000
        val cap = try {
            RootPcmCapture(cfg.card, cfg.device, cfg.rate, rate, setup, DeviceProfile.resolveCmd(cfg.restoreCmd))
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Root PCM capture: ${e.message}")
            return false
        }
        if (!cap.start()) return false
        capture = cap
        captureRate = rate
        audioSourceName = cap.name
        currentSourceId = -1
        // stop() may have run while su was spawning: it saw no capture to
        // close, so this one has to go the same way itself.
        if (!running.get()) {
            releaseCaptureSource()
            return false
        }
        return true
    }

    /** Drop the current capture source: unblock its reader now, give the
     *  device back off this thread. */
    private fun releaseCaptureSource() {
        val src = capture ?: return
        capture = null
        src.close()
        Thread({ src.release() }, "capture-release").start()
    }

    /** Leave the current source, saying why, and have the caller pick another.
     *  [blacklist] keeps an AudioRecord source from being tried again this
     *  call; a root PCM has no source id and is never blacklisted. */
    private fun giveUpSource(msg: String, blacklist: Boolean = true): Boolean {
        Log.w(TAG, msg)
        listener?.onRtpStats(msg)
        if (blacklist && currentSourceId >= 0) silentSourceIds.add(currentSourceId)
        releaseCaptureSource()
        return false
    }

    /** Build the prioritized list of capture source configs, excluding
     *  sources already detected as silent. */
    private fun buildCaptureConfigs(wideband: Boolean): List<SourceConfig> {
        val configs = mutableListOf<SourceConfig>()
        if (wideband) {
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL@16k", 16000))
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL", 8000))
        } else {
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL", 8000))
        }
        // VOICE_DOWNLINK ahead of the acoustic sources when the profile says
        // it works here.  It captures the caller's voice digitally, off the
        // modem downlink, which is what lets the physical mic stay muted — on
        // the acoustic path the mic is the capture source, so the caller hears
        // the room and the agent hears itself through the speaker.
        if (profile.voiceDownlinkWorks) {
            if (wideband) {
                configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK@16k", 16000))
            }
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK", 8000))
        }
        if (profile.preferUnprocessedMic) {
            // Raw mic, ahead of the voice-tuned sources: no AEC, no noise
            // suppression, no AGC.  When the caller reaches us as sound out of
            // the phone's own speaker, those are all working against us.
            configs.add(SourceConfig(MediaRecorder.AudioSource.UNPROCESSED, "UNPROCESSED", 8000))
            configs.add(SourceConfig(MediaRecorder.AudioSource.CAMCORDER, "CAMCORDER", 8000))
        }
        configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION", 8000))
        configs.add(SourceConfig(MediaRecorder.AudioSource.MIC, "MIC", 8000))
        configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION", 8000))

        // With the digital bridge on, VOICE_CALL stays in the chain: the
        // agent's voice goes into the modem's uplink digitally and never
        // passes the microphone, so the uplink it mixes in is clean — and on
        // the devices whose VOICE_DOWNLINK delivers nothing but silence it is
        // the only source that carries the caller at all.
        if (profile.voiceDownlinkWorks && !digitalBridgeOn()) {
            // Where the modem downlink is capturable, it is the only source we
            // want: VOICE_CALL mixes uplink AND downlink, and the uplink now
            // carries the agent's own injected voice, so the agent hears itself
            // folded into the caller — which is what the far end perceives as
            // noise.  The downlink alone is the caller.  Acoustic sources stay
            // behind it purely as a last resort.
            configs.removeAll { it.source == MediaRecorder.AudioSource.VOICE_CALL }
            val downlink = configs.filter {
                it.source == MediaRecorder.AudioSource.VOICE_DOWNLINK
            }
            configs.removeAll(downlink)
            configs.addAll(0, downlink)
        }
        if (!profile.voiceDownlinkWorks) {
            if (wideband) {
                configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK@16k", 16000))
            }
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK", 8000))
        }
        if (profile.preferVoiceRecognition) {
            // Ahead of everything, including VOICE_DOWNLINK: on these handsets
            // it is the source that actually delivers a steady frame rate, and
            // arriving steadily matters more than arriving digitally.
            val vr = configs.filter { it.source == MediaRecorder.AudioSource.VOICE_RECOGNITION }
            configs.removeAll(vr)
            configs.addAll(0, vr)
        }
        // The early phase has its own short list — see initAudio.
        if (earlyCapture) {
            val allowed = EarlyCapturePolicy.sources(profile.voiceDownlinkWorks)
            configs.removeAll { it.source !in allowed }
        }
        return configs.filterNot { it.source in silentSourceIds }
    }

    /**
     * Send silence RTP packets for the specified duration (ms).
     *
     * Keeps the NAT pinhole alive and prevents Asterisk's RTP timeout
     * while AudioRecord is unavailable on cold boot.  Uses proper RTP
     * sequencing (txSequence/txTimestamp) so captureLoop() can take
     * over seamlessly when AudioRecord finally initializes.
     */
    private fun sendSilencePackets(durationMs: Long, defaultRemoteInet: InetAddress) {
        val intervalMs = 20L  // one RTP frame = 20ms
        val end = System.currentTimeMillis() + durationMs

        // Pre-encode silence for the negotiated codec (reused every frame)
        val silencePayload = when (payloadType) {
            RtpPacket.PT_PCMA -> ByteArray(160) { 0xD5.toByte() }  // A-law silence
            RtpPacket.PT_PCMU -> ByteArray(160) { 0xFF.toByte() }  // µ-law silence
            else -> ByteArray(160)  // G.722: zeros → near-silence
        }

        while (System.currentTimeMillis() < end && running.get()) {
            try {
                val destAddr = latchedAddr ?: defaultRemoteInet
                val destPort = if (latchedAddr != null) latchedPort else remotePort

                val packet = RtpPacket(payloadType, txSequence, txTimestamp, txSsrc, silencePayload)
                val data = packet.encode()
                sendRtp(data, destAddr, destPort)

                txSequence = (txSequence + 1) and 0xFFFF
                txPacketCount++
                txTimestamp += 160  // 20ms at 8000Hz RTP clock
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "Silence RTP send error: ${e.message}")
            }
            Thread.sleep(intervalMs)
        }
    }

    /**
     * Stop the session and release the audio devices.
     *
     * The release has to happen *after* the loops have left `record.read()`
     * and `track.write()` — releasing an AudioRecord while another thread is
     * inside a read is a use-after-free in the native layer, which the broad
     * catch in the loops was quietly papering over.
     *
     * The waiting is done on its own thread because stop() is reached from
     * tearDown(), which runs on the main thread via the Telecom callback;
     * blocking there for up to four joins is how ANRs happen.
     */
    fun stop() {
        val wasRunning = running.getAndSet(false)
        // A key held at hang-up must not leave a tone queued on the GSM leg.
        try { dtmfDetector?.flush() } catch (_: Exception) {}

        // Do this before waiting for AudioFlinger/AudioRecord threads.  On the
        // Pixel 4a an AudioTrack write can remain blocked while the old
        // incall_music_uplink output is still alive; waiting first lets the
        // next call open a second output on top of that stale one.
        stopHalAudioImmediately()
        // Put the in-call HAL parameters back here as well as in
        // GsmCallManager.restoreAudio(): that one is skipped when the
        // InCallService is already gone, which would leave the handset's mic and
        // earpiece muted for the next ordinary call.
        restoreHalCallParams()

        if (!wasRunning) {
            notifyRtpStopped()
            return
        }
        Log.i(TAG, "Stopping RTP session on port $localPort")
        try { capDump?.close() } catch (_: Exception) {}
        try { playDump?.close() } catch (_: Exception) {}

        setMonitorEnabled(false)

        // Closing the socket unblocks receiveLoop's blocking receive at once
        // rather than leaving it to time out.
        try { socket?.close() } catch (_: Exception) {}

        val ws = workers
        workers = emptyList()
        val cap = capture
        val trk = audioTrack
        // Unblock the capture thread's read now — for tinycap that means
        // killing it — so the joins below do not wait on it.
        capture = null
        cap?.close()
        audioTrack = null
        socket = null
        jitterBuffer.clear()

        Thread({
            val me = Thread.currentThread()
            ws.forEach { if (it !== me) it.interrupt() }
            ws.forEach {
                if (it === me) return@forEach
                try { it.join(JOIN_TIMEOUT_MS) } catch (_: InterruptedException) {}
                if (it.isAlive) Log.w(TAG, "${it.name} still running after ${JOIN_TIMEOUT_MS}ms")
            }
            try { stopRootPcm() } catch (e: Exception) { Log.w(TAG, "Root PCM stop: ${e.message}") }
            cap?.let { try { it.release() } catch (_: Exception) {} }
            trk?.let {
                try { it.stop() } catch (_: Exception) {}
                try { it.release() } catch (_: Exception) {}
            }
            // Repeat the inactive state after the native streams are released
            // in case AudioFlinger recreated the output while they drained.
            stopHalAudioImmediately()
            Log.i(TAG, "RTP session on port $localPort fully released")
            notifyRtpStopped()
        }, "RTP-Stop-$localPort").start()
    }

    /** Shut down the Qualcomm voice/in-call output without waiting for worker
     * threads that may be blocked inside AudioFlinger. */
    private fun stopHalAudioImmediately() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val param = profile.incallMusicParam
            if (am != null && param.isNotEmpty()) {
                am.setParameters("${param}=false")
                Log.i(TAG, "${param}=false (immediate RTP stop)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Immediate incall_music shutdown failed: ${e.message}")
        }
        setHalCallState(1)
    }

    private fun notifyRtpStopped() {
        if (stopNotified.compareAndSet(false, true)) {
            listener?.onRtpStopped()
        }
    }

    // ── Capture: VOICE_CALL → echo gate → gain → encode → RTP send ──

    // Audio parameters from device profile
    private val profile get() = GsmCallManager.profile
    private val captureGain get() = profile.captureGain
    /** The profile's digital gain with its dB trim and the manual trim from
     *  settings applied. */
    private val playbackGain: Double
        get() = profile.playbackGain * Math.pow(10.0, profile.playbackGainDb / 20) *
            GsmCallManager.agentGainFactor

    // Double-talk detection: VOICE_CALL captures uplink+downlink from
    // the modem DSP.  The uplink contains the SIP agent's voice (injected
    // via incall_music).  Instead of a hard echo gate (which made the
    // bridge half-duplex — caller COMPLETELY silenced during agent speech),
    // we adaptively estimate the echo level and detect when the caller is
    // speaking simultaneously (double-talk / barge-in).
    //
    // How it works: incall_music injects AudioTrack digitally into the
    // modem uplink with a consistent gain ratio.  We track that ratio
    // (echoGainRatio = captureRMS / playbackRMS) during echo-only frames.
    // When captureRMS significantly exceeds the expected echo level,
    // the caller must be speaking — forward the audio (some echo leaks
    // but the caller is audible).  This enables full-duplex barge-in.
    private val echoGateThreshold get() = profile.echoGateThreshold
    @Volatile private var currentPlaybackActive = false

    // Decaying echo reference: instead of a hard on/off echo gate, we
    // track a decaying playback RMS that persists through brief inter-word
    // gaps.  When the agent pauses between words (50-100ms), the echo
    // tail in the capture pipeline still has energy.  Without decay, the
    // gate flips off and the echo tail passes the noise gate, reaching
    // the agent as "caller speech" = false interruption.
    //
    // Decay factor 0.80 per 20ms frame:
    //   50ms (2-3 frames): ~64% of peak → echo tail suppressed
    //   100ms (5 frames):  ~33% → still suppressed
    //   200ms (10 frames): ~11% → fading, real speech passes easily
    //   300ms (15 frames): ~3%  → effectively zero
    // This allows barge-in after ~200ms while suppressing echo tails.
    private var decayingPlaybackRms = 0

    // Adaptive echo gain: ratio of capture RMS to playback RMS during
    // confirmed echo-only frames.  Updated via exponential moving average.
    // Initial 1.0 = assume 1:1 coupling; adapts to actual modem DSP gain.
    @Volatile private var echoGainRatio = 1.0f
    private var echoGainSamples = 0
    private val DOUBLE_TALK_RATIO get() = profile.doubleTalkRatio

    // Noise gate: below this RMS, send silence instead of captured audio.
    // Threshold is device-specific (modem DSP noise floor varies).
    private val noiseGateThreshold get() = profile.noiseGateThreshold

    // Raw PCM dump for diagnosing audio by ear or by analysis.  Opt-in:
    // only when files/dump_audio exists.  Overwritten every call.
    private val dumpDir = java.io.File(context.filesDir, "dump")
    private val dumpOn = java.io.File(context.filesDir, "dump_audio").exists()
    private val capDump: java.io.OutputStream? = if (dumpOn) try {
        dumpDir.mkdirs(); java.io.BufferedOutputStream(java.io.FileOutputStream(java.io.File(dumpDir, "cap.raw")))
    } catch (_: Exception) { null } else null
    private val playDump: java.io.OutputStream? = if (dumpOn) try {
        java.io.BufferedOutputStream(java.io.FileOutputStream(java.io.File(dumpDir, "play.raw")))
    } catch (_: Exception) { null } else null

    // Diagnostic counters: track how frames are classified for stats logging
    @Volatile private var echoGatedFrames = 0L
    @Volatile private var noiseGatedFrames = 0L
    @Volatile private var forwardedFrames = 0L
    @Volatile private var doubleTalkFrames = 0L  // caller spoke during agent playback

    // CPU tracking: process CPU ticks at last sample for delta calculation
    private var lastCpuTicks = 0L
    private var lastCpuSampleMs = 0L

    /**
     * Capture loop: read from AudioRecord, gate, encode, send via RTP.
     * Returns true on normal exit (call ended), false if the source was
     * detected as silent (rawCapRMS near zero for 3 seconds) and should
     * be replaced with a fallback source.
     */
    private fun captureLoop(): Boolean {
        val src = capture ?: return false
        if (!src.start()) return giveUpSource("Source $audioSourceName would not start — trying fallback")

        // Re-assert in-call capture routing now that the stream exists — see
        // DeviceProfile.mixerCaptureCmd.  Off the capture thread, because the
        // su round-trip takes ~100ms and would stall the first RTP frames.
        val capCmd = DeviceProfile.resolveCmd(profile.mixerCaptureCmd)
        if (capCmd.isNotEmpty()) {
            Thread({
                val out = RootShell.execForOutput(capCmd, timeoutMs = 8000)
                Log.i(TAG, "Mixer capture routing: $out")
            }, "mixer-capture").start()
        }

        // The room must not be in the capture from the very first frame.
        // The late pass in the timeout loop is three seconds into the call,
        // and the microphone is live until then — so the cut goes in here,
        // as soon as the capture exists.  The HAL programs the call path
        // around this moment and puts the microphone back, which is why the
        // cut is re-asserted later as well.
        applyHandsetMicCut(captureRms)

        Log.i(TAG, "Capture started: source=$audioSourceName capRate=$captureRate session=$audioSessionId gain=${captureGain}x profile=${profile.name}")
        // Also report via RTP stats so it appears in the app log viewer
        listener?.onRtpStats("Capture: source=$audioSourceName rate=$captureRate gain=${captureGain}x profile=${profile.name}")

        // Buffer: 20ms of PCM at the actual capture sample rate
        val samplesPerFrame = captureRate / 50  // 160 @ 8kHz, 320 @ 16kHz
        val pcmBuf = ByteArray(samplesPerFrame * 2)
        val defaultRemoteInet = InetAddress.getByName(remoteAddr)
        var silenceFrameCount = 0
        // Whether this source has ever delivered audio.  The silence detector
        // answers "does this source work at all", which is only an open
        // question until the first frame arrives; after that, quiet means the
        // line is quiet.
        var sourceProven = false
        var lastDeadAirLog = 0L

        // Frame-rate watchdog.  The silence detector asks "is this source
        // delivering audio"; this asks "is it delivering it on time".  A
        // source can pass the first and fail the second, which is what a
        // bursty VOICE_CALL does, and only the second failure produces the
        // gappy uplink that makes an agent talk over itself.
        val captureStartMs = System.currentTimeMillis()
        var framesCaptured = 0L
        var rateChecked = false

        while (running.get()) {
            try {
                val read = src.read(pcmBuf)
                if (read < 0) {
                    // The source is gone: tinycap exited, or a dead IAudioRecord.
                    if (!running.get()) break
                    return giveUpSource("Source $audioSourceName ended — trying fallback", blacklist = false)
                }
                if (read == 0) continue
                framesCaptured++

                // Checked once, on a window early in the call: switching
                // sources mid-call is disruptive, and a source that is going
                // to stall does so from the start.  A proven source is still
                // eligible -- "proven" only means it produced sound, which is
                // exactly the state this failure hides behind.
                // Not where the profile has ruled fallback out: on the S4 Mini
                // VOICE_RECOGNITION ran at 83% on a live call (2026-10-24) and
                // was swapped for VOICE_CALL, which hands the agent its own
                // voice back.  A slightly slow right source beats a wrong one.
                if (!rateChecked && !profile.silenceFallback) rateChecked = true
                if (!rateChecked) {
                    val elapsed = System.currentTimeMillis() - captureStartMs
                    if (elapsed >= RATE_CHECK_AFTER_MS) {
                        rateChecked = true
                        val expected = elapsed / 20                 // 50 frames/s
                        if (expected > 0 && framesCaptured * 100 / expected < RATE_MIN_PERCENT) {
                            val pct = framesCaptured * 100 / expected
                            val msg = "Source $audioSourceName too slow: $framesCaptured/$expected " +
                                "frames ($pct%) in ${elapsed}ms — trying fallback"
                            return giveUpSource(msg)
                        }
                    }
                }

                // Measure raw capture level BEFORE echo gate for diagnostics.
                // If rawCaptureRms=0, the audio source itself is silent
                // (HAL/modem not providing audio).  If rawCaptureRms>0 but
                // captureRms=0, the echo gate is suppressing.
                rawCaptureRms = pcmRms(pcmBuf)

                // The cellular leg came up: the source has to be opened again.
                // The HAL picks the input usecase when the record stream is
                // created, and a stream created before the voice path existed
                // is bound to a path that stays silent — it never recovers.
                if (reopenCaptureRequested) {
                    reopenCaptureRequested = false
                    Log.i(TAG, "Capture reopen requested — the source is opened again with the voice path up")
                    listener?.onRtpStats("Capture reopened after the cellular leg came up")
                    // The sources retired during the early phase get another
                    // turn: they were judged before the voice path existed.
                    silentSourceIds.clear()
                    releaseCaptureSource()
                    return false
                }
                capDump?.let { try { it.write(pcmBuf, 0, read) } catch (_: Exception) {} }
                if (monitorTrack != null) lastCallerFrame = pcmBuf.copyOf()

                // Silence detection: only count during non-echo periods (when
                // decayingPlaybackRms <= echoGateThreshold).  incall_music echo
                // leaks back through VOICE_CALL capture, spiking rawCapRMS
                // above the threshold during agent speech — this would reset a
                // naive counter even though the source delivers NO caller audio.
                // By only counting non-echo frames, we accurately detect dead
                // sources regardless of whether the agent is speaking.
                val noEchoPeriod = decayingPlaybackRms <= echoGateThreshold
                if (noEchoPeriod) {
                    if (rawCaptureRms < SILENCE_RMS_THRESHOLD) {
                        silenceFrameCount++
                        if (silenceFrameCount == 10 || silenceFrameCount == 20) {
                            Log.w(TAG, "Source $audioSourceName low audio (no-echo): rawCapRMS=$rawCaptureRms silence=${silenceFrameCount}/${SILENCE_FRAME_LIMIT} frames")
                        }
                        if (silenceFrameCount >= SILENCE_FRAME_LIMIT) {
                            if (sourceProven || !profile.silenceFallback) {
                                // Dead air, not a dead source.  Tearing the
                                // AudioRecord down here and blacklisting the
                                // source was catastrophic mid-call: with
                                // playbackLeaksIntoCapture off every quiet
                                // frame counts, so one limit's worth of nobody
                                // speaking — six seconds on this profile —
                                // retired a working source.  A few of those
                                // exhausted the source list and capture stayed
                                // dead for the rest of the call: the agent
                                // stopped hearing the caller and never got
                                // them back.
                                val now = System.currentTimeMillis()
                                if (now - lastDeadAirLog > 30_000) {
                                    lastDeadAirLog = now
                                    val msg = "Dead air ${silenceFrameCount / 50}s on " +
                                        "$audioSourceName (source proven — keeping it)"
                                    Log.w(TAG, msg)
                                    listener?.onRtpStats(msg)
                                }
                                silenceFrameCount = 0
                            } else if (allowSilentStart &&
                                System.currentTimeMillis() - captureStartMs < SILENT_START_GRACE_MS) {
                                // The cellular leg was answered early, so the
                                // downlink was opened before the network had
                                // anything to say on it.  Silence now means the
                                // network is quiet, not that the source is
                                // dead — and retiring it here would drop the
                                // announcement on the floor and fall back to
                                // a source that carries the room instead of
                                // the call.
                                silenceFrameCount = 0
                                Log.w(TAG, "Source $audioSourceName silent early in an " +
                                        "early-answered call — keeping it for now")
                            } else {
                                return giveUpSource("Source $audioSourceName SILENT ($silenceFrameCount non-echo frames) — trying fallback")
                            }
                        }
                    } else {
                        // Source delivered audio during non-echo → working
                        silenceFrameCount = 0
                        sourceProven = true
                    }
                }
                // During echo periods: don't update counter (can't distinguish
                // caller audio from incall_music echo)

                gateEncodeAndSend(pcmBuf, read, defaultRemoteInet)
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "Capture error: ${e.message}")
            }
        }
        return true  // Normal exit (call ended)
    }

    /**
     * Capture loop for the raw PCM path: 20ms mono frames off tinypcm's stdout.
     *
     * None of the AudioRecord machinery applies.  The source is the call
     * itself, fixed by the profile, so there is nothing to fall back to and no
     * silence to judge it by; and tinypcm drops rather than blocks, so a frame
     * that arrives is a frame on time.
     */
    private fun rootCaptureLoop(pcm: RootPcm) {
        val defaultRemoteInet = InetAddress.getByName(remoteAddr)
        val pcmBuf = ByteArray(captureRate / 50 * 2)
        val input = java.io.DataInputStream(pcm.input)
        listener?.onRtpStats("Capture: source=$audioSourceName rate=$captureRate gain=${captureGain}x profile=${profile.name}")
        while (running.get()) {
            try {
                input.readFully(pcmBuf)
            } catch (e: java.io.IOException) {
                if (running.get()) {
                    Log.e(TAG, "Root PCM capture ended: ${e.message} (${pcm.recentMessages()})")
                    listener?.onRtpStats("Root PCM capture ended: ${pcm.recentMessages()}")
                }
                break
            }
            rawCaptureRms = pcmRms(pcmBuf)
            capDump?.let { try { it.write(pcmBuf) } catch (_: Exception) {} }
            if (monitorTrack != null) lastCallerFrame = pcmBuf.copyOf()
            try {
                gateEncodeAndSend(pcmBuf, pcmBuf.size, defaultRemoteInet)
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "Capture error: ${e.message}")
            }
        }
        // Keep the far end's NAT pinhole and RTP timer alive for the rest of
        // the call even with capture gone; the agent can still be heard.
        while (running.get()) sendSilencePackets(5000, defaultRemoteInet)
    }

    /** Gate, gain, encode and send one captured 20ms frame — the part of the
     *  capture path that does not care where the frame came from. */
    private fun gateEncodeAndSend(pcmBuf: ByteArray, read: Int, defaultRemoteInet: InetAddress) {
        // Double-talk-aware gating: replaces the old hard echo gate
        // that made the bridge half-duplex (caller completely silenced
        // during agent speech).  Now uses adaptive echo level estimation
        // to detect when the caller is speaking over the agent.
        //
        // VOICE_CALL captures uplink+downlink.  incall_music injects
        // the agent's voice digitally into the uplink with a consistent
        // gain ratio.  We track that ratio and detect when capture energy
        // exceeds the expected echo — that excess is the caller's voice.
        // Update decaying echo reference: tracks playback level
        // through brief inter-word gaps to suppress echo tails.
        if (currentPlaybackActive && playbackRms > 0) {
            decayingPlaybackRms = playbackRms
        } else if (decayingPlaybackRms > 0) {
            decayingPlaybackRms = (decayingPlaybackRms * 0.80).toInt()
        }

        val shouldForward: Boolean
        if (profile.playbackLeaksIntoCapture &&
            decayingPlaybackRms > echoGateThreshold) {
            // Agent is speaking (or echo tail still decaying) —
            // check for double-talk (barge-in).
            val expectedEcho = (echoGainRatio * decayingPlaybackRms).toInt()
                .coerceAtLeast(noiseGateThreshold)
            if (rawCaptureRms > (expectedEcho * DOUBLE_TALK_RATIO).toInt()) {
                // Double-talk: caller speaking over agent — forward.
                // Some echo leaks through but caller is audible.
                shouldForward = true
                doubleTalkFrames++
            } else {
                // Echo-only: agent speaking, caller silent — send silence.
                // Update echo gain estimate from confirmed echo frames.
                if (currentPlaybackActive && playbackRms > 500) {
                    val r = rawCaptureRms.toFloat() / playbackRms
                    echoGainRatio = echoGainRatio * 0.95f + r * 0.05f
                    echoGainSamples++
                }
                shouldForward = false
                echoGatedFrames++
            }
        } else if (rawCaptureRms < noiseGateThreshold) {
            // Noise gate: only modem digital noise, no caller speech.
            shouldForward = false
            noiseGatedFrames++
        } else {
            // Caller speaking, agent silent — forward normally.
            shouldForward = true
        }

        if (shouldForward) {
            // Apply gain boost
            if (captureGain > 1) {
                for (i in 0 until read / 2) {
                    val lo = pcmBuf[i * 2].toInt() and 0xFF
                    val hi = pcmBuf[i * 2 + 1].toInt()
                    val sample = ((hi shl 8) or lo) * captureGain
                    val clamped = sample.coerceIn(-32768, 32767)
                    pcmBuf[i * 2] = (clamped and 0xFF).toByte()
                    pcmBuf[i * 2 + 1] = ((clamped shr 8) and 0xFF).toByte()
                }
            }
            captureRms = pcmRms(pcmBuf)
            forwardedFrames++
        } else {
            java.util.Arrays.fill(pcmBuf, 0, read, 0.toByte())
            captureRms = 0
        }

        // Encode based on codec and capture sample rate.
        // G.722 expects 16 kHz PCM; if capture is 8 kHz, upsample first.
        val encoded = when (payloadType) {
            RtpPacket.PT_G722 -> {
                val pcm16k = if (captureRate == 8000) upsample8kTo16k(pcmBuf) else pcmBuf
                g722Encoder.encode(pcm16k)
            }
            RtpPacket.PT_PCMA -> {
                if (captureRate == 8000) PcmaCodec.encode8k(pcmBuf)
                else PcmaCodec.encode(pcmBuf)
            }
            RtpPacket.PT_PCMU -> {
                if (captureRate == 8000) PcmuCodec.encode8k(pcmBuf)
                else PcmuCodec.encode(pcmBuf)
            }
            else -> {
                val pcm16k = if (captureRate == 8000) upsample8kTo16k(pcmBuf) else pcmBuf
                g722Encoder.encode(pcm16k)
            }
        }

        val destAddr = latchedAddr ?: defaultRemoteInet
        val destPort = if (latchedAddr != null) latchedPort else remotePort

        val packet = RtpPacket(payloadType, txSequence, txTimestamp, txSsrc, encoded)
        val data = packet.encode()
        sendRtp(data, destAddr, destPort)

        txSequence = (txSequence + 1) and 0xFFFF
        txPacketCount++
        txTimestamp += 160 // 20ms at 8000Hz RTP clock
    }

    // ── Receive: RTP recv → jitter buffer ───────────────

    private fun receiveLoop() {
        val buf = ByteArray(4096)
        while (running.get()) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket?.receive(packet) ?: break

                // Authenticate before parsing.  A packet that fails is a
                // forgery, a replay or a key mismatch, and none of those
                // should reach the jitter buffer.
                var data = buf
                var len = packet.length
                val ctx = srtpRecv
                if (ctx != null) {
                    val plain = ctx.unprotect(buf, packet.length)
                    if (plain == null) {
                        srtpAuthFailures++
                        // Said out loud, because the symptom of dropping every
                        // packet is a call that simply dies at the 30s media
                        // timeout with nothing explaining why.  Rate-limited so
                        // a wrong key cannot flood the log at 50 packets/s.
                        if (srtpAuthFailures == 1L || srtpAuthFailures % 100L == 0L) {
                            Log.w(TAG, "SRTP auth failed on $srtpAuthFailures packet(s) — wrong key or forged")
                        }
                        continue
                    }
                    data = plain
                    len = plain.size
                }
                val rtp = RtpPacket.decode(data, len) ?: continue

                // Symmetric RTP: latch onto the actual source address
                if (latchedAddr == null) {
                    latchedAddr = packet.address
                    latchedPort = packet.port
                    Log.i(TAG, "Symmetric RTP: latched to ${packet.address.hostAddress}:${packet.port}")
                }

                lastRtpReceivedTime = System.currentTimeMillis()
                rxPacketCount++
                // Log first packet details for debugging
                if (rxPacketCount == 1L) {
                    Log.i(TAG, "First RX: pt=${rtp.payloadType} len=${rtp.payload.size}")
                }
                // Telephone-event first: a DTMF packet must never reach the
                // jitter buffer, and it must not be dropped either — that is
                // how IVR menus used to miss every key press.
                if (rtp.payloadType in telephoneEventPts && rtp.payloadType != payloadType) {
                    rxDtmfPackets++
                    dtmfDetector?.onPacket(rtp.timestamp, rtp.payload)
                    continue
                }
                // Only the negotiated payload type is decoded.  The session
                // runs one decoder, chosen by the payload type the SDP agreed
                // on, so bytes of another codec would be noise — accepting
                // them "just in case" turns a codec change into hiss instead
                // of silence.
                if (rtp.payloadType == payloadType) {
                    if (!jitterBuffer.offer(rtp.payload)) {
                        jitterBuffer.poll() // drop oldest
                        jitterBuffer.offer(rtp.payload)
                        rxDropped++
                    }
                }
            } catch (_: SocketTimeoutException) {
                // normal
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "Receive error: ${e.message}")
            }
        }
    }

    // ── RTP inactivity timeout ─────────────────────────

    private fun timeoutLoop() {
        // Early re-assertion at 3s: combat Android re-revoking RECORD_AUDIO
        // when screen is off, and re-toggle incall_music after speaker route
        // is fully settled (~1.5s after configureAudioBridge).
        try { Thread.sleep(3_000) } catch (_: InterruptedException) { return }
        if (running.get()) {
            reAssertAppOps()
            // In the early phase the cellular leg is not up yet: the handset is
            // still playing the operator's announcement out loud and the
            // microphone is the last source that can pick it up.  The re-toggle
            // and the mutes wait for the leg — see applyForActiveLeg().
            if (!earlyCapture) {
                reToggleIncallMusic()
                // The handset's own loudspeaker is muted now — three seconds in,
                // with the digital capture already running.  The order matters: a
                // framework mute of the voice path can take the path down with it
                // on some chipsets, and then the agent stops hearing the caller.
                // Muting after the capture is alive keeps the two apart, and the
                // log carries the capture level from just before the mute.
                try {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    if (am != null) GsmCallManager.muteLocalVoiceOutput(am, captureRms)
                } catch (e: Exception) {
                    Log.w(TAG, "Local voice output mute failed: ${e.message}")
                }
                // The profile's late HAL parameters — the ones that may take the
                // voice path down with them — go in now, with the capture level
                // from just before in the log.  If the capture goes quiet right
                // after, that is what took it down.
                // The handset's loudspeaker is muted at the output device — the
                // codec's receiver and speaker paths — which is the one mute that
                // cannot take the modem's downlink out of the agent's capture.
                // This is the late pass: the HAL has programmed the call path by
                // now, so this write is the one that sticks.  The keeper re-asserts
                // it every few seconds from here on.
                applyHandsetOutputMute(captureRms)
                // The handset's microphone is disconnected from the analogue stage
                // at the same moment: that is what takes the room out of what the
                // agent hears, and it is the one cut that leaves the caller's
                // voice alone (the caller's voice is digital by then).
                applyHandsetMicCut(captureRms)
            }
        }

        var tick = 0
        while (running.get()) {
            try {
                // Close together at first, then settle down.  At a flat 15s a
                // call that lasted sixteen seconds ended before a single stats
                // line was emitted, so there was nothing at all to diagnose it
                // with afterwards — which is exactly when it is wanted.
                Thread.sleep(if (tick < 3) 5_000L else 15_000L)
                tick++

                // Periodic appops check: Android's AppOpsService can revoke
                // RECORD_AUDIO for background apps when the screen goes off.
                // The check is cheap now (see reAssertAppOps), but there is no
                // reason to make it at all once a call has been up and
                // capturing for a while — the revocation, when it happens,
                // happens early.  First minute at 15s, then once a minute.
                if (tick <= 4 || tick % 4 == 0) reAssertAppOps()

                // Stats.  The stream volumes and mic-mute state used to be
                // queried and appended here every cycle; they are set once at
                // bridge setup and never change during a call, so reading them
                // fifty times over a call told us nothing new.
                val stats = "tx=$txPacketCount rx=$rxPacketCount play=$playbackFrames " +
                        "capRMS=$captureRms rawCapRMS=$rawCaptureRms playRMS=$playbackRms src=$audioSourceName " +
                        "rate=${captureRate}/${playbackRate} jbuf=${jitterBuffer.size} " +
                        "drop=$rxDropped under=$underruns trim=$trimmedFrames dtmf=$rxDtmfPackets " +
                        "gates:echo=$echoGatedFrames noise=$noiseGatedFrames fwd=$forwardedFrames dt=$doubleTalkFrames" +
                        getCpuStats()
                Log.i(TAG, "RTP: $stats")
                listener?.onRtpStats(stats)

                // The vendor's uplink mute — the only lever that takes the
                // handset's microphone out of the cellular uplink on this
                // chipset — goes in here, with the capture level from just
                // before in the log.  It may mute the whole uplink rather
                // than the microphone source, and the agent's voice is
                // injected into that same uplink, so it goes in late and its
                // effect is measured rather than assumed.
                //
                // It is reconciled on every tick, not only on the first one:
                // the override file can switch it off and on again while the
                // call is up, and the effect of that switch on the capture
                // level is what the A/B measurement is made of.
                applyLateHalCallParams(captureRms)
                applyDiagnosticMicCmd()

                // …and the same for the microphone cut: the HAL programs the
                // call path itself and puts the microphone back when it does,
                // so the room would come back into the capture without this.
                //
                // Not before the cellular leg is up: in the early phase the
                // microphone is the last source that can pick up the operator's
                // announcement while the handset plays it out loud.
                if (cellularLegUp) {
                    applyHandsetMicCut(captureRms, repeat = true)
                }

                val elapsed = System.currentTimeMillis() - lastRtpReceivedTime
                // Before the cellular leg is up the stream is one-sided: the
                // server has nothing to send while the agent's phone is still
                // ringing.  The timeout would fire on a call that is perfectly
                // alive, so it only counts once the leg is up.
                if (elapsed > RTP_TIMEOUT_MS && cellularLegUp) {
                    Log.w(TAG, "RTP timeout: no packets received for ${elapsed / 1000}s")
                    listener?.onRtpTimeout()
                    break
                }
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    // ── Playback: jitter buffer → decode → speaker → mic → GSM uplink ──

    /**
     * Ask this recorder to take its audio from the telephony downlink.
     *
     * Logs the available input device types, since whether TYPE_TELEPHONY is
     * offered at all is the thing worth knowing when the agent ends up hearing
     * the room instead of the caller.
     */
    private fun routeCaptureToTelephonyRx(rec: AudioRecord) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val telephony = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
            if (telephony == null) {
                Log.w(TAG, "No TYPE_TELEPHONY input exposed — capture stays on the mic; " +
                    "inputs: " + inputs.joinToString { "${it.type}/${it.productName}" })
                return
            }
            val accepted = rec.setPreferredDevice(telephony)
            Log.i(TAG, "Capture preferred device -> TELEPHONY id=${telephony.id} accepted=$accepted")
        } catch (e: Exception) {
            Log.w(TAG, "routeCaptureToTelephonyRx failed: ${e.message}")
        }
    }

    /**
     * Ask for this track to be routed to the telephony uplink.
     *
     * Logs every output device type it can see, because whether the platform
     * exposes TYPE_TELEPHONY at all is the thing worth knowing when injection
     * does not work.
     */
    private fun routeToTelephonyTx(track: AudioTrack) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val telephony = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
            if (telephony == null) {
                Log.w(TAG, "No TYPE_TELEPHONY output exposed — cannot request the uplink route; " +
                    "outputs: " + outputs.joinToString { "${it.type}/${it.productName}" })
                return
            }
            val accepted = track.setPreferredDevice(telephony)
            Log.i(TAG, "Preferred device -> TELEPHONY id=${telephony.id} accepted=$accepted")
        } catch (e: Exception) {
            Log.w(TAG, "routeToTelephonyTx failed: ${e.message}")
        }
    }

    // ── Per-frame scratch buffers ────────────────────────────────────
    // Fifty frames a second, each of these used to allocate a fresh array:
    // the upsampler's two, the stereo interleave, and the monitor mix.  Over a
    // ten-minute call that is on the order of a hundred thousand short-lived
    // arrays whose only effect is GC pressure — and a GC pause on a 20ms audio
    // loop is an audible glitch.  Each buffer belongs to exactly one thread
    // (upsample to capture; stereo and monitor to playback) and is handed
    // straight to the next call on that same thread, so reuse is safe.
    private var upsampleIn: IntArray = IntArray(0)
    private var upsampleOut: ByteArray = ByteArray(0)
    private var stereoBuf: ByteArray = ByteArray(0)
    private var monitorBuf: ByteArray = ByteArray(0)

    /**
     * Write one playback frame without allowing a wedged Qualcomm incall
     * output to block the RTP thread for seconds.  Blocking AudioTrack.write()
     * was observed to stall for 6-10 seconds when the voice route changed;
     * that left the old incall_music_uplink alive and made the next call
     * intermittently one-way.
     */
    private fun writePlaybackFrame(track: AudioTrack, data: ByteArray) {
        var offset = 0
        val deadline = System.nanoTime() + 30_000_000L
        while (offset < data.size && running.get()) {
            val written = track.write(
                data, offset, data.size - offset, AudioTrack.WRITE_NON_BLOCKING
            )
            if (written > 0) {
                offset += written
                continue
            }
            if (written < 0) {
                Log.w(TAG, "AudioTrack nonblocking write failed: $written")
                return
            }
            if (System.nanoTime() >= deadline) {
                Log.w(TAG, "AudioTrack nonblocking write timed out (${data.size - offset} bytes left)")
                return
            }
            try {
                Thread.sleep(1)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /** One playback frame to wherever this session plays: the AudioTrack, or
     *  the root PCM pipe. */
    private fun writePlaybackOut(track: AudioTrack?, data: ByteArray) {
        val root = pcmPlayback
        if (root != null) writeRootPlaybackFrame(root, data)
        else if (track != null) writePlaybackFrame(track, data)
    }

    /**
     * Write one frame to tinypcm, then wait until the next is due.
     *
     * An AudioTrack paces this loop by blocking when its buffer is full; the
     * pipe does not, and tinypcm on the other end never pushes back — it
     * drops what it has no room for so that it can never stall the DMA.  So
     * the pacing is done here instead, one frame per 20ms of the monotonic
     * clock.  Behind by more than three frames (a stall, a GC), start the
     * schedule afresh rather than racing to catch up.
     */
    private fun writeRootPlaybackFrame(pcm: RootPcm, data: ByteArray) {
        try {
            pcm.output.write(data)
            pcm.output.flush()
        } catch (e: java.io.IOException) {
            if (running.get()) {
                Log.e(TAG, "Root PCM playback ended: ${e.message} (${pcm.recentMessages()})")
                listener?.onRtpStats("Root PCM playback ended: ${pcm.recentMessages()}")
                pcmPlayback = null
            }
            return
        }
        val now = System.nanoTime()
        if (nextPlayNs == 0L || now - nextPlayNs > 60_000_000L) nextPlayNs = now
        nextPlayNs += 20_000_000L
        val waitNs = nextPlayNs - System.nanoTime()
        if (waitNs > 0) Thread.sleep(waitNs / 1_000_000, (waitNs % 1_000_000).toInt())
    }

    /**
     * Duplicate each 16-bit mono sample into both channels.
     *
     * Needed only so the track can match the HAL's stereo-only
     * incall_music_uplink mixPort; the content is still mono.
     */
    private fun monoToStereo(mono: ByteArray): ByteArray {
        if (stereoBuf.size != mono.size * 2) stereoBuf = ByteArray(mono.size * 2)
        val out = stereoBuf
        var i = 0
        var j = 0
        while (i + 1 < mono.size) {
            val lo = mono[i]
            val hi = mono[i + 1]
            out[j] = lo; out[j + 1] = hi
            out[j + 2] = lo; out[j + 3] = hi
            i += 2
            j += 4
        }
        return out
    }

    /**
     * Mark the voice sessions ACTIVE (2) or INACTIVE (1) with the audio HAL.
     * See DeviceProfile.halCallActiveVsids for why this is needed at all.
     */
    private fun setHalCallState(state: Int) {
        if (profile.halCallActiveVsids.isEmpty()) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            for (vsid in profile.halCallActiveVsids) {
                am.setParameters("vsid=$vsid;call_state=$state")
            }
            Log.i(TAG, "HAL call_state=$state for ${profile.halCallActiveVsids}")
        } catch (e: Exception) {
            Log.w(TAG, "setHalCallState($state) failed: ${e.message}")
        }
    }

    /**
     * Set the profile's in-call HAL parameters.  See DeviceProfile.halCallParams
     * for why a HAL without a mixer needs them.
     */
    private fun applyHalCallParams() {
        if (profile.halCallParams.isEmpty()) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            for (p in profile.halCallParams) am.setParameters(p)
            Log.i(TAG, "HAL call params set: ${profile.halCallParams}")
        } catch (e: Exception) {
            Log.w(TAG, "applyHalCallParams failed: ${e.message}")
        }
    }

    /** Undo [applyHalCallParams]; a no-op for profiles that set none. */
    private fun restoreHalCallParams() {
        if (profile.halCallParamsRestore.isEmpty()) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            for (p in profile.halCallParamsRestore) am.setParameters(p)
        } catch (e: Exception) {
            Log.w(TAG, "restoreHalCallParams failed: ${e.message}")
        }
    }

    /**
     * AudioRecord for [source].  Android 8.1 refuses the call sources
     * (VOICE_UPLINK/DOWNLINK/CALL) through the public constructor (8.0 shares
     * the framework code but is untested): the AudioAttributes it builds goes
     * through setCapturePreset(), which only accepts DEFAULT, MIC, CAMCORDER,
     * VOICE_RECOGNITION, VOICE_COMMUNICATION and UNPROCESSED.  VOICE_DOWNLINK
     * is not among them, so the attributes carry "source -1" and the
     * constructor then throws "Invalid audio source -1".  The hidden
     * (attributes, format, size, session) constructor takes the preset as given,
     * so build the attributes by hand and force the source into them.
     * Only reached when the public constructor throws.
     *
     * Both the private field and the hidden constructor are hidden APIs, and
     * from Android 15 the platform blocks them for apps that target API 35 or
     * later.  Nothing in an app can work around that: the platform is the only
     * thing that hands out the VOICE_DOWNLINK source in the first place.  So
     * the failure is reported plainly and the caller moves on to the next
     * capture source, instead of the session dying with a bare
     * NoSuchFieldException.
     */
    @SuppressLint("SoonBlockedPrivateApi")
    private fun newAudioRecord(source: Int, rate: Int, bufSize: Int): AudioRecord {
        // RECORD_AUDIO is a runtime permission.  Without it the constructor
        // throws a SecurityException, and the caller would read that as "this
        // source is not available on this phone" — which is a different
        // problem with a different fix.
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            throw IllegalArgumentException("RECORD_AUDIO is not granted")
        }
        try {
            return AudioRecord(
                source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (e: IllegalArgumentException) {
            Log.i(TAG, "AudioRecord source=$source rejected by the public constructor " +
                "(${e.message}); trying the hidden one")
        }
        try {
            val attrs = AudioAttributes.Builder().build()
            AudioAttributes::class.java.getDeclaredField("mSource").apply {
                isAccessible = true
                setInt(attrs, source)
            }
            val fmt = AudioFormat.Builder()
                .setSampleRate(rate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val ctor = AudioRecord::class.java.getDeclaredConstructor(
                AudioAttributes::class.java, AudioFormat::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            ctor.isAccessible = true
            return ctor.newInstance(attrs, fmt, bufSize, 0)
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord source=$source: the hidden constructor path is not " +
                "available on this platform (${e.javaClass.simpleName}: ${e.message}). " +
                "Android 15 blocks the hidden AudioAttributes.mSource field for apps " +
                "targeting API 35 and later; capturing this source needs the public " +
                "one to accept it.")
            throw IllegalArgumentException("audio source $source is not available on this platform", e)
        }
    }

    /** Mute or unmute the agent towards the caller. */
    fun setAgentMuted(on: Boolean) {
        agentMuted = on
        Log.i(TAG, if (on) "Agent MUTED towards caller" else "Agent unmuted")
        listener?.onRtpStats(if (on) "Agent muted" else "Agent unmuted")
    }

    /**
     * The cellular leg is up.
     *
     * The session was started before that, so everything that depends on the
     * voice path is applied now:
     *
     *  * the capture is opened again — the HAL picks the input usecase when
     *    the record stream is created, and a stream created before the call
     *    was programmed stays bound to a path that delivers silence;
     *  * the injection is written — the HAL rewrites the voice path when it
     *    programs the call, so an injection written before that is gone with
     *    it;
     *  * the handset's loudspeaker is closed and its microphone is taken out
     *    of the call.
     *
     * The announcement has been carried by then, if the downlink could be
     * captured at all; from here on this is an ordinary bridged call.
     */
    fun applyForActiveLeg() {
        if (!earlyCapture) return
        cellularLegUp = true
        Log.i(TAG, "Cellular leg up — the capture is opened again and the injection applied")
        listener?.onRtpStats("Cellular leg up — capture reopened, injection applied")

        // The source is opened again: the one from the early phase may be
        // bound to a path that stays silent.  The capture thread does the
        // work, so the list of retired sources is cleared there and not here.
        reopenCaptureRequested = true

        // The injection: written now, after the HAL programmed the call path.
        val track = audioTrack
        if (track == null) {
            // The session is still starting: the injection is written by the
            // playback loop, once the track is playing.
            injectionPending = true
        } else {
            enableIncallMusic()
            // The playback track was created before the call was up: the
            // telephony uplink device only exists while a call is up, so the
            // track has to be pointed at it again — otherwise the agent's
            // voice would go to the loudspeaker instead of into the call.
            if (digitalBridgeActive || profile.playbackToTelephonyTx) {
                routeToTelephonyTx(track)
            }
        }

        // The room leaves the call, and the loudspeaker is closed.
        applyHandsetMutes(digitalBridgeActive)
        applyHandsetOutputMute(captureRms)
    }

    /** Knee of [softLimit]: about 75% of full scale. */
    private val limitKnee = 24576

    /**
     * Map a boosted sample into 16 bits without a hard edge.  Linear up to
     * [limitKnee]; past it the remainder is compressed with x/(1+x) so the
     * output approaches 32767 but never jumps to it.
     */
    private fun softLimit(sample: Int): Int {
        val a = kotlin.math.abs(sample)
        if (a <= limitKnee) return sample
        val room = 32767 - limitKnee
        val over = (a - limitKnee).toDouble() / room
        val shaped = limitKnee + (room * over / (1.0 + over)).toInt()
        return if (sample < 0) -shaped else shaped
    }

    /** True while the speaker monitor (Snoop) is playing. */
    val isMonitoring: Boolean get() = monitorTrack != null

    /** Turn the speaker monitor on or off mid-call.  Safe to call repeatedly. */
    fun setMonitorEnabled(on: Boolean) {
        if (on) {
            if (monitorTrack != null) return
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    playbackRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            // Deliberately NOT USAGE_MEDIA.  Media maps to
                            // STREAM_MUSIC, and this bridge sets
                            // incall_music_enabled=true precisely so that
                            // STREAM_MUSIC is injected into the modem uplink —
                            // so a monitor track on USAGE_MEDIA would be sent
                            // to the caller instead of the speaker, which is
                            // the opposite of what monitoring is for.
                            //
                            // And not USAGE_ASSISTANCE_SONIFICATION, which it
                            // was: that is STREAM_SYSTEM, and on MIUI a phone in
                            // vibrate or silent mode mutes STREAM_SYSTEM, so the
                            // monitor played into a muted stream — "Monitor ON"
                            // in the log and nothing on the speaker (Poco, MIUI
                            // 14, 2026-10-23).
                            //
                            // Nor USAGE_ALARM, tried next: during a call the
                            // audio policy mutes alarm/sonification streams on
                            // the speaker, so on MIUI the monitor was silent and
                            // only the call's own downlink leaked out — the
                            // caller and no agent.  VOICE_COMMUNICATION is the
                            // stream that plays on the speaker in a call; only
                            // STREAM_MUSIC goes to the uplink.  GsmCallManager
                            // unmutes it while snooping (setMonitoring).
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(playbackRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                // Force it to the loudspeaker; the call itself is elsewhere.
                try {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        ?.let { t.setPreferredDevice(it) }
                } catch (_: Exception) {}
                try {
                    (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                        ?.let { GsmCallManager.setMonitoring(it, true) }
                } catch (e: Exception) {
                    Log.w(TAG, "Monitor audio: ${e.message}")
                }
                t.play()
                monitorTrack = t
                Log.i(TAG, "Monitor ON — both sides on the speaker, mic stays muted")
                listener?.onRtpStats("Monitor ON")
            } catch (e: Exception) {
                Log.w(TAG, "Monitor start failed: ${e.message}")
            }
        } else {
            monitorTrack?.let {
                try { it.stop() } catch (_: Exception) {}
                try { it.release() } catch (_: Exception) {}
            }
            monitorTrack = null
            try {
                (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                    ?.let { GsmCallManager.setMonitoring(it, false) }
            } catch (_: Exception) {}
            Log.i(TAG, "Monitor OFF")
            listener?.onRtpStats("Monitor OFF")
        }
    }

    /**
     * Mix the agent's frame with the most recent caller frame and play the
     * result on the speaker.
     *
     * Weighted 3:1 towards the agent rather than mixed evenly.  The caller
     * arrives from the modem downlink at close to full scale while the agent's
     * side is quieter, so an even mix buries the agent — and the agent is
     * usually the half worth listening to, since the point of monitoring is to
     * hear what it is saying.  The weights still sum to 1, so two loud sides
     * cannot clip against each other.
     */
    private fun feedMonitor(agentPcm: ByteArray) {
        val t = monitorTrack ?: return
        try {
            // Where the call itself plays on the speaker while snooping
            // (GsmCallManager.monitorCarriesCallAudio), the caller is already
            // audible — mixing them in again would play them twice, slightly
            // apart.  Then this track carries the agent alone.
            val caller = if (GsmCallManager.monitorCarriesCallAudio) null else lastCallerFrame
            if (monitorBuf.size != agentPcm.size) monitorBuf = ByteArray(agentPcm.size)
            val out = monitorBuf
            var i = 0
            while (i + 1 < agentPcm.size) {
                val a = ((agentPcm[i + 1].toInt() shl 8) or (agentPcm[i].toInt() and 0xFF)).toShort().toInt()
                val c = if (caller != null && i + 1 < caller.size) {
                    ((caller[i + 1].toInt() shl 8) or (caller[i].toInt() and 0xFF)).toShort().toInt()
                } else 0
                val mixed = if (caller == null) a else (((a * 3) + c) / 4).coerceIn(-32768, 32767)
                out[i] = (mixed and 0xFF).toByte()
                out[i + 1] = ((mixed shr 8) and 0xFF).toByte()
                i += 2
            }
            t.write(out, 0, out.size)
        } catch (e: Exception) {
            Log.w(TAG, "Monitor write failed: ${e.message}")
        }
    }

    private fun playbackLoop() {
        val track = audioTrack
        if (track == null && pcmPlayback == null) return

        if (track != null) {
            // No prefill — the silence-frame loop below feeds the AudioTrack
            // continuously, preventing underruns.  Removing the old 20ms prefill
            // saves that much initial latency.
            track.play()
            Log.i(TAG, "Playback started (rate=$playbackRate usage=$playbackUsageName deepBuffer=true) " +
                "routedTo=${track.routedDevice?.type}")

            // Set incall_music_enabled=true AFTER AudioTrack.play(): the Qualcomm
            // HAL on MSM8930 starts the incall-music usecase only once there is an
            // active STREAM_MUSIC output.
            //
            // Skipped where the profile already set it before the track existed.
            // Toggling it again here reconfigures the voice path and tears down the
            // in-call record session the HAL has just started — measured on SM6150:
            // VOICE_CALL delivered real audio (rawRMS=208) until this second toggle
            // fired 8ms later, after which capture went to zero.
            //
            // Also skipped in the early phase: the HAL rewrites the voice path
            // when it programs the call, so the injection is written when the
            // leg comes up instead.
            if (injectionPending) {
                // The cellular leg came up while this session was still
                // starting: the injection is written now, with the track
                // playing, which is what the HAL needs.
                injectionPending = false
                Log.i(TAG, "Injection written after the leg came up")
                enableIncallMusic()
            } else if (!profile.incallMusicBeforeTrack && !earlyCapture) {
                enableIncallMusic()
            }
            // Set mixer controls via root — also handles Voice Tx Mute=0.
            // No separate ensureVoiceTxOpen() call here: the mixer thread below
            // already issues that command, and waiting for su -c was blocking
            // the playback thread for ~100ms.
            enableIncallMusicViaMixer()
            // The HAL rewrites the voice mutes when it programs the call path,
            // so the handset's microphone is taken out of the call here — after
            // the HAL has settled, not at setup.  Only with the digital
            // bridge: without the injection into the modem's uplink the
            // microphone is the only way the agent reaches the caller.
            //
            // Not in the early phase: the room must not be cut away while the
            // handset is still playing the announcement and the microphone is
            // the only source left that can pick it up.
            if (!earlyCapture) {
                applyHandsetMutes(digitalBridgeActive)
            }
        }

        // Silence frame for when jitter buffer is empty — prevents underruns
        // that cause BUFFER TIMEOUT and AudioTrack disable/restart cycles.
        val silenceFrame = ByteArray(playbackRate * 2 * playbackChannels / 50) // 20ms

        // Track silence→speech transitions for fade-in.
        // Modem DSP AGC/DTX settles during silence; abrupt speech onset
        // gets over-amplified causing distortion at the start of each phrase.
        // A short fade-in smooths the transition.
        var wasPlayingSilence = true

        while (running.get()) {
            try {
                // Drain excess packets to bound latency.  When network
                // jitter causes burst arrivals, the buffer can accumulate.
                // Keep at most 5 (100ms) — enough headroom to absorb
                // jitter without audible gaps.  100ms is still well within
                // the GSM bridge's inherent latency budget.

                // Longer than the 20ms frame period, deliberately: at 18ms a
                // packet that was merely a little late was treated as missing
                // and a silence frame went to the caller instead.
                val encoded = jitterBuffer.poll(JITTER_POLL_MS, TimeUnit.MILLISECONDS)
                if (encoded == null) {
                    // Write silence to keep AudioTrack fed and prevent underruns.
                    writePlaybackOut(track, silenceFrame)
                    underruns++
                    playbackRms = 0
                    currentPlaybackActive = false
                    wasPlayingSilence = true
                    continue
                }

                // Shed one frame if a burst has left the queue deep.  Without
                // this the depth reached after a burst is simply kept, and the
                // caller hears every later word that much later.
                if (jitterBuffer.size > JITTER_HIGH_WATER) {
                    jitterBuffer.poll()
                    trimmedFrames++
                }

                // Decode based on codec.  G.722 outputs 16 kHz natively;
                // PCMA always decodes to 8 kHz (playbackRate==8000).
                val pcm = when (payloadType) {
                    RtpPacket.PT_G722 -> g722Decoder.decodeToBytes(encoded)
                    RtpPacket.PT_PCMA -> {
                        if (playbackRate == 8000) PcmaCodec.decode8k(encoded)
                        else PcmaCodec.decode(encoded)
                    }
                    RtpPacket.PT_PCMU -> {
                        if (playbackRate == 8000) PcmuCodec.decode8k(encoded)
                        else PcmuCodec.decode(encoded)
                    }
                    else -> g722Decoder.decodeToBytes(encoded)
                }

                // Fade-in after silence: prevents modem DSP AGC spike that
                // causes distorted/harsh beginning of each agent phrase.
                // 5ms ramp (40 samples @ 8kHz) is enough to smooth the onset.
                if (wasPlayingSilence) {
                    if (profile.playbackFadeIn) applyFadeIn(pcm)
                    wasPlayingSilence = false
                }

                // Measure RMS BEFORE gain for the echo gate.  The echo gate
                // threshold was calibrated to raw codec output levels.  If
                // playbackGain > 1, measuring after gain would lower the
                // effective threshold, over-suppressing caller speech.
                val rawRms = pcmRms(pcm)
                currentPlaybackActive = rawRms > echoGateThreshold

                // Software gain: boost PCM before writing to AudioTrack.
                // This increases the digital level injected via incall_music
                // without changing STREAM_MUSIC volume (which clips at >14%).
                val gain = playbackGain
                if (gain != 1.0) {
                    for (i in 0 until pcm.size / 2) {
                        val lo = pcm[i * 2].toInt() and 0xFF
                        val hi = pcm[i * 2 + 1].toInt()
                        val sample = ((((hi shl 8) or lo) * gain).toInt())
                        // Soft-limit, not a hard clamp: a boosted peak used to
                        // be cut flat at full scale, heard as crackle on every
                        // loud syllable (playRMS 25198 on a live call, MIUI,
                        // 2026-10-23 — gain x4).  Below the knee nothing
                        // changes; above it the excess is compressed smoothly
                        // towards full scale.
                        val clamped = softLimit(sample)
                        pcm[i * 2] = (clamped and 0xFF).toByte()
                        pcm[i * 2 + 1] = ((clamped shr 8) and 0xFF).toByte()
                    }
                }

                if (agentMuted) {
                    // Zero before both the modem and the monitor, so someone
                    // listening in hears the mute take effect rather than
                    // audio the caller is no longer getting.
                    java.util.Arrays.fill(pcm, 0)
                }
                playbackRms = if (playbackGain > 1) pcmRms(pcm) else rawRms
                feedMonitor(pcm)
                playDump?.let { try { it.write(pcm) } catch (_: Exception) {} }
                val out = if (playbackChannels == 2) monoToStereo(pcm) else pcm
                writePlaybackOut(track, out)
                playbackFrames++
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "Playback error: ${e.message}")
            }
        }
    }

    /**
     * Apply a 5ms linear fade-in to the start of a PCM buffer.
     * Smooths the silence→speech transition that otherwise causes
     * the modem's uplink AGC/DTX to over-amplify the first samples.
     */
    private fun applyFadeIn(pcm: ByteArray, fadeMs: Int = 5) {
        val fadeSamples = playbackRate * fadeMs / 1000
        val totalSamples = pcm.size / 2
        val count = fadeSamples.coerceAtMost(totalSamples)
        for (i in 0 until count) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            val faded = (sample.toLong() * (i + 1) / count).toInt().coerceIn(-32768, 32767)
            pcm[i * 2] = (faded and 0xFF).toByte()
            pcm[i * 2 + 1] = ((faded shr 8) and 0xFF).toByte()
        }
    }

    /**
     * Upsample 8 kHz PCM16 to 16 kHz using 4-tap Catmull-Rom interpolation.
     * Each input sample produces two output samples: the original sample
     * and a midpoint computed as (-s[i-1] + 9*s[i] + 9*s[i+1] - s[i+2]) / 16.
     *
     * This provides ~35 dB spectral image rejection vs ~6 dB for simple
     * linear interpolation, suppressing artifacts in the 4-8 kHz band that
     * G.722's upper sub-band would otherwise encode as spurious content.
     */
    private fun upsample8kTo16k(input: ByteArray): ByteArray {
        val n = input.size / 2
        if (upsampleIn.size != n) {
            upsampleIn = IntArray(n)
            upsampleOut = ByteArray(n * 4) // 2x samples, 2 bytes each
        }
        val output = upsampleOut

        // Read all input samples into an array for random access
        val s = upsampleIn
        for (i in 0 until n) {
            val lo = input[i * 2].toInt() and 0xFF
            val hi = input[i * 2 + 1].toInt()
            s[i] = (hi shl 8) or lo
        }

        for (i in 0 until n) {
            // Even output: original sample (pass-through)
            val even = s[i]

            // Odd output: 4-tap Catmull-Rom midpoint interpolation
            val sm1 = if (i > 0) s[i - 1] else s[0]
            val s0 = s[i]
            val s1 = if (i + 1 < n) s[i + 1] else s[n - 1]
            val s2 = if (i + 2 < n) s[i + 2] else s[n - 1]
            val mid = ((-sm1 + 9 * s0 + 9 * s1 - s2 + 8) shr 4).coerceIn(-32768, 32767)

            output[i * 4] = (even and 0xFF).toByte()
            output[i * 4 + 1] = ((even shr 8) and 0xFF).toByte()
            output[i * 4 + 2] = (mid and 0xFF).toByte()
            output[i * 4 + 3] = ((mid shr 8) and 0xFF).toByte()
        }
        return output
    }

    /** Compute RMS level of PCM16 little-endian audio buffer (0-32767) */
    private fun pcmRms(pcm: ByteArray): Int {
        val sampleCount = pcm.size / 2
        if (sampleCount == 0) return 0
        var sumSquares = 0L
        for (i in 0 until sampleCount) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            sumSquares += sample.toLong() * sample
        }
        return Math.sqrt(sumSquares.toDouble() / sampleCount).toInt()
    }

    /**
     * Set incall_music_enabled=true via AudioManager.
     * Must be called AFTER AudioTrack.play() so the HAL has an active
     * STREAM_MUSIC output to route through the incall-music usecase.
     *
     * Also re-enforces stream volumes here as a secondary safeguard.
     * GsmCallManager sets volumes in configureAudioBridge(), but Android's
     * AudioPolicyManager can reset them when the speaker route change
     * completes.  By the time we get here (after AudioTrack.play()),
     * the route change is long finished, so our volume sticks.
     *
     * On Samsung Exynos (ABOX HAL), the audio_hw_proxy should handle
     * incall_music_enabled internally by routing SIFS→NSRC→voice TX.
     * We also try Samsung-specific parameter names as fallbacks in case
     * the standard parameter is not implemented in this LineageOS build.
     */
    private fun enableIncallMusic() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.let {
                // Force a false→true transition to start the incall-music
                // usecase in the HAL.  configureAudioBridge() no longer
                // primes true (it caused problems: the earpiece→speaker
                // route change would tear down the voice path and lose the
                // incall-music state, then the second true here was a no-op).
                //
                // By this point:
                //  1. AudioTrack.play() has started (active STREAM_MUSIC output)
                //  2. Speaker route is settled (configureAudioBridge ran 1+ sec ago)
                //  3. restoreAudio from previous call set false (clean slate)
                //
                // The explicit false first ensures the HAL processes it as a
                // genuine state transition, even if some stale true leaked.
                // 50ms gap lets the HAL fully tear down before re-creating.
                val param = profile.incallMusicParam
                if (param.isNotEmpty()) {
                    it.setParameters("${param}=false")
                    Thread.sleep(50)
                    it.setParameters("${param}=true")
                }

                // Samsung Exynos: additional HAL params for incall music injection.
                // Only when incallMusicParam is configured (empty = skip to avoid
                // breaking VOICE_CALL capture).
                // Samsung Exynos: additional HAL params for incall music.
                // These are "best effort" — on Exynos 9820, no visible mixer
                // effect is observed, but they may help on other Exynos devices.
                if (profile.name.contains("Exynos") && param.isNotEmpty()) {
                    it.setParameters("g_call_path=on")
                    it.setParameters("abox_incall_music=on")
                    it.setParameters("incall_music=1")
                }

                GsmCallManager.enforceVolumes(it)

                val msg = "incall_music: param=${param.ifEmpty { "NONE" }}, mode=${it.mode}" +
                    if (param.isNotEmpty() && profile.name.contains("Exynos")) " +g_call_path +abox_incall_music +incall_music=1" else ""
                Log.i(TAG, msg)
                listener?.onRtpStats(msg)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set incall_music_enabled: ${e.message}")
            listener?.onRtpStats("incall_music FAILED: ${e.message}")
        }
    }

    /**
     * Fallback: use root (Magisk) to set incall_music mixer controls
     * directly via tinymix.  Device-specific commands from the profile.
     *
     * The bare 'tinymix' in profile commands is resolved to the
     * discovered full path via [DeviceProfile.resolveCmd].
     */
    private fun enableIncallMusicViaMixer() {
        val mixerCmd = profile.mixerIncallMusicCmd
        if (mixerCmd.isEmpty()) {
            Log.i(TAG, "Mixer: no incall_music commands for ${profile.name}")
            return
        }
        val resolvedMixerCmd = DeviceProfile.resolveCmd(mixerCmd)
        if (resolvedMixerCmd.isEmpty()) {
            val msg = "Mixer: tinymix not found — cannot set incall_music mixer"
            Log.e(TAG, msg)
            listener?.onRtpStats(msg)
            return
        }
        Thread({
            try {
                // Just the routing commands.  This used to append a readback of
                // four 'ABOX ...' controls, which exist only on Exynos — on
                // every call, on a Qualcomm device, that was four more root
                // round-trips during audio setup to print "Invalid mixer ctl".
                val output = RootShell.execForOutput(resolvedMixerCmd, timeoutMs = 8000)
                if (output.isNotBlank()) Log.i(TAG, "Mixer incall_music: $output")
            } catch (e: Exception) {
                Log.w(TAG, "Mixer fallback failed: ${e.message}")
                listener?.onRtpStats("Mixer incall_music FAILED: ${e.message}")
            }
        }, "RTP-Mixer").start()
    }

    /**
     * Take the handset's microphone out of the call.
     *
     * The mixer commands come from the profile and are written only while the
     * digital bridge is on: without the injection into the modem's uplink the
     * microphone is the only path the agent's voice has to the caller, and
     * muting it would leave the GSM side silent.
     *
     * The readback is part of the command, so the log shows what the mixer
     * actually holds rather than what was asked for.
     */
    private fun applyHandsetMutes(digital: Boolean) {
        val cmd = profile.mixerHandsetMuteCmd
        if (cmd.isEmpty()) {
            Log.i(TAG, "Handset mutes: none for ${profile.name}")
            return
        }
        if (!digital) {
            Log.i(TAG, "Handset mutes: skipped — the acoustic bridge needs the microphone")
            return
        }
        val resolved = DeviceProfile.resolveCmd(cmd)
        if (resolved.isEmpty()) {
            Log.w(TAG, "Handset mutes: tinymix not found — cannot take the microphone out of the call")
            return
        }
        Thread({
            try {
                val output = RootShell.execForOutput(resolved, timeoutMs = 8000)
                Log.i(TAG, "Handset mutes: $output")
                listener?.onRtpStats("Handset mutes: $output")
            } catch (e: Exception) {
                Log.w(TAG, "Handset mutes failed: ${e.message}")
            }
        }, "RTP-HandsetMute").start()
    }

    /**
     * Re-assert RECORD_AUDIO appops via root.  Android's AppOpsService
     * re-revokes this permission for background apps when the screen turns
     * off, killing VOICE_CALL capture (rawCapRMS drops to ~6).  Called
     * at 3s and then every 5s from timeoutLoop to keep capture alive.
     *
     * CRITICAL: Must use --uid flag to set the UID-level mode.
     * `appops set <pkg>` sets the package mode, but AudioFlinger checks
     * the UID mode (set by PermissionController).  UID mode overrides
     * package mode.  Without --uid, the command "succeeds" (exit=0) but
     * AudioFlinger still denies with "Request denied by app op: 27".
     */
    private fun reAssertAppOps() {
        try {
            val pkg = context.packageName
            val uidProbe = if (Build.VERSION.SDK_INT >= 29) "--uid " else ""
            // Ask before acting.  The sequence below is eight root commands,
            // two of them killing PermissionController, and pm/appops/cmd each
            // fork an app_process; running it unconditionally every 15s cost
            // hundreds of process launches across a single call to re-grant a
            // permission that was already granted.  One `appops get` is cheap,
            // and the expensive path now only runs when something really has
            // revoked it — which is the situation it was written for.
            val probe = RootShell.execForOutput(
                "appops get ${uidProbe}$pkg RECORD_AUDIO 2>&1"
            )
            if (probe.contains("allow", ignoreCase = true)) {
                Log.d(TAG, "appops RECORD_AUDIO still allow — nothing to do")
                return
            }
            Log.w(TAG, "appops RECORD_AUDIO not allowed [$probe] — re-granting")
            val t0 = System.currentTimeMillis()
            // Use execForOutput to capture stderr/stdout from appops commands.
            // Previous approach hid all errors and put killall last (exit=1 always).
            // Now: appops get --uid is the LAST command so exit code is meaningful,
            // and all errors are captured via 2>&1.
            // AUTO_REVOKE_PERMISSIONS_IF_UNUSED: Android 11+ (API 30)
            // appops --uid flag: Android 10+ (API 29)
            val autoRevoke = if (Build.VERSION.SDK_INT >= 30)
                "appops set $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore 2>&1; " else ""
            val uidFlag = if (Build.VERSION.SDK_INT >= 29) "--uid " else ""
            val result = RootShell.execForOutput(
                permissionControllerKill() +
                "pm grant $pkg android.permission.RECORD_AUDIO 2>&1; " +
                autoRevoke +
                "appops set ${uidFlag}$pkg RECORD_AUDIO allow 2>&1; " +
                "appops set $pkg RECORD_AUDIO allow 2>&1; " +
                permissionControllerKill() +
                "appops get ${uidFlag}$pkg RECORD_AUDIO 2>&1"
            )
            val elapsed = System.currentTimeMillis() - t0
            val allowed = result.contains("allow", ignoreCase = true)
            Log.i(TAG, "appops re-assert: [$result] ok=$allowed (${elapsed}ms)")

            if (!allowed) {
                // Fallback: try cmd appops (different IPC path to AppOpsService)
                val fb = RootShell.execForOutput(
                    "cmd appops set ${uidFlag}$pkg RECORD_AUDIO allow 2>&1; " +
                    "cmd appops set $pkg RECORD_AUDIO allow 2>&1; " +
                    "cmd appops get ${uidFlag}$pkg RECORD_AUDIO 2>&1"
                )
                Log.w(TAG, "appops fallback cmd: [$fb]")
            } else {
                Log.d(TAG, "appops RECORD_AUDIO verified: allow")
            }
        } catch (e: Exception) {
            Log.w(TAG, "appops re-assert failed: ${e.message}")
        }
    }

    /**
     * The PermissionController kill, and only when no call is up.
     *
     * Killing it is what makes it re-apply the UID-level appop mode — but it
     * also makes it re-run the permission policy synchronisation, and
     * ActivityManager kills a package whose runtime permission was revoked the
     * moment that synchronisation lands ("Killing com.callagent.gateway:
     * permissions revoked").  Measured 2026-11-19 17:43: the gateway killed
     * itself in the middle of a live call that way, and the call died with
     * it.  The UID mode is set directly by the appops command below, so the
     * kill is not needed to get it right — only to make PermissionController
     * stop overriding it, and that is not worth a dropped call.
     */
    private fun permissionControllerKill(): String =
        if (GsmCallManager.activeCall == null) {
            "killall com.google.android.permissioncontroller 2>/dev/null; " +
            "killall com.android.permissioncontroller 2>/dev/null; "
        } else ""

    /**
     * Apply the profile's late HAL parameters, and keep them applied.
     *
     * These are the parameters that may take a direction of the bridge down
     * with them (the vendor's uplink mute on MediaTek).  The capture level
     * from just before goes into the log, so that a parameter which silenced
     * the caller's voice as well shows up as the drop it is instead of as a
     * mystery.
     *
     * The mute can be switched off and on again while the call is up, by
     * writing `0` or `1` into `/data/local/tmp/gw_ul_mute`.  That is the only
     * honest way to tell "the caller went quiet" from "the mute took the
     * downlink with it" — measured 2026-11-19 18:41 both happened within one
     * call and the log alone could not tell them apart.
     */
    private fun applyLateHalCallParams(capRmsBefore: Int) {
        val params = lateHalParams()
        if (params.isEmpty()) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            for (p in params) {
                val key = p.substringBefore('=')
                val on = p.substringAfter('=')
                val off = if (on == "1") "0" else "1"
                val eff = if (lateHalOverride() == false) "$key=$off" else p
                if (eff == lastLateHalParam) continue
                val r = try { am.setParameters(eff) } catch (e: Exception) {
                    Log.w(TAG, "late HAL param '$eff' failed: ${e.message}")
                    continue
                }
                lastLateHalParam = eff
                Log.i(TAG, "late HAL param '$eff' -> $r " +
                        "(capRMS before=$capRmsBefore, override=${lateHalOverride() ?: "profile"})")
                listener?.onRtpStats("Late HAL param $eff (capRMS before=$capRmsBefore)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyLateHalCallParams failed: ${e.message}")
        }
    }

    /**
     * The late HAL parameters to keep applied.
     *
     * The profile's own list is the default; `/data/local/tmp/gw_hal_param`
     * replaces it with raw `key=value` lines, one per line.  That is what
     * makes the candidates testable on a live call without a rebuild each
     * time — and the candidates are the whole problem, because every mute of
     * the voice path on this chipset takes the modem's downlink out of the
     * agent's capture with it (measured 2026-11-19 19:12 in one call: the
     * vendor's uplink mute took `capRMS` from 13 to 1 and back again when it
     * was switched off).
     */
    private fun lateHalParams(): List<String> {
        val raw = overrideFile("/data/local/tmp/gw_hal_param")
        val lines = raw.lines().map { it.trim() }
            .filter { it.contains('=') && !it.startsWith("#") }
        return if (lines.isEmpty()) profile.halCallParamsLate else lines
    }

    /**
     * The live override for the vendor uplink mute, or null when the profile
     * decides.
     *
     * `/data/local/tmp/gw_ul_mute` holds `0` (the microphone goes back into
     * the call) or `1` (it stays out of it).  It is read through the root
     * shell: the file lives in a directory the app has no access to.
     */
    private fun lateHalOverride(): Boolean? {
        val v = overrideFile("/data/local/tmp/gw_ul_mute")
        return when (v) { "0" -> false; "1" -> true; else -> null }
    }

    /** A file under /data/local/tmp, read through the root shell. */
    private fun overrideFile(path: String): String = try {
        RootShell.execForOutput("cat $path 2>/dev/null", timeoutMs = 3000).trim()
    } catch (e: Exception) { "" }

    /**
     * A mixer command that reroutes the handset's microphone out of the
     * cellular uplink without touching the voice path.
     *
     * Read from `/data/local/tmp/gw_mic_cmd` (a tinymix command), so the
     * candidates can be A/B'd on a live call without a rebuild.  The ones
     * that matter on this chipset are the microphone source muxes
     * (`VOW_UL_SRC_MUX`, `VOW_AMIC0_MUX`, `DMIC*_MUX`): they decide what the
     * *modem* hears, while the modem's downlink to the agent is the other leg
     * of the path and stays where it is.
     */
    private fun applyDiagnosticMicCmd() {
        val cmd = try {
            RootShell.execForOutput("cat /data/local/tmp/gw_mic_cmd 2>/dev/null", timeoutMs = 3000).trim()
        } catch (e: Exception) { "" }
        if (cmd.isEmpty()) return
        val resolved = DeviceProfile.resolveCmd(cmd)
        if (resolved.isEmpty()) {
            Log.w(TAG, "Diagnostic mic command: tinymix not found — '$cmd' not applied")
            return
        }
        Thread({
            try {
                val out = RootShell.execForOutput(resolved, timeoutMs = 8000)
                if (out != lastDiagnosticMicOut) {
                    lastDiagnosticMicOut = out
                    Log.i(TAG, "Diagnostic mic command applied: $cmd -> $out")
                    listener?.onRtpStats("Diagnostic mic command: $cmd -> $out")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Diagnostic mic command failed: ${e.message}")
            }
        }, "RTP-MicCmd").start()
    }

    /**
     * Silence the handset's own loudspeaker at the output device.
     *
     * The caller must not be heard in the room, but every mute of the *voice*
     * path on this chipset takes the modem's downlink out of the agent's
     * capture as well (measured 2026-11-19 17:50: the caller's voice was
     * there right up to the moment the mute was applied and gone afterwards).
     * The output path is the other way round — muting it silences the
     * loudspeaker and leaves the modem's voice path alone.
     *
     * Three things are muted, because the far end reaches the room through
     * two of them (measured 2026-11-27 by diffing the whole mixer, idle
     * against a live call):
     *
     * * `RCV Mux` / `LOL Mux` — the codec's analog output muxes;
     * * `I2S3_CH* HW_SRC_3_OUT_CH*` — the link the HAL opens while the call
     *   is up to carry the modem's voice to the speaker amplifier.  This is
     *   the one that matters: with it open the room hears the caller, the
     *   handset's microphone picks that up again, and the caller hears their
     *   own voice back;
     * * `STF_CH* ADDA_UL_CH*` — the sidetone, the room's own voice played
     *   back locally.
     *
     * The capture level from just before goes into the log, so a mute that
     * did take the capture down with it shows up as the drop it is.
     */
    private fun applyHandsetOutputMute(capRmsBefore: Int, repeat: Boolean = false) {
        Thread({
            writeHandsetOutputMute(capRmsBefore, repeat)
        }, "RTP-OutputMute").start()
    }

    /**
     * The write itself, synchronous.
     *
     * The keeper calls it inline: it must not start a second write while the
     * first one is still inside the root shell, or two shells would be writing
     * the same controls at once and the log would show two different states
     * for one moment.
     */
    private fun writeHandsetOutputMute(capRmsBefore: Int, repeat: Boolean) {
        val cmd = profile.mixerHandsetOutputMuteCmd
        if (cmd.isEmpty()) {
            Log.i(TAG, "Handset output: no output-device mute for ${profile.name}")
            return
        }
        if (!digitalBridgeActive) {
            Log.i(TAG, "Handset output: skipped — the acoustic bridge needs the loudspeaker")
            return
        }
        val resolved = DeviceProfile.resolveCmd(cmd)
        if (resolved.isEmpty()) {
            Log.w(TAG, "Handset output: tinymix not found — cannot silence the loudspeaker")
            return
        }
        try {
            val output = RootShell.execForOutput(resolved, timeoutMs = 8000)
            if (repeat) {
                // The HAL rewrites these controls when it programs the call
                // path and when it switches the output device, so the
                // mute is re-asserted for as long as the call lasts.
                Log.d(TAG, "Handset output still muted: $output")
            } else {
                Log.i(TAG, "Handset output muted: $output (capRMS before=$capRmsBefore)")
                listener?.onRtpStats("Handset output muted (capRMS before=$capRmsBefore): $output")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Handset output mute failed: ${e.message}")
        }
    }

    /**
     * What the output path looks like *now*, without writing to it.
     *
     * The mute command reports the value it has just written, and that is
     * not the value the HAL leaves behind: it re-programs `RCV Mux` and
     * `LOL Mux` every time it sets the call path up (that is what the
     * `turnon` paths in `audio_device.xml` do).  So the mute can be written
     * every few seconds and still be gone half a second later — and the
     * far end is audible in the room in between.
     *
     * Measured 2026-11-24 18:03: the mute was written and re-asserted on
     * every tick of a four-minute call, and the far end was still audible
     * from the handset.  A read-back taken *after* the write is what tells
     * the two cases apart, so the keeper takes one every few passes.
     *
     * Synchronous: the keeper runs it inline, so that the state it checks is
     * the state of the path at that moment.
     */
    private fun readHandsetOutputMute() {
        val cmd = profile.mixerHandsetOutputMuteVerifyCmd
        if (cmd.isEmpty()) return
        val resolved = DeviceProfile.resolveCmd(cmd)
        if (resolved.isEmpty()) return
        try {
            val state = RootShell.execForOutput(resolved, timeoutMs = 8000)
                .lines().filter { it.contains(':') }
                .joinToString(" | ")
            if (state.isEmpty()) return
            val rcvMuted = state.contains("RCV Mux: Open")
            val lolMuted = state.contains("LOL Mux: Open")
            // The far end's voice reaches the room through the I2S link,
            // not through the codec's output muxes — that link has to be
            // open as well, or the caller is heard in the room and the
            // microphone hands it straight back.
            val i2sMuted = state.contains("I2S3_CH1 HW_SRC_3_OUT_CH1: Off")
            val sidetoneMuted = state.contains("STF_CH1 ADDA_UL_CH1: Off")
            if (rcvMuted && lolMuted && i2sMuted && sidetoneMuted) {
                Log.d(TAG, "Handset output path: $state")
            } else {
                Log.w(TAG, "Handset output path OPEN — the HAL undid the mute: $state")
                listener?.onRtpStats("Handset output path was reset by the HAL: $state")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Handset output verification failed: ${e.message}")
        }
    }

    /**
     * Take the handset's microphone out of the agent's capture.
     *
     * The room reaches the agent through the handset's microphone, and the
     * microphone is analogue: it is amplified and converted before it reaches
     * the capture.  The caller's voice, on the other hand, is the modem's
     * downlink and is already digital when it arrives in the CPU.  Cutting the
     * microphone at the analogue stage therefore takes the room out of the
     * capture and leaves the caller's voice in it — which is what every other
     * lever on this chipset could not do: they all cut the *route*, and the
     * route the microphone shares with the downlink is the one the downlink
     * arrives on.
     *
     * Applied late, because the HAL programs the call path itself and puts
     * the microphone back when it does.
     */
    private fun applyHandsetMicCut(capRmsBefore: Int, repeat: Boolean = false) {
        val cmd = profile.mixerHandsetMicCutCmd
        if (cmd.isEmpty()) {
            Log.i(TAG, "Handset microphone: no analogue cut for ${profile.name}")
            return
        }
        if (!digitalBridgeActive) {
            Log.i(TAG, "Handset microphone: skipped — the acoustic bridge needs the microphone")
            return
        }
        val resolved = DeviceProfile.resolveCmd(cmd)
        if (resolved.isEmpty()) {
            Log.w(TAG, "Handset microphone: tinymix not found — cannot take the room out of the capture")
            return
        }
        Thread({
            try {
                val output = RootShell.execForOutput(resolved, timeoutMs = 8000)
                if (repeat) {
                    Log.d(TAG, "Handset microphone still cut: $output")
                    verifyHandsetMicCut()
                } else {
                    Log.i(TAG, "Handset microphone cut: $output (capRMS before=$capRmsBefore)")
                    listener?.onRtpStats("Handset microphone cut (capRMS before=$capRmsBefore): $output")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Handset microphone cut failed: ${e.message}")
            }
        }, "RTP-MicCut").start()
    }

    /**
     * Keep the handset's loudspeaker closed for as long as the call lasts.
     *
     * The HAL owns these controls: it rewrites `RCV Mux`, `LOL Mux` and the
     * I2S link every time it programs the call path and every time it switches
     * the output device.  A mute written once is undone within a second, and
     * until the next write the far end's voice is in the room — measured on
     * 2026-11-24, when the mute was re-asserted on every tick of a
     * four-minute call and the far end was still audible from the handset.
     *
     * The write used to ride on the statistics tick, which runs every fifteen
     * seconds: the room could hear the far end for a quarter of a minute at a
     * time.  This keeper writes every [OutputMuteCadence.INTERVAL_MS] instead,
     * and reads the controls back every [OutputMuteCadence.VERIFY_EVERY]
     * passes so a HAL that undoes the mute still shows up in the log.
     *
     * The microphone cut is not here: the HAL only reconnects the microphone
     * when it programs the call path, and the statistics tick re-asserts that
     * one often enough.
     */
    private fun outputMuteKeeperLoop() {
        var pass = 0
        var reported = ""
        Log.i(TAG, "Handset output keeper: every ${OutputMuteCadence.INTERVAL_MS} ms")
        while (running.get()) {
            try {
                Thread.sleep(OutputMuteCadence.INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
            if (!running.get()) return
            pass++

            // Not before the cellular leg is up: in the early phase the
            // handset is still playing the operator's announcement out loud,
            // and the microphone is the last source that can pick it up.
            if (!cellularLegUp) {
                if (pass == 1) {
                    Log.i(TAG, "Handset output keeper: waiting for the cellular leg")
                    listener?.onRtpStats("Handset output: held open until the cellular leg is up")
                }
                continue
            }

            // Why the mute cannot be written right now — if it cannot be
            // written at all, the reason is said once and the keeper keeps
            // trying: tinymix can appear later (the module copies it into
            // /data/local/tmp on late start), and a keeper that gave up would
            // leave the handset in the conversation for the rest of the call.
            val reason = outputMuteUnavailableReason()
            if (reason != null) {
                if (reason != reported) {
                    reported = reason
                    Log.i(TAG, "Handset output keeper: $reason")
                }
                continue
            }
            reported = ""

            writeHandsetOutputMute(captureRms, repeat = true)
            if (OutputMuteCadence.shouldVerify(pass)) readHandsetOutputMute()
        }
    }

    /**
     * Why the output mute cannot be applied right now, or null when it can.
     *
     * An empty command means the profile was built without an output mute at
     * all — the mixer was not readable when the device was probed — and that
     * does not change while the call lasts.  A missing tinymix can.
     */
    private fun outputMuteUnavailableReason(): String? = when {
        profile.mixerHandsetOutputMuteCmd.isEmpty() ->
            "no output-device mute for ${profile.name} — nothing to keep closed"
        !digitalBridgeActive ->
            "the acoustic bridge needs the loudspeaker"
        DeviceProfile.resolveCmd(profile.mixerHandsetOutputMuteCmd).isEmpty() ->
            "tinymix not found yet"
        else -> null
    }

    /**
     * Whether the handset's microphone is still disconnected, and the cut re-asserted
     * when it is not.
     *
     * The HAL owns this control — it connects the microphone (AIN0) every time
     * it programs the call path, and it may do so again while the call is up.
     * A mute that is written once and then silently undone is worse than no
     * mute at all, because the log says the room is out of the call.
     */
    private fun verifyHandsetMicCut() {
        val cmd = profile.mixerHandsetMicCutVerifyCmd
        val marker = profile.mixerHandsetMicCutOffMarker
        if (cmd.isEmpty() || marker.isEmpty()) return
        val resolved = DeviceProfile.resolveCmd(cmd)
        if (resolved.isEmpty()) return
        Thread({
            try {
                val state = RootShell.execForOutput(resolved, timeoutMs = 8000)
                    .lines().filter { it.contains(':') }
                    .joinToString(" | ")
                if (state.isEmpty()) return@Thread
                if (state.contains(marker)) {
                    Log.d(TAG, "Handset microphone input: $state")
                } else {
                    Log.w(TAG, "Handset microphone back in the capture — the HAL undid the cut: $state")
                    listener?.onRtpStats("Handset microphone back in the capture: $state")
                    val write = DeviceProfile.resolveCmd(profile.mixerHandsetMicCutCmd)
                    if (write.isNotEmpty()) {
                        val out = RootShell.execForOutput(write, timeoutMs = 8000)
                        Log.w(TAG, "Handset microphone cut re-asserted: $out")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Handset microphone verification failed: ${e.message}")
            }
        }, "RTP-MicCutVerify").start()
    }

    /**
     * Re-toggle incall_music_enabled false→true as a safety net.
     * Called ~3s after RTP start when the speaker route change is
     * guaranteed to be complete.  Handles edge cases where the initial
     * toggle in enableIncallMusic() fired before the route settled.
     */
    private fun reToggleIncallMusic() {
        val param = profile.incallMusicParam
        if (param.isEmpty()) return
        if (profile.incallMusicBeforeTrack) {
            // The toggle is what kills in-call capture on this HAL; the
            // pre-track set has already done the job.
            return
        }
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.let {
                it.setParameters("${param}=false")
                Thread.sleep(50)
                it.setParameters("${param}=true")
                GsmCallManager.enforceVolumes(it)
                Log.i(TAG, "${param} re-toggled (3s safety), mode=${it.mode}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "${param} re-toggle failed: ${e.message}")
        }
    }

    /**
     * Log diagnostic info about capture capability:
     * Phase 1: NSRC routing + bridge state
     * Phase 2: mixer_paths.xml incall_music voice path definitions
     * Phase 3: /proc/asound capture/playback PCM status
     * Phase 6: Delayed NSRC re-check (t+5s)
     * Phase 7: ALSA capture PCM probe (tinycap, if available)
     */
    /**
     * Whether to run the full capture diagnostic sweep at call setup.
     *
     * That sweep greps every mixer_paths XML, walks all of /proc/asound,
     * sleeps five seconds and re-checks, then tinycaps every running PCM and
     * pipes each through od|grep — a 30s root budget, on every single call.
     * It is what established the routing on this SoC and on the Exynos that
     * turned out to have no digital path at all; with that settled it is pure
     * cost on the call path.  Left switchable for the next unknown device:
     *
     *   adb shell su -c "am broadcast ..."  — or simply set the pref:
     *   getSharedPreferences("gateway", 0).edit().putBoolean("audio_diag", true)
     */
    private fun audioDiagnosticsEnabled(): Boolean = try {
        context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
            .getBoolean("audio_diag", false)
    } catch (_: Exception) { false }

    private fun logCaptureDiagnostics(record: AudioRecord) {
        try {
            // Check CAPTURE_AUDIO_OUTPUT (system permission, not runtime)
            val hasCaptureOutput = context.checkCallingOrSelfPermission(
                "android.permission.CAPTURE_AUDIO_OUTPUT"
            ) == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "Diag: CAPTURE_AUDIO_OUTPUT=$hasCaptureOutput " +
                "session=${record.audioSessionId} state=${record.state} " +
                "recState=${record.recordingState} source=$audioSourceName")
            listener?.onRtpStats("Diag: CAPTURE_AUDIO_OUTPUT=$hasCaptureOutput")

            // Comprehensive ABOX routing dump via root
            val tinymix = DeviceProfile.tinymixBin
            if (tinymix.isNotEmpty()) {
                Thread({
                    try {
                        // Phase 1: NSRC routing + bridge state
                        val routingCmd = buildString {
                            append("echo '=== NSRC routing ==='; ")
                            for (i in 0..2) {
                                append("echo -n 'NSRC${i}='; $tinymix 'ABOX NSRC${i}' 2>/dev/null || echo 'N/A'; ")
                                append("echo -n 'NSRC${i}_Bridge='; $tinymix 'ABOX NSRC${i} Bridge' 2>/dev/null || echo 'N/A'; ")
                            }
                            append("echo -n 'SoundType='; $tinymix 'ABOX Sound Type' 2>/dev/null || echo 'N/A'")
                        }
                        val routing = RootShell.execForOutput(routingCmd, timeoutMs = 8000)
                        for (line in routing.lines().filter { it.isNotBlank() }) {
                            Log.i(TAG, "Diag: $line")
                        }

                        // Phase 2: mixer_paths.xml — voice call & incall_music paths
                        val mixerPathsCmd = buildString {
                            append("echo '=== mixer_paths voice/incall ==='; ")
                            // Search for incall_music path definitions
                            append("for f in /vendor/etc/mixer_paths*.xml /vendor/etc/audio/mixer_paths*.xml; do ")
                            append("  if [ -f \"\$f\" ]; then ")
                            append("    echo \"--- \$f ---\"; ")
                            // Grep for incall_music path (if it exists, shows what controls to set)
                            append("    grep -B2 -A15 'incall.music\\|incallmusic' \"\$f\" 2>/dev/null | head -40; ")
                            // Also show voice_call path for comparison
                            append("    echo '--- voice_call path ---'; ")
                            append("    grep -B1 -A10 'name=\"voice-call\"\\|name=\"voice_call\"' \"\$f\" 2>/dev/null | head -30; ")
                            // Search for any path that mentions UAIF (modem TX)
                            append("    echo '--- UAIF paths ---'; ")
                            append("    grep -i 'uaif' \"\$f\" 2>/dev/null | head -20; ")
                            append("  fi; done")
                        }
                        val mixerPaths = RootShell.execForOutput(mixerPathsCmd, timeoutMs = 5000)
                        for (line in mixerPaths.lines().filter { it.isNotBlank() }) {
                            Log.i(TAG, "Diag: $line")
                        }

                        // Phase 3: Active capture/playback PCMs + hw_params (all cards)
                        val pcmCmd = buildString {
                            append("echo '=== capture PCMs (all cards) ==='; ")
                            append("for f in /proc/asound/card*/pcm*c/sub0/status; do ")
                            append("s=\$(head -1 \$f 2>/dev/null); d=\${f%/status}; ")
                            append("card=\${f#/proc/asound/}; card=\${card%%/*}; ")
                            append("pcm=\${d##*/}; ")
                            append("echo \"\$card/\$pcm: \$s\"; ")
                            append("if echo \"\$s\" | grep -q RUNNING; then ")
                            append("echo \"  hw: \$(cat \${d}/hw_params 2>/dev/null | head -5 | tr '\\n' ' ')\"; ")
                            append("name=\$(cat \${d%/sub0}/info 2>/dev/null | grep '^name:' | head -1); ")
                            append("[ -n \"\$name\" ] && echo \"  \$name\"; ")
                            append("fi; done; ")
                            // Also dump playback PCMs (all cards)
                            append("echo '=== playback PCMs (all cards) ==='; ")
                            append("for f in /proc/asound/card*/pcm*p/sub0/status; do ")
                            append("s=\$(head -1 \$f 2>/dev/null); d=\${f%/status}; ")
                            append("card=\${f#/proc/asound/}; card=\${card%%/*}; ")
                            append("pcm=\${d##*/}; ")
                            append("if echo \"\$s\" | grep -q RUNNING; then ")
                            append("echo \"\$card/\$pcm: \$s  hw: \$(cat \${d}/hw_params 2>/dev/null | head -3 | tr '\\n' ' ')\"; ")
                            append("fi; done")
                        }
                        val pcmResult = RootShell.execForOutput(pcmCmd, timeoutMs = 3000)
                        for (line in pcmResult.lines().filter { it.isNotBlank() }) {
                            Log.i(TAG, "Diag: $line")
                        }

                        // Phase 4-5 removed in v2.8.43 — routing map fully
                        // established (Madera codec, SLIMTX, HPOUT, DSP, ASRC).

                        // Phase 6: Delayed re-check (5s) — see if routing changes
                        // after enableIncallMusic/enableIncallMusicViaMixer run.
                        Thread.sleep(5000)
                        if (running.get()) {
                            val recheck = buildString {
                                append("echo '=== NSRC re-check (t+5s) ==='; ")
                                for (i in 0..2) {
                                    append("echo -n 'NSRC${i}='; $tinymix 'ABOX NSRC${i}' 2>/dev/null || echo 'N/A'; ")
                                    append("echo -n 'NSRC${i}_Bridge='; $tinymix 'ABOX NSRC${i} Bridge' 2>/dev/null || echo 'N/A'; ")
                                }
                                append("echo -n 'SoundType='; $tinymix 'ABOX Sound Type' 2>/dev/null || echo 'N/A'")
                            }
                            val recheckResult = RootShell.execForOutput(recheck, timeoutMs = 8000)
                            for (line in recheckResult.lines().filter { it.isNotBlank() }) {
                                Log.i(TAG, "Diag: $line")
                            }
                        }

                        // Phase 7: Probe ALSA capture PCMs for non-zero audio data.
                        // All AudioRecord sources return silence (rawCapRMS=0) on this
                        // device.  This probe reads raw bytes from each RUNNING capture
                        // PCM across ALL cards to find which device has modem downlink
                        // audio.  Card 1 (aboxvdma) may carry modem voice data.
                        if (running.get()) {
                            val probeCmd = buildString {
                                append("echo '=== ALSA capture PCM probe (all cards) ==='; ")
                                append("if [ ! -x ${RootPcmCapture.TINYCAP} ]; then ")
                                append("  echo 'tinycap not found'; ")
                                append("else ")
                                // Iterate all capture PCMs across all cards
                                append("for d in /proc/asound/card*/pcm*c; do ")
                                append("  [ -d \"\$d/sub0\" ] || continue; ")
                                append("  s=\$(head -1 \$d/sub0/status 2>/dev/null); ")
                                append("  card=\${d#/proc/asound/}; card=\${card%%/*}; ")
                                append("  cardnum=\${card#card}; ")
                                append("  pcm=\${d##*/}; devnum=\${pcm#pcm}; devnum=\${devnum%c}; ")
                                append("  name=\$(cat \$d/info 2>/dev/null | grep '^name:' | head -1 | cut -d: -f2-); ")
                                append("  echo \"\$card/\$pcm:\$name status=\$s\"; ")
                                append("  if echo \"\$s\" | grep -q RUNNING; then ")
                                // Parse actual hw_params for this PCM
                                append("    ch=\$(cat \$d/sub0/hw_params 2>/dev/null | grep '^channels:' | awk '{print \$2}'); ")
                                append("    rate=\$(cat \$d/sub0/hw_params 2>/dev/null | grep '^rate:' | awk '{print \$2}'); ")
                                append("    fmt=\$(cat \$d/sub0/hw_params 2>/dev/null | grep '^format:' | awk '{print \$2}'); ")
                                append("    bits=16; echo \"\$fmt\" | grep -q S32 && bits=32; ")
                                append("    echo \"  hw: fmt=\$fmt ch=\$ch rate=\$rate bits=\$bits\"; ")
                                // Capture 1 second of raw audio with actual params
                                append("    timeout 2 ${RootPcmCapture.TINYCAP} /data/local/tmp/probe_\${cardnum}_\${devnum}.raw ")
                                append("-D \$cardnum -d \$devnum -c \${ch:-2} -r \${rate:-48000} -b \${bits} -p 480 -n 4 2>&1 | head -2; ")
                                append("    f=/data/local/tmp/probe_\${cardnum}_\${devnum}.raw; ")
                                append("    if [ -f \"\$f\" ] && [ -s \"\$f\" ]; then ")
                                append("      sz=\$(wc -c < \"\$f\"); ")
                                append("      nz=\$(od -An -tx1 \"\$f\" | tr ' ' '\\n' | grep -cv '^00\$\\|^\$'); ")
                                append("      echo \"  probe: \${sz}B, \${nz} non-zero bytes\"; ")
                                append("      rm -f \"\$f\"; ")
                                append("    else ")
                                append("      echo '  probe: no data captured'; ")
                                append("    fi; ")
                                append("  fi; ")
                                append("done; ")
                                append("fi")
                            }
                            val probeResult = RootShell.execForOutput(probeCmd, timeoutMs = 30000)
                            for (line in probeResult.lines().filter { it.isNotBlank() }) {
                                Log.i(TAG, "Diag: $line")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Diag routing check failed: ${e.message}")
                    }
                }, "CaptureDiag").start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Capture diagnostics failed: ${e.message}")
        }
    }

    /**
     * Read CPU/memory/thread stats for diagnostics.
     * - System load from /proc/loadavg (1/5/15 min averages; >2.0 = overloaded on dual-core)
     * - Process CPU% from /proc/self/stat utime+stime delta over sample interval
     * - JVM heap usage and thread count
     */
    private fun getCpuStats(): String {
        return try {
            val sb = StringBuilder()

            // /proc/loadavg is blocked by SELinux (proc_loadavg) on priv_app.
            // Process CPU% — read utime(14) + stime(15) from /proc/self/stat
            // These are in clock ticks (typically 100 Hz on ARM).
            try {
                val statFields = java.io.File("/proc/self/stat").readText().trim()
                    .substringAfter(") ")  // skip past "(comm) "
                    .split(" ")
                // Fields after ") ": index 0=state, ..., 11=utime, 12=stime
                val utime = statFields.getOrNull(11)?.toLongOrNull() ?: 0L
                val stime = statFields.getOrNull(12)?.toLongOrNull() ?: 0L
                val totalTicks = utime + stime
                val nowMs = System.currentTimeMillis()

                if (lastCpuSampleMs > 0) {
                    val tickDelta = totalTicks - lastCpuTicks
                    val msDelta = nowMs - lastCpuSampleMs
                    if (msDelta > 0) {
                        // Convert ticks to ms (100 ticks/sec = 10ms/tick)
                        val cpuMs = tickDelta * 10
                        val cpuPct = (cpuMs * 100) / msDelta
                        sb.append(" cpu=${cpuPct}%")
                    }
                }
                lastCpuTicks = totalTicks
                lastCpuSampleMs = nowMs
            } catch (_: Exception) {}

            // Thread count and JVM heap
            val rt = Runtime.getRuntime()
            val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val maxMb = rt.maxMemory() / (1024 * 1024)
            val threads = Thread.activeCount()
            sb.append(" mem=${usedMb}/${maxMb}M thr=$threads")

            sb.toString()
        } catch (_: Exception) { "" }
    }

    companion object {
        private const val TAG = "RtpSession"

        /** Per-thread wait in [stop] before the audio devices are released
         *  anyway.  A capture read is one 20ms frame and the playback poll is
         *  18ms, so a healthy loop is gone well inside this. */
        private const val JOIN_TIMEOUT_MS = 400L
        /** Installed by the Magisk module, next to tinymix. */
        private const val TINYPCM = "/data/local/tmp/tinypcm"
        /** One raw PCM path per device: a session being replaced must finish
         *  its teardown before the next one routes. */
        private val ROOT_PCM_LOCK = Any()

        /** Launches of tinycap per call before the AudioRecord sources get
         *  a turn — see [captureInitAndLoop]. */
        private const val ROOT_CAPTURE_ATTEMPTS = 3

        /** Jitter buffer ceiling, in 20ms frames (240ms). */
        private const val JITTER_CAPACITY = 12
        /** Depth above which one frame per cycle is shed (140ms). */
        private const val JITTER_HIGH_WATER = 7
        /** How long playback waits for a late frame before writing silence. */
        private const val JITTER_POLL_MS = 30L
    }
}

/**
 * G.711 A-law (PCMA) codec — fallback if G.722 is not negotiated.
 * Narrowband (8 kHz), adequate for basic voice.
 */
object PcmaCodec {
    private val ALAW_ENCODE_TABLE = intArrayOf(
        1, 1, 2, 2, 3, 3, 3, 3,
        4, 4, 4, 4, 4, 4, 4, 4,
        5, 5, 5, 5, 5, 5, 5, 5,
        5, 5, 5, 5, 5, 5, 5, 5,
        6, 6, 6, 6, 6, 6, 6, 6,
        6, 6, 6, 6, 6, 6, 6, 6,
        6, 6, 6, 6, 6, 6, 6, 6,
        6, 6, 6, 6, 6, 6, 6, 6,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7
    )

    /**
     * G.711 A-law, as in the ITU reference encoder.
     *
     * Below 256 the segment is always 0 and the mantissa is the sample's
     * bits 4..7.  The segment table used to be applied there too, so every
     * quiet sample (16..255, which is every zero crossing of speech) was
     * coded as a loud one: 150 came out as 2240.  GSM-to-SIP audio sounded
     * like a radio at the edge of range while the other direction, which
     * only decodes, was clean.  Found and fixed by Evgenii Shapovalov.
     */
    fun encodeSample(pcm: Short): Byte {
        var linear = pcm.toInt()
        val mask: Int
        if (linear >= 0) {
            mask = 0xD5
        } else {
            mask = 0x55
            linear = -linear - 1
        }
        val seg = if (linear < 256) 0 else ALAW_ENCODE_TABLE[(linear shr 8) and 0x7F]
        val mantissa = if (seg == 0) (linear shr 4) and 0x0F else (linear shr (seg + 3)) and 0x0F
        return (((seg shl 4) or mantissa) xor mask).toByte()
    }

    fun decodeSample(alaw: Byte): Short {
        var value = (alaw.toInt() and 0xFF) xor 0xD5
        val sign = value and 0x80
        val exponent = (value shr 4) and 7
        var mantissa = value and 0x0F

        mantissa = (mantissa shl 4) + 8
        if (exponent > 0) mantissa = (mantissa + 256) shl (exponent - 1)

        return if (sign != 0) (-mantissa).toShort() else mantissa.toShort()
    }

    /** Encode 8kHz PCM16 to A-law directly (no sample rate conversion). */
    fun encode8k(pcm: ByteArray): ByteArray {
        val sampleCount = pcm.size / 2
        val out = ByteArray(sampleCount)
        for (i in 0 until sampleCount) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort()
            out[i] = encodeSample(sample)
        }
        return out
    }

    /** Encode 16kHz PCM16 to A-law with 2:1 averaging downsample.
     *  Averages each pair of adjacent samples before encoding, acting as
     *  a simple low-pass filter that prevents aliasing artifacts from
     *  high frequencies folding back into the 4kHz output band. */
    fun encode(pcm: ByteArray): ByteArray {
        val sampleCount = pcm.size / 2
        val outSize = sampleCount / 2
        val out = ByteArray(outSize)
        var outIdx = 0
        for (i in 0 until sampleCount step 2) {
            val lo0 = pcm[i * 2].toInt() and 0xFF
            val hi0 = pcm[i * 2 + 1].toInt()
            val s0 = (hi0 shl 8) or lo0
            val s1 = if (i + 1 < sampleCount) {
                val lo1 = pcm[(i + 1) * 2].toInt() and 0xFF
                val hi1 = pcm[(i + 1) * 2 + 1].toInt()
                (hi1 shl 8) or lo1
            } else s0
            val avg = ((s0 + s1) / 2).toShort()
            out[outIdx++] = encodeSample(avg)
            if (outIdx >= outSize) break
        }
        return out
    }

    /** Decode A-law to 8kHz PCM16 directly (no sample rate conversion). */
    fun decode8k(alaw: ByteArray): ByteArray {
        val out = ByteArray(alaw.size * 2)
        var outIdx = 0
        for (byte in alaw) {
            val sample = decodeSample(byte)
            out[outIdx++] = (sample.toInt() and 0xFF).toByte()
            out[outIdx++] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Decode A-law to 16kHz PCM16 with 1:2 linear-interpolation upsampling.
     *  Each input sample produces two output samples: the original and a
     *  midpoint interpolated toward the next sample.  Eliminates the
     *  aliasing/zipper noise from the previous zero-order hold approach. */
    fun decode(alaw: ByteArray): ByteArray {
        val out = ByteArray(alaw.size * 4)
        var outIdx = 0
        for (i in alaw.indices) {
            val s0 = decodeSample(alaw[i]).toInt()
            val s1 = if (i + 1 < alaw.size) decodeSample(alaw[i + 1]).toInt() else s0
            val mid = (s0 + s1) / 2
            out[outIdx++] = (s0 and 0xFF).toByte()
            out[outIdx++] = ((s0 shr 8) and 0xFF).toByte()
            out[outIdx++] = (mid and 0xFF).toByte()
            out[outIdx++] = ((mid shr 8) and 0xFF).toByte()
        }
        return out
    }
}
