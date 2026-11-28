package com.callagent.gateway.ussd

/**
 * A USSD code, as it was typed and as it goes to the network.
 *
 * A code is not a phone number: it is a command for the network, written as
 * `*100#`.  The rules here exist so a typo is refused before it reaches the
 * modem, where it would come back as an unknown-code answer nobody can act
 * on — and so a number that was meant to be a number is never sent as a code.
 *
 * The logic is pure so it can be tested without a phone: the unit tests run
 * on the JVM, where the Android framework is not there at all.
 */
object UssdRequest {

    /**
     * The longest code the network carries.
     *
     * 3GPP TS 27.007 gives a USSD string 182 characters; a longer one is
     * dropped by the modem without an answer.
     */
    const val MAX_LENGTH = 182

    /** Why a code cannot be sent. */
    enum class Rejection {
        /** Nothing was typed. */
        EMPTY,

        /** The code does not start with '*' or '#'. */
        NO_PREFIX,

        /** Something that is not a digit, a '*' or a '#' is in the code. */
        UNSUPPORTED_CHARACTERS,

        /** No digits in it — a code has to say something. */
        NO_DIGITS,

        /** Longer than the network will carry. */
        TOO_LONG;

        /** What to tell the person who typed the code. */
        fun describe(): String = when (this) {
            EMPTY -> "no code was entered"
            NO_PREFIX -> "a code starts with * or #"
            UNSUPPORTED_CHARACTERS -> "a code holds digits, * and # only"
            NO_DIGITS -> "a code has to hold digits"
            TOO_LONG -> "a code holds at most $MAX_LENGTH characters"
        }
    }

    /** The code as it goes to the modem, or the reason it does not go. */
    sealed class Prepared {
        /** The code, cleaned up and ready. */
        data class Ready(val code: String) : Prepared()

        /** The code cannot be sent, and this is why. */
        data class Refused(val reason: Rejection) : Prepared()
    }

    /** What a code may contain: digits, and the two marks that frame it. */
    private val ALLOWED = Regex("^[0-9*#]+$")

    /**
     * The code without the spaces around it and inside it.
     *
     * A code is typed with the fingers spread out as often as not — "* 100 #"
     * is one code, not three — and the network is given the code without
     * spaces.
     */
    fun normalize(raw: String): String = raw.filterNot { it.isWhitespace() }

    /**
     * The code as it will be sent, or the reason it will not be.
     *
     * The prefix is judged before the characters, so a number that was meant
     * to be a number is told it is not a code, rather than being complained
     * about for its plus sign.
     */
    fun prepare(raw: String): Prepared {
        val code = normalize(raw)
        if (code.isEmpty()) return Prepared.Refused(Rejection.EMPTY)
        if (code.length > MAX_LENGTH) return Prepared.Refused(Rejection.TOO_LONG)
        if (code[0] != '*' && code[0] != '#') return Prepared.Refused(Rejection.NO_PREFIX)
        if (!ALLOWED.matches(code)) return Prepared.Refused(Rejection.UNSUPPORTED_CHARACTERS)
        if (!code.any { it.isDigit() }) return Prepared.Refused(Rejection.NO_DIGITS)
        // A USSD session is closed by the hash.  Without it the network keeps
        // the session open, waiting for more digits, and the answer never
        // comes — the request would simply hang.
        val terminated = if (code.endsWith("#")) code else "$code#"
        if (terminated.length > MAX_LENGTH) return Prepared.Refused(Rejection.TOO_LONG)
        return Prepared.Ready(terminated)
    }
}
