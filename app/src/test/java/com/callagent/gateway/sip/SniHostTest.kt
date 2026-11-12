package com.callagent.gateway.sip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling an address from a name.
 *
 * SNI may only carry a name, so the gateway has to know which one it was
 * given before it builds the server_name list.
 */
class SniHostTest {

    @Test
    fun aNameIsNotAnAddress() {
        assertFalse(SniHost.isIpAddress("pbx.example.com"))
        assertFalse(SniHost.isIpAddress("callagent.pro"))
        assertFalse(SniHost.isIpAddress("my-pbx"))
        assertFalse(SniHost.isIpAddress("localhost"))
    }

    @Test
    fun anIpv4LiteralIsAnAddress() {
        assertTrue(SniHost.isIpAddress("192.168.1.10"))
        assertTrue(SniHost.isIpAddress("82.38.129.6"))
        assertTrue(SniHost.isIpAddress("127.0.0.1"))
    }

    @Test
    fun anIpv6LiteralIsAnAddress() {
        assertTrue(SniHost.isIpAddress("::1"))
        assertTrue(SniHost.isIpAddress("2001:db8::1"))
    }
}
