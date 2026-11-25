package com.lnight.valence.server.domain

class PreKeyValidator(
    private val keyVerifier: PublicKeyVerifier,
) {
    fun isValidSignedPreKey(key: SignedPublicKey): Boolean = isValidSigned(key, keyVerifier::isValidEcKey)

    fun isValidKyberPreKey(key: SignedPublicKey): Boolean = isValidSigned(key, keyVerifier::isValidKemKey)

    fun areValidOneTimePreKeys(keys: List<OneTimePreKey>): Boolean =
        keys.size in 1..MAX_PER_REQUEST &&
            keys.map { it.id }.toSet().size == keys.size &&
            keys.all { it.id >= MIN_ID && keyVerifier.isValidEcKey(it.publicKey) }

    fun areValidOneTimeKyberPreKeys(
        keys: List<OneTimeKyberPreKey>,
        allowEmpty: Boolean = false,
    ): Boolean =
        (allowEmpty || keys.isNotEmpty()) &&
            keys.size <= MAX_PER_REQUEST &&
            keys.map { it.id }.toSet().size == keys.size &&
            keys.all {
                it.id >= MIN_ID &&
                    it.signature.size == WireSizes.SIGNATURE &&
                    keyVerifier.isValidKemKey(it.publicKey)
            }

    private fun isValidSigned(
        key: SignedPublicKey,
        isValidKey: (ByteArray) -> Boolean,
    ): Boolean = key.id >= MIN_ID && key.signature.size == WireSizes.SIGNATURE && isValidKey(key.publicKey)

    private companion object {
        const val MIN_ID = 1
        const val MAX_PER_REQUEST = 100
    }
}
