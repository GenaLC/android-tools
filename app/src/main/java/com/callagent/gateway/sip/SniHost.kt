package com.callagent.gateway.sip

/**
 * Whether a host string is an IP literal rather than a name.
 *
 * SNI carries a hostname and nothing else: RFC 6066 says the server_name
 * must not be an IP literal, and a server that receives one either ignores
 * the extension or answers with whichever certificate it considers the
 * default — which is then refused for the wrong reason.
 *
 * When the PBX is reached by its address, the certificate is matched by its
 * iPAddress SAN instead, which the endpoint identification algorithm does on
 * its own.  So the address needs no server_name at all.
 *
 * This is a plain text check and not a name lookup on purpose: resolving a
 * name would cost a DNS round trip on the path that opens the socket, and
 * the answer would only be used to decide whether to send an extension.
 */
internal object SniHost {

    /** A dotted-quad IPv4 literal. */
    private val IPV4 = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")

    /**
     * True when [host] is an IPv4 or IPv6 literal.
     *
     * A hostname never contains a colon, so the colon test covers IPv6
     * without having to parse it.
     */
    fun isIpAddress(host: String): Boolean =
        IPV4.matches(host) || host.contains(':')
}
