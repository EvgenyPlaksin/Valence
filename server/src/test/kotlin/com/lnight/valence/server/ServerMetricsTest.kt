package com.lnight.valence.server

import com.lnight.valence.server.routes.ServerMetrics
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
