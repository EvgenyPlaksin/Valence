package com.lnight.valence.server.domain

class KeyManagementService(
    private val keyVerifier: PublicKeyVerifier,
    private val store: MailboxStore,
) {
    private val validator = PreKeyValidator(keyVerifier)

    fun status(mailboxId: String): Int? = store.oneTimePreKeyCount(mailboxId)

    fun replaceSignedPreKeys(
        mailboxId: String,
        replacement: PreKeyReplacement,
    ): KeyUpdateResult {
        val mailbox = store.find(mailboxId) ?: return KeyUpdateResult.Rejected(KeyUpdateRejection.UNKNOWN_MAILBOX)
        val rejection = replacementRejection(mailbox, replacement)
        return if (rejection != null) {
            KeyUpdateResult.Rejected(rejection)
        } else {
            resultOf(
                mailboxId,
                store.replaceSignedPreKeys(mailboxId, replacement.signedPreKey, replacement.kyberPreKey),
            )
        }
    }

    fun uploadOneTimePreKeys(
        mailboxId: String,
        keys: List<OneTimePreKey>,
    ): KeyUpdateResult =
        if (validator.areValidOneTimePreKeys(keys)) {
            resultOf(mailboxId, store.addOneTimePreKeys(mailboxId, keys, MAX_STORED_ONE_TIME_KEYS))
        } else {
            KeyUpdateResult.Rejected(KeyUpdateRejection.MALFORMED)
        }

    private fun replacementRejection(
        mailbox: Mailbox,
        replacement: PreKeyReplacement,
    ): KeyUpdateRejection? =
        when {
            !validator.isValidSignedPreKey(replacement.signedPreKey) ||
                !validator.isValidKyberPreKey(replacement.kyberPreKey) -> KeyUpdateRejection.MALFORMED

            !isSignedBy(mailbox.identityKey, replacement.signedPreKey) ||
                !isSignedBy(mailbox.identityKey, replacement.kyberPreKey) -> KeyUpdateRejection.INVALID_SIGNATURE

            else -> null
        }

    private fun isSignedBy(
        identityKey: ByteArray,
        key: SignedPublicKey,
    ): Boolean = keyVerifier.verifySignature(identityKey, key.publicKey, key.signature)

    private fun resultOf(
        mailboxId: String,
        outcome: KeyStoreOutcome,
    ): KeyUpdateResult =
        when (outcome) {
            KeyStoreOutcome.UPDATED -> KeyUpdateResult.Updated(store.oneTimePreKeyCount(mailboxId) ?: 0)
            KeyStoreOutcome.ID_EXISTS -> KeyUpdateResult.Rejected(KeyUpdateRejection.ID_EXISTS)
            KeyStoreOutcome.LIMIT_EXCEEDED -> KeyUpdateResult.Rejected(KeyUpdateRejection.MALFORMED)
            KeyStoreOutcome.UNKNOWN_MAILBOX -> KeyUpdateResult.Rejected(KeyUpdateRejection.UNKNOWN_MAILBOX)
        }

    private companion object {
        const val MAX_STORED_ONE_TIME_KEYS = 200
    }
}
