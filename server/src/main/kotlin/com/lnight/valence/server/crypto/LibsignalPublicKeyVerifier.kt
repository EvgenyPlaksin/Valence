package com.lnight.valence.server.crypto

import com.lnight.valence.server.domain.PublicKeyVerifier
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey

class LibsignalPublicKeyVerifier : PublicKeyVerifier {
    override fun isValidEcKey(bytes: ByteArray): Boolean = parseEcKey(bytes).isSuccess

    override fun isValidKemKey(bytes: ByteArray): Boolean = attempt { KEMPublicKey(bytes) }.isSuccess

    override fun verifySignature(
        signerKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean = parseEcKey(signerKey).map { it.verifySignature(message, signature) }.getOrDefault(false)

    private fun parseEcKey(bytes: ByteArray): Result<ECPublicKey> = attempt { ECPublicKey(bytes) }

    private fun <T> attempt(parse: () -> T): Result<T> =
        try {
            Result.success(parse())
        } catch (e: InvalidKeyException) {
            Result.failure(e)
        }
}
