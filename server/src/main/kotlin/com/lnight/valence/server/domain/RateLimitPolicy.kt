package com.lnight.valence.server.domain

data class RateLimitPolicy(
    val burst: Int,
    val refillPerSecond: Double,
) {
    init {
        require(burst >= 1) { "The burst must be at least 1" }
        require(refillPerSecond > 0.0) { "The refill rate must be positive" }
    }
}
