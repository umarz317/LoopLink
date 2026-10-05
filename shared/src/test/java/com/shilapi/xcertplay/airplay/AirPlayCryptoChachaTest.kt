package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import kotlin.random.Random

class AirPlayCryptoChachaTest {
    private val random = Random(7)
    private fun bytes(size: Int) = random.nextBytes(size)

    @Test fun rfc8439VectorSealsTheSame() {
        val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val plaintext = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip " +
            "for the future, sunscreen would be it.").toByteArray(Charsets.US_ASCII)
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, plaintext, aad)
        assertEquals("1ae10b594f09e26a7e902ecbd0600691", sealed.copyOfRange(sealed.size - 16, sealed.size).hex())
        assertArrayEquals(plaintext, AirPlayCrypto.chachaOpen(key, nonce, sealed, aad))
    }

    @Test fun platformAndBouncyCastleOpenEachOther() {
        assertNotEquals("BouncyCastle", AirPlayCrypto.chachaImplementation)
        // Growing and shrinking sizes reuse and enlarge the per-thread buffers.
        for (size in listOf(0, 1, 63, 64, 65, 1400, 500_000, 17, 300_000, 1_000_001, 0)) {
            val key = bytes(32)
            val nonce = AirPlayCrypto.nonce64(size.toLong())
            val aad = if (size % 2 == 0) ByteArray(0) else bytes(12)
            val plaintext = bytes(size)
            // Call the platform path itself, so a silent fallback to BouncyCastle fails here.
            val sealed = AirPlayCrypto.platformChacha(Cipher.ENCRYPT_MODE, key, nonce, plaintext, aad)
            assertNotNull("platform ChaCha20-Poly1305 refused to seal $size bytes", sealed)
            assertArrayEquals(AirPlayCrypto.chachaSealBouncyCastle(key, nonce, plaintext, aad), sealed)
            assertArrayEquals(plaintext, AirPlayCrypto.chachaOpenBouncyCastle(key, nonce, sealed!!, aad))
            val bouncySealed = AirPlayCrypto.chachaSealBouncyCastle(key, nonce, plaintext, aad)
            assertArrayEquals(plaintext, AirPlayCrypto.platformChacha(Cipher.DECRYPT_MODE, key, nonce, bouncySealed, aad))
            assertArrayEquals(plaintext, AirPlayCrypto.chachaOpen(key, nonce, sealed, aad))
        }
    }

    @Test fun aChangedByteOrAadFailsToOpen() {
        val key = bytes(32)
        val nonce = AirPlayCrypto.nonce64(1)
        val aad = bytes(8)
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, bytes(100), aad)
        val tampered = sealed.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertFails { AirPlayCrypto.chachaOpen(key, nonce, tampered, aad) }
        assertFails { AirPlayCrypto.chachaOpen(key, nonce, sealed, bytes(8)) }
        assertFails { AirPlayCrypto.platformChacha(Cipher.DECRYPT_MODE, key, nonce, tampered, aad) }
        // A failed tag must not leave the per-thread cipher unusable.
        assertArrayEquals(AirPlayCrypto.chachaOpenBouncyCastle(key, nonce, sealed, aad),
            AirPlayCrypto.chachaOpen(key, nonce, sealed, aad))
    }

    @Test fun sealingTheSameKeyAndNonceTwiceStillWorks() {
        val key = bytes(32)
        val nonce = AirPlayCrypto.nonceLabel("PV-Msg02")
        val first = AirPlayCrypto.chachaSeal(key, nonce, byteArrayOf(1, 2, 3))
        assertArrayEquals(first, AirPlayCrypto.chachaSeal(key, nonce, byteArrayOf(1, 2, 3)))
    }

    /** The platform cipher is present on the test JVM, so a bad tag must surface as its exception. */
    private fun assertFails(block: () -> Unit) {
        try {
            block()
        } catch (_: AEADBadTagException) {
            return
        }
        fail("expected the platform tag check to throw AEADBadTagException")
    }

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
