package com.lnight.valence.server

import com.lnight.valence.server.domain.DatabasePoolMetrics
import com.lnight.valence.server.domain.DatabasePoolMetricsSnapshot
import com.lnight.valence.server.routes.ServerMetrics
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerMetricsTest {
    private val metrics = ServerMetrics()

    @Test
    fun `counters are rendered sorted with type lines and sorted labels`() {
        metrics.increment("b_total")
        metrics.increment("a_total", mapOf("x" to "2", "a" to "1"), by = 5)
        metrics.increment("a_total", mapOf("x" to "2", "a" to "1"))

        assertEquals(
            "# TYPE a_total counter\na_total{a=\"1\",x=\"2\"} 6\n# TYPE b_total counter\nb_total 1\n",
            metrics.render(),
        )
    }

    @Test
    fun `gauges are rendered after the counters`() {
        metrics.increment("a_total")

        assertEquals(
            "# TYPE a_total counter\na_total 1\n# TYPE depth gauge\ndepth 4\n",
            metrics.render(mapOf("depth" to 4L)),
        )
    }

    @Test
    fun `concurrent increments are all counted`() {
        val executor = Executors.newFixedThreadPool(8)

        (1..8)
            .map { executor.submit(Callable { repeat(1_000) { metrics.increment("hits_total") } }) }
            .forEach { it.get() }
        executor.shutdown()

        assertEquals("# TYPE hits_total counter\nhits_total 8000\n", metrics.render())
    }

    @Test
    fun `database pool metrics render label free gauges and counters`() {
        val metrics = ServerMetrics()
        metrics.attachDatabasePoolMetrics(
            DatabasePoolMetrics {
                DatabasePoolMetricsSnapshot(
                    activeConnections = 2,
                    idleConnections = 3,
                    totalConnections = 5,
                    maxConnections = 8,
                    threadsAwaitingConnection = 1,
                    connectionAcquireAttempts = 11,
                    connectionAcquireNanos = 1234,
                    connectionTimeouts = 2,
                )
            },
        )

        val rendered = metrics.render()

        assertTrue(rendered.contains("valence_hikari_connections_active 2"))
        assertTrue(rendered.contains("valence_hikari_connections_idle 3"))
        assertTrue(rendered.contains("valence_hikari_connections_total 5"))
        assertTrue(rendered.contains("# TYPE valence_hikari_connections_max gauge"))
        assertTrue(rendered.contains("valence_hikari_connections_max 8"))
        assertTrue(rendered.contains("valence_hikari_threads_awaiting_connection 1"))
        assertTrue(rendered.contains("# TYPE valence_hikari_connection_acquire_attempts_total counter"))
        assertTrue(rendered.contains("valence_hikari_connection_acquire_attempts_total 11"))
        assertTrue(rendered.contains("valence_hikari_connection_acquire_nanos_total 1234"))
        assertTrue(rendered.contains("valence_hikari_connection_timeouts_total 2"))
        assertFalse(rendered.contains("{"))
    }

    @Test
    fun `database pool metrics are omitted when the provider is unavailable`() {
        val metrics = ServerMetrics()
        metrics.attachDatabasePoolMetrics(DatabasePoolMetrics { null })

        assertEquals("", metrics.render())
    }
}
