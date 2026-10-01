package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenCodecTest {
    @Test
    fun validLengthPrefixesBecomeAnnexBInPlace() {
        val first = byteArrayOf(0x40, 0x01)
        val second = byteArrayOf(0x42, 0x01, 0x02)
        val payload =
            byteArrayOf(0, 0, 0, first.size.toByte()) + first +
                byteArrayOf(0, 0, 0, second.size.toByte()) + second

        val converted = ScreenCodec.lengthPrefixedToAnnexB(payload)

        assertSame(payload, converted)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1) + first +
                byteArrayOf(0, 0, 0, 1) + second,
            converted,
        )
    }

    @Test
    fun malformedLengthsRejectTheAccessUnitWithoutMutatingInput() {
        val payload = byteArrayOf(0, 0, 0, 5, 0x40, 0x01)
        val original = payload.copyOf()

        assertEquals(0, ScreenCodec.lengthPrefixedToAnnexB(payload).size)
        assertArrayEquals(original, payload)
    }

    @Test
    fun damagedLaterNalDoesNotMutateEarlierValidPrefixes() {
        val validNal = byteArrayOf(0, 0, 0, 2, 0x41, 1)
        val damagedTails = listOf(
            byteArrayOf(0, 0, 0, 5, 0x40, 1), // Length exceeds the remaining payload.
            byteArrayOf(0, 0, 0, 0), // Empty NAL.
            byteArrayOf(0, 0, 0), // Incomplete prefix.
            byteArrayOf(0x80.toByte(), 0, 0, 0), // Unsigned length exceeds Int.MAX_VALUE.
            byteArrayOf(0x7f, -1, -1, -1), // Large positive length must not overflow bounds.
        )
        for (tail in damagedTails) {
            val payload = validNal + tail
            val original = payload.copyOf()

            assertEquals(0, ScreenCodec.lengthPrefixedToAnnexB(payload).size)
            assertArrayEquals(original, payload)
        }
    }

    @Test
    fun multiByteNalLengthIsRewrittenWithoutChangingNalBytes() {
        val nal = ByteArray(0x010203) { (it % 251).toByte() }
        val payload = byteArrayOf(0, 1, 2, 3) + nal

        val converted = ScreenCodec.lengthPrefixedToAnnexB(payload)

        assertSame(payload, converted)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + nal, converted)
    }

    @Test
    fun negotiatedLengthSizesAreHonored() {
        for (lengthSize in listOf(1, 2, 4)) {
            val nal = byteArrayOf(0x26, 1, 0x80.toByte())
            val prefix = ByteArray(lengthSize).apply { this[lastIndex] = nal.size.toByte() }
            assertArrayEquals(byteArrayOf(0, 0, 0, 1) + nal,
                ScreenCodec.lengthPrefixedToAnnexB(prefix + nal, lengthSize))
        }
    }

    @Test
    fun oneByteAvcNalIsNotMistakenForAnAnnexBStartCode() {
        val payload = byteArrayOf(0, 0, 0, 1, 9, 0, 0, 0, 2, 0x65, 1)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 9, 0, 0, 0, 1, 0x65, 1),
            ScreenCodec.lengthPrefixedToAnnexB(payload))
    }
}
