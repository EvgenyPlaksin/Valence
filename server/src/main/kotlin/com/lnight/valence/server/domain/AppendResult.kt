package com.lnight.valence.server.domain

sealed interface AppendResult {
    data class Appended(
        val serverSeq: Long,
    ) : AppendResult

    data object QuotaExceeded : AppendResult

    data object Unauthorized : AppendResult

    data class RateLimited(
        val retryAfterMillis: Long,
    ) : AppendResult
}
