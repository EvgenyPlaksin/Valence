package com.lnight.valence.server.domain

data class InboxPollResult(
    val generation: Long,
    val hasMessages: Boolean,
)

enum class ConfirmResult {
    CONFIRMED,
    INVALID,
    STALE,
}
