package com.lnight.valence.server.routes

import com.lnight.valence.server.domain.DatabasePoolMetrics
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

object MetricNames {
    const val HTTP_RESPONSES = "valence_http_responses_total"
    const val SOCKETS_OPENED = "valence_socket_connections_opened_total"
    const val MESSAGES_DELIVERED = "valence_messages_delivered_total"
    const val INVALID_FRAMES = "valence_invalid_frames_total"
    const val MESSAGES_PURGED = "valence_messages_purged_total"
    const val QUEUE_DEPTH = "valence_queue_depth"
    const val ACTIVE_CONNECTIONS = "valence_active_connections"
    internal const val HIKARI_CONNECTIONS_ACTIVE = "valence_hikari_connections_active"
    internal const val HIKARI_CONNECTIONS_IDLE = "valence_hikari_connections_idle"
    internal const val HIKARI_CONNECTIONS_TOTAL = "valence_hikari_connections_total"
    internal const val HIKARI_CONNECTIONS_MAX = "valence_hikari_connections_max"
    internal const val HIKARI_THREADS_AWAITING_CONNECTION = "valence_hikari_threads_awaiting_connection"
    internal const val HIKARI_CONNECTION_ACQUIRE_ATTEMPTS = "valence_hikari_connection_acquire_attempts_total"
    internal const val HIKARI_CONNECTION_ACQUIRE_NANOS = "valence_hikari_connection_acquire_nanos_total"
    internal const val HIKARI_CONNECTION_TIMEOUTS = "valence_hikari_connection_timeouts_total"
}

class ServerMetrics {
    private val counters = ConcurrentHashMap<String, LongAdder>()

    @Volatile
    private var databasePoolMetrics: DatabasePoolMetrics? = null

    internal fun attachDatabasePoolMetrics(metrics: DatabasePoolMetrics) {
        check(databasePoolMetrics == null)
        databasePoolMetrics = metrics
    }

    fun increment(
        name: String,
        labels: Map<String, String> = emptyMap(),
        by: Long = 1L,
    ) {
        counters.computeIfAbsent(keyOf(name, labels)) { LongAdder() }.add(by)
    }

    fun render(gauges: Map<String, Long> = emptyMap()): String {
        val output = StringBuilder()
        val countersToRender = counters.mapValuesTo(mutableMapOf()) { it.value.sum() }
        val gaugesToRender = gauges.toMutableMap()
        databasePoolMetrics
            ?.snapshot()
            ?.let { pool ->
                countersToRender[MetricNames.HIKARI_CONNECTION_ACQUIRE_ATTEMPTS] = pool.connectionAcquireAttempts
                countersToRender[MetricNames.HIKARI_CONNECTION_ACQUIRE_NANOS] = pool.connectionAcquireNanos
                countersToRender[MetricNames.HIKARI_CONNECTION_TIMEOUTS] = pool.connectionTimeouts
                gaugesToRender[MetricNames.HIKARI_CONNECTIONS_ACTIVE] = pool.activeConnections.toLong()
                gaugesToRender[MetricNames.HIKARI_CONNECTIONS_IDLE] = pool.idleConnections.toLong()
                gaugesToRender[MetricNames.HIKARI_CONNECTIONS_TOTAL] = pool.totalConnections.toLong()
                gaugesToRender[MetricNames.HIKARI_CONNECTIONS_MAX] = pool.maxConnections.toLong()
                gaugesToRender[MetricNames.HIKARI_THREADS_AWAITING_CONNECTION] =
                    pool.threadsAwaitingConnection.toLong()
            }
        var current = ""
        countersToRender.toSortedMap().forEach { (key, value) ->
            val name = key.substringBefore('{')
            if (name != current) {
                output.append("# TYPE ").append(name).append(" counter\n")
                current = name
            }
            output
                .append(key)
                .append(' ')
                .append(value)
                .append('\n')
        }
        gaugesToRender.toSortedMap().forEach { (name, value) ->
            output.append("# TYPE ").append(name).append(" gauge\n")
            output
                .append(name)
                .append(' ')
                .append(value)
                .append('\n')
        }
        return output.toString()
    }

    private fun keyOf(
        name: String,
        labels: Map<String, String>,
    ): String =
        if (labels.isEmpty()) {
            name
        } else {
            labels.toSortedMap().entries.joinToString(separator = ",", prefix = "$name{", postfix = "}") {
                "${it.key}=\"${it.value}\""
            }
        }
}
