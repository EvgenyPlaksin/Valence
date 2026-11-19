package com.lnight.valence.server

import com.lnight.valence.server.domain.RateLimitDecision
import com.lnight.valence.server.domain.RateLimitPolicy
import com.lnight.valence.server.domain.TokenBucketRateLimiter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TokenBucketRateLimiterTest {
    private val clock = TestClock(1_000_000L)
    private val limiter = TokenBucketRateLimiter(clock)
    private val policy = RateLimitPolicy(burst = 3, refillPerSecond = 1.0)

    private fun acquire(
        key: String = "a",
        using: RateLimitPolicy = policy,
    ) = limiter.tryAcquire(key, using)

    @Test
    fun `the burst is allowed and the next request is limited with a retry hint`() {
        repeat(3) { assertEquals(RateLimitDecision.Allowed, acquire()) }

        assertEquals(RateLimitDecision.Limited(1_000L), acquire())
    }

    @Test
    fun `tokens refill over time but never beyond the burst`() {
        repeat(3) { acquire() }

        clock.advance(1_000L)
        assertEquals(RateLimitDecision.Allowed, acquire())
        assertEquals(RateLimitDecision.Limited(1_000L), acquire())

        clock.advance(60_000L)
        repeat(3) { assertEquals(RateLimitDecision.Allowed, acquire()) }
        assertEquals(RateLimitDecision.Limited(1_000L), acquire())
    }

    @Test
    fun `the retry hint is the time until the next token`() {
        repeat(3) { acquire() }

        clock.advance(500L)

        assertEquals(RateLimitDecision.Limited(500L), acquire())
    }

    @Test
    fun `a slow refill gives a long retry hint`() {
        val slow = RateLimitPolicy(burst = 1, refillPerSecond = 0.1)
        acquire(using = slow)

        assertEquals(RateLimitDecision.Limited(10_000L), acquire(using = slow))
    }

    @Test
    fun `keys are independent`() {
        repeat(3) { acquire("a") }

        assertEquals(RateLimitDecision.Allowed, acquire("b"))
    }

    @Test
    fun `idle buckets are swept`() {
        repeat(100) { acquire("key-$it") }

        clock.advance(600_000L)
        acquire("fresh")

        assertEquals(1, limiter.trackedKeys)
    }

    @Test
    fun `concurrent requests never exceed the burst`() {
        val twenty = RateLimitPolicy(burst = 20, refillPerSecond = 1.0)
        val executor = Executors.newFixedThreadPool(8)

        val decisions = (1..400).map { executor.submit(Callable { acquire(using = twenty) }) }.map { it.get() }
        executor.shutdown()

        assertEquals(20, decisions.count { it == RateLimitDecision.Allowed })
    }

    @Test
    fun `invalid policies are rejected`() {
        assertFailsWith<IllegalArgumentException> { RateLimitPolicy(burst = 0, refillPerSecond = 1.0) }
        assertFailsWith<IllegalArgumentException> { RateLimitPolicy(burst = 1, refillPerSecond = 0.0) }
    }
}
