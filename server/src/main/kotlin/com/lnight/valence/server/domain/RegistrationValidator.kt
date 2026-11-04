package com.lnight.valence.server.domain

class RegistrationValidator(
    private val keyVerifier: PublicKeyVerifier,
) {
    private val preKeys = PreKeyValidator(keyVerifier)

    fun isWellFormed(registration: Registration): Boolean =
        hasValidFields(registration) &&
            keyVerifier.isValidEcKey(registration.identityKey) &&
            hasValidPreKeys(registration)

    private fun hasValidFields(registration: Registration): Boolean =
        MailboxIdFormat.isValid(registration.mailboxId) &&
            registration.deliveryKeyHash.size == WireSizes.HASH &&
            registration.proof.size == WireSizes.SIGNATURE

    private fun hasValidPreKeys(registration: Registration): Boolean =
        preKeys.isValidSignedPreKey(registration.signedPreKey) &&
            preKeys.isValidKyberPreKey(registration.kyberPreKey) &&
            preKeys.areValidOneTimePreKeys(registration.oneTimePreKeys)
}
