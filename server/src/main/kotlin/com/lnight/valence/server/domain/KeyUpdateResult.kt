package com.lnight.valence.server.domain

sealed interface KeyUpdateResult {
    data class Updated(
        val oneTimeRemaining: Int,
    ) : KeyUpdateResult

    data class Rejected(
        val reason: KeyUpdateRejection,
    ) : KeyUpdateResult
}

enum class KeyUpdateRejection {
    MALFORMED,
    INVALID_SIGNATURE,
    ID_EXISTS,
    UNKNOWN_MAILBOX,
}
