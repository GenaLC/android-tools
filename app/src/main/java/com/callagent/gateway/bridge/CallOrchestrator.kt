package com.callagent.gateway.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.Manifest
import android.os.Build
import android.telecom.Call
import android.telecom.DisconnectCause
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.callagent.gateway.RootShell
import com.callagent.gateway.gsm.GsmCallManager
import com.callagent.gateway.rtp.RtpPacket
import com.callagent.gateway.rtp.SrtpContext
import com.callagent.gateway.rtp.RtpSession
import com.callagent.gateway.rtp.DtmfDetector
import com.callagent.gateway.sip.SipCall
import com.callagent.gateway.sip.SipClient
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Orchestrates the bidirectional GSM ↔ SIP bridge.
 *
 * Two call flows:
 *
 * INBOUND (someone calls the Israeli SIM):
 *   1. GSM rings → keep ringing, place SIP call to Asterisk
 *   2. Asterisk/agent answers SIP → answer GSM call
 *   3. GSM goes active → RTP starts immediately
 *   4. Audio flows: GSM speaker/mic ↔ RTP/SIP (shared hardware)
 *   5. Either side hangs up → terminate both
 *
 *   Caller hears normal ringing until the agent is ready, then
 *   picks up and hears the agent immediately — no dead air.
 *
 * OUTBOUND (Asterisk wants to call an Israeli number):
 *   1. SIP INVITE arrives with X-GSM-Forward header
 *   2. Dial GSM call to the destination
 *   3. GSM answers → SIP 200 OK
 *   4. Audio flows: SIP RTP ↔ GSM speaker/mic (shared hardware)
 *   5. Either side hangs up → terminate both
 */
class CallOrchestrator(
    private val context: Context,
    private val sipClient: SipClient
) : SipClient.Listener, GsmCallManager.Listener, SipCall.Listener {

    private var activeRtpSession: RtpSession? = null
    private var activeSipCall: SipCall? = null
    private var activeGsmCall: Call? = null
    /** False while the previous RTP/HAL session is still being released. */
    @Volatile private var audioReady = true

    private data class PendingIncoming(val call: Call, val number: String)
    @Volatile private var pendingIncoming: PendingIncoming? = null

    @Volatile private var diallerInitiated = false
    @Volatile private var lastStateChangeTime = 0L

    // Pending RTP info: saved when SIP answers before GSM is picked up.
    // onGsmCallActive reads these to start RTP immediately after GSM pickup.
    private var pendingRtpAddr: String? = null
    private var pendingRtpPort: Int = 0
    private var pendingPayloadType: Int = 0
    private var pendingLocalRtpPort: Int = 0

    // SIP call retry: if SIP fails while GSM is ringing, retry before giving up.
    // Transient network issues or socket races can kill the first attempt.
    private var sipCallRetries = 0
    private val MAX_SIP_RETRIES = 2

    /** True once we have offered early media (183 with SDP) to the server. */
    @Volatile private var earlyMediaSent = false

    /**
     * True when the cellular leg was answered by us rather than by the far
     * end.
     *
     * It matters to the RTP session: such a leg opens the downlink before
     * the network has anything to say on it, so the first seconds of the
     * capture are legitimately silent and must not be taken for a dead
     * source.
     */
    @Volatile private var gsmAutoAnswered = false

    // ── Early media and auto-answer ─────────────────────

    private fun prefBool(key: String, default: Boolean): Boolean =
        context.getSharedPreferences("gateway", Context.MODE_PRIVATE).getBoolean(key, default)

    private fun prefInt(key: String, default: Int): Int =
        context.getSharedPreferences("gateway", Context.MODE_PRIVATE).getInt(key, default)

    /**
     * Answer the cellular leg we place ourselves, before the far end does.
     *
     * The cellular network says things of its own before anybody picks up:
     * "this number is not available", a billing prompt, a queue announcement.
     * That sound exists only on the cellular downlink, and the downlink is
     * only open once the leg is answered — so without an early answer it is
     * never heard at all.  Answering the leg ourselves is what puts the
     * announcement on the downlink where the RTP session can pick it up.
     */
    private fun autoAnswerOutboundEnabled(): Boolean = prefBool("auto_answer_out", true)

    /**
     * Answer an incoming cellular call before the agent does.
     *
     * Off by default: the caller would hear silence instead of a ringback
     * while the agent's phone is still ringing, which reads to the caller as
     * an answered call with nobody there.
     */
    private fun autoAnswerInboundEnabled(): Boolean = prefBool("auto_answer_in", false)

    /** How long to wait before the early answer. */
    private fun autoAnswerDelayMs(): Int = AutoAnswerPolicy.delayMs(
        prefInt("auto_answer_delay_ms", AutoAnswerPolicy.DEFAULT_DELAY_MS)
    )

    /** Send (and accept) early media: a 183 carrying our SDP. */
    private fun earlyMediaEnabled(): Boolean = prefBool("early_media", true)

    /** One thread for every deferred bridge action, instead of a fresh Thread
     *  per dial, per INVITE and per retry. */
    private val timers: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "bridge-timers").apply { isDaemon = true }
        }

    /**
     * Telecom normally delivers a state callback when the remote GSM party
     * hangs up.  On some Android/RadioInterface combinations it updates the
     * Call object first and delivers onCallRemoved later (or skips the state
     * callback altogether).  Polling the tracked Call is a cheap safety net
     * which prevents the SIP leg from being left up after the cellular leg is
     * gone.
     */
    @Volatile private var gsmStateWatchdog: ScheduledFuture<*>? = null

    /**
     * The PermissionController kill, and only when no call is up.
     *
     * Killing it makes it re-apply the UID-level appop mode, but it also
     * makes it re-run the permission policy synchronisation — and
     * ActivityManager kills a package whose runtime permission was revoked
     * the moment that synchronisation lands.  Measured 2026-11-19 17:43:
     * the gateway killed itself that way, in the middle of a live call, and
     * the call died with it.
     */
    private fun permissionControllerKill(): String =
        if (GsmCallManager.activeCall == null) {
            "killall com.google.android.permissioncontroller 2>/dev/null; " +
            "killall com.android.permissioncontroller 2>/dev/null; "
        } else ""

    private fun ensureGsmStateWatchdog() {
        if (gsmStateWatchdog?.isCancelled == false) return
        val gen = generation
        gsmStateWatchdog = timers.scheduleAtFixedRate(watchdog@{
            if (generation != gen || bridgeState == BridgeState.IDLE ||
                bridgeState == BridgeState.TEARING_DOWN) {
                gsmStateWatchdog?.cancel(false)
                gsmStateWatchdog = null
                return@watchdog
            }

            val call = activeGsmCall ?: return@watchdog
            val state = try {
                call.state
            } catch (e: Exception) {
                Log.w(TAG, "Unable to read GSM call state: ${e.message}")
                return@watchdog
            }
            if (state == Call.STATE_DISCONNECTING || state == Call.STATE_DISCONNECTED) {
                Log.i(TAG, "GSM watchdog detected call end (state=$state)")
                tearDown("GSM call disconnected")
            }
        }, 250, 250, TimeUnit.MILLISECONDS)
    }

    /** Bumped every time the bridge returns to IDLE.
     *
     *  Timers used to check nothing but [bridgeState], which is shared by every
     *  call there has ever been.  A dial that failed at t+5s left its 45s
     *  timeout running; a different call answered at t+20s was still setting up
     *  when that timer fired, saw a non-IDLE state and tore the new call down
     *  as "GSM dial timeout".  Each timer now captures the generation it was
     *  scheduled in and does nothing if the bridge has moved on since. */
    @Volatile private var generation = 0L

    /** Run [action] after [delayMs], unless the bridge has moved on. */
    private fun schedule(delayMs: Long, action: () -> Unit) {
        val gen = generation
        timers.schedule({
            if (generation != gen) return@schedule
            try { action() } catch (e: Exception) {
                Log.w(TAG, "Timer action failed: ${e.message}")
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Keep a cellular caller ringing while the previous Qualcomm audio output
     * finishes shutting down. Starting a new incall_music track before the
     * old one reaches standby caused intermittent SIP->GSM one-way calls on
     * Pixel 4a.
     */
    private fun waitForAudioReady(
        call: Call,
        number: String,
        waitingSince: Long = System.currentTimeMillis()
    ) {
        val pending = pendingIncoming
        if (pending?.call !== call || activeGsmCall !== call ||
            bridgeState != BridgeState.GSM_RINGING) {
            return
        }

        val state = try { call.state } catch (e: Exception) {
            Log.w(TAG, "Unable to read waiting GSM call state: ${e.message}")
            return
        }
        if (state == Call.STATE_DISCONNECTING || state == Call.STATE_DISCONNECTED) {
            pendingIncoming = null
            tearDown("Waiting GSM call disconnected")
            return
        }

        if (audioReady) {
            pendingIncoming = null
            Log.i(TAG, "Audio HAL ready — starting SIP flow for waiting GSM call from $number")
            Thread({
            // Anything thrown here used to kill the whole process, taking the
            // registration down with the one call (S4 Mini, 2026-10-23: a
            // RejectedExecutionException from a SIP client whose executor
            // had been shut down).  Fail the call, keep the gateway.
            try {
                handleInboundFlow(call)
            } catch (t: Throwable) {
                Log.e(TAG, "Inbound flow failed: $t", t)
                tearDown("inbound flow failed: ${t.javaClass.simpleName}")
            }
        }, "SIP-OutCall").start()
            return
        }

        val waitedMs = System.currentTimeMillis() - waitingSince
        if (waitedMs >= AUDIO_READY_WAIT_TIMEOUT_MS) {
            pendingIncoming = null
            Log.w(TAG, "Audio HAL did not become ready after ${waitedMs}ms — rejecting waiting GSM call")
            tearDown("Audio HAL not ready")
            return
        }

        schedule(AUDIO_READY_POLL_MS) {
            waitForAudioReady(call, number, waitingSince)
        }
    }

    /**
     * Probe the actual AudioFlinger state instead of blindly sleeping for the
     * whole Pixel guard window.  The Qualcomm output is reusable when its
     * TELEPHONY_TX thread is in standby and no longer blocked in a write.
     * If the dump is unavailable (for example, root is temporarily denied),
     * keep waiting and use the configured maximum as the safe fallback.
     */
    private fun waitForAudioHalDrain(
        startedAt: Long = System.currentTimeMillis()
    ) {
        if (activeRtpSession != null) return

        val dump = try {
            RootShell.execForOutput(
                "dumpsys media.audio_flinger 2>/dev/null",
                timeoutMs = AUDIO_HAL_PROBE_TIMEOUT_MS
            )
        } catch (e: Exception) {
            Log.w(TAG, "Audio HAL readiness probe failed: ${e.message}")
            ""
        }

        val ready = if (dump.isNotBlank()) {
            val blocks = dump.split(Regex("(?=\\n\\s*Output devices:)"))
                .filter { it.contains("AUDIO_DEVICE_OUT_TELEPHONY_TX") }
            // No TELEPHONY_TX output means the old output is already gone.
            // If one exists, require it to be fully standby and not blocked.
            blocks.isEmpty() || blocks.all {
                it.contains("Blocked in write: no") &&
                    Regex("Hal stream dump:\\s*Standby: yes").containsMatchIn(it)
            }
        } else {
            false
        }

        val elapsed = System.currentTimeMillis() - startedAt
        val maxWait = GsmCallManager.profile.audioStopSettleDelayMs
        if (ready) {
            audioReady = true
            Log.i(TAG, "Audio HAL ready after ${elapsed}ms (AudioFlinger standby)")
            return
        }
        if (elapsed >= maxWait) {
            // Do not strand a real incoming call forever if dumpsys cannot
            // report readiness. The maximum remains the safety fallback.
            audioReady = true
            Log.w(TAG, "Audio HAL readiness not confirmed after ${elapsed}ms — releasing wait by timeout")
            return
        }

        schedule(AUDIO_READY_POLL_MS) {
            waitForAudioHalDrain(startedAt)
        }
    }

    /** Current bridge state */
    @Volatile var bridgeState: BridgeState = BridgeState.IDLE
        private set

    @Volatile var listener: OrchestratorListener? = null

    interface OrchestratorListener {
        fun onStateChanged(state: BridgeState, info: String)
        fun onError(error: String)
        fun onRtpStats(stats: String) {}
    }

    enum class BridgeState {
        IDLE,
        GSM_RINGING,        // Incoming GSM, waiting to answer
        GSM_ANSWERED,        // GSM answered, placing SIP call
        SIP_CALLING,         // SIP INVITE sent, waiting for answer
        SIP_RINGING,         // SIP ringing at Asterisk
        BRIDGED,             // Both sides active, audio flowing
        GSM_DIALING,         // Outbound: dialing GSM number
        TEARING_DOWN         // Hanging up
    }

    fun start() {
        sipClient.listener = this
        GsmCallManager.listener = this
        Log.i(TAG, "CallOrchestrator started")
    }

    fun stop() {
        tearDown("Orchestrator stopped")
        sipClient.listener = null
        GsmCallManager.listener = null
        timers.shutdownNow()
    }

    /**
     * The number this gateway answers on, sent as the SIP destination.
     *
     * The server maps an incoming number to an assistant the same way it does
     * a Fritz!Box DID, so it needs the MSISDN of our SIM here.  Addressing our
     * own SIP account instead makes the platform see account N calling account
     * N, match it as a local peer and ring us straight back — the INVITE comes
     * back, the orchestrator rejects it as busy, and the GSM leg is never
     * answered.
     *
     * Asks the platform first; many carriers do not publish the number on the
     * SIM, in which case the configured value is used.
     */
    private fun inboundSipDestination(gsmCall: Call? = null): String {
        val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
        val subscription = gsmCall?.details?.accountHandle?.let { account ->
            try {
                val subId = com.callagent.gateway.gsm.GsmCallManager.subscriptionIdOf(context, account)
                if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    // getActiveSubscriptionInfo() needs READ_PHONE_STATE.  It
                    // is a runtime permission: without it the call throws a
                    // SecurityException, and the incoming call is then
                    // reported on whichever SIM the fallback picks.
                    val granted = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    val info = if (granted) {
                        context.getSystemService(SubscriptionManager::class.java)
                            ?.getActiveSubscriptionInfo(subId)
                    } else null
                    if (info != null && info.simSlotIndex >= 0) {
                        Log.i(TAG, "Incoming call on SIM slot ${info.simSlotIndex} (subscription $subId)")
                        subId to info.simSlotIndex
                    } else null
                } else null
            } catch (e: Exception) {
                Log.w(TAG, "Could not resolve incoming call SIM: ${e.message}")
                null
            }
        }

        if (subscription != null) {
            val (subId, slot) = subscription
            val configured = prefs.getString("own_number_slot_$slot", "")?.trim().orEmpty()
            if (configured.isNotEmpty()) {
                val intl = toInternational(configured, subId)
                Log.i(TAG, "Own number for SIM slot $slot from settings: $configured → $intl")
                return intl
            }
            simNumber(subId)?.let {
                val intl = toInternational(it, subId)
                Log.i(TAG, "Own number for subscription $subId from SIM: $it → $intl")
                return intl
            }
            Log.w(TAG, "No number configured for incoming SIM slot $slot; using legacy fallback")
        } else if (gsmCall != null) {
            Log.w(TAG, "Incoming call SIM unresolved; using legacy fallback")
        }

        val configured = prefs.getString("own_number", DEFAULT_OWN_NUMBER)?.trim().orEmpty()
        if (configured.isNotEmpty()) {
            val intl = toInternational(configured)
            Log.i(TAG, "Own number from settings: $configured → $intl")
            return intl
        }
        simNumber(SubscriptionManager.getDefaultSubscriptionId())?.let {
            val intl = toInternational(it)
            Log.i(TAG, "Own number from default SIM: $it → $intl")
            return intl
        }
        Log.w(TAG, "No own number known — addressing our own extension, which loops back")
        return sipClient.username
    }

    /**
     * The destination always goes out in international form.  What we have to
     * start from varies: the platform may hand back E.164, the settings field
     * may hold a national number ("015112345678"), and either may use a 00
     * prefix.  PhoneNumberUtils resolves the national case against the SIM's
     * country rather than us guessing a dialling code.
     *
     * The caller number in From is deliberately NOT put through this — that one
     * is passed on exactly as the carrier delivered it.
     */
    private fun toInternational(number: String, subId: Int? = null): String {
        val trimmed = number.trim().filterNot { it == ' ' || it == '-' || it == '/' }
        if (trimmed.startsWith("+")) return trimmed
        if (trimmed.startsWith("00")) return "+" + trimmed.substring(2)
        // Android/carrier settings often store a US number as 11 digits
        // without the leading '+'.  Keep it E.164 or Asterisk will treat the
        // Request-URI as a different, unknown DID.
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length == 11 && digits.startsWith("1")) return "+$digits"
        val iso = try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            (if (subId != null) tm.createForSubscriptionId(subId) else tm)
                .simCountryIso?.uppercase()?.ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "SIM country unavailable: ${e.message}")
            null
        }
        if (iso != null) {
            PhoneNumberUtils.formatNumberToE164(trimmed, iso)?.let { return it }
            if (iso == "US" && digits.length == 10) return "+1$digits"
        }
        Log.w(TAG, "Cannot make '$trimmed' international (SIM country=$iso) — sending as is")
        return trimmed
    }

    /** The SIM's own number, when the carrier publishes it — many do not. */
    @SuppressLint("MissingPermission")
    private fun simNumber(subId: Int): String? = try {
        val number = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(SubscriptionManager::class.java)
                ?.getPhoneNumber(subId)
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
                .createForSubscriptionId(subId).line1Number
        }
        number?.trim()?.ifEmpty { null }
    } catch (e: Exception) {
        Log.w(TAG, "SIM number unavailable: ${e.message}")
        null
    }

    /** Cut the agent's audio to the caller, leaving the call itself up. */
    fun setAgentMuted(on: Boolean) {
        val session = activeRtpSession
        if (session == null) {
            listener?.onError("No active call to mute")
            return
        }
        session.setAgentMuted(on)
    }

    /** Listen in on the active call through the phone's speaker.
     *
     *  Plays both sides mixed; the microphone stays muted, so nothing the room
     *  says reaches either the caller or the agent. */
    /** Whether the active call is on Snoop right now (manual or Call monitor). */
    val isMonitoring: Boolean get() = activeRtpSession?.isMonitoring == true

    fun setMonitorEnabled(on: Boolean) {
        val session = activeRtpSession
        if (session == null) {
            Log.w(TAG, "Monitor requested with no active call")
            listener?.onError("No active call to monitor")
            return
        }
        session.setMonitorEnabled(on)
    }

    /** Initiate an outgoing GSM call from the dialler, then bridge to SIP */
    fun initiateDiallerCall(number: String) {
        // MMI/USSD is not a call — it never produces a Telecom Connection, so
        // arming the bridge for it would strand us in GSM_DIALING until the
        // stale-state timeout.
        if (GsmCallManager.isMmiCode(number)) {
            Log.i(TAG, "MMI code $number — sending as USSD, not bridging")
            GsmCallManager.sendMmi(context, number) { result ->
                Log.i(TAG, "MMI result: $result")
                listener?.onStateChanged(bridgeState, "MMI: $result")
            }
            return
        }
        if (bridgeState != BridgeState.IDLE) {
            // Check for stale state: if bridge has been non-IDLE for too long
            // without reaching BRIDGED, force a reset.  This happens on cold boot
            // when InCallService isn't bound yet and call events never arrive.
            val staleMs = System.currentTimeMillis() - lastStateChangeTime
            if (staleMs > STALE_STATE_TIMEOUT_MS) {
                Log.w(TAG, "Bridge stuck in $bridgeState for ${staleMs/1000}s — force resetting")
                forceReset("Stale state: $bridgeState for ${staleMs/1000}s")
            } else {
                Log.w(TAG, "Busy ($bridgeState) — cannot dial from dialler")
                listener?.onError("Busy — cannot dial")
                return
            }
        }
        Log.i(TAG, "Dialler-initiated call to $number")
        diallerInitiated = true
        lastStateChangeTime = System.currentTimeMillis()
        bridgeState = BridgeState.GSM_DIALING
        listener?.onStateChanged(bridgeState, "Dialing $number")
        GsmCallManager.makeCall(context, number)

        // Timeout: if GSM doesn't go active within 45s, tear down.
        // On cold boot, InCallService may not be bound, so call events
        // never arrive and the bridge gets stuck in GSM_DIALING.
        schedule(GSM_DIAL_TIMEOUT_MS) {
            if (bridgeState == BridgeState.GSM_DIALING) {
                Log.w(TAG, "GSM dial timeout — no call events in ${GSM_DIAL_TIMEOUT_MS / 1000}s")
                tearDown("GSM dial timeout")
            }
        }
    }

    // ── SipClient.Listener ──────────────────────────────

    override fun onRegistered() {
        Log.i(TAG, "SIP registered — ready for calls")
        listener?.onStateChanged(BridgeState.IDLE, "SIP registered")
    }

    override fun onRegistrationFailed() {
        Log.e(TAG, "SIP registration failed")
        listener?.onError("SIP registration failed")
    }

    /** Incoming SIP INVITE from Asterisk */
    override fun onIncomingCall(call: SipCall) {
        Log.i(TAG, "Incoming SIP call: ${call.callId}, gsm_forward=${call.gsmForwardNumber}, outbound_cid=${call.callerNumber}")

        if (bridgeState != BridgeState.IDLE) {
            Log.w(TAG, "Busy — rejecting SIP call 486")
            call.reject(486, "Busy Here")
            sipClient.removeCall(call.callId)
            return
        }

        val gsmDest = outboundDestination(call)
        if (gsmDest != null) {
            // OUTBOUND flow: Asterisk wants us to dial a GSM number
            handleOutboundFlow(call, gsmDest)
        } else {
            // Nothing to dial: no X-GSM-Forward, and no number in the
            // Request-URI either.  Answering used to look harmless, but a 200
            // tells the server the call is up and leaves it bridged to
            // silence for as long as it cares to wait.  Say we cannot take it.
            Log.w(TAG, "SIP INVITE names no GSM destination " +
                "(uri=${call.originalInvite?.requestUri}) — rejecting 488")
            listener?.onError("INVITE with no GSM destination — rejected")
            call.reject(488, "Not Acceptable Here")
            sipClient.removeCall(call.callId)
        }
    }

    /**
     * The number an INVITE asks us to dial.
     *
     * The rules live in [CallDestination] so they can be tested without a
     * phone: X-GSM-Forward first, then the Request-URI, then the To header —
     * res_pjsip builds the Request-URI from the endpoint's AOR and puts the
     * dialled part into the To header, so which of the two carries the number
     * depends on the dial string the server used.
     */
    private fun outboundDestination(call: SipCall): String? {
        val invite = call.originalInvite ?: return null
        val resolved = CallDestination.resolve(
            xGsmForward = call.gsmForwardNumber,
            requestUri = invite.requestUri,
            toHeader = invite.to,
            accountName = sipClient.username,
            ownNumbers = listOf(inboundSipDestination()),
        )
        if (resolved == null) return null
        Log.i(TAG, "GSM destination resolved: $resolved")
        return resolved
    }

    /** Handles termination from both SipClient.Listener and SipCall.Listener */
    override fun onCallTerminated(call: SipCall) {
        Log.i(TAG, "SIP call terminated: ${call.callId} (bridge=$bridgeState, retries=$sipCallRetries)")
        if (call != activeSipCall) return

        // If GSM is still ringing and we haven't exhausted retries, try again —
        // but only after a transient failure.  A deliberate answer from the far
        // side (busy, declined, unavailable) must end the GSM call: retrying it
        // re-rang the softphone after the user had rejected the call.
        val status = call.finalStatus
        val transient = status == 0 || status == 408 || status == 500 || status == 503 || status == 504
        if ((bridgeState == BridgeState.SIP_CALLING || bridgeState == BridgeState.SIP_RINGING)
            && transient && sipCallRetries < MAX_SIP_RETRIES && activeGsmCall != null) {
            sipCallRetries++
            Log.w(TAG, "SIP call failed while GSM ringing — retrying ($sipCallRetries/$MAX_SIP_RETRIES)")
            listener?.onStateChanged(bridgeState, "SIP retry $sipCallRetries/$MAX_SIP_RETRIES")
            activeSipCall = null
            sipClient.removeCall(call.callId)
            // Retry after a short delay to let any transient issue settle
            schedule(1000) {
                if (bridgeState != BridgeState.SIP_CALLING &&
                    bridgeState != BridgeState.SIP_RINGING) return@schedule
                activeGsmCall?.let { handleInboundFlow(it) }
                    ?: Log.e(TAG, "SIP retry: GSM call gone, aborting")
            }
            return
        }

        if (status >= 300) Log.i(TAG, "SIP side answered $status — not retrying, ending the GSM call")
        tearDown("SIP call ended")
    }

    // ── GsmCallManager.Listener ─────────────────────────

    /** Incoming GSM call — this is the INBOUND flow trigger */
    @Synchronized
    override fun onIncomingGsmCall(call: Call, number: String) {
        Log.i(TAG, "Incoming GSM call from $number")

        // onCallAdded() and the first STATE_RINGING callback can describe the
        // same Telecom call. Do not reject the call as a duplicate.
        if (activeGsmCall === call && bridgeState != BridgeState.IDLE &&
            bridgeState != BridgeState.TEARING_DOWN) {
            Log.d(TAG, "Ignoring duplicate ringing callback for the same GSM call (state=$bridgeState)")
            return
        }

        if (bridgeState != BridgeState.IDLE) {
            Log.w(TAG, "Busy — rejecting GSM call")
            GsmCallManager.rejectCall(call)
            return
        }

        sipCallRetries = 0
        bridgeState = BridgeState.GSM_RINGING
        activeGsmCall = call
        lastStateChangeTime = System.currentTimeMillis()
        ensureGsmStateWatchdog()
        listener?.onStateChanged(bridgeState, "GSM call from $number")

        if (!audioReady) {
            pendingIncoming = PendingIncoming(call, number)
            listener?.onStateChanged(bridgeState, "Waiting for audio HAL")
            Log.i(TAG, "GSM ringing from $number — audio HAL is not ready, holding SIP flow")
            waitForAudioReady(call, number)
            return
        }

        // Don't answer GSM yet — place SIP call to Asterisk first.
        // When the agent answers on SIP, we'll answer GSM so the caller
        // hears the agent immediately with no dead air.
        // The caller hears normal ringing in the meantime.
        Log.i(TAG, "GSM ringing from $number — placing SIP call first")
        Thread({
            // Anything thrown here used to kill the whole process, taking the
            // registration down with the one call (S4 Mini, 2026-10-23: a
            // RejectedExecutionException from a SIP client whose executor
            // had been shut down).  Fail the call, keep the gateway.
            try {
                handleInboundFlow(call)
            } catch (t: Throwable) {
                Log.e(TAG, "Inbound flow failed: $t", t)
                tearDown("inbound flow failed: ${t.javaClass.simpleName}")
            }
        }, "SIP-OutCall").start()

        // The cellular leg may also be answered on our own, so that whatever
        // the network says before anybody picks up is not lost.  Off by
        // default — see autoAnswerInboundEnabled().
        if (autoAnswerInboundEnabled()) {
            val delay = autoAnswerDelayMs()
            Log.i(TAG, "Auto-answer of the incoming cellular leg in ${delay}ms")
            schedule(delay.toLong()) {
                answerCellularLegEarly(setOf(
                    BridgeState.GSM_RINGING, BridgeState.SIP_CALLING, BridgeState.SIP_RINGING
                ))
            }
        }
    }

    /**
     * Answer the cellular leg before the far end does, so that whatever the
     * network says in the meantime is on the downlink, where the RTP session
     * can pick it up and carry it to the agent.
     *
     * Retried a few times: the call object appears only once Telecom has
     * created it, which can be a moment after the ringing callback.
     */
    private fun answerCellularLegEarly(allowed: Set<BridgeState>, attempt: Int = 0) {
        if (bridgeState !in allowed) return
        val call = activeGsmCall ?: GsmCallManager.activeCall
        if (!AutoAnswerPolicy.shouldAttempt(call != null, attempt)) {
            Log.w(TAG, "Auto-answer: no cellular call object after ${attempt + 1} tries")
            return
        }
        if (call == null) {
            schedule(AutoAnswerPolicy.RETRY_MS) {
                answerCellularLegEarly(allowed, attempt + 1)
            }
            return
        }
        if (GsmCallManager.isCallActive) {
            Log.i(TAG, "Auto-answer: the cellular leg is already active")
            return
        }
        Log.i(TAG, "Answering the cellular leg now (auto-answer) — " +
                "the operator's announcement becomes audible")
        gsmAutoAnswered = true
        // The retry is forced on: this answer lands before the network has
        // set the channel up, and one attempt at the wrong moment is one
        // attempt too few.
        GsmCallManager.answerCall(call, retry = true)
    }

    /** GSM call is now active (answered) */
    override fun onGsmCallActive(call: Call) {
        Log.i(TAG, "GSM call active")
        activeGsmCall = call
        ensureGsmStateWatchdog()

        when (bridgeState) {
            BridgeState.SIP_CALLING, BridgeState.SIP_RINGING -> {
                // INBOUND flow: GSM answered (triggered from onRtpReady).
                // SIP agent is ready — start RTP immediately so caller
                // hears the agent from the first moment.
                val addr = pendingRtpAddr
                val port = pendingRtpPort
                val pt = pendingPayloadType
                val localPort = pendingLocalRtpPort
                pendingRtpAddr = null

                if (addr != null && port > 0) {
                    Thread({
                        ensureRtp(localPort, addr, port, pt)
                        // Guard: tearDown may have run while startRtp was blocking
                        // (AudioRecord retries take 30+ seconds on cold boot).
                        // Don't overwrite IDLE — that causes "Busy" on next call.
                        if (bridgeState == BridgeState.IDLE || bridgeState == BridgeState.TEARING_DOWN) {
                            Log.w(TAG, "Bridge torn down during RTP setup — not transitioning to BRIDGED")
                            return@Thread
                        }
                        bridgeState = BridgeState.BRIDGED
                        listener?.onStateChanged(bridgeState, "Bridged (inbound)")
                        Log.i(TAG, "Inbound bridge established — zero dead air")
                    }, "RTP-Start").start()
                } else {
                    // Edge case: GSM answered but SIP RTP info not ready yet.
                    // This shouldn't happen in normal flow since we answer GSM
                    // from onRtpReady, but handle gracefully.
                    Log.w(TAG, "GSM active but no pending RTP info — waiting for SIP")
                    bridgeState = BridgeState.GSM_ANSWERED
                }
            }
            BridgeState.GSM_DIALING -> {
                if (diallerInitiated) {
                    // DIALLER flow: GSM active → place SIP call to Asterisk (like inbound)
                    diallerInitiated = false
                    bridgeState = BridgeState.GSM_ANSWERED
                    listener?.onStateChanged(bridgeState, "GSM answered, calling Asterisk")
                    Thread({
            // Anything thrown here used to kill the whole process, taking the
            // registration down with the one call (S4 Mini, 2026-10-23: a
            // RejectedExecutionException from a SIP client whose executor
            // had been shut down).  Fail the call, keep the gateway.
            try {
                handleInboundFlow(call)
            } catch (t: Throwable) {
                Log.e(TAG, "Inbound flow failed: $t", t)
                tearDown("inbound flow failed: ${t.javaClass.simpleName}")
            }
        }, "SIP-OutCall").start()
                } else {
                    // SIP-initiated OUTBOUND flow: the cellular leg is up.
                    // Whatever the network was saying — the operator's
                    // announcement — is on the cellular downlink right now,
                    // and it has to reach the agent.
                    bridgeState = BridgeState.BRIDGED
                    listener?.onStateChanged(bridgeState, "Bridged (outbound)")

                    // Answer the SIP call off the main thread
                    Thread({
                        try {
                            activeSipCall?.let { sipCall ->
                                // The port was named in the 183, so the media
                                // that is already running and the media the
                                // answer describes are one stream.
                                val rtpPort = sipCall.localRtpPort.takeIf { it > 0 }
                                    ?: allocateRtpPort()
                                sipCall.listener = this
                                val addr = sipCall.remoteRtpAddress ?: sipClient.serverDomain
                                val port = sipCall.remoteRtpPort
                                val pt = sipCall.negotiatedPayloadType

                                // The early media may already be carrying the
                                // stream; then there is nothing left to start,
                                // only the call to answer.
                                if (activeRtpSession == null && port > 0) {
                                    try {
                                        ensureRtp(rtpPort, addr, port, pt)
                                    } catch (t: Throwable) {
                                        Log.e(TAG, "RTP setup failed: $t", t)
                                        listener?.onError("RTP: ${t.javaClass.simpleName}")
                                    }
                                } else if (activeRtpSession != null) {
                                    Log.i(TAG, "Early media is already carrying the stream — " +
                                            "answering the call")
                                }
                                sipCall.accept(rtpPort)
                            }
                            Log.i(TAG, "Outbound bridge established")
                        } catch (t: Throwable) {
                            // The server is waiting for an answer.  Letting the
                            // thread die silently would leave it ringing out
                            // its own transaction timeout instead of being
                            // told what went wrong.
                            Log.e(TAG, "Outbound bridge failed: $t", t)
                            tearDown("outbound bridge failed: ${t.javaClass.simpleName}")
                        }
                    }, "SIP-Bridge").start()
                }
            }
            else -> {}
        }
    }

    override fun onGsmCallStateChanged(call: Call, state: Int) {
        val stateStr = when (state) {
            Call.STATE_DIALING -> "DIALING"
            Call.STATE_RINGING -> "RINGING"
            Call.STATE_ACTIVE -> "ACTIVE"
            Call.STATE_DISCONNECTED -> "DISCONNECTED"
            else -> "OTHER($state)"
        }
        Log.d(TAG, "GSM state: $stateStr")

        // Track the GSM call object as soon as we see it, so teardown works
        // even if the call never reaches ACTIVE (e.g. wrong number, rejected)
        if (activeGsmCall == null && bridgeState != BridgeState.IDLE) {
            activeGsmCall = call
            ensureGsmStateWatchdog()
        }

        if (state == Call.STATE_DISCONNECTED && bridgeState != BridgeState.IDLE) {
            tearDown("GSM call disconnected",
                sipStatusFor(GsmCallManager.lastDisconnectCause))
        } else if (state == Call.STATE_DISCONNECTING && bridgeState != BridgeState.IDLE) {
            // Telecom can remain in DISCONNECTING for a while after the radio
            // has already released the call.  End the SIP leg immediately.
            tearDown("GSM call disconnecting")
        }
    }

    /**
     * The SIP status that says why a GSM leg never connected.
     *
     * A provider that cannot complete a call answers the INVITE with a final
     * response the dialplan can branch on — busy, declined, unobtainable.
     * We used to send BYE instead, which is not a valid way to end an INVITE
     * that was never answered: the server replies 481 and then waits out its
     * own timer, so a busy number, a declined call and a dead SIM all looked
     * alike and all looked like a timeout.
     */
    /**
     * Why a GSM call we placed failed, as the SIP final response the server
     * gets.  Android folds unreachable / invalid / out-of-service / switched
     * off / congestion into code ERROR; the public reason string still names
     * the telephony cause ("BUSY", "UNOBTAINABLE_NUMBER", …), so it is used
     * first.  Before, ERROR became 500, which Asterisk treats like an
     * unreachable channel (CHANUNAVAIL) — a wrong or switched-off number
     * looked to the server like the gateway itself had failed.
     */
    private fun sipStatusFor(cause: DisconnectCause?): Pair<Int, String> {
        val r = cause?.reason.orEmpty().uppercase()
        Log.i(TAG, "GSM disconnect: code=${cause?.code} reason=${cause?.reason} label=${cause?.label}")
        fun has(vararg k: String) = k.any { r.contains(it) }
        return when {
            cause?.code == DisconnectCause.BUSY || has("USER_BUSY", "BUSY") -> 486 to "Busy Here"
            cause?.code == DisconnectCause.REJECTED || has("CALL_REJECTED", "INCOMING_REJECTED") -> 603 to "Decline"
            has("UNOBTAINABLE_NUMBER", "UNASSIGNED_NUMBER", "NO_ROUTE_TO_DESTINATION") -> 404 to "Number Not In Service"
            has("INVALID_NUMBER", "INVALID_NUMBER_FORMAT") -> 484 to "Address Incomplete"
            cause?.code == DisconnectCause.RESTRICTED || has("CALL_BARRED", "FDN_BLOCKED", "RESTRICTED") -> 403 to "Call Barred"
            has("NUMBER_UNREACHABLE", "POWER_OFF", "NO_ANSWER", "TIMED_OUT", "NO_USER_RESPONDING") ||
                cause?.code == DisconnectCause.MISSED -> 480 to "Not Reachable"
            has("OUT_OF_SERVICE", "EMERGENCY_ONLY", "RADIO_OFF", "ICC_ERROR", "CONGESTION",
                "OUT_OF_NETWORK", "NOT_VALID") ||
                cause?.code == DisconnectCause.CONNECTION_MANAGER_NOT_SUPPORTED -> 503 to "Service Unavailable"
            cause?.code == DisconnectCause.CANCELED || cause?.code == DisconnectCause.LOCAL -> 487 to "Request Terminated"
            // ERROR without a recognisable reason, REMOTE: not reachable.
            else -> 480 to "Temporarily Unavailable"
        }
    }

    override fun onGsmCallEnded(call: Call) {
        Log.i(TAG, "GSM call ended")
        // Tear down if this is our tracked call, OR if we're in a call state
        // but activeGsmCall was never set (call failed before going ACTIVE)
        if (call == activeGsmCall ||
            (activeGsmCall == null && bridgeState != BridgeState.IDLE)) {
            tearDown("GSM call ended",
                sipStatusFor(GsmCallManager.lastDisconnectCause))
        }
    }

    // ── SipCall.Listener ────────────────────────────────

    override fun onCallAnswered(call: SipCall) {
        Log.i(TAG, "SIP call answered: ${call.callId}")
    }

    // onCallTerminated is already implemented above (shared by SipClient.Listener and SipCall.Listener)

    override fun onDtmf(call: SipCall, digit: Char, durationMs: Int) {
        if (call !== activeSipCall) {
            Log.w(TAG, "INFO DTMF '$digit' for a call that is not bridged — ignored")
            return
        }
        GsmCallManager.dtmfRelay.pulse(digit, durationMs)
    }

    /**
     * The server is sending media before the call is answered — a 183 with
     * our SDP in it.
     *
     * That is the far end's early media, and it means the stream is live
     * while the agent's phone is still ringing.  Whatever the cellular side
     * has to say goes into it right away; the cellular side has to be up for
     * that, so the address is remembered when it is not.
     */
    override fun onEarlyMedia(call: SipCall, remoteRtpAddr: String, remoteRtpPort: Int, payloadType: Int) {
        Log.i(TAG, "Early media from the server: $remoteRtpAddr:$remoteRtpPort pt=$payloadType " +
                "(bridge=$bridgeState)")
        if (call !== activeSipCall) {
            Log.w(TAG, "Early media for a call that is not bridged — ignored")
            return
        }
        if (bridgeState != BridgeState.SIP_CALLING && bridgeState != BridgeState.SIP_RINGING &&
            bridgeState != BridgeState.GSM_ANSWERED) {
            Log.w(TAG, "Early media ignored — bridge state $bridgeState")
            return
        }

        pendingRtpAddr = remoteRtpAddr
        pendingRtpPort = remoteRtpPort
        pendingPayloadType = payloadType
        pendingLocalRtpPort = call.localRtpPort

        // With the cellular leg already up there is something to send, and
        // the far end is already listening for it.
        if (GsmCallManager.isCallActive) {
            Log.i(TAG, "Early media and the cellular leg is up — starting RTP now")
            ensureRtp(call.localRtpPort, remoteRtpAddr, remoteRtpPort, payloadType)
            if (activeRtpSession != null &&
                (bridgeState == BridgeState.SIP_CALLING || bridgeState == BridgeState.SIP_RINGING)) {
                bridgeState = BridgeState.BRIDGED
                listener?.onStateChanged(bridgeState, "Bridged (early media)")
            }
        } else {
            Log.i(TAG, "Early media noted — the media starts when the cellular leg comes up")
        }
    }

    /**
     * Start the RTP session, unless one is already running.
     *
     * The early media and the final answer can both reach the media at the
     * same time; two sessions would mean two capture streams and two audio
     * routes on one call.
     */
    private fun ensureRtp(localPort: Int, addr: String, port: Int, pt: Int,
                          allowSilentStart: Boolean = gsmAutoAnswered) {
        if (activeRtpSession != null) {
            Log.i(TAG, "RTP session already running — keeping it")
            return
        }
        startRtp(localPort, addr, port, pt, allowSilentStart)
    }

    override fun onRtpReady(call: SipCall, remoteRtpAddr: String, remoteRtpPort: Int, payloadType: Int) {
        val codecName = when (payloadType) {
            RtpPacket.PT_G722 -> "G.722"
            RtpPacket.PT_PCMA -> "PCMA"
            RtpPacket.PT_PCMU -> "PCMU"
            else -> "PT$payloadType"
        }
        Log.i(TAG, "RTP ready: $remoteRtpAddr:$remoteRtpPort codec=$codecName bridgeState=$bridgeState")

        // The media can already be running: the early media started it, and
        // the 200 OK that follows only confirms a stream that is already
        // there.  Two sessions on one call would mean two capture streams
        // and two audio routes.
        if (activeRtpSession != null) {
            Log.i(TAG, "RTP is already running — the 200 OK does not start a second session" +
                    (if (earlyMediaSent) " (the early media is what is running it)" else ""))
            return
        }

        if (bridgeState == BridgeState.SIP_CALLING || bridgeState == BridgeState.SIP_RINGING) {
            // Check if GSM is already active (dialler-initiated calls).
            // For inbound calls GSM is still ringing — answer it and wait for
            // onGsmCallActive to start RTP.  For dialler calls GSM is already
            // active so onGsmCallActive won't fire again — start RTP now.
            val gsmAlreadyActive = GsmCallManager.isCallActive

            if (gsmAlreadyActive) {
                Log.i(TAG, "SIP answered (codec=$codecName) — GSM already active, starting RTP now")
                val localRtpPort = call.localRtpPort
                Thread({
                    startRtp(localRtpPort, remoteRtpAddr, remoteRtpPort, payloadType)
                    if (bridgeState == BridgeState.IDLE || bridgeState == BridgeState.TEARING_DOWN) {
                        Log.w(TAG, "Bridge torn down during RTP setup — not transitioning to BRIDGED")
                        return@Thread
                    }
                    bridgeState = BridgeState.BRIDGED
                    listener?.onStateChanged(bridgeState, "Bridged (dialler)")
                    Log.i(TAG, "Dialler bridge established (codec=$codecName)")
                }, "RTP-Start").start()
            } else {
                // INBOUND flow: SIP/agent answered — save RTP info and answer GSM.
                // When GSM goes active (onGsmCallActive), RTP starts immediately
                // so the caller hears the agent from the first moment.
                pendingRtpAddr = remoteRtpAddr
                pendingRtpPort = remoteRtpPort
                pendingPayloadType = payloadType
                pendingLocalRtpPort = call.localRtpPort

                Log.i(TAG, "SIP answered (codec=$codecName) — answering GSM call now")
                activeGsmCall?.let { GsmCallManager.answerCall(it) }
                    ?: Log.e(TAG, "SIP answered but no active GSM call to answer!")
            }
        } else if (bridgeState == BridgeState.GSM_ANSWERED) {
            // Edge case: GSM was already answered (e.g. user picked up manually
            // or the auto-answer got there first) before SIP was ready.
            // Start RTP now.
            val localRtpPort = call.localRtpPort
            ensureRtp(localRtpPort, remoteRtpAddr, remoteRtpPort, payloadType)
            bridgeState = BridgeState.BRIDGED
            listener?.onStateChanged(bridgeState, "Bridged (inbound)")
            Log.i(TAG, "Bridge established (codec=$codecName)")
        } else {
            Log.w(TAG, "onRtpReady ignored — bridgeState=$bridgeState (expected SIP_CALLING or SIP_RINGING)")
            listener?.onError("RTP ready but bridge state wrong: $bridgeState")
            Log.i(TAG, "Inbound bridge established — GSM was already active (codec=$codecName)")
        }
    }

    // ── Inbound flow (GSM → SIP) ───────────────────────

    private fun handleInboundFlow(gsmCall: Call) {
        val (callerNumber, callerName) = GsmCallManager.callerOf(gsmCall)
        Log.i(TAG, "Inbound flow: placing SIP call for GSM caller $callerNumber ($callerName)")

        bridgeState = BridgeState.SIP_CALLING
        listener?.onStateChanged(bridgeState, "Calling Asterisk for $callerNumber")

        val rtpPort = allocateRtpPort()
        val sipCall = sipClient.makeCall(
            targetExtension = inboundSipDestination(gsmCall),
            localRtpPort = rtpPort,
            callerIdNumber = callerNumber,
            callerIdName = callerName,
            // The gateway states who is on the line, and identifies itself as
            // its own account.  The server then matches the request to the
            // gateway's extension by that name — from any address, so the
            // phone needs no static IP and the server needs no identify
            // section.  The caller's number is in P-Asserted-Identity.
            assertedIdentity = callerNumber,
        )
        sipCall.listener = this
        activeSipCall = sipCall

        Log.i(TAG, "SIP INVITE sent to Asterisk (caller=$callerNumber, rtp=$rtpPort)")

        // Timeout: if the server has not even said "ringing" within 30 s, give
        // up.  Once it rings (180), the server's own dial timeout decides; ours
        // only stops a call that rings for ever.  Cutting at 30 s regardless
        // ended calls the softphone was still ringing for — a push-woken
        // phone got about 20 s to answer.
        val sentAt = System.currentTimeMillis()
        fun check() {
            if (bridgeState != BridgeState.SIP_CALLING && bridgeState != BridgeState.SIP_RINGING) return
            if (activeSipCall !== sipCall) return
            val elapsed = System.currentTimeMillis() - sentAt
            val ringing = sipCall.state == SipCall.State.RINGING
            if (ringing && elapsed < SIP_RING_MAX_MS) {
                schedule(5_000) { check() }
                return
            }
            Log.w(TAG, "SIP call timeout — ${if (ringing) "rang ${elapsed / 1000}s" else "no ringing from the server in ${elapsed / 1000}s"}")
            tearDown(if (ringing) "No answer" else "Asterisk not answering")
        }
        schedule(SIP_CALL_TIMEOUT_MS) { check() }
    }

    // ── Outbound flow (SIP → GSM) ──────────────────────

    private fun handleOutboundFlow(sipCall: SipCall, gsmDestination: String) {
        Log.i(TAG, "Outbound flow: dialing GSM $gsmDestination")

        bridgeState = BridgeState.GSM_DIALING
        activeSipCall = sipCall
        listener?.onStateChanged(bridgeState, "Dialing $gsmDestination")

        // The server is told the media path is up right away: 183 with our own
        // SDP instead of a bare 180.  The server bridges early media to the
        // agent, so the cellular side reaches the agent while the agent's
        // phone is still ringing — which is the whole point: the operator's
        // announcement is played before anybody picks up.
        val rtpPort = try {
            allocateRtpPort()
        } catch (e: Exception) {
            Log.e(TAG, "No RTP port for the early media: ${e.message}")
            tearDown("No free RTP port")
            return
        }
        val early = earlyMediaEnabled() && sipCall.answerEarly(rtpPort)
        if (early) {
            earlyMediaSent = true
            Log.i(TAG, "Early media offered to the server (183 + SDP), port=$rtpPort")
        } else {
            // No early media: a plain 180 while the cellular side dials.
            sipCall.originalInvite?.let { invite ->
                val ringing = com.callagent.gateway.sip.SipBuilder.ringing180(invite, sipCall.localTag)
                sipClient.sendTo(ringing, sipCall.responseTarget())
            }
        }

        // Dial via the SIM whose configured own number matches the SIP
        // Outbound CID.  On a dual-SIM device omitting the account leaves
        // Telecom in SELECT_PHONE_ACCOUNT, which a headless gateway cannot
        // answer.  A single-SIM gateway dials on its SIM whatever the CID is,
        // or without one — makeCall decides.
        val cid = sipCall.callerNumber?.trim()?.takeIf { it.isNotEmpty() }
        if (!GsmCallManager.makeCall(context, gsmDestination, cid, headless = true)) {
            Log.e(TAG, "Cannot dial GSM: SIP Outbound CID ${cid ?: "missing"} does not select a unique SIM")
            tearDown("No matching SIM for Outbound CID", 503 to "No Matching SIM")
            return
        }

        // The RTP session does NOT go up with the 183.  It is put up when the
        // cellular leg comes up, because the HAL rewrites the voice path when
        // it programs the call — the incall-music setup written before that
        // is gone with it, and the agent's voice would never reach the
        // caller.  The port named in the 183 is the one the session binds, so
        // the early media and the answer are one stream.

        // The cellular network says things of its own before the far end
        // picks up — "this number is not available", a billing prompt, a queue
        // announcement.  That sound is only on the cellular downlink, and the
        // downlink is only open once the leg is answered.  Answering it here
        // is what lets the agent hear it.
        if (autoAnswerOutboundEnabled()) {
            val delay = autoAnswerDelayMs()
            Log.i(TAG, "Auto-answer of the cellular leg in ${delay}ms — " +
                    "catching the operator's announcement")
            schedule(delay.toLong()) {
                answerCellularLegEarly(setOf(BridgeState.GSM_DIALING))
            }
        }
    }

    // ── RTP ─────────────────────────────────────────────

    private fun startRtp(localPort: Int, remoteAddr: String, remotePort: Int,
                         payloadType: Int = RtpPacket.PT_G722,
                         allowSilentStart: Boolean = gsmAutoAnswered) {
        // Not @Synchronized, deliberately: tearDown() is, and it is reached
        // from the Telecom callback on the main thread, so sharing a monitor
        // with this method — which blocks for as long as AudioRecord takes to
        // come up — would hold the UI thread for seconds.  The generation
        // counter gives the same protection without the lock.
        val gen = generation
        // Re-assert RECORD_AUDIO appops SYNCHRONOUSLY before AudioRecord
        // creation.  Must complete before RtpSession.start() so AudioFlinger
        // sees "allow" when the record thread begins reading.  Running async
        // caused a race: AudioRecord started reading silence (denied) before
        // the appops command finished.  RtpSession also periodically re-asserts
        // appops in its timeoutLoop for screen-off resilience.
        forceAllowRecordAudio()
        if (generation != gen) {
            Log.w(TAG, "Bridge torn down before RTP setup — not starting")
            return
        }

        activeRtpSession?.let { oldSession ->
            audioReady = false
            oldSession.stop()
            // This path is normally only reached after a duplicate/late RTP
            // callback, but it must obey the same HAL handoff rule as teardown.
            val deadline = System.currentTimeMillis() + RTP_STOP_WAIT_TIMEOUT_MS
            while (!audioReady && generation == gen &&
                System.currentTimeMillis() < deadline) {
                try { Thread.sleep(AUDIO_READY_POLL_MS) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        val session = RtpSession(
            context, localPort, remoteAddr, remotePort, payloadType,
            // A leg we answered ourselves opens the downlink before the
            // network has anything to say on it: the silence at the start of
            // that capture is the network being quiet, not a dead source.
            // The same goes for the early media, whose capture runs before
            // the cellular leg is up at all.
            allowSilentStart = allowSilentStart
        )

        // DTMF from the SIP side → the GSM call, so IVR menus work.  Accept
        // the payload type the server named in its SDP as well as our own 101.
        val dtmfPt = activeSipCall?.telephoneEventPt ?: RtpPacket.PT_TELEPHONE_EVENT
        session.telephoneEventPts = setOf(dtmfPt, RtpPacket.PT_TELEPHONE_EVENT)
        session.dtmfDetector = DtmfDetector(
            onBegin = { digit -> GsmCallManager.dtmfRelay.begin(digit) },
            onEnd = { digit, _ -> GsmCallManager.dtmfRelay.end(digit) }
        )

        // Attach the negotiated SRTP keys, if this call has any.  Done before
        // start() so no packet is ever sent or accepted unprotected on a call
        // that agreed to be protected.
        activeSipCall?.let { call ->
            val local = call.localSrtpKeys
            val remote = call.remoteSrtpKeys
            if (local != null && remote != null) {
                session.srtpSend = SrtpContext(local)
                session.srtpRecv = SrtpContext(remote)
                Log.i(TAG, "SRTP enabled for this call (${local.suite.sdpName})")
            }
        }
        session.listener = object : RtpSession.Listener {
            override fun onRtpStarted() {
                Log.i(TAG, "RTP session started")
            }
            override fun onRtpStopped() {
                if (activeRtpSession === session) {
                    activeRtpSession = null
                }
                Log.i(TAG, "RTP session stopped")

                // Java/native stream release is not the same as Qualcomm HAL
                // readiness on Pixel 4a. The old deep-buffer output has been
                // observed blocked in AudioFlinger for ~10 seconds after this
                // callback. Keep the next GSM call ringing until that drain
                // window has elapsed, otherwise it can reuse stale TELEPHONY_TX.
                val settleMs = GsmCallManager.profile.audioStopSettleDelayMs
                if (settleMs <= 0) {
                    audioReady = true
                    return
                }
                audioReady = false
                Log.i(TAG, "Checking Qualcomm audio HAL readiness (max wait ${settleMs}ms)")
                waitForAudioHalDrain()
            }
            override fun onRtpError(error: String) {
                Log.e(TAG, "RTP error: $error")
                listener?.onError("RTP: $error")
            }
            override fun onRtpTimeout() {
                Log.w(TAG, "RTP timeout — no audio from Asterisk, tearing down")
                tearDown("RTP timeout")
            }
            override fun onRtpStats(stats: String) {
                listener?.onRtpStats(stats)
            }
        }
        // Published before start() so a teardown arriving mid-setup can find
        // and stop it rather than leaving an orphaned session holding the
        // audio devices and the RTP socket.
        activeRtpSession = session
        session.start()
        if (generation != gen) {
            Log.w(TAG, "Bridge torn down during RTP start — stopping orphaned session")
            session.stop()
            if (activeRtpSession === session) activeRtpSession = null
        } else if (context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
                .getBoolean("call_monitor", false)) {
            // Settings → Call monitor: every call starts on Snoop.
            Log.i(TAG, "Call monitor on — snooping this call")
            session.setMonitorEnabled(true)
        }
    }

    // ── Teardown ────────────────────────────────────────

    @Synchronized
    private fun tearDown(reason: String, sipStatus: Pair<Int, String>? = null) {
        if (bridgeState == BridgeState.IDLE || bridgeState == BridgeState.TEARING_DOWN) return
        bridgeState = BridgeState.TEARING_DOWN
        gsmStateWatchdog?.cancel(false)
        gsmStateWatchdog = null
        diallerInitiated = false
        Log.i(TAG, "Tearing down bridge: $reason")
        GsmCallManager.dtmfRelay.cancel()

        try {
            activeRtpSession?.let {
                audioReady = false
                it.stop()
            }
            activeRtpSession = null

            activeSipCall?.let {
                try {
                    if (it.state != SipCall.State.TERMINATED) {
                        // An INVITE we accepted is ended with BYE; one we never
                        // answered has to be turned down with a final response
                        // instead, which is also the only place the server ever
                        // learns why the GSM leg did not come up.
                        if (it.direction == SipCall.Direction.INBOUND &&
                            it.state != SipCall.State.ANSWERED) {
                            val (code, phrase) = sipStatus ?: (480 to "Temporarily Unavailable")
                            it.reject(code, phrase)
                        } else {
                            it.hangup()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error ending SIP call: ${e.message}")
                }
                sipClient.removeCall(it.callId)
            }
            activeSipCall = null

            // In the outbound flow Telecom can create the Call object and
            // publish it to GsmCallManager just before the InCallService
            // state callback reaches this class.  Prefer our tracked object,
            // but fall back to the manager's object so a SIP CANCEL during GSM
            // ringing cannot leave the cellular leg ringing indefinitely.
            val gsmCallToDisconnect = activeGsmCall ?: GsmCallManager.activeCall
            gsmCallToDisconnect?.let { call ->
                try {
                    // Always disconnect — not just when ACTIVE.  If the SIP
                    // call fails before GSM is answered, the ringing GSM call
                    // was left dangling (S4 Mini: "second call never answered").
                    // Call.disconnect() works for RINGING, DIALING, and ACTIVE.
                    call.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Error disconnecting GSM: ${e.message}")
                }
            }
            activeGsmCall = null
            pendingIncoming = null
            pendingRtpAddr = null
            earlyMediaSent = false
            gsmAutoAnswered = false
        } finally {
            bridgeState = BridgeState.IDLE
            generation++
            lastStateChangeTime = System.currentTimeMillis()
            listener?.onStateChanged(BridgeState.IDLE, reason)
            Log.i(TAG, "Bridge torn down: $reason")
        }
    }

    // ── Utility ─────────────────────────────────────────

    /**
     * Pick a free even UDP port for RTP.
     *
     * The scan starts at a random offset rather than always at 30000.  The
     * port is probed by binding and closing, so there is a gap before
     * RtpSession binds it for real; starting from the same place every time
     * meant consecutive calls raced each other for the very same port, which
     * is the one way that gap reliably loses.
     */
    private fun allocateRtpPort(): Int {
        val span = (RTP_PORT_MAX - RTP_PORT_MIN) / 2
        val start = (Math.random() * span).toInt()
        for (i in 0 until span) {
            val port = RTP_PORT_MIN + ((start + i) % span) * 2
            try {
                DatagramSocket(null).use { sock ->
                    sock.reuseAddress = true
                    sock.bind(InetSocketAddress(port))
                    return port
                }
            } catch (_: Exception) {
                continue
            }
        }
        throw RuntimeException("No free RTP port available")
    }

    /**
     * Force-allow RECORD_AUDIO via appops using root (Magisk).
     *
     * Android's AppOpsService revokes RECORD_AUDIO (app op 27) for
     * foreground services when the screen is off.  This must be
     * re-asserted before EVERY call, not just at startup.
     *
     * CRITICAL: Must use --uid flag to set the UID-level mode.
     * `appops set <pkg>` sets the package mode, but AudioFlinger checks
     * the UID mode (set by PermissionController).  UID mode overrides
     * package mode, so without --uid the allow is ineffective on cold boot.
     */
    private fun forceAllowRecordAudio() {
        try {
            val pkg = context.packageName
            val uidProbe = if (Build.VERSION.SDK_INT >= 29) "--uid " else ""
            // This sits directly between the call being answered and the first
            // frame of audio, so ask before acting: one `appops get` costs a
            // single root round-trip, where the grant sequence below is eight
            // commands and forks an app_process for each of pm/appops/cmd.
            // In the steady state the permission is already allowed and this
            // returns immediately.
            val probe = RootShell.execForOutput(
                "appops get ${uidProbe}$pkg RECORD_AUDIO 2>&1"
            )
            if (probe.contains("allow", ignoreCase = true)) {
                Log.i(TAG, "appops RECORD_AUDIO already allow — skipping grant")
                return
            }
            Log.w(TAG, "appops RECORD_AUDIO not allowed [$probe] — granting")
            val t0 = System.currentTimeMillis()
            // Capture all output (2>&1) for diagnosis.  appops get is LAST
            // so exit code reflects verification, not a stray killall.
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
            Log.i(TAG, "appops RECORD_AUDIO: [$result] ok=$allowed (${elapsed}ms)")

            if (!allowed) {
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
            Log.w(TAG, "appops force-allow failed: ${e.message}")
        }
    }

    /** Force-reset bridge to IDLE, clearing all state.  Used to recover from
     *  stale states where the normal tearDown path was never triggered. */
    @Synchronized
    private fun forceReset(reason: String) {
        Log.w(TAG, "Force-resetting bridge: $reason")
        gsmStateWatchdog?.cancel(false)
        gsmStateWatchdog = null
        try {
            activeRtpSession?.let {
                audioReady = false
                it.stop()
            }
        } catch (_: Exception) {}
        activeRtpSession = null
        try {
            activeSipCall?.let {
                if (it.state != SipCall.State.TERMINATED) it.hangup()
                sipClient.removeCall(it.callId)
            }
        } catch (_: Exception) {}
        activeSipCall = null
        try {
            activeGsmCall?.disconnect()
        } catch (_: Exception) {}
        activeGsmCall = null
        pendingIncoming = null
        pendingRtpAddr = null
        earlyMediaSent = false
        gsmAutoAnswered = false
        diallerInitiated = false
        bridgeState = BridgeState.IDLE
        generation++
        lastStateChangeTime = System.currentTimeMillis()
        listener?.onStateChanged(BridgeState.IDLE, reason)
        Log.i(TAG, "Bridge force-reset complete: $reason")
    }

    companion object {
        private const val TAG = "CallOrchestrator"
        private const val SIP_CALL_TIMEOUT_MS = 30_000L
        /** Safety net once the server reports ringing (its own dial timeout normally ends it first). */
        private const val SIP_RING_MAX_MS = 70_000L
        private const val GSM_DIAL_TIMEOUT_MS = 45_000L
        /** If bridge is non-IDLE for this long, consider it stale */
        private const val STALE_STATE_TIMEOUT_MS = 60_000L
        private const val AUDIO_READY_POLL_MS = 100L
        private const val AUDIO_READY_WAIT_TIMEOUT_MS = 30_000L
        private const val RTP_STOP_WAIT_TIMEOUT_MS = 10_000L
        private const val AUDIO_HAL_PROBE_TIMEOUT_MS = 1_500L

        private const val RTP_PORT_MIN = 30000
        private const val RTP_PORT_MAX = 40000

        /** Placeholder shown in settings until a real MSISDN is entered. */
        private const val DEFAULT_OWN_NUMBER = "+49123123123123"
    }
}
