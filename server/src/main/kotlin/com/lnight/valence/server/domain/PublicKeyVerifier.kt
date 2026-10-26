package com.lnight.valence.server.domain

interface PublicKeyVerifier {
    fun isValidEcKey(bytes: ByteArray): Boolean

    fun isValidKemKey(bytes: ByteArray): Boolean

    fun verifySignature(
        signerKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean
}
