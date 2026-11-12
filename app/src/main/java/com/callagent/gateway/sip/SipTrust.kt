package com.callagent.gateway.sip

import java.io.ByteArrayInputStream
import java.security.cert.CertPathValidator
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The trust configuration used for SIP over TLS.
 *
 * The platform trust store is always consulted.  The certificates the user
 * imported are consulted *in addition* to it, never instead of it: an
 * imported CA is a way to trust a private PBX, not a way to stop trusting
 * the world's CAs.  Hostname verification is not touched here at all — it
 * lives in the transport, and nothing in this class can weaken it.
 *
 * The class is deliberately free of Android dependencies so the interesting
 * parts — PEM parsing and the composite trust decision — can be tested on a
 * plain JVM.
 */
class SipTrust private constructor(
    private val platform: X509TrustManager?,
    private val imported: List<X509Certificate>,
) {

    /** How many CAs the user imported.  Zero means "only the system store". */
    val importedCount: Int get() = imported.size

    /** True when the platform trust store was consulted for the last check. */
    var platformConsulted: Boolean = false
        private set

    val trustManager: X509TrustManager = object : X509TrustManager {

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
            // The gateway is never the server side of a TLS connection; a
            // client certificate check cannot be satisfied by anything we
            // know, and refusing is the only honest answer.
            throw CertificateException("this gateway does not accept client certificates")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            var platformError: Exception? = null
            if (platform != null) {
                // The platform store is consulted first, and always: an
                // imported CA adds to the trust, it never replaces it.
                platformConsulted = true
                try {
                    platform.checkServerTrusted(chain, authType)
                    return
                } catch (e: Exception) {
                    platformError = e
                }
            }
            if (imported.isNotEmpty() && validatesAgainstImported(chain)) return
            throw CertificateException(
                buildString {
                    append("the server's certificate is not trusted: it is not signed by a CA in the ")
                    append("system store")
                    if (imported.isEmpty()) {
                        append(" and no certificate authority has been imported in Settings")
                    } else {
                        append(" nor by the ").append(imported.size)
                        append(" imported certificate(s)")
                    }
                },
            ).apply { initCause(platformError) }
        }

        /**
         * Validate the chain against the imported certificates as anchors.
         *
         * The chain is validated with the standard PKIX machinery rather
         * than by comparing certificates: the server usually sends only its
         * own certificate, and the private CA that signed it is the anchor —
         * which means the path has to be built, not matched.
         */
        private fun validatesAgainstImported(chain: Array<out X509Certificate>): Boolean {
            if (chain.isEmpty()) return false
            return try {
                // CertPath.getInstance() is not part of Android's public API,
                // so the path is built through the certificate factory.
                val certificates: MutableList<Certificate> = chain.toMutableList()
                val path = CertificateFactory.getInstance("X.509")
                    .generateCertPath(certificates)
                val anchors: MutableSet<TrustAnchor> =
                    // Android's TrustAnchor takes the certificate and the
                    // optional name constraints — it has no third argument.
                    imported.map { TrustAnchor(it, null as ByteArray?) }.toMutableSet()
                val parameters = PKIXParameters(anchors)
                // Revocation needs the network and a CRL the private CA does
                // not publish; the chain is otherwise checked in full.
                parameters.setRevocationEnabled(false)
                CertPathValidator.getInstance("PKIX").validate(path, parameters)
                true
            } catch (e: Exception) {
                false
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            (platform?.acceptedIssuers ?: emptyArray()) + imported
    }

    /** A socket factory that trusts exactly this set of anchors. */
    fun socketFactory(): SSLSocketFactory {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustManager), null)
        // SSLContext.getSocketFactory() is declared to return SocketFactory;
        // with our trust manager it is always an SSLSocketFactory.
        return context.socketFactory as SSLSocketFactory
    }

    companion object {

        /**
         * Parse PEM text into certificates.  Accepts a file holding several
         * certificates, ignores anything that is not a certificate block, and
         * throws when no certificate could be read at all — a file that is
         * not a certificate is a user mistake worth reporting, not a silent
         * no-op.
         */
        fun parseCertificates(pem: ByteArray): List<X509Certificate> {
            val text = String(pem, Charsets.UTF_8)
            val factory = CertificateFactory.getInstance("X.509")
            val certificates = mutableListOf<X509Certificate>()
            val block = Regex(
                "-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----",
                RegexOption.DOT_MATCHES_ALL,
            )
            for (match in block.findAll(text)) {
                val body = match.groupValues[1].filterNot { it.isWhitespace() }
                if (body.isEmpty()) continue
                val der = try {
                    Base64.decode(body)
                } catch (e: IllegalArgumentException) {
                    throw CertificateException("the file contains a certificate block that is not valid base64")
                }
                certificates += factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            }
            if (certificates.isEmpty()) {
                throw CertificateException("the file contains no PEM certificate")
            }
            return certificates
        }

        /** The platform trust store on its own — what the gateway used before. */
        fun platformOnly(): SipTrust = SipTrust(platformTrustManager(), emptyList())

        /** The platform trust store plus the given imported certificates. */
        fun withImported(certificates: List<X509Certificate>): SipTrust =
            SipTrust(platformTrustManager(), certificates)

        /** Build from PEM text, as read from the imported file. */
        fun fromPem(pem: ByteArray): SipTrust = withImported(parseCertificates(pem))

        /** The platform's default trust manager, or null if the platform has none. */
        fun platformTrustManager(): X509TrustManager? {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            // A null KeyStore means the system CA store — the same anchors
            // every other app on the device trusts.
            factory.init(null as java.security.KeyStore?)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        }
    }
}
