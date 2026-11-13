package com.callagent.gateway.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which TLS versions the gateway offers.
 *
 * The rule matters because Android 15 forbids TLS 1.0 and 1.1 for apps that
 * target it: a PBX transport pinned to one of those cannot be reached from the
 * phones this project runs on, and the failure has to be reported in words a
 * PBX administrator can act on.
 */
class TlsProtocolsTest {

    @Test
    fun offersTls13And12WhenTheDeviceHasThem() {
        val chosen = TlsProtocols.negotiable(arrayOf("TLSv1.2", "TLSv1.3"))
        assertEquals(listOf("TLSv1.3", "TLSv1.2"), chosen)
    }

    @Test
    fun offersTls12AloneOnOlderDevices() {
        assertEquals(listOf("TLSv1.2"), TlsProtocols.negotiable(arrayOf("TLSv1.2")))
    }

    @Test
    fun neverOffersTls10OrTls11() {
        val chosen = TlsProtocols.negotiable(
            arrayOf("SSLv3", "TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3")
        )
        assertEquals(listOf("TLSv1.3", "TLSv1.2"), chosen)
        assertTrue(chosen.none { it == "TLSv1" || it == "TLSv1.1" })
    }

    @Test
    fun reportsNothingUsableWhenOnlyAncientVersionsAreOffered() {
        // The shape of a PBX transport left at its default on older Asterisk:
        // only TLS 1.0 is on offer and the handshake can never succeed.
        assertTrue(TlsProtocols.negotiable(arrayOf("TLSv1", "SSLv3")).isEmpty())
    }

    @Test
    fun reportsNothingUsableWhenThePeerOffersNothing() {
        assertTrue(TlsProtocols.negotiable(arrayOf()).isEmpty())
        assertTrue(TlsProtocols.negotiable(null).isEmpty())
    }

    @Test
    fun keepsThePreferenceOrderRegardlessOfWhatThePeerListsFirst() {
        val chosen = TlsProtocols.negotiable(arrayOf("TLSv1.2", "TLSv1.3"))
        assertEquals(listOf("TLSv1.3", "TLSv1.2"), chosen)
    }
}
