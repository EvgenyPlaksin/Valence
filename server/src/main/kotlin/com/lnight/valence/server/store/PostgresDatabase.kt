package com.lnight.valence.server.store

import com.lnight.valence.server.DatabaseConfiguration
import com.lnight.valence.server.DefaultDispatcherProvider
import com.lnight.valence.server.DispatcherProvider
import com.lnight.valence.server.domain.DatabasePoolMetrics
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import java.io.Closeable
import java.sql.SQLException

class PostgresDatabase private constructor(
    internal val dataSource: HikariDataSource,
    val dispatcher: CoroutineDispatcher,
    internal val poolMetrics: DatabasePoolMetrics,
) : Closeable {
    val mailboxStore = PostgresMailboxStore(dataSource, dispatcher)
    val inboxStore = PostgresInboxStore(dataSource, dispatcher)

    suspend fun isReady(): Boolean =
        try {
            withContext(dispatcher) {
                dataSource.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("SELECT 1")
                    }
                }
            }
        } catch (_: SQLException) {
            false
        }

    override fun close() {
        dataSource.close()
    }

    companion object {
        fun open(
            configuration: DatabaseConfiguration,
            dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
        ): PostgresDatabase {
            val dispatcher = dispatcherProvider.database(configuration.maxConnections)
            val poolMetrics = HikariPoolMetrics()
            val dataSource =
                HikariDataSource(
                    HikariConfig().apply {
                        jdbcUrl = configuration.url
                        username = configuration.username
                        password = configuration.password
                        maximumPoolSize = configuration.maxConnections
                        minimumIdle = minOf(configuration.maxConnections, 2)
                        poolName = "valence-postgres"
                        metricsTrackerFactory = poolMetrics
                    },
                )
            poolMetrics.attach(dataSource)
            try {
                val flyway =
                    Flyway
                        .configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/migration")
                        .load()
                if (configuration.autoMigrate) {
                    flyway.migrate()
                } else {
                    flyway.validate()
                    if (flyway.info().pending().isNotEmpty()) {
                        failStartup(
                            dataSource,
                            "Database migrations are pending; enable $AUTO_MIGRATE_VARIABLE " +
                                "or run migrations before startup",
                        )
                    }
                    if (flyway.info().current() == null) {
                        failStartup(
                            dataSource,
                            "Database schema is not initialized; enable $AUTO_MIGRATE_VARIABLE " +
                                "or run migrations before startup",
                        )
                    }
                }
                return PostgresDatabase(dataSource, dispatcher, poolMetrics)
            } catch (failure: FlywayException) {
                dataSource.close()
                throw failure
            }
        }

        private fun failStartup(
            dataSource: HikariDataSource,
            message: String,
        ): Nothing {
            dataSource.close()
            error(message)
        }

        private const val AUTO_MIGRATE_VARIABLE = "VALENCE_DB_AUTO_MIGRATE"
    }
}
