package com.callagent.gateway.rtp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PcmuCodecTest {

    /**
     * The ITU G.711 reference µ-law encoder (the algorithm pjmedia and
     * therefore Asterisk implement), kept here so the codec is checked
     * against something other than itself.
     */
    private fun referenceEncode(pcm: Int): Int {
        val segUend = intArrayOf(0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF, 0x1FFF)
        var v = pcm shr 2
        val mask: Int
        if (v < 0) {
            v = -v
            mask = 0x7F
        } else {
            mask = 0xFF
        }
        if (v > 8159) v = 8159
        v += 0x84 shr 2
        var seg = 8
        for (i in segUend.indices) {
            if (v <= segUend[i]) {
                seg = i
                break
            }
        }
        if (seg >= 8) return (0x7F xor mask) and 0xFF
        val uval = (seg shl 4) or ((v shr (seg + 1)) and 0x0F)
        return (uval xor mask) and 0xFF
    }

    @Test
    fun encoderMatchesItuReferenceForEverySample() {
        for (s in Short.MIN_VALUE..Short.MAX_VALUE) {
            val got = PcmuCodec.encodeSample(s.toShort()).toInt() and 0xFF
            assertEquals("sample $s", referenceEncode(s), got)
        }
    }

    /**
     * The code words the curve is known by.
     *
     * The two ends of the range are the ones that matter: 0xFF is silence
     * and 0x00/0x80 are the loudest samples the curve can carry.  A codec
     * that is off by a bit anywhere shows up here first.
     */
    @Test
    fun knownCodeWordsAreWhatTheCurveSays() {
        val vectors = mapOf(
            0 to 0xFF,        // silence
            1 to 0xFF,
            4 to 0xFE,
            100 to 0xF2,
            1000 to 0xCE,
            12345 to 0x97,
            32767 to 0x80,    // loudest positive
            -4 to 0x7E,
            -100 to 0x72,
            -1000 to 0x4E,
            -12345 to 0x17,
            -32768 to 0x00    // loudest negative
        )
        for ((pcm, expected) in vectors) {
            val got = PcmuCodec.encodeSample(pcm.toShort()).toInt() and 0xFF
            assertEquals("sample $pcm", expected, got)
        }
    }

    /**
     * Both zeroes decode to zero.
     *
     * 0xFF is the positive zero of the curve and 0x7F the negative one.
     * The negative zero is never produced by the encoder — the smallest
     * negative magnitude is quantised to 0x7E — but a peer may send it, and
     * it must not decode to a click.
     */
    @Test
    fun bothZeroesDecodeToSilence() {
        assertEquals(0, PcmuCodec.decodeSample(0xFF.toByte()).toInt())
        assertEquals(0, PcmuCodec.decodeSample(0x7F.toByte()).toInt())
    }

    @Test
    fun theExtremesDecodeToTheFullScale() {
        // The widest values the curve can carry: ±32124, not ±32767.  A
        // decoder that returns the full scale here is not the reference one.
        assertEquals(32124, PcmuCodec.decodeSample(0x80.toByte()).toInt())
        assertEquals(-32124, PcmuCodec.decodeSample(0x00.toByte()).toInt())
    }

    @Test
    fun roundTripErrorIsWithinOneStepEverywhere() {
        // The largest quantisation step is 1024 (segment 7), so the error of
        // a round trip can never exceed one step.
        for (s in Short.MIN_VALUE..Short.MAX_VALUE) {
            val back = PcmuCodec.decodeSample(PcmuCodec.encodeSample(s.toShort())).toInt()
            assertTrue("sample $s decoded to $back", abs(back - s) <= 1024)
        }
    }

    @Test
    fun roundTripErrorIsWithinHalfAStepInTheSpeechRange() {
        // Segment 6 carries the speech range, and its step is 512.
        for (s in -8000..8000) {
            val back = PcmuCodec.decodeSample(PcmuCodec.encodeSample(s.toShort())).toInt()
            assertTrue("sample $s decoded to $back", abs(back - s) <= 256)
        }
    }

    @Test
    fun silenceIsEncodedAsTheCurveSays() {
        // The byte the RTP session sends for silence has to be the one that
        // decodes back to silence, not just any quiet value.
        val silence = PcmuCodec.encodeSample(0)
        assertEquals(0xFF, silence.toInt() and 0xFF)
        assertEquals(0, PcmuCodec.decodeSample(silence).toInt())
    }

    @Test
    fun eightKilohertzBuffersHaveTheExpectedShape() {
        val pcm = ByteArray(160 * 2)   // 160 samples of 16-bit PCM
        val encoded = PcmuCodec.encode8k(pcm)
        assertEquals(160, encoded.size)
        assertEquals(320, PcmuCodec.decode8k(encoded).size)

        // And the 16 kHz paths: half the sample count in, twice as many out.
        val pcm16k = ByteArray(320 * 2)
        assertEquals(160, PcmuCodec.encode(pcm16k).size)
        assertEquals(640, PcmuCodec.decode(ByteArray(160)).size)
    }
}
