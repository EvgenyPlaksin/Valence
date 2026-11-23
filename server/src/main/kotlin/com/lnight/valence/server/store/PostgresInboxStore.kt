package com.lnight.valence.server.store

import com.lnight.valence.server.domain.AppendResult
import com.lnight.valence.server.domain.InboxStore
import com.lnight.valence.server.domain.NewMessage
import com.lnight.valence.server.domain.QueuedMessage
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineDispatcher
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant

@Suppress("MagicNumber")
class PostgresInboxStore(
    private val dataSource: HikariDataSource,
    private val dispatcher: CoroutineDispatcher,
) : InboxStore {
    override suspend fun append(
        mailboxId: String,
        message: NewMessage,
        maxQueued: Int,
    ): AppendResult =
        dataSource.withConnection(dispatcher) { connection -> connection.append(mailboxId, message, maxQueued) }

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

    override suspend fun deleteUpTo(
        mailboxId: String,
        upToServerSeq: Long,
    ) {
        dataSource.withConnection(dispatcher) { connection ->
            connection
                .prepareStatement(
                    "DELETE FROM inbox_message WHERE mailbox_id = ? AND server_seq <= ?",
                ).use { statement ->
                    statement.setString(1, mailboxId)
                    statement.setLong(2, upToServerSeq)
                    statement.executeUpdate()
                }
        }
    }

    override suspend fun purgeExpired(nowMillis: Long): Int =
        dataSource.withConnection(dispatcher) { connection ->
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
        message: NewMessage,
        maxQueued: Int,
    ): AppendResult {
        val originalAutoCommit = autoCommit
        autoCommit = false
        try {
            lockMailbox(mailboxId)
            deleteExpired(mailboxId, message.serverTsMillis)
            if (activeMessageCount(mailboxId, message.serverTsMillis) >= maxQueued) {
                rollback()
                return AppendResult.QuotaExceeded
            }
            val sequence = incrementSequence(mailboxId)
            insertMessage(mailboxId, sequence, message)
            commit()
            return AppendResult.Appended(sequence)
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

    private fun java.sql.Connection.lockMailbox(mailboxId: String) {
        prepareStatement("SELECT next_server_seq FROM mailbox WHERE mailbox_id = ? FOR UPDATE").use { statement ->
            statement.setString(1, mailboxId)
            statement.executeQuery().use { result ->
                check(result.next()) { "Inbox append attempted for an unknown mailbox" }
            }
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
}
