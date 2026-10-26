package com.lnight.valence.server.crypto

import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibsignalPublicKeyVerifierTest {
    private val verifier = LibsignalPublicKeyVerifier()
    private val identity = ECKeyPair.generate()
    private val identityPublic = identity.publicKey.serialize()
    private val message = "message".toByteArray(Charsets.UTF_8)
    private val signature = identity.privateKey.calculateSignature(message)

    @Test
    fun `a valid signature verifies`() {
        assertTrue(verifier.verifySignature(identityPublic, message, signature))
    }

    @Test
    fun `a signature over a different message does not verify`() {
        assertFalse(verifier.verifySignature(identityPublic, "other".toByteArray(Charsets.UTF_8), signature))
    }

    @Test
    fun `a modified signature does not verify`() {
        val modified = signature.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }

        assertFalse(verifier.verifySignature(identityPublic, message, modified))
    }

    @Test
    fun `a signature of the wrong length does not verify`() {
        assertFalse(verifier.verifySignature(identityPublic, message, signature.copyOf(10)))
    }

    @Test
    fun `a signature from another key does not verify`() {
        val other = ECKeyPair.generate()

        assertFalse(verifier.verifySignature(other.publicKey.serialize(), message, signature))
    }

    @Test
    fun `a malformed signer key does not verify`() {
        assertFalse(verifier.verifySignature(ByteArray(5), message, signature))
        assertFalse(verifier.verifySignature(ByteArray(33), message, signature))
    }

    @Test
    fun `prekey signatures cover the serialised public key`() {
        val signedPreKey = ECKeyPair.generate().publicKey.serialize()
        val kyberPreKey = KEMKeyPair.generate(KEMKeyType.KYBER_1024).publicKey.serialize()

        assertTrue(
            verifier.verifySignature(
                identityPublic,
                signedPreKey,
                identity.privateKey.calculateSignature(signedPreKey),
            ),
        )
        assertTrue(
            verifier.verifySignature(identityPublic, kyberPreKey, identity.privateKey.calculateSignature(kyberPreKey)),
        )
    }

    @Test
    fun `generated keys are recognised and mixed up keys are rejected`() {
        val kyber = KEMKeyPair.generate(KEMKeyType.KYBER_1024).publicKey.serialize()

        assertTrue(verifier.isValidEcKey(identityPublic))
        assertTrue(verifier.isValidKemKey(kyber))
        assertFalse(verifier.isValidEcKey(kyber))
        assertFalse(verifier.isValidKemKey(identityPublic))
        assertFalse(verifier.isValidEcKey(ByteArray(0)))
    }
}
