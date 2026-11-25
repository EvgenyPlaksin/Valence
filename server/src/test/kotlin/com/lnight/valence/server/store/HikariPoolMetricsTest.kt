package com.lnight.valence.server.store

import com.zaxxer.hikari.metrics.PoolStats
import kotlin.test.Test
import kotlin.test.assertEquals

class HikariPoolMetricsTest {
    @Test
    fun `connection acquisition tracker records durations and timeouts cumulatively`() {
        val metrics = HikariPoolMetrics()
        val tracker =
            metrics.create(
                "test-pool",
                object : PoolStats(0) {
                    override fun update() = Unit
                },
            )

        tracker.recordConnectionAcquiredNanos(1_234L)
        tracker.recordConnectionAcquiredNanos(2_000L)
        tracker.recordConnectionTimeout()

        val counters = metrics.acquisitionMetrics()
        assertEquals(2L, counters.attempts)
        assertEquals(3_234L, counters.nanos)
        assertEquals(1L, counters.timeouts)
    }
}
