package com.lnight.valence.server.domain

import com.lnight.valence.protocol.SignedMessages
import java.time.Clock

class RegistrationService(
    private val challenges: ChallengeService,
    private val keyVerifier: PublicKeyVerifier,
    private val store: MailboxStore,
    private val tokens: AccessTokenIssuer,
    private val clock: Clock,
) {
    private val validator = RegistrationValidator(keyVerifier)

    fun register(registration: Registration): RegistrationResult {
        val rejection =
            challengeRejection(registration) ?: structureRejection(registration) ?: signatureRejection(registration)
        return if (rejection != null) RegistrationResult.Rejected(rejection) else persist(registration)
    }

    private fun persist(registration: Registration): RegistrationResult {
        val mailbox =
            Mailbox(
                id = registration.mailboxId,
                identityKey = registration.identityKey,
                signedPreKey = registration.signedPreKey,
                kyberPreKey = registration.kyberPreKey,
                deliveryKeyHash = registration.deliveryKeyHash,
                createdAtMillis = clock.millis(),
            )
        return if (store.create(mailbox, registration.oneTimePreKeys)) {
            RegistrationResult.Registered(mailbox.id, tokens.issue(mailbox.id))
        } else {
            RegistrationResult.Rejected(RegistrationRejection.MAILBOX_EXISTS)
        }
    }

    private fun challengeRejection(registration: Registration): RegistrationRejection? =
        when (challenges.verify(registration.challenge)) {
            ChallengeCheck.Valid -> null
            ChallengeCheck.Malformed -> RegistrationRejection.MALFORMED
            ChallengeCheck.Forged, ChallengeCheck.Expired -> RegistrationRejection.INVALID_CHALLENGE
        }

    private fun structureRejection(registration: Registration): RegistrationRejection? =
        if (validator.isWellFormed(registration)) null else RegistrationRejection.MALFORMED

    private fun signatureRejection(registration: Registration): RegistrationRejection? {
        val proofMessage =
            SignedMessages.register(
                challenge = registration.challenge,
                mailboxId = registration.mailboxId,
                identityKey = registration.identityKey,
                deliveryKeyHash = registration.deliveryKeyHash,
            )
        return when {
            !keyVerifier.verifySignature(registration.identityKey, proofMessage, registration.proof) -> {
                RegistrationRejection.INVALID_PROOF
            }

            !hasValidPreKeySignatures(registration) -> {
                RegistrationRejection.INVALID_PREKEY_SIGNATURE
            }

            else -> {
                null
            }
        }
    }

    private fun hasValidPreKeySignatures(registration: Registration): Boolean =
        isSignedBy(registration.identityKey, registration.signedPreKey) &&
            isSignedBy(registration.identityKey, registration.kyberPreKey)

    private fun isSignedBy(
        identityKey: ByteArray,
        key: SignedPublicKey,
    ): Boolean = keyVerifier.verifySignature(identityKey, key.publicKey, key.signature)
}
