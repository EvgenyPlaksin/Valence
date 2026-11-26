package com.lnight.valence.core.model

data class Outbox(
    val messageId: MessageId,
    val state: OutboxState,
    val attemptCount: Int,
    val nextAttemptAtMillis: Long,
    val expiresAtMillis: Long,
)
