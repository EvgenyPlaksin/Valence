package com.lnight.valence.server.store

import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.SQLException

internal suspend fun <T> HikariDataSource.withConnection(
    dispatcher: CoroutineDispatcher,
    operation: (Connection) -> T,
): T =
    withContext(dispatcher) {
        connection.use(operation)
    }

@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> HikariDataSource.inTransaction(
    dispatcher: CoroutineDispatcher,
    operation: (Connection) -> T,
): T =
    withConnection(dispatcher) { connection ->
        val originalAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            operation(connection).also { connection.commit() }
        } catch (failure: Throwable) {
            try {
                connection.rollback()
            } catch (rollbackFailure: SQLException) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        } finally {
            connection.autoCommit = originalAutoCommit
        }
    }
