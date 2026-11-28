package com.callagent.gateway.ussd

/**
 * Why a USSD request came back without an answer.
 *
 * The platform reports failures as numbers that mean nothing to a person, and
 * the codes it defines have very different causes: one says the system
 * refused the request, one says the telephony service was not there to take
 * it, one says the network itself would not answer.
 *
 * The numbers stay here rather than in the platform's constants: one of them
 * was only added in Android 17, which is newer than the SDK this app
 * compiles against, and a name that does not exist yet cannot be named.  The
 * app's own codes — a request that never left the handset — live in the same
 * space, so that everything that can go wrong is one thing to handle.
 */
enum class UssdFailure {

    /** The system refused the request. */
    NOT_ALLOWED,

    /** The telephony service was not there to take the request. */
    SERVICE_UNAVAILABLE,

    /** The network did not complete the request. */
    NETWORK_FAILED,

    /** The permission the platform asks for is missing. */
    NOT_PERMITTED,

    /** The request could not be made at all. */
    NOT_SENT,

    /** Anything else the platform reported. */
    UNKNOWN;

    /** What to tell the person waiting for the answer. */
    fun describe(): String = when (this) {
        NOT_ALLOWED -> "not allowed — the system refused the request"
        SERVICE_UNAVAILABLE -> "the telephony service was unavailable"
        NETWORK_FAILED -> "the network did not answer the request"
        NOT_PERMITTED -> "the permission to send USSD is missing"
        NOT_SENT -> "the request could not be sent"
        UNKNOWN -> "the request failed"
    }

    companion object {
        /** The platform's code for a request the network would not complete. */
        const val CODE_NETWORK_FAILED = -1

        /** The platform's code for a request the telephony service could not take. */
        const val CODE_SERVICE_UNAVAILABLE = -2

        /** The platform's code for a request the system refused (Android 17). */
        const val CODE_NOT_ALLOWED = -3

        /** The app's own code: the permission the platform asks for is missing. */
        const val CODE_NOT_PERMITTED = -100

        /** The app's own code: the request never left the handset. */
        const val CODE_NOT_SENT = -101

        /** Which failure a code stands for. */
        fun fromCode(code: Int): UssdFailure = when (code) {
            CODE_NETWORK_FAILED -> NETWORK_FAILED
            CODE_SERVICE_UNAVAILABLE -> SERVICE_UNAVAILABLE
            CODE_NOT_ALLOWED -> NOT_ALLOWED
            CODE_NOT_PERMITTED -> NOT_PERMITTED
            CODE_NOT_SENT -> NOT_SENT
            else -> UNKNOWN
        }
    }
}
