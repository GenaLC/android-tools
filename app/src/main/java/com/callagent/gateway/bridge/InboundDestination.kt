package com.callagent.gateway.bridge

/**
 * Where an incoming GSM call is sent.
 *
 * The SIM card's own number is not a routing key.  It is the phone's number,
 * and a server that routes by it needs a dialplan entry for every line — one
 * more thing to write and to keep in step with the SIMs.  The gateway
 * therefore names the destination itself: the SIP number the server dials,
 * the way a GoIP box is told where to send the call.
 *
 * With the destination named by the gateway, one server rule serves every
 * line, and the CDR shows the number the call was actually placed to.
 */
object InboundDestination {

    /** What may be typed in: digits, with an optional leading '+'. */
    private val PATTERN = Regex("^\\+?[0-9]{1,20}$")

    /**
     * Whether the value can be a SIP destination at all.
     *
     * A destination is not a phone number: it is an extension on the server
     * (40181) or a number the server knows.  Letters and spaces have no place
     * in it, and a value that cannot be dialled would only produce a call
     * that rings nowhere.
     */
    fun isValid(value: String): Boolean = PATTERN.matches(value.trim())

    /**
     * The destination to use.
     *
     * The configured one wins when it is usable.  An unusable one falls back
     * to the SIM's own number, so a gateway with a typo in the field keeps
     * routing the way it did instead of dropping the call.
     */
    fun resolve(configured: String?, fallback: String?): String? {
        val c = configured?.trim().orEmpty()
        if (c.isNotEmpty() && isValid(c)) return c
        return fallback?.trim()?.takeIf { it.isNotEmpty() }
    }
}
