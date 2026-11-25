package com.lnight.valence.server.store

import com.lnight.valence.server.domain.AppendResult
import com.lnight.valence.server.domain.ConfirmResult
import com.lnight.valence.server.domain.DeliveryAuthorizationResult
import com.lnight.valence.server.domain.InboxPollResult
import com.lnight.valence.server.domain.InboxStore
import com.lnight.valence.server.domain.NewMessage
import com.lnight.valence.server.domain.QueuedMessage
import com.lnight.valence.server.domain.RateLimitDecision
import com.lnight.valence.server.domain.WireSizes
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineDispatcher
import java.security.MessageDigest
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant

@Suppress("MagicNumber", "TooManyFunctions")
class PostgresInboxStore(
    private val dataSource: HikariDataSource,
    private val dispatcher: CoroutineDispatcher,
) : InboxStore {
    override suspend fun authorize(
        mailboxId: String,
        expectedDeliveryKeyHash: ByteArray,
        rateLimit: () -> RateLimitDecision,
    ): DeliveryAuthorizationResult =
        dataSource.inTransaction(dispatcher) { connection ->
            val storedHash = connection.lockMailbox(mailboxId)
            val deliveryKeyMatches =
                MessageDigest.isEqual(expectedDeliveryKeyHash, storedHash ?: ByteArray(WireSizes.HASH))
            if (storedHash == null || !deliveryKeyMatches) {
                DeliveryAuthorizationResult.Unauthorized
            } else {
                when (val decision = rateLimit()) {
                    RateLimitDecision.Allowed -> DeliveryAuthorizationResult.Authorized
                    is RateLimitDecision.Limited -> DeliveryAuthorizationResult.RateLimited(decision.retryAfterMillis)
                }
            }
        }

    override suspend fun append(
        mailboxId: String,
        expectedDeliveryKeyHash: ByteArray,
        message: NewMessage,
        maxQueued: Int,
        rateLimit: () -> RateLimitDecision,
    ): AppendResult =
        dataSource.withConnection(dispatcher) { connection ->
            connection.append(mailboxId, expectedDeliveryKeyHash, message, maxQueued, rateLimit)
        }

    override suspend fun messagesAfter(
        mailboxId: String,
        afterServerSeq: Long,
        nowMillis: Long,
        limit: Int,
    ): List<QueuedMessage> {
        if (limit <= 0) return emptyList()
        return dataSource.withConnection(dispatcher) { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT server_seq, server_ts, envelope, expires_at
                    FROM inbox_message
                    WHERE mailbox_id = ? AND server_seq > ? AND expires_at > ?
                    ORDER BY server_seq ASC
                    LIMIT ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, mailboxId)
                    statement.setLong(2, afterServerSeq)
                    statement.setTimestamp(3, Timestamp.from(Instant.ofEpochMilli(nowMillis)))
                    statement.setInt(4, limit)
                    statement.executeQuery().use { result ->
                        buildList {
                            while (result.next()) {
                                add(
                                    QueuedMessage(
                                        serverSeq = result.getLong("server_seq"),
                                        serverTsMillis = result.getTimestamp("server_ts").toInstant().toEpochMilli(),
                                        envelope = result.getBytes("envelope"),
                                        expiresAtMillis = result.getTimestamp("expires_at").toInstant().toEpochMilli(),
                                    ),
                                )
                            }
                        }
                    }
                }
        }
    }

    override suspend fun advanceSocketGeneration(mailboxId: String): Long? =
        dataSource.inTransaction(dispatcher) { connection ->
            connection
                .prepareStatement(
                    """
                    UPDATE mailbox_socket_state
                    SET active_socket_generation = active_socket_generation + 1
                    WHERE mailbox_id = ?
                    RETURNING active_socket_generation
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, mailboxId)
                    statement.executeQuery().use { result ->
                        if (result.next()) result.getLong(1) else null
                    }
                }
        }

    override suspend fun currentSocketGeneration(mailboxId: String): Long? =
        dataSource.withConnection(dispatcher) { connection ->
            connection
                .prepareStatement(
                    "SELECT active_socket_generation FROM mailbox_socket_state WHERE mailbox_id = ?",
                ).use { statement ->
                    statement.setString(1, mailboxId)
                    statement.executeQuery().use { result ->
                        if (result.next()) result.getLong(1) else null
                    }
                }
        }

    override suspend fun checkForUpdates(
        mailboxId: String,
        afterServerSeq: Long,
        nowMillis: Long,
    ): InboxPollResult? =
        dataSource.withConnection(dispatcher) { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT active_socket_generation,
                           EXISTS (
                               SELECT 1
                               FROM inbox_message
                               WHERE mailbox_id = ?
                                 AND server_seq > ?
                                 AND expires_at > ?
                           ) AS has_messages
                    FROM mailbox_socket_state
                    WHERE mailbox_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, mailboxId)
                    statement.setLong(2, afterServerSeq)
                    statement.setTimestamp(3, Timestamp.from(Instant.ofEpochMilli(nowMillis)))
                    statement.setString(4, mailboxId)
                    statement.executeQuery().use { result ->
                        if (result.next()) {
                            InboxPollResult(
                                result.getLong("active_socket_generation"),
                                result.getBoolean("has_messages"),
                            )
                        } else {
                            null
                        }
                    }
                }
        }

    override suspend fun confirm(
        mailboxId: String,
        generation: Long,
        upToServerSeq: Long,
    ): ConfirmResult =
        dataSource.inTransaction(dispatcher) { connection ->
            val currentGeneration =
                connection
                    .prepareStatement(
                        "SELECT active_socket_generation FROM mailbox_socket_state WHERE mailbox_id = ? FOR UPDATE",
                    ).use { statement ->
                        statement.setString(1, mailboxId)
                        statement.executeQuery().use { result ->
                            if (result.next()) result.getLong(1) else null
                        }
                    } ?: return@inTransaction ConfirmResult.STALE
            if (currentGeneration != generation) return@inTransaction ConfirmResult.STALE
            connection
                .prepareStatement("DELETE FROM inbox_message WHERE mailbox_id = ? AND server_seq <= ?")
                .use { statement ->
                    statement.setString(1, mailboxId)
                    statement.setLong(2, upToServerSeq)
                    statement.executeUpdate()
                }
            ConfirmResult.CONFIRMED
        }

    override suspend fun purgeExpired(nowMillis: Long): Int =
        dataSource.inTransaction(dispatcher) { connection ->
            val acquired =
                connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?)").use { statement ->
                    statement.setLong(1, PURGE_LOCK_ID)
                    statement.executeQuery().use { result ->
                        result.next()
                        result.getBoolean(1)
                    }
                }
            if (!acquired) return@inTransaction 0
            connection.prepareStatement("DELETE FROM inbox_message WHERE expires_at <= ?").use { statement ->
                statement.setTimestamp(1, Timestamp.from(Instant.ofEpochMilli(nowMillis)))
                statement.executeUpdate()
            }
        }

    override suspend fun queueDepth(): Int =
        dataSource.withConnection(dispatcher) { connection ->
            connection.prepareStatement("SELECT count(*) FROM inbox_message").use { statement ->
                statement.executeQuery().use { result ->
                    result.next()
                    result.getLong(1).toInt()
                }
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private fun java.sql.Connection.append(
        mailboxId: String,
        expectedDeliveryKeyHash: ByteArray,
        message: NewMessage,
        maxQueued: Int,
        rateLimit: () -> RateLimitDecision,
    ): AppendResult {
        val originalAutoCommit = autoCommit
        autoCommit = false
        return try {
            val storedHash = lockMailbox(mailboxId)
            val deliveryKeyMatches =
                MessageDigest.isEqual(expectedDeliveryKeyHash, storedHash ?: ByteArray(WireSizes.HASH))
            val result =
                if (storedHash == null || !deliveryKeyMatches) {
                    AppendResult.Unauthorized
                } else {
                    when (val decision = rateLimit()) {
                        is RateLimitDecision.Limited -> {
                            AppendResult.RateLimited(decision.retryAfterMillis)
                        }

                        RateLimitDecision.Allowed -> {
                            deleteExpired(mailboxId, message.serverTsMillis)
                            if (activeMessageCount(mailboxId, message.serverTsMillis) >= maxQueued) {
                                AppendResult.QuotaExceeded
                            } else {
                                val sequence = incrementSequence(mailboxId)
                                insertMessage(mailboxId, sequence, message)
                                AppendResult.Appended(sequence)
                            }
                        }
                    }
                }
            if (result is AppendResult.Appended) commit() else rollback()
            result
        } catch (failure: Throwable) {
            try {
                rollback()
            } catch (rollbackFailure: SQLException) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        } finally {
            autoCommit = originalAutoCommit
        }
    }

    private fun java.sql.Connection.lockMailbox(mailboxId: String): ByteArray? =
        prepareStatement("SELECT delivery_key_hash FROM mailbox WHERE mailbox_id = ? FOR UPDATE").use { statement ->
            statement.setString(1, mailboxId)
            statement.executeQuery().use { result ->
                if (result.next()) result.getBytes("delivery_key_hash") else null
            }
        }

    private fun java.sql.Connection.deleteExpired(
        mailboxId: String,
        nowMillis: Long,
    ) {
        prepareStatement("DELETE FROM inbox_message WHERE mailbox_id = ? AND expires_at <= ?").use { statement ->
            statement.setString(1, mailboxId)
            statement.setTimestamp(2, Timestamp.from(Instant.ofEpochMilli(nowMillis)))
            statement.executeUpdate()
        }
    }

    private fun java.sql.Connection.activeMessageCount(
        mailboxId: String,
        nowMillis: Long,
    ): Long =
        prepareStatement(
            "SELECT count(*) FROM inbox_message WHERE mailbox_id = ? AND expires_at > ?",
        ).use { statement ->
            statement.setString(1, mailboxId)
            statement.setTimestamp(2, Timestamp.from(Instant.ofEpochMilli(nowMillis)))
            statement.executeQuery().use { result ->
                result.next()
                result.getLong(1)
            }
        }

    private fun java.sql.Connection.incrementSequence(mailboxId: String): Long =
        prepareStatement(
            """
            UPDATE mailbox
            SET next_server_seq = next_server_seq + 1
            WHERE mailbox_id = ?
            RETURNING next_server_seq
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, mailboxId)
            statement.executeQuery().use { result ->
                check(result.next()) { "Locked mailbox disappeared during message append" }
                result.getLong(1)
            }
        }

    private fun java.sql.Connection.insertMessage(
        mailboxId: String,
        sequence: Long,
        message: NewMessage,
    ) {
        prepareStatement(
            """
            INSERT INTO inbox_message (mailbox_id, server_seq, server_ts, envelope, expires_at)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, mailboxId)
            statement.setLong(2, sequence)
            statement.setTimestamp(3, Timestamp.from(Instant.ofEpochMilli(message.serverTsMillis)))
            statement.setBytes(4, message.envelope)
            statement.setTimestamp(5, Timestamp.from(Instant.ofEpochMilli(message.expiresAtMillis)))
            check(statement.executeUpdate() == 1) { "Message append did not insert one row" }
        }
    }

    private companion object {
        const val PURGE_LOCK_ID = 0x56414C454E4345L
    }
}
