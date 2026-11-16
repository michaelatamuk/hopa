package com.hopa.capabilities.backend

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.XECPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * X25519 in the two shapes the backend contract names: the raw 32-byte
 * big-endian u-coordinate for `publicKey`, and PKCS#8 for the stored
 * private half.
 */
object X25519Keys {
    fun generate(): KeyPair = KeyPairGenerator.getInstance("X25519").generateKeyPair()

    /** The u-coordinate as exactly 32 bytes, however BigInteger padded it. */
    fun publicKeyBytes(publicKey: PublicKey): ByteArray {
        val xec = publicKey as? XECPublicKey
            ?: throw IllegalArgumentException("not an X25519 public key")
        val encoded = xec.u.toByteArray()
        val start = maxOf(0, encoded.size - KEY_BYTES)
        val bytes = ByteArray(KEY_BYTES)
        encoded.copyInto(bytes, destinationOffset = KEY_BYTES - (encoded.size - start), startIndex = start)
        return bytes
    }

    fun privateKeyBase64(privateKey: PrivateKey): String =
        Base64.getEncoder().encodeToString(PKCS8EncodedKeySpec(privateKey.encoded).encoded)

    private const val KEY_BYTES = 32
}
