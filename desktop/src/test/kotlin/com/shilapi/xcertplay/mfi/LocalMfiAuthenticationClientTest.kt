package com.shilapi.xcertplay.mfi

import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.spec.ECGenParameterSpec
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalMfiAuthenticationClientTest {
    @get:Rule val temporary = TemporaryFolder()

    // Fresh self-signed test material for each test. No stored accessory identity.
    private fun identity(): File = temporary.newFolder().also(SyntheticMfiIdentity::write)

    @Test fun freshDigestsVerifyWithoutDoubleHashingAndRejectReplay() {
        val directory = identity()
        var count = 0
        val client = LocalMfiAuthenticationClient.load(directory) { count++ }
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificates(client.readCertificate().inputStream()).single()
        repeat(8) { index ->
            val digest = MessageDigest.getInstance("SHA-256").digest("challenge-$index".toByteArray())
            val signature = client.signChallenge(digest)
            assertEquals(64, signature.size)
            val der = DERSequence(arrayOf(
                ASN1Integer(BigInteger(1, signature.copyOfRange(0, 32))),
                ASN1Integer(BigInteger(1, signature.copyOfRange(32, 64))),
            )).encoded
            val verifier = Signature.getInstance("NONEwithECDSA")
            verifier.initVerify(certificate.publicKey)
            verifier.update(digest)
            assertTrue(verifier.verify(der))
            digest[0] = (digest[0].toInt() xor 1).toByte()
            verifier.initVerify(certificate.publicKey)
            verifier.update(digest)
            assertFalse(verifier.verify(der))
        }
        assertEquals(8, count)
        assertEquals(3, client.protocolMajor())
        assertEquals(MfiCertificateType.MFI, client.certificateType)
    }

    @Test fun rejectsDifferentPrivateKey() {
        val directory = identity()
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        File(directory, "identity.pk8").writeBytes(generator.generateKeyPair().private.encoded)
        assertThrows(IllegalArgumentException::class.java) { LocalMfiAuthenticationClient.load(directory) }
    }

    @Test fun rejectsIncompleteIdentityAndWrongChallengeLength() {
        assertThrows(Exception::class.java) { LocalMfiAuthenticationClient.load(temporary.newFolder()) }
        val client = LocalMfiAuthenticationClient.load(identity())
        for (length in listOf(0, 20, 31, 33, 64)) {
            assertThrows(IllegalArgumentException::class.java) { client.signChallenge(ByteArray(length)) }
        }
        assertThrows(IllegalArgumentException::class.java) { client.readCertificate(1) }
    }

    @Test fun certificateIsReturnedAsIndependentCopyAndSurvivesReload() {
        val directory = identity()
        val first = LocalMfiAuthenticationClient.load(directory)
        val certificate = first.readCertificate()
        val second = LocalMfiAuthenticationClient.load(directory)
        assertArrayEquals(certificate, second.readCertificate())
        certificate.fill(0)
        assertFalse(first.readCertificate().all { it == 0.toByte() })
        assertEquals(64, second.signChallenge(ByteArray(32) { it.toByte() }).size)
    }
}
