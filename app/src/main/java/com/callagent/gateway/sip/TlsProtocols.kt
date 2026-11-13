package com.callagent.gateway.sip

/**
 * The TLS versions the gateway is willing to speak.
 *
 * Android 15 forbids TLS 1.0 and 1.1 for applications that target it, and the
 * platform has been narrowing them down for years: a PBX that insists on TLS
 * 1.0 cannot be talked to from a modern phone at all.  The gateway therefore
 * never offers 1.0 or 1.1 — not as a policy choice, but because on the phones
 * this project targets those protocols are unavailable anyway.
 *
 * TLS 1.2 is the floor.  Every PBX that FreePBX 15/16 runs supports it, and
 * TLS 1.3 is offered on top wherever the device has it (Android 10 and newer).
 *
 * The class holds no Android dependencies, so the selection rule can be
 * tested on a plain JVM.
 */
object TlsProtocols {

    /** The protocols to offer, best first. */
    val preferred: List<String> = listOf("TLSv1.3", "TLSv1.2")

    /**
     * The subset of [supported] that the gateway accepts, in preference order.
     *
     * An empty result means the peer can offer nothing the gateway is able to
     * use — the caller reports that as a configuration problem rather than
     * letting the handshake fail with a message nobody can act on.
     */
    fun negotiable(supported: Array<String>?): List<String> {
        if (supported == null) return emptyList()
        val available = supported.toSet()
        return preferred.filter { available.contains(it) }
    }
}
