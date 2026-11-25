package com.lnight.valence.server.domain

sealed interface KeyUpdateResult {
    data class Updated(
        val oneTimeRemaining: Int,
        val oneTimeKyberRemaining: Int = 0,
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
