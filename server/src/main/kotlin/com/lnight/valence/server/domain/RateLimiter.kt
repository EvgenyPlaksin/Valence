package com.lnight.valence.server.domain

fun interface RateLimiter {
    fun tryAcquire(
        key: String,
        policy: RateLimitPolicy,
    ): RateLimitDecision
}
