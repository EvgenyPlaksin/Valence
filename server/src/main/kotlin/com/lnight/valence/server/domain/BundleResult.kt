package com.lnight.valence.server.domain

sealed interface BundleResult {
    data class Found(
        val bundle: PreKeyBundle,
    ) : BundleResult

    data object Unauthorized : BundleResult

    data class RateLimited(
        val retryAfterMillis: Long,
    ) : BundleResult
}
