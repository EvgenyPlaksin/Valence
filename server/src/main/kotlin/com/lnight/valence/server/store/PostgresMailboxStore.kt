package com.lnight.valence.server.store

import com.lnight.valence.server.domain.KeyStoreOutcome
import com.lnight.valence.server.domain.Mailbox
import com.lnight.valence.server.domain.MailboxStore
import com.lnight.valence.server.domain.OneTimePreKey
import com.lnight.valence.server.domain.PreKeyBundle
import com.lnight.valence.server.domain.SignedPublicKey
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineDispatcher
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

@Suppress("MagicNumber")
class PostgresMailboxStore(
    private val dataSource: HikariDataSource,
    private val dispatcher: CoroutineDispatcher,
) : MailboxStore {
    override suspend fun create(
        mailbox: Mailbox,
        oneTimePreKeys: List<OneTimePreKey>,
    ): Boolean =
        dataSource.inTransaction(dispatcher) { connection ->
            val inserted =
                connection
                    .prepareStatement(
                        """
                        INSERT INTO mailbox (
                            mailbox_id, identity_key, signed_prekey_id, signed_prekey, signed_prekey_sig,
                            kyber_prekey_id, kyber_prekey, kyber_prekey_sig, delivery_key_hash, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (mailbox_id) DO NOTHING
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, mailbox.id)
                        statement.setBytes(2, mailbox.identityKey)
                        statement.setInt(3, mailbox.signedPreKey.id)
                        statement.setBytes(4, mailbox.signedPreKey.publicKey)
                        statement.setBytes(5, mailbox.signedPreKey.signature)
                        statement.setInt(6, mailbox.kyberPreKey.id)
                        statement.setBytes(7, mailbox.kyberPreKey.publicKey)
                        statement.setBytes(8, mailbox.kyberPreKey.signature)
                        statement.setBytes(9, mailbox.deliveryKeyHash)
                        statement.setTimestamp(10, Timestamp.from(Instant.ofEpochMilli(mailbox.createdAtMillis)))
                        statement.executeUpdate() == 1
                    }
            if (inserted) insertPreKeys(connection, mailbox.id, oneTimePreKeys)
            inserted
        }

    override suspend fun find(mailboxId: String): Mailbox? =
        dataSource.withConnection(dispatcher) { connection ->
            connection.prepareStatement("$SELECT_MAILBOX WHERE mailbox_id = ?").use { statement ->
                statement.setString(1, mailboxId)
                statement.executeQuery().use { result ->
                    if (result.next()) result.mailbox() else null
                }
            }
        }

    override suspend fun oneTimePreKeyCount(mailboxId: String): Int? =
        dataSource.withConnection(dispatcher) { connection ->
            connection.prepareStatement("SELECT count(*) FROM one_time_prekey WHERE mailbox_id = ?").use { statement ->
                statement.setString(1, mailboxId)
                statement.executeQuery().use { result ->
                    result.next()
                    result.getLong(1).takeIf { mailboxExists(connection, mailboxId) }?.toInt()
                }
            }
        }

    override suspend fun replaceSignedPreKeys(
        mailboxId: String,
        signedPreKey: SignedPublicKey,
        kyberPreKey: SignedPublicKey,
    ): KeyStoreOutcome =
        dataSource.inTransaction(dispatcher) { connection ->
            val existing =
                connection
                    .prepareStatement(
                        "SELECT signed_prekey_id, kyber_prekey_id FROM mailbox WHERE mailbox_id = ? FOR UPDATE",
                    ).use { statement ->
                        statement.setString(1, mailboxId)
                        statement.executeQuery().use { result ->
                            if (result.next()) result.getInt(1) to result.getInt(2) else null
                        }
                    } ?: return@inTransaction KeyStoreOutcome.UNKNOWN_MAILBOX
            if (existing.first == signedPreKey.id || existing.second == kyberPreKey.id) {
                return@inTransaction KeyStoreOutcome.ID_EXISTS
            }
            connection
                .prepareStatement(
                    """
                    UPDATE mailbox
                    SET signed_prekey_id = ?, signed_prekey = ?, signed_prekey_sig = ?,
                        kyber_prekey_id = ?, kyber_prekey = ?, kyber_prekey_sig = ?
                    WHERE mailbox_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setInt(1, signedPreKey.id)
                    statement.setBytes(2, signedPreKey.publicKey)
                    statement.setBytes(3, signedPreKey.signature)
                    statement.setInt(4, kyberPreKey.id)
                    statement.setBytes(5, kyberPreKey.publicKey)
                    statement.setBytes(6, kyberPreKey.signature)
                    statement.setString(7, mailboxId)
                    check(statement.executeUpdate() == 1) { "Locked mailbox disappeared during key replacement" }
                }
            KeyStoreOutcome.UPDATED
        }

    override suspend fun addOneTimePreKeys(
        mailboxId: String,
        keys: List<OneTimePreKey>,
        maxStored: Int,
    ): KeyStoreOutcome =
        dataSource.inTransaction(dispatcher) { connection ->
            val mailboxExists =
                connection.prepareStatement("SELECT 1 FROM mailbox WHERE mailbox_id = ? FOR UPDATE").use { statement ->
                    statement.setString(1, mailboxId)
                    statement.executeQuery().use(ResultSet::next)
                }
            if (!mailboxExists) return@inTransaction KeyStoreOutcome.UNKNOWN_MAILBOX
            if (keys.map { it.id }.toSet().size != keys.size) return@inTransaction KeyStoreOutcome.ID_EXISTS
            val currentCount =
                connection
                    .prepareStatement("SELECT count(*) FROM one_time_prekey WHERE mailbox_id = ?")
                    .use { statement ->
                        statement.setString(1, mailboxId)
                        statement.executeQuery().use { result ->
                            result.next()
                            result.getInt(1)
                        }
                    }
            if (currentCount + keys.size > maxStored) return@inTransaction KeyStoreOutcome.LIMIT_EXCEEDED
            val existingId =
                connection
                    .prepareStatement(
                        "SELECT 1 FROM one_time_prekey WHERE mailbox_id = ? AND prekey_id = ?",
                    ).use { statement ->
                        keys.any { key ->
                            statement.setString(1, mailboxId)
                            statement.setInt(2, key.id)
                            statement.executeQuery().use(ResultSet::next)
                        }
                    }
            if (existingId) return@inTransaction KeyStoreOutcome.ID_EXISTS
            insertPreKeys(connection, mailboxId, keys)
            KeyStoreOutcome.UPDATED
        }

    override suspend fun takeBundle(mailboxId: String): PreKeyBundle? =
        dataSource.inTransaction(dispatcher) { connection ->
            val mailbox =
                connection.prepareStatement("$SELECT_MAILBOX WHERE mailbox_id = ? FOR UPDATE").use { statement ->
                    statement.setString(1, mailboxId)
                    statement.executeQuery().use { result ->
                        if (result.next()) result.mailbox() else null
                    }
                } ?: return@inTransaction null
            val oneTimePreKey =
                connection
                    .prepareStatement(
                        """
                        DELETE FROM one_time_prekey
                        WHERE (mailbox_id, prekey_sequence) IN (
                            SELECT mailbox_id, prekey_sequence
                            FROM one_time_prekey
                            WHERE mailbox_id = ?
                            ORDER BY prekey_sequence ASC
                            LIMIT 1
                            FOR UPDATE SKIP LOCKED
                        )
                        RETURNING prekey_id, public_key
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, mailboxId)
                        statement.executeQuery().use { result ->
                            if (result.next()) {
                                OneTimePreKey(result.getInt("prekey_id"), result.getBytes("public_key"))
                            } else {
                                null
                            }
                        }
                    }
            PreKeyBundle(mailbox.identityKey, mailbox.signedPreKey, mailbox.kyberPreKey, oneTimePreKey)
        }

    private fun insertPreKeys(
        connection: Connection,
        mailboxId: String,
        keys: List<OneTimePreKey>,
    ) {
        if (keys.isEmpty()) return
        connection
            .prepareStatement(
                "INSERT INTO one_time_prekey (mailbox_id, prekey_id, public_key) VALUES (?, ?, ?)",
            ).use { statement ->
                keys.forEach { key ->
                    statement.setString(1, mailboxId)
                    statement.setInt(2, key.id)
                    statement.setBytes(3, key.publicKey)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
    }

    private fun mailboxExists(
        connection: Connection,
        mailboxId: String,
    ): Boolean =
        connection.prepareStatement("SELECT 1 FROM mailbox WHERE mailbox_id = ?").use { statement ->
            statement.setString(1, mailboxId)
            statement.executeQuery().use(ResultSet::next)
        }

    private fun ResultSet.mailbox() =
        Mailbox(
            id = getString("mailbox_id"),
            identityKey = getBytes("identity_key"),
            signedPreKey =
                SignedPublicKey(
                    getInt("signed_prekey_id"),
                    getBytes("signed_prekey"),
                    getBytes("signed_prekey_sig"),
                ),
            kyberPreKey =
                SignedPublicKey(
                    getInt("kyber_prekey_id"),
                    getBytes("kyber_prekey"),
                    getBytes("kyber_prekey_sig"),
                ),
            deliveryKeyHash = getBytes("delivery_key_hash"),
            createdAtMillis = getTimestamp("created_at").toInstant().toEpochMilli(),
        )

    private companion object {
        const val SELECT_MAILBOX =
            """
            SELECT mailbox_id, identity_key, signed_prekey_id, signed_prekey, signed_prekey_sig,
                   kyber_prekey_id, kyber_prekey, kyber_prekey_sig, delivery_key_hash, created_at
            FROM mailbox
            """
    }
}
