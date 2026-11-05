package com.lnight.valence.server.domain

sealed interface SendResult {
    data class Accepted(
        val serverTsMillis: Long,
    ) : SendResult

    data class Rejected(
        val reason: SendRejection,
    ) : SendResult
}

enum class SendRejection {
    UNAUTHORIZED,
    INVALID_REQUEST,
    TOO_LARGE,
    QUOTA_EXCEEDED,
}
