package com.lnight.valence.protocol.frame

import kotlinx.serialization.Serializable

@Serializable
enum class ErrorCode {
    RATE_LIMITED,
    UNKNOWN,
}