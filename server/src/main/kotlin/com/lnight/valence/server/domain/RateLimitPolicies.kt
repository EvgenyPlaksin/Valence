package com.lnight.valence.server.domain

data class RateLimitPolicies(
    val challengePerAddress: RateLimitPolicy = RateLimitPolicy(burst = 30, refillPerSecond = 5.0),
    val registerPerAddress: RateLimitPolicy = RateLimitPolicy(burst = 10, refillPerSecond = 0.033),
    val loginPerAddress: RateLimitPolicy = RateLimitPolicy(burst = 20, refillPerSecond = 1.0),
    val bundlePerAddress: RateLimitPolicy = RateLimitPolicy(burst = 30, refillPerSecond = 5.0),
    val bundlePerMailbox: RateLimitPolicy = RateLimitPolicy(burst = 10, refillPerSecond = 0.1),
    val sendPerAddress: RateLimitPolicy = RateLimitPolicy(burst = 120, refillPerSecond = 20.0),
    val sendPerMailbox: RateLimitPolicy = RateLimitPolicy(burst = 60, refillPerSecond = 20.0),
    val otherPerAddress: RateLimitPolicy = RateLimitPolicy(burst = 60, refillPerSecond = 10.0),
    val socketFrames: RateLimitPolicy = RateLimitPolicy(burst = 20, refillPerSecond = 10.0),
)
