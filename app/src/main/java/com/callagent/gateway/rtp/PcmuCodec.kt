package com.callagent.gateway.rtp

/**
 * G.711 µ-law (PCMU) codec — payload type 0.
 *
 * The SDP builder has always advertised `a=rtpmap:0 PCMU/8000` and listed PT 0
 * among the offered payloads, but nothing implemented it: the RTP encode and
 * decode dispatchers fell through to the G.722 encoder for any payload type
 * they did not recognise, so a peer that picked PT 0 received G.722 bytes
 * tagged as µ-law and both ends heard noise.
 *
 * Narrowband (8 kHz), 8 bits per sample — the same wire format as A-law but a
 * different curve: µ-law is sign-and-magnitude with the code word
 * complemented, while A-law uses a different segment table and inversion.
 *
 * This is the ITU-T G.711 reference algorithm, which is also what pjmedia —
 * and therefore Asterisk — implements, so what the server sends decodes
 * bit-exactly rather than approximately.
 */
object PcmuCodec {

    /**
     * Segment boundaries for the biased magnitude, ITU-T G.711.
     *
     * The magnitude is carried in quarter-units of a 16-bit sample (the
     * encoder shifts right by two), so the largest value a segment can hold
     * is four times its step.
     */
    private val SEG_UEND = intArrayOf(
        0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF, 0x1FFF
    )

    /** Bias added to the magnitude before the segment is chosen (132 / 4). */
    private const val BIAS_SHIFTED = 0x84 shr 2

    /** The bias itself, in 16-bit sample units — the decoder works in them. */
    private const val BIAS = 0x84

    /** Largest magnitude the companding curve can express, in 1/4 units. */
    private const val MAX_MAGNITUDE = 8159

    /**
     * Encode one 16-bit PCM sample to a µ-law byte.
     *
     * The curve is a sign bit, a three-bit segment and four bits of
     * quantisation, all complemented on the wire: 0xFF is digital silence and
     * 0x7F is the negative zero the curve can represent but the encoder never
     * produces (the smallest negative magnitude is quantised to 0x7E).
     */
    fun encodeSample(pcm: Short): Byte {
        // Shift down to the 14-bit magnitude the curve is defined over.  The
        // arithmetic shift floors negative values, which is what the
        // reference does.
        var magnitude = pcm.toInt() shr 2
        val mask: Int
        if (magnitude < 0) {
            magnitude = -magnitude
            mask = 0x7F
        } else {
            mask = 0xFF
        }
        if (magnitude > MAX_MAGNITUDE) magnitude = MAX_MAGNITUDE
        magnitude += BIAS_SHIFTED

        var segment = SEG_UEND.size
        for (i in SEG_UEND.indices) {
            if (magnitude <= SEG_UEND[i]) {
                segment = i
                break
            }
        }
        // Out of range: the largest magnitude the curve can carry.  Only
        // reachable after the clip above, and only for the extreme samples.
        if (segment >= SEG_UEND.size) return (0x7F xor mask).toByte()

        val code = (segment shl 4) or ((magnitude shr (segment + 1)) and 0x0F)
        return (code xor mask).toByte()
    }

    /**
     * Decode one µ-law byte to a 16-bit PCM sample.
     *
     * The reconstruction adds the bias back at the segment's scale, so the
     * decoded value sits at the centre of its quantisation interval — the
     * same value pjmedia produces, which is what keeps the two ends in step.
     */
    fun decodeSample(ulaw: Byte): Short {
        val code = (ulaw.toInt() and 0xFF).inv() and 0xFF
        var value = ((code and 0x0F) shl 3) + BIAS
        value = value shl ((code and 0x70) shr 4)
        return if (code and 0x80 != 0) (BIAS - value).toShort() else (value - BIAS).toShort()
    }

    /** Encode 8kHz PCM16 to µ-law directly (no sample rate conversion). */
    fun encode8k(pcm: ByteArray): ByteArray {
        val sampleCount = pcm.size / 2
        val out = ByteArray(sampleCount)
        for (i in 0 until sampleCount) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort()
            out[i] = encodeSample(sample)
        }
        return out
    }

    /** Encode 16kHz PCM16 to µ-law with 2:1 averaging downsample.
     *  Averages each pair of adjacent samples before encoding, acting as
     *  a simple low-pass filter that prevents aliasing artifacts from
     *  high frequencies folding back into the 4kHz output band. */
    fun encode(pcm: ByteArray): ByteArray {
        val sampleCount = pcm.size / 2
        val outSize = sampleCount / 2
        val out = ByteArray(outSize)
        var outIdx = 0
        for (i in 0 until sampleCount step 2) {
            val lo0 = pcm[i * 2].toInt() and 0xFF
            val hi0 = pcm[i * 2 + 1].toInt()
            val s0 = (hi0 shl 8) or lo0
            val s1 = if (i + 1 < sampleCount) {
                val lo1 = pcm[(i + 1) * 2].toInt() and 0xFF
                val hi1 = pcm[(i + 1) * 2 + 1].toInt()
                (hi1 shl 8) or lo1
            } else s0
            val avg = ((s0 + s1) / 2).toShort()
            out[outIdx++] = encodeSample(avg)
            if (outIdx >= outSize) break
        }
        return out
    }

    /** Decode µ-law to 8kHz PCM16 directly (no sample rate conversion). */
    fun decode8k(ulaw: ByteArray): ByteArray {
        val out = ByteArray(ulaw.size * 2)
        var outIdx = 0
        for (byte in ulaw) {
            val sample = decodeSample(byte)
            out[outIdx++] = (sample.toInt() and 0xFF).toByte()
            out[outIdx++] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Decode µ-law to 16kHz PCM16 with 1:2 linear-interpolation upsampling.
     *  Each input sample produces two output samples: the original and a
     *  midpoint interpolated toward the next sample. */
    fun decode(ulaw: ByteArray): ByteArray {
        val out = ByteArray(ulaw.size * 4)
        var outIdx = 0
        for (i in ulaw.indices) {
            val s0 = decodeSample(ulaw[i]).toInt()
            val s1 = if (i + 1 < ulaw.size) decodeSample(ulaw[i + 1]).toInt() else s0
            val mid = (s0 + s1) / 2
            out[outIdx++] = (s0 and 0xFF).toByte()
            out[outIdx++] = (s0 shr 8).toByte()
            out[outIdx++] = (mid and 0xFF).toByte()
            out[outIdx++] = (mid shr 8).toByte()
        }
        return out
    }
}
