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
        (publicKey as? XECPublicKey)?.let { xec ->
            val encoded = xec.u.toByteArray()
            val start = maxOf(0, encoded.size - KEY_BYTES)
            val bytes = ByteArray(KEY_BYTES)
            encoded.copyInto(bytes, destinationOffset = KEY_BYTES - (encoded.size - start), startIndex = start)
            return bytes
        }
        // Android's provider hands back an opaque X.509 key, not an XECPublicKey.
        val encoded = publicKey.encoded
        if (encoded != null &&
            encoded.size == SPKI_HEADER.size + KEY_BYTES &&
            encoded.copyOfRange(0, SPKI_HEADER.size).contentEquals(SPKI_HEADER)
        ) {
            return encoded.copyOfRange(SPKI_HEADER.size, encoded.size)
        }
        throw IllegalArgumentException("not an X25519 public key")
    }

    fun privateKeyBase64(privateKey: PrivateKey): String =
        Base64.getEncoder().encodeToString(PKCS8EncodedKeySpec(privateKey.encoded).encoded)

    private const val KEY_BYTES = 32

    /** DER SubjectPublicKeyInfo header for X25519 (OID 1.3.101.110), BIT STRING of 32 bytes. */
    private val SPKI_HEADER = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00,
    )
}
