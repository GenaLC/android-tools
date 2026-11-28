package com.callagent.gateway.sip

import com.callagent.gateway.rtp.SrtpCryptoSuite
import com.callagent.gateway.rtp.SrtpKeys

import android.util.Log

/**
 * Represents one SIP call dialog.
 * Tracks dialog state (tags, CSeq, contact) and provides methods to
 * send in-dialog requests (ACK, BYE).
 */
class SipCall(
    val callId: String,
    val direction: Direction,
    private val sipClient: SipClient
) {
    enum class Direction { INBOUND, OUTBOUND }
    enum class State { TRYING, RINGING, ANSWERED, TERMINATED }

    var state: State = State.TRYING
        private set

    /** Set when any SIP response is received — stops INVITE retransmission (Timer A) */
    @Volatile var responseReceived = false

    /** Final error status (>= 300) that ended this call, or 0 if none was received
     *  (timeout, transport failure).  Lets the orchestrator tell a deliberate
     *  rejection (486/603...) from a transient failure worth retrying. */
    @Volatile var finalStatus: Int = 0
        private set

    // Dialog identifiers
    var localTag: String = "gw${(100000000..999999999).random()}"
    var remoteTag: String? = null
    var fromHeader: String? = null
    var toHeader: String? = null

    // Remote endpoint
    var remoteContactUri: String? = null
    var remoteContactAddress: Pair<String, Int>? = null

    /**
     * Where the request that created this call actually came from — the
     * source address of the packet, not the address in its Contact header.
     *
     * RFC 3261 §8.1.3.3: a response to a request goes back to where the
     * request came from (the Via sent-by address, or the real source address
     * when the sender used `rport`).  The Contact header names the address
     * for *in-dialog* requests only.  Answering to the Contact instead sends
     * the response somewhere the caller is not listening, and the caller
     * then waits out its own transaction timeout: the call looks like it
     * rings for ever and then drops.
     *
     * This bites in practice when the server's Contact does not match the
     * address it actually sends from — a PBX behind NAT, or one whose
     * `external_signaling_address` names a different host.
     */
    var remoteSourceAddress: Pair<String, Int>? = null

    /**
     * Where a response to this call's initial request has to go.  For an
     * inbound call that is the address the request came from; for an
     * outbound one there is no source address, and the Contact (which the
     * server put in its 200 OK) is the right target.
     */
    fun responseTarget(): Pair<String, Int> =
        Companion.responseTarget(remoteSourceAddress, remoteContactAddress, sipClient.serverAddress)

    // CSeq tracking
    var localCseq: Int = 1
    var remoteCseq: Int = 0

    // Auth: prevent re-sending credentials on duplicate/retransmitted 401s.
    // UDP can retransmit the server's 401, causing us to send a SECOND
    // authenticated INVITE (CSeq N+1) which gets 491 (Request Pending)
    // because the server is already processing the first one (CSeq N).
    @Volatile var authHandled = false

    // RTP endpoints
    var localRtpPort: Int = 0
    var remoteRtpPort: Int = 0
    var remoteRtpAddress: String? = null
    var negotiatedPayloadType: Int = 9 // default G.722, updated from SDP
    /** The peer's RFC 4733 payload type, from its SDP; 101 if it named none. */
    var telephoneEventPt: Int = com.callagent.gateway.rtp.RtpPacket.PT_TELEPHONE_EVENT

    // ── SRTP (RFC 3711 / RFC 4568) ──────────────────────
    // Two independent keys, one per direction: ours protects what we send,
    // theirs authenticates what we receive.  Both null means plain RTP.

    /** Our keying material, offered or answered in our own SDP. */
    var localSrtpKeys: SrtpKeys? = null

    /** The peer's, taken from their SDP. */
    var remoteSrtpKeys: SrtpKeys? = null

    /** Crypto tag to answer with -- an answer must echo the accepted tag. */
    var srtpTag: Int = 1

    /** True only when both directions are keyed and the profile is SAVP. */
    val srtpActive: Boolean get() = localSrtpKeys != null && remoteSrtpKeys != null

    /**
     * Take the peer's key out of their SDP, if they offered a suite we can do.
     *
     * A crypto line on a non-SAVP stream is ignored deliberately: the profile
     * decides, and answering plain RTP with encrypted audio produces a call
     * where neither side hears anything and nothing looks wrong.
     */
    fun absorbRemoteSrtp(msg: SipMessage): Boolean {
        if (!msg.sdpIsSavp) return false
        for ((tag, suiteName, inlineValue) in msg.sdpCryptoLines) {
            val suite = SrtpCryptoSuite.byName(suiteName) ?: continue
            val keys = SrtpKeys.fromInline(suite, inlineValue) ?: continue
            remoteSrtpKeys = keys
            srtpTag = tag
            return true
        }
        return false
    }

    // Caller info (for inbound and outbound caller-ID)
    var callerNumber: String? = null
    var callerDisplayName: String? = null
    // Outbound caller-ID (preserved for auth re-INVITE)
    var outboundCallerIdNumber: String? = null
    var outboundCallerIdName: String? = null

    /**
     * The number the gateway asserts on behalf of the GSM leg, and the name
     * that goes with it.
     *
     * While this is set, the gateway identifies itself to the server as its
     * own account (the user in From) and states the caller's number in
     * P-Asserted-Identity.  That is what lets the server match the request
     * to the endpoint by name — from any address, with no identify section
     * and no static IP on either side.
     */
    var assertedIdentity: String? = null
    var assertedIdentityName: String? = null

    // GSM forward target (for outbound from server)
    var gsmForwardNumber: String? = null

    /**
     * True once a provisional response has carried SDP: the peer is sending
     * media before the call is answered.
     *
     * Early media matters because the cellular side can have sound of its own
     * at that point — the operator's announcement, played before anybody
     * picks up — and that sound has to reach the far end while the call is
     * still ringing there.
     */
    @Volatile var earlyMediaReceived: Boolean = false
        private set

    // Original INVITE (for building responses)
    var originalInvite: SipMessage? = null

    var listener: Listener? = null

    interface Listener {
        fun onCallAnswered(call: SipCall)
        fun onCallTerminated(call: SipCall)
        fun onRtpReady(call: SipCall, remoteRtpAddr: String, remoteRtpPort: Int, payloadType: Int)
        /** A key press that arrived as SIP INFO rather than RTP. */
        fun onDtmf(call: SipCall, digit: Char, durationMs: Int) {}
        /**
         * The peer offered media in a provisional response (183/180 with SDP).
         *
         * The stream is live from this moment on, so the cellular side can be
         * sent to it right away — before the call itself is answered.
         */
        fun onEarlyMedia(call: SipCall, remoteRtpAddr: String, remoteRtpPort: Int, payloadType: Int) {}
    }

    /** Process incoming SIP message for this dialog */
    fun handleMessage(msg: SipMessage): Boolean {
        // Stop INVITE retransmission as soon as any response arrives
        if (msg.isResponse) responseReceived = true

        when {
            // 200 OK to our INVITE
            msg.isResponse && msg.statusCode == 200 && msg.cseq?.contains("INVITE") == true -> {
                remoteTag = msg.toTag
                toHeader = msg.to
                msg.contactUri?.let { remoteContactUri = it }
                msg.contactAddress?.let { remoteContactAddress = it }
                msg.sdpRtpPort?.let { remoteRtpPort = it }
                msg.sdpAddress?.let { remoteRtpAddress = it }
                // Only a payload the peer offered may be sent; the configured
                // fallback covers a peer that offered no codec this build
                // speaks, and the answer is built from the same value.
                //
                // The answer's SDP is what decides — but only when it has one.
                // An answer without SDP must not undo a codec the early media
                // has already agreed on: the media stream is running with it.
                val answerPayload = if (msg.body.contains("m=audio")) {
                    msg.sdpPreferredPayloadType
                } else null
                negotiatedPayloadType = answerPayload
                    ?: if (earlyMediaReceived) negotiatedPayloadType
                    else SipBuilder.defaultPayloadType()
                msg.sdpTelephoneEventPt?.let { telephoneEventPt = it }

                // We offered SRTP; this is where we find out whether they took
                // it.  If they did not, our key is dropped so the media path
                // does not try to protect a stream the far end will read as
                // plain RTP -- but it is a downgrade, so it is said out loud
                // rather than logged at debug and forgotten.
                //
                // Keys already taken from the early media are kept: the media
                // has been running with them since the 183, and dropping them
                // now would leave the two ends protecting different streams.
                if (localSrtpKeys != null && remoteSrtpKeys == null) {
                    if (absorbRemoteSrtp(msg)) {
                        Log.i(TAG, "SRTP negotiated: ${remoteSrtpKeys?.suite?.sdpName}")
                        sipClient.logListener?.invoke("SRTP active (${remoteSrtpKeys?.suite?.sdpName})")
                    } else {
                        localSrtpKeys = null
                        Log.w(TAG, "Peer declined SRTP — media will be unencrypted")
                        sipClient.logListener?.invoke("SRTP declined by server — audio NOT encrypted")
                    }
                }

                // ACK must use the same CSeq as the INVITE being acknowledged
                val ackCseq = msg.cseq?.split(" ")?.firstOrNull()?.toIntOrNull() ?: localCseq
                sendAck(ackCseq)

                // Guard against duplicate 200 OK (Asterisk retransmits until ACK)
                if (state == State.ANSWERED) {
                    Log.i(TAG, "Duplicate 200 OK for call $callId (already answered), ACKed")
                    return true
                }

                Log.i(TAG, "SDP codec: pt=$negotiatedPayloadType codecs=${msg.sdpCodecs}")
                state = State.ANSWERED

                val addr = remoteRtpAddress ?: remoteContactAddress?.first
                Log.i(TAG, "200 OK RTP: addr=$addr port=$remoteRtpPort listener=${listener != null}")
                if (addr != null && remoteRtpPort > 0) {
                    listener?.onRtpReady(this, addr, remoteRtpPort, negotiatedPayloadType)
                } else {
                    Log.w(TAG, "200 OK missing RTP info — addr=$addr port=$remoteRtpPort, cannot bridge")
                    sipClient.logListener?.invoke("200 OK missing RTP: addr=$addr port=$remoteRtpPort")
                }
                listener?.onCallAnswered(this)
                return true
            }

            // 100 Trying
            msg.isResponse && msg.statusCode == 100 -> {
                state = State.TRYING
                return true
            }

            // 180 Ringing
            msg.isResponse && msg.statusCode == 180 -> {
                state = State.RINGING
                remoteTag = msg.toTag
                // A 180 may carry SDP as well — rare, but legal, and it means
                // the same thing it means in a 183: the media stream is live.
                handleEarlyMedia(msg)
                return true
            }

            // 401/407 Auth required for INVITE
            msg.isResponse && (msg.statusCode == 401 || msg.statusCode == 407) -> {
                if (authHandled) {
                    // UDP retransmission of the original 401 — ignore it.
                    // We already sent an authenticated re-INVITE; sending another
                    // would create a duplicate CSeq that gets 491 (Request Pending).
                    Log.i(TAG, "Ignoring duplicate ${msg.statusCode} — already re-sent with credentials")
                    return true
                }
                val authParams = SipAuth.parseChallenge(msg)
                if (authParams != null) {
                    authHandled = true
                    Log.i(TAG, "INVITE auth challenge, re-sending with credentials")
                    sipClient.resendInviteWithAuth(this, authParams)
                }
                return true
            }

            // Incoming BYE
            msg.isRequest && msg.method == "BYE" -> {
                Log.i(TAG, "Received BYE for call $callId")
                // Send 200 OK to BYE
                sipClient.sendResponse(
                    SipBuilder.ok200(msg, sipClient.username, sipClient.publicIp, sipClient.localPort),
                    responseTarget()
                )
                state = State.TERMINATED
                listener?.onCallTerminated(this)
                return true
            }

            // CANCEL is how Asterisk ends an INVITE that is still ringing.
            // It is different from BYE: acknowledge the CANCEL transaction,
            // then reject the original INVITE with 487 so the UAC can finish
            // its call state machine.  Without this branch the GSM leg kept
            // ringing because the gateway ignored the caller's cancellation.
            msg.isRequest && msg.method == "CANCEL" -> {
                Log.i(TAG, "Received CANCEL for call $callId")
                sipClient.sendResponse(
                    SipBuilder.ok200(msg, sipClient.username, sipClient.publicIp, sipClient.localPort),
                    responseTarget()
                )
                if (state != State.ANSWERED && state != State.TERMINATED) {
                    reject(487, "Request Terminated", localTag)
                }
                return true
            }

            // 183 Session Progress (early media)
            msg.isResponse && msg.statusCode == 183 -> {
                state = State.RINGING
                remoteTag = msg.toTag
                Log.i(TAG, "183 Session Progress for call $callId")
                // The SDP in a 183 is the early media: the peer is already
                // sending sound, and the answer to it is to start sending back.
                handleEarlyMedia(msg)
                return true
            }

            // 4xx/5xx/6xx error responses — log and terminate
            msg.isResponse && msg.statusCode != null && msg.statusCode!! >= 300 -> {
                Log.e(TAG, "SIP error ${msg.statusCode} for call $callId (CSeq: ${msg.cseq})")
                // ACK the error response (required by RFC 3261 for INVITE transactions)
                if (msg.cseq?.contains("INVITE") == true) {
                    val ackCseq = msg.cseq?.split(" ")?.firstOrNull()?.toIntOrNull() ?: localCseq
                    val uri = remoteContactUri ?: "sip:${sipClient.serverDomain}:${sipClient.serverPort}"
                    val ack = SipBuilder.ack(
                        uri, null, msg.to, fromHeader,
                        callId, ackCseq,
                        sipClient.username, sipClient.publicIp, sipClient.localPort
                    )
                    sipClient.sendResponse(ack, remoteContactAddress ?: sipClient.serverAddress)
                }
                // Ignore error responses once the dialog is established (200 OK
                // received).  This handles the case where a duplicate INVITE
                // (CSeq N+1, sent because a retransmitted 401 was treated as a
                // new challenge) gets a 491 (Request Pending) AFTER the original
                // INVITE's 200 OK already established the dialog.  Without this
                // guard, the 491 tears down an active call.
                if (state == State.ANSWERED) {
                    Log.i(TAG, "Ignoring ${msg.statusCode} — call already answered (CSeq: ${msg.cseq})")
                    return true
                }
                sipClient.logListener?.invoke("INVITE rejected: ${msg.statusCode} (call $callId)")
                finalStatus = msg.statusCode ?: 0
                state = State.TERMINATED
                listener?.onCallTerminated(this)
                return true
            }

            // ── In-dialog requests from the server ──────────────────
            // None of these used to get an answer.  An unanswered INFO is
            // retransmitted for 32s; an unanswered re-INVITE or UPDATE — a
            // session-timer refresh, typically ~15 minutes in — makes the
            // server tear the call down.  So long calls dropped.

            // DTMF as SIP INFO (application/dtmf-relay or application/dtmf).
            msg.isRequest && msg.method == "INFO" -> {
                sipClient.sendResponse(
                    SipBuilder.ok200(msg, sipClient.username, sipClient.publicIp, sipClient.localPort),
                    remoteContactAddress ?: sipClient.serverAddress
                )
                val digit = com.callagent.gateway.rtp.SipInfoDtmf.parse(msg.contentType, msg.body)
                if (digit != null) {
                    Log.i(TAG, "INFO DTMF '${digit.char}' (${digit.durationMs}ms) for call $callId")
                    listener?.onDtmf(this, digit.char, digit.durationMs)
                } else {
                    Log.d(TAG, "INFO without DTMF for call $callId (${msg.contentType})")
                }
                return true
            }

            // Re-INVITE (session refresh, hold, codec change) on an answered
            // dialog.  A To-tag is what tells it apart from a retransmission of
            // the initial INVITE, which must not be answered from here.
            msg.isRequest && msg.method == "INVITE" && state == State.ANSWERED &&
                msg.to?.contains(";tag=") == true -> {
                answerInDialogOffer(msg, "re-INVITE")
                return true
            }

            // UPDATE (RFC 3311) — session timers again, sometimes with SDP.
            msg.isRequest && msg.method == "UPDATE" -> {
                answerInDialogOffer(msg, "UPDATE")
                return true
            }

            // In-dialog OPTIONS (some servers probe established calls).
            msg.isRequest && msg.method == "OPTIONS" -> {
                sipClient.sendResponse(
                    SipBuilder.ok200(msg, sipClient.username, sipClient.publicIp, sipClient.localPort),
                    remoteContactAddress ?: sipClient.serverAddress
                )
                return true
            }

            // ACK (for our 200 OK)
            msg.isRequest && msg.method == "ACK" -> {
                Log.d(TAG, "Received ACK for call $callId")
                return true
            }

            else -> {
                Log.w(TAG, "Unhandled SIP message for call $callId: ${msg.startLine}")
                return false
            }
        }
    }

    /**
     * A provisional response can carry SDP, and that SDP describes a media
     * stream that is already live: the peer is sending sound while the call
     * is still ringing.
     *
     * Everything the session needs is taken from it — the address to send to,
     * the codec, the DTMF payload and, when the media is protected, the keys.
     * The 200 OK may then arrive without any SDP of its own, and the values
     * found here are the ones the call keeps.
     */
    private fun handleEarlyMedia(msg: SipMessage) {
        val port = msg.sdpRtpPort
        val addr = msg.sdpAddress
        if (port == null || port <= 0 || addr.isNullOrEmpty()) return

        // Protected media: the early media and the final answer are one
        // stream, so they are protected with the same keys.
        if (localSrtpKeys != null && remoteSrtpKeys == null) {
            if (absorbRemoteSrtp(msg)) {
                Log.i(TAG, "SRTP negotiated in the early media: ${remoteSrtpKeys?.suite?.sdpName}")
            }
        }

        remoteRtpPort = port
        remoteRtpAddress = addr
        val pt = msg.sdpPreferredPayloadType ?: negotiatedPayloadType
        negotiatedPayloadType = pt
        msg.sdpTelephoneEventPt?.let { telephoneEventPt = it }

        earlyMediaReceived = true
        Log.i(TAG, "Early media for call $callId: $addr:$port pt=$pt codecs=${msg.sdpCodecs}")
        listener?.onEarlyMedia(this, addr, port, pt)
    }

    /**
     * Answer an in-dialog offer (re-INVITE or UPDATE) with 200 OK.
     *
     * The media session is not renegotiated: the answer repeats our existing
     * port, codec offer and SRTP key, which is what a refresh expects.  If the
     * peer moved its media, that is logged — symmetric RTP keeps sending to
     * the address the first packets came from, so configure the server not to
     * re-route media mid-call (Asterisk: direct_media=no).
     */
    private fun answerInDialogOffer(msg: SipMessage, what: String) {
        val hasSdp = msg.body.contains("m=audio")
        if (hasSdp) {
            val newAddr = msg.sdpAddress
            val newPort = msg.sdpRtpPort
            if ((newAddr != null && newAddr != remoteRtpAddress) ||
                (newPort != null && newPort != remoteRtpPort)) {
                Log.w(TAG, "$what moves media to $newAddr:$newPort (was " +
                        "$remoteRtpAddress:$remoteRtpPort) — not followed; set direct_media=no")
                sipClient.logListener?.invoke("$what tried to move media — set direct_media=no on the server")
            }
            msg.sdpTelephoneEventPt?.let { telephoneEventPt = it }
        }
        val ok = SipBuilder.ok200(
            msg, sipClient.username, sipClient.publicIp, sipClient.localPort,
            // A re-INVITE without SDP is a delayed offer: the 200 OK must
            // carry one.  An UPDATE without SDP is answered without.
            localRtpPort = if ((hasSdp || msg.method == "INVITE") && localRtpPort > 0) localRtpPort else null,
            toTag = localTag,
            srtp = if (srtpActive) localSrtpKeys else null,
            srtpTag = srtpTag,
            // The media session is not renegotiated, so the answer names the
            // payload the session is already carrying.  Listing the whole
            // configured set here would leave the choice to the server, which
            // may pick a different codec than the one the RTP session was set
            // up for — and then both ends hear noise.
            negotiatedPayloadType = negotiatedPayloadType
        )
        sipClient.sendResponse(ok, remoteContactAddress ?: sipClient.serverAddress)
        Log.i(TAG, "Answered $what for call $callId (sdp=$hasSdp)")
    }

    /** Accept an inbound INVITE: send 200 OK with SDP */
    fun accept(localRtpPort: Int) {
        this.localRtpPort = localRtpPort
        val invite = originalInvite ?: return
        val toTag = localTag

        // The INVITE's To header has no local tag.  The tag we put into the
        // 200 OK becomes part of the dialog identifier and must also be used
        // on a later BYE.  Keeping the untagged INVITE header here makes
        // Asterisk answer the BYE with 481, leaving the SIP caller up until
        // its own timeout even though the GSM leg already ended.
        toHeader = invite.to?.let { to ->
            if (to.contains(";tag=")) to else "$to;tag=$toTag"
        }

        val ok = SipBuilder.ok200(
            invite, sipClient.username, sipClient.publicIp, sipClient.localPort,
            localRtpPort = localRtpPort, toTag = toTag,
            srtp = localSrtpKeys, srtpTag = srtpTag,
            // The answer names the payload the RTP session is going to send.
            negotiatedPayloadType = this.negotiatedPayloadType
        )

        val address = responseTarget()
        sipClient.sendResponse(ok, address)

        state = State.ANSWERED
        Log.i(TAG, "Sent 200 OK for inbound call $callId (RTP port: $localRtpPort)")
    }

    /**
     * Answer with early media: 183 Session Progress carrying our own SDP.
     *
     * The dialog is not established — the call is still ringing at the far
     * end — but the media stream named in the SDP is real, and what goes into
     * it can already be the cellular side.  That is how the operator's
     * announcement, played before anybody picks the phone up, reaches the
     * agent while their phone is still ringing.
     *
     * The To tag is the one [accept] uses, so the 200 OK that ends the call
     * belongs to this dialog.
     */
    fun answerEarly(localRtpPort: Int): Boolean {
        if (state == State.TERMINATED || state == State.ANSWERED) return false
        // Only the gateway's own incoming call can be answered this way: as
        // the caller of a dialog we would have nothing to send a 183 into.
        val invite = originalInvite ?: return false

        this.localRtpPort = localRtpPort
        val toTag = localTag
        toHeader = invite.to?.let { to ->
            if (to.contains(";tag=")) to else "$to;tag=$toTag"
        }

        val progress = SipBuilder.sessionProgress183(
            invite, sipClient.username, sipClient.publicIp, sipClient.localPort,
            localRtpPort = localRtpPort,
            toTag = toTag,
            srtp = if (srtpActive) localSrtpKeys else null,
            srtpTag = srtpTag,
            negotiatedPayloadType = negotiatedPayloadType
        )
        sipClient.sendResponse(progress, responseTarget())

        state = State.RINGING
        Log.i(TAG, "Sent 183 Session Progress with SDP for call $callId (RTP port: $localRtpPort)")
        return true
    }

    /** Turn down an inbound INVITE we cannot bridge. */
    fun reject(code: Int, reason: String, toTag: String = localTag) {
        if (state == State.TERMINATED) return
        val invite = originalInvite ?: return

        val response = SipBuilder.reject(invite, code, reason, toTag)
        sipClient.sendResponse(response, responseTarget())

        state = State.TERMINATED
        Log.i(TAG, "Rejected inbound call $callId with $code $reason")
        listener?.onCallTerminated(this)
    }

    /** Send ACK for a received 200 OK */
    private fun sendAck(cseq: Int) {
        val uri = remoteContactUri ?: return
        val ack = SipBuilder.ack(
            uri, null, toHeader, fromHeader,
            callId, cseq,
            sipClient.username, sipClient.publicIp, sipClient.localPort
        )
        sipClient.sendResponse(ack, remoteContactAddress ?: sipClient.serverAddress)
        Log.d(TAG, "Sent ACK for call $callId (CSeq: $cseq)")
    }

    /**
     * Terminate the call using the SIP method appropriate to its state.
     *
     * Before 200 OK there is no established dialog, so BYE is invalid and
     * Asterisk ignores it while continuing to ring the Ring Group.  CANCEL
     * must reuse the exact INVITE Via branch and CSeq instead.
     */
    fun hangup() {
        if (state == State.TERMINATED) return

        if (state != State.ANSWERED) {
            val invite = originalInvite
            val inviteCseq = invite?.cseq
                ?.split(" ")?.firstOrNull()?.toIntOrNull()
            if (invite != null && invite.via != null && inviteCseq != null) {
                val cancel = SipBuilder.cancel(
                    invite.requestUri ?: "sip:${sipClient.serverDomain}:${sipClient.serverPort}",
                    invite.via,
                    invite.from ?: fromHeader,
                    invite.to ?: toHeader,
                    callId,
                    inviteCseq,
                    sipClient.username,
                    sipClient.publicIp,
                    sipClient.localPort
                )
                sipClient.sendResponse(cancel, remoteContactAddress ?: sipClient.serverAddress)
                state = State.TERMINATED
                Log.i(TAG, "Sent CANCEL for early call $callId (CSeq=$inviteCseq)")
                listener?.onCallTerminated(this)
                return
            }
            Log.w(TAG, "Cannot build CANCEL for early call $callId; INVITE headers unavailable")
            state = State.TERMINATED
            listener?.onCallTerminated(this)
            return
        }

        val uri = remoteContactUri ?: "sip:${sipClient.serverDomain}:${sipClient.serverPort}"

        // For an INVITE received by the gateway, the gateway is the UAS and
        // must send the in-dialog BYE with the original To header as From and
        // the original From header as To.  Outbound calls use the headers in
        // their stored order.  Using one order for both directions produces
        // a syntactically valid BYE that Asterisk rejects with 481.
        val byeFrom = if (direction == Direction.INBOUND) toHeader else fromHeader
        val byeTo = if (direction == Direction.INBOUND) fromHeader else toHeader

        val bye = SipBuilder.bye(
            uri, byeFrom, byeTo,
            callId, localCseq++,
            sipClient.username, sipClient.publicIp, sipClient.localPort
        )
        sipClient.sendResponse(bye, remoteContactAddress ?: sipClient.serverAddress)
        state = State.TERMINATED
        Log.i(TAG, "Sent BYE for call $callId")
        listener?.onCallTerminated(this)
    }

    companion object {
        private const val TAG = "SipCall"

        /**
         * Pick the address a response has to go to.
         *
         * RFC 3261 §8.1.3.3 sends a response to where the request came from.
         * The Contact header names the address for in-dialog requests, and a
         * server may send from a different host than the one it advertised
         * there — behind NAT, or with a stale `external_signaling_address`.
         * Answering to the Contact then delivers the answer to nobody, and
         * the caller waits out its own transaction timeout: the call rings
         * for ever and then drops.
         *
         * [source] is the source address of the request that started the
         * call, [contact] the address from its Contact header, [fallback]
         * where to go when neither is known.
         */
        fun responseTarget(
            source: Pair<String, Int>?,
            contact: Pair<String, Int>?,
            fallback: Pair<String, Int>,
        ): Pair<String, Int> = source ?: contact ?: fallback
    }
}
