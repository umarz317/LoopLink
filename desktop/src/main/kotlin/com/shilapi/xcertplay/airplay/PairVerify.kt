package com.shilapi.xcertplay.airplay

/**
 * CarPlay pair-verify responder (accessory/server side).
 *
 * Uses ephemeral X25519 plus Ed25519 signatures against the controller's stored long-term key,
 * then derives the ChaCha20-Poly1305 control-channel keys.
 */
class PairVerify(
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
) {
    data class ControlKeys(val readKey: ByteArray, val writeKey: ByteArray)

    private var ephemeralPublicKey: ByteArray? = null
    private var clientEphemeralPublicKey: ByteArray? = null
    private var sharedSecret: ByteArray? = null
    private var encryptionKey: ByteArray? = null
    private var keys: ControlKeys? = null
    private var verified = false
    private var controllerId: String? = null

    val isVerified: Boolean get() = verified
    val verifiedControllerId: String? get() = controllerId
    val controlKeys: ControlKeys? get() = keys
    val shared: ByteArray? get() = sharedSecret

    fun handle(body: ByteArray): ByteArray {
        val tlv = Tlv8Codec.decode(body)
        val state = tlv[TYPE_STATE]?.firstOrNull()?.toInt()?.and(0xff)
        return try {
            when (state) {
                1 -> m2(tlv)
                3 -> m4(tlv)
                else -> err(state ?: 0)
            }
        } catch (_: Exception) {
            err(state ?: 0)
        }
    }

    private fun m2(tlv: Map<Int, ByteArray>): ByteArray {
        val clientEphemeralPublicKey = tlv[TYPE_PUBLIC_KEY] ?: return err(2)
        val pair = AirPlayCrypto.x25519Generate()
        ephemeralPublicKey = pair.publicKey
        this.clientEphemeralPublicKey = clientEphemeralPublicKey

        val shared = AirPlayCrypto.x25519Shared(pair.privateKey, clientEphemeralPublicKey)
        sharedSecret = shared
        val encryptionKey = AirPlayCrypto.hkdfSha512(shared, VERIFY_ENCRYPT_SALT.asciiBytes(), VERIFY_ENCRYPT_INFO.asciiBytes())
        this.encryptionKey = encryptionKey

        val accessoryId = identity.pairingId.toByteArray(Charsets.UTF_8)
        val signature = AirPlayCrypto.ed25519Sign(
            identity.privateKey,
            concatBytes(pair.publicKey, accessoryId, clientEphemeralPublicKey),
        )
        val sub = Tlv8Codec.encode(
            listOf(
                Tlv8Item(TYPE_IDENTIFIER, accessoryId),
                Tlv8Item(TYPE_SIGNATURE, signature),
            ),
        )
        val sealed = AirPlayCrypto.chachaSeal(encryptionKey, AirPlayCrypto.nonceLabel(MSG02), sub)
        return Tlv8Codec.encode(
            listOf(
                Tlv8Item(TYPE_STATE, byteArrayOf(2)),
                Tlv8Item(TYPE_PUBLIC_KEY, pair.publicKey),
                Tlv8Item(TYPE_ENCRYPTED_DATA, sealed),
            ),
        )
    }

    private fun m4(tlv: Map<Int, ByteArray>): ByteArray {
        val encrypted = tlv[TYPE_ENCRYPTED_DATA]
        val encryptionKey = this.encryptionKey
        val shared = sharedSecret
        val ownEphemeralPublicKey = ephemeralPublicKey
        val clientEphemeralPublicKey = this.clientEphemeralPublicKey
        if (
            encryptionKey == null || shared == null || ownEphemeralPublicKey == null ||
            clientEphemeralPublicKey == null || encrypted == null
        ) {
            return err(4)
        }

        val sub = Tlv8Codec.decode(AirPlayCrypto.chachaOpen(encryptionKey, AirPlayCrypto.nonceLabel(MSG03), encrypted))
        val controllerId = sub[TYPE_IDENTIFIER]
        val controllerSignature = sub[TYPE_SIGNATURE]
        if (controllerId == null || controllerSignature == null) return err(4)

        val controllerLtpk = pairings.get(controllerId.toString(Charsets.UTF_8)) ?: return err(4)
        val signatureData = concatBytes(clientEphemeralPublicKey, controllerId, ownEphemeralPublicKey)
        if (!AirPlayCrypto.ed25519Verify(controllerLtpk, signatureData, controllerSignature)) return err(4)

        val readKey = AirPlayCrypto.hkdfSha512(shared, CONTROL_SALT.asciiBytes(), CONTROL_WRITE_KEY_INFO.asciiBytes())
        val writeKey = AirPlayCrypto.hkdfSha512(shared, CONTROL_SALT.asciiBytes(), CONTROL_READ_KEY_INFO.asciiBytes())
        keys = ControlKeys(readKey, writeKey)
        verified = true
        this.controllerId = controllerId.toString(Charsets.UTF_8)
        return Tlv8Codec.encode(listOf(Tlv8Item(TYPE_STATE, byteArrayOf(4))))
    }

    private fun err(state: Int): ByteArray = Tlv8Codec.encode(
        listOf(
            Tlv8Item(TYPE_STATE, byteArrayOf(state.toByte())),
            Tlv8Item(TYPE_ERROR, byteArrayOf(ERROR_AUTHENTICATION.toByte())),
        ),
    )

    private companion object {
        const val TYPE_IDENTIFIER = 0x01
        const val TYPE_PUBLIC_KEY = 0x03
        const val TYPE_ENCRYPTED_DATA = 0x05
        const val TYPE_STATE = 0x06
        const val TYPE_ERROR = 0x07
        const val TYPE_SIGNATURE = 0x0a
        const val ERROR_AUTHENTICATION = 2
        const val VERIFY_ENCRYPT_SALT = "Pair-Verify-Encrypt-Salt"
        const val VERIFY_ENCRYPT_INFO = "Pair-Verify-Encrypt-Info"
        const val CONTROL_SALT = "Control-Salt"
        const val CONTROL_READ_KEY_INFO = "Control-Read-Encryption-Key"
        const val CONTROL_WRITE_KEY_INFO = "Control-Write-Encryption-Key"
        const val MSG02 = "PV-Msg02"
        const val MSG03 = "PV-Msg03"
    }
}
