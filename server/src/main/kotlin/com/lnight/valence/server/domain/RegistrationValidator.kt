package com.lnight.valence.server.domain

class RegistrationValidator(
    private val keyVerifier: PublicKeyVerifier,
) {
    fun isWellFormed(registration: Registration): Boolean = hasValidFields(registration) && hasValidKeys(registration)

    private fun hasValidFields(registration: Registration): Boolean =
        MAILBOX_ID.matches(registration.mailboxId) &&
            registration.deliveryKeyHash.size == HASH_SIZE &&
            registration.proof.size == SIGNATURE_SIZE

    private fun hasValidKeys(registration: Registration): Boolean =
        keyVerifier.isValidEcKey(registration.identityKey) && hasValidPreKeys(registration)

    private fun hasValidPreKeys(registration: Registration): Boolean =
        isValidSigned(registration.signedPreKey, keyVerifier::isValidEcKey) &&
            isValidSigned(registration.kyberPreKey, keyVerifier::isValidKemKey) &&
            hasValidOneTimeKeys(registration.oneTimePreKeys)

    private fun isValidSigned(
        key: SignedPublicKey,
        isValidKey: (ByteArray) -> Boolean,
    ): Boolean = key.id >= MIN_ID && key.signature.size == SIGNATURE_SIZE && isValidKey(key.publicKey)

    private fun hasValidOneTimeKeys(oneTimeKeys: List<OneTimePreKey>): Boolean =
        oneTimeKeys.size in 1..MAX_ONE_TIME_KEYS &&
            oneTimeKeys.map { it.id }.toSet().size == oneTimeKeys.size &&
            oneTimeKeys.all { it.id >= MIN_ID && keyVerifier.isValidEcKey(it.publicKey) }

    private companion object {
        const val HASH_SIZE = 32
        const val SIGNATURE_SIZE = 64
        const val MIN_ID = 1
        const val MAX_ONE_TIME_KEYS = 100

        val MAILBOX_ID = Regex("""mbx_[a-z2-7]{26}""")
    }
}
