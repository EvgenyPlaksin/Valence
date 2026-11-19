package com.lnight.valence.server.domain

sealed interface RateLimitDecision {
    data object Allowed : RateLimitDecision

    data class Limited(
        val retryAfterMillis: Long,
    ) : RateLimitDecision
}
