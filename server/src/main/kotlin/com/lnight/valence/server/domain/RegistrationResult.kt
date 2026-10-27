package com.lnight.valence.server.domain

sealed interface RegistrationResult {
    data class Registered(
        val mailboxId: String,
        val token: AccessToken,
    ) : RegistrationResult

    data class Rejected(
        val reason: RegistrationRejection,
    ) : RegistrationResult
}

enum class RegistrationRejection {
    MALFORMED,
    INVALID_CHALLENGE,
    INVALID_PROOF,
    INVALID_PREKEY_SIGNATURE,
    MAILBOX_EXISTS,
}
