package com.callagent.gateway.bridge

/**
 * Which number an incoming INVITE asks the gateway to dial.
 *
 * The order is deliberate:
 *
 *  1. `X-GSM-Forward` — our own header, put there by the server's dialplan.
 *     When it is there, nothing else is consulted.
 *  2. the user part of the Request-URI;
 *  3. the user part of the To header.
 *
 * Both URI places are needed, because res_pjsip builds the Request-URI from
 * the endpoint's AOR and puts the dialled part into the To header:
 * `Dial(PJSIP/1001/+4915112345678)` reaches us as Request-URI `sip:1001@pbx`
 * and To `sip:+4915112345678@pbx`.  Which of the two carries the number
 * depends on the dial string the server used, so the number is taken from
 * whichever has it.
 *
 * Two things must never be treated as a destination:
 *
 *  - the account name — the server addresses the gateway by it whenever it
 *    calls the peer rather than a number;
 *  - our own MSISDN — the server routes the SIM's own DID back to us, and
 *    dialling it would be a loop.
 */
object CallDestination {

    /** A number shorter than this is not a phone number. */
    const val MIN_DIALABLE_DIGITS = 3

    /**
     * @param xGsmForward value of the X-GSM-Forward header, if any
     * @param requestUri  the Request-URI of the INVITE, if any
     * @param toHeader    the To header of the INVITE, if any
     * @param accountName our own SIP account name ("1001")
     * @param ownNumbers  our own numbers (the SIM's MSISDN in E.164), in any
     *                    form — only the digits are compared
     * @return the number to dial, or null when the INVITE names none
     */
    fun resolve(
        xGsmForward: String?,
        requestUri: String?,
        toHeader: String?,
        accountName: String,
        ownNumbers: Collection<String>,
    ): String? {
        xGsmForward?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        for (uri in listOfNotNull(requestUri, toHeader)) {
            val user = extractUser(uri)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (user.equals(accountName, ignoreCase = true)) continue
            if (ownNumbers.any { digitsOf(it) == digitsOf(user) }) continue
            if (!looksDialable(user)) continue
            return user
        }
        return null
    }

    /** Extract SIP URI user part from a header value like <sip:user@host>. */
    fun extractUser(headerValue: String): String? {
        val sipIdx = headerValue.indexOf("sip:")
        if (sipIdx < 0) return null
        val atIdx = headerValue.indexOf('@', sipIdx)
        if (atIdx < 0) return null
        return headerValue.substring(sipIdx + 4, atIdx)
    }

    /**
     * Strict on purpose: a URI user is a number only when that is all it is.
     * An account name made of digits is ruled out by the caller; one with a
     * letter in it was never a number to begin with.
     */
    fun looksDialable(user: String): Boolean =
        user.all { it.isDigit() || it in "+-.()" } &&
            digitsOf(user).length >= MIN_DIALABLE_DIGITS

    fun digitsOf(value: String): String = value.filter { it.isDigit() }
}
