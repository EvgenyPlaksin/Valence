package com.lnight.valence.server.store

import com.lnight.valence.server.domain.DatabasePoolMetrics
import com.lnight.valence.server.domain.DatabasePoolMetricsSnapshot
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.metrics.IMetricsTracker
import com.zaxxer.hikari.metrics.MetricsTrackerFactory
import com.zaxxer.hikari.metrics.PoolStats
import java.util.concurrent.atomic.LongAdder

internal data class HikariConnectionAcquisitionMetrics(
    val attempts: Long,
    val nanos: Long,
    val timeouts: Long,
)

internal class HikariPoolMetrics :
    DatabasePoolMetrics,
    MetricsTrackerFactory {
    private val connectionAcquireAttempts = LongAdder()
    private val connectionAcquireNanos = LongAdder()
    private val connectionTimeouts = LongAdder()

    @Volatile
    private var dataSource: HikariDataSource? = null

    fun attach(dataSource: HikariDataSource) {
        check(this.dataSource == null)
        this.dataSource = dataSource
    }

    override fun create(
        poolName: String,
        poolStats: PoolStats,
    ): IMetricsTracker =
        object : IMetricsTracker {
            override fun recordConnectionAcquiredNanos(elapsedAcquiredNanos: Long) {
                connectionAcquireAttempts.increment()
                connectionAcquireNanos.add(elapsedAcquiredNanos)
            }

            override fun recordConnectionTimeout() {
                connectionTimeouts.increment()
            }
        }

    override fun snapshot(): DatabasePoolMetricsSnapshot? {
        val source = dataSource
        if (source == null || source.isClosed) return null
        val pool = source.hikariPoolMXBean
        val configuration = source.hikariConfigMXBean
        return if (pool == null || configuration == null) {
            null
        } else {
            val acquisitionMetrics = acquisitionMetrics()
            DatabasePoolMetricsSnapshot(
                activeConnections = pool.activeConnections,
                idleConnections = pool.idleConnections,
                totalConnections = pool.totalConnections,
                maxConnections = configuration.maximumPoolSize,
                threadsAwaitingConnection = pool.threadsAwaitingConnection,
                connectionAcquireAttempts = acquisitionMetrics.attempts,
                connectionAcquireNanos = acquisitionMetrics.nanos,
                connectionTimeouts = acquisitionMetrics.timeouts,
            )
        }
    }

    internal fun acquisitionMetrics() =
        HikariConnectionAcquisitionMetrics(
            attempts = connectionAcquireAttempts.sum(),
            nanos = connectionAcquireNanos.sum(),
            timeouts = connectionTimeouts.sum(),
        )
}
