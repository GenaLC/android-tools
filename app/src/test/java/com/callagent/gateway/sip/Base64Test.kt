package com.callagent.gateway.sip

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * The base64 decoder that reads PEM certificates.
 *
 * It exists because `java.util.Base64` needs Android 8 (API 26) and
 * `android.util.Base64` is a stub in the JVM unit tests, while the PEM
 * parser has to run in both places.  These tests hold it to the RFC 4648
 * test vectors and to what the JVM's own decoder produces, so the two
 * implementations cannot drift apart.
 */
class Base64Test {

    @Test
    fun decodesTheEmptyString() {
        assertEquals(0, Base64.decode("").size)
    }

    @Test
    fun decodesRfc4648TestVectors() {
        // RFC 4648 section 10.
        assertArrayEquals("".toByteArray(), Base64.decode(""))
        assertArrayEquals("f".toByteArray(), Base64.decode("Zg=="))
        assertArrayEquals("fo".toByteArray(), Base64.decode("Zm8="))
        assertArrayEquals("foo".toByteArray(), Base64.decode("Zm9v"))
        assertArrayEquals("foob".toByteArray(), Base64.decode("Zm9vYg=="))
        assertArrayEquals("fooba".toByteArray(), Base64.decode("Zm9vYmE="))
        assertArrayEquals("foobar".toByteArray(), Base64.decode("Zm9vYmFy"))
    }

    @Test
    fun decodesEveryByteValue() {
        val all = ByteArray(256) { it.toByte() }
        val encoded = java.util.Base64.getEncoder().encodeToString(all)
        assertArrayEquals(all, Base64.decode(encoded))
    }

    @Test
    fun ignoresWhitespaceBetweenCharacters() {
        assertArrayEquals("foobar".toByteArray(), Base64.decode("Zm9v\nYmFy"))
        assertArrayEquals("foobar".toByteArray(), Base64.decode("Zm9v YmFy"))
        assertArrayEquals("foobar".toByteArray(), Base64.decode("Zm9v\r\n\tYmFy"))
    }

    @Test
    fun stopsAtPadding() {
        assertArrayEquals("f".toByteArray(), Base64.decode("Zg=="))
        // Anything after the padding is dropped rather than decoded.
        assertArrayEquals("f".toByteArray(), Base64.decode("Zg==Zg=="))
    }

    @Test
    fun rejectsCharactersThatAreNotBase64() {
        try {
            Base64.decode("Zm9v*mFy")
            fail("expected the invalid character to be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun rejectsCharactersOutsideTheTable() {
        try {
            Base64.decode("Zm9v\u00ffmFy")
            fail("expected the invalid character to be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
