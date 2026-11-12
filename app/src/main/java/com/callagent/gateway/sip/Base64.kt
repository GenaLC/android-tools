package com.callagent.gateway.sip

import java.io.ByteArrayOutputStream

/**
 * A base64 decoder that runs on every Android version the gateway supports.
 *
 * The two base64 decoders in the platform are both unusable here:
 * `java.util.Base64` arrived in Android 8 (API 26) and is missing on the
 * Android 7 handsets this build still targets, and `android.util.Base64` is a
 * stub in the JVM unit tests — calling it from a test throws "not mocked".
 *
 * The PEM parser has to work in both places at once, so it uses this instead.
 * The algorithm is the whole RFC 4648 section 6.3 decode loop: four
 * characters carry six bits each, and every eight bits that accumulate become
 * one output byte.
 */
internal object Base64 {

    /** The value of a table slot that holds a padding character. */
    private const val PAD = -2

    /**
     * The reverse alphabet: the index of a character is the six bits it
     * stands for.  `-1` marks a character that is not base64 at all.
     */
    private val TABLE: IntArray = IntArray(128) { -1 }.also { table ->
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        alphabet.forEachIndexed { index, c -> table[c.code] = index }
        table['='.code] = PAD
    }

    /**
     * Decode [text] into bytes.
     *
     * Whitespace between the characters is ignored, so a PEM body can be
     * handed over with its line breaks still in place.  A `=` ends the
     * data: everything after it is padding and is dropped.
     *
     * @throws IllegalArgumentException if the text holds a character that is
     *   not base64.
     */
    fun decode(text: String): ByteArray {
        val out = ByteArrayOutputStream(text.length * 3 / 4 + 3)
        var accumulator = 0
        var bits = 0
        for (c in text) {
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') continue
            val value = if (c.code < TABLE.size) TABLE[c.code] else -1
            if (value == PAD) break
            if (value < 0) throw IllegalArgumentException("not a base64 character: '$c'")
            accumulator = (accumulator shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((accumulator shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
