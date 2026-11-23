package com.lnight.valence.server.routes

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
}

class ServerMetrics {
    private val counters = ConcurrentHashMap<String, LongAdder>()

    fun increment(
        name: String,
        labels: Map<String, String> = emptyMap(),
        by: Long = 1L,
    ) {
        counters.computeIfAbsent(keyOf(name, labels)) { LongAdder() }.add(by)
    }

    fun render(gauges: Map<String, Long> = emptyMap()): String {
        val output = StringBuilder()
        var current = ""
        counters.entries.sortedBy { it.key }.forEach { (key, adder) ->
            val name = key.substringBefore('{')
            if (name != current) {
                output.append("# TYPE ").append(name).append(" counter\n")
                current = name
            }
            output
                .append(key)
                .append(' ')
                .append(adder.sum())
                .append('\n')
        }
        gauges.toSortedMap().forEach { (name, value) ->
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
