package com.lnight.valence.server

import com.lnight.valence.server.domain.RateLimitDecision
import com.lnight.valence.server.domain.RateLimitPolicy
import com.lnight.valence.server.domain.ReceiveSession
import com.lnight.valence.server.store.InMemoryInboxStore
import kotlin.test.Test
import kotlin.test.assertEquals

class ReceiveSessionFrameLimitTest {
    private val clock = TestClock(1_000L)
    private val session =
        ReceiveSession(
            RegistrationTestSupport.MAILBOX_ID,
            0,
            InMemoryInboxStore(),
            clock,
            RateLimitPolicy(burst = 2, refillPerSecond = 1.0),
        )

    @Test
    fun `frames beyond the burst are limited and refill over time`() {
        assertEquals(RateLimitDecision.Allowed, session.allowFrame())
        assertEquals(RateLimitDecision.Allowed, session.allowFrame())
        assertEquals(RateLimitDecision.Limited(1_000L), session.allowFrame())

        clock.advance(1_000L)

        assertEquals(RateLimitDecision.Allowed, session.allowFrame())
    }
}
