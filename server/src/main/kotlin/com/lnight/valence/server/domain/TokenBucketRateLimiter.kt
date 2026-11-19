package com.lnight.valence.server.domain

import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

private const val MILLIS_PER_SECOND = 1_000.0
private const val SWEEP_INTERVAL_MILLIS = 60_000L

class TokenBucketRateLimiter(
    private val clock: Clock,
) : RateLimiter {
    private val buckets = ConcurrentHashMap<String, Bucket>()

    @Volatile
    private var lastSweepMillis = clock.millis()

    internal val trackedKeys: Int get() = buckets.size

    override fun tryAcquire(
        key: String,
        policy: RateLimitPolicy,
    ): RateLimitDecision {
        val now = clock.millis()
        sweepIfDue(now)
        val bucket = buckets.computeIfAbsent(key) { Bucket(policy.burst.toDouble(), now) }
        return synchronized(bucket) { bucket.take(policy, now) }
    }

    private fun sweepIfDue(now: Long) {
        if (now - lastSweepMillis >= SWEEP_INTERVAL_MILLIS) {
            synchronized(this) {
                if (now - lastSweepMillis >= SWEEP_INTERVAL_MILLIS) {
                    lastSweepMillis = now
                    buckets.entries.removeIf { synchronized(it.value) { it.value.fullAtMillis <= now } }
                }
            }
        }
    }

    private class Bucket(
        var tokens: Double,
        var updatedMillis: Long,
    ) {
        var fullAtMillis = updatedMillis

        fun take(
            policy: RateLimitPolicy,
            now: Long,
        ): RateLimitDecision {
            val elapsedSeconds = maxOf(0L, now - updatedMillis) / MILLIS_PER_SECOND
            tokens = minOf(policy.burst.toDouble(), tokens + elapsedSeconds * policy.refillPerSecond)
            updatedMillis = now
            val decision =
                if (tokens >= 1.0) {
                    tokens -= 1.0
                    RateLimitDecision.Allowed
                } else {
                    RateLimitDecision.Limited(
                        ceil((1.0 - tokens) / policy.refillPerSecond * MILLIS_PER_SECOND).toLong(),
                    )
                }
            fullAtMillis = now + ceil((policy.burst - tokens) / policy.refillPerSecond * MILLIS_PER_SECOND).toLong()
            return decision
        }
    }
}
