package com.lnight.valence.server.domain

sealed interface DeliveryAuthorizationResult {
    data object Authorized : DeliveryAuthorizationResult

    data object Unauthorized : DeliveryAuthorizationResult

    data class RateLimited(
        val retryAfterMillis: Long,
    ) : DeliveryAuthorizationResult
}
