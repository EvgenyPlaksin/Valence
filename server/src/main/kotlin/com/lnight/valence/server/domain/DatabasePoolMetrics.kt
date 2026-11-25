package com.lnight.valence.server.domain

internal data class DatabasePoolMetricsSnapshot(
    val activeConnections: Int,
    val idleConnections: Int,
    val totalConnections: Int,
    val maxConnections: Int,
    val threadsAwaitingConnection: Int,
    val connectionAcquireAttempts: Long,
    val connectionAcquireNanos: Long,
    val connectionTimeouts: Long,
)

internal fun interface DatabasePoolMetrics {
    fun snapshot(): DatabasePoolMetricsSnapshot?
}
