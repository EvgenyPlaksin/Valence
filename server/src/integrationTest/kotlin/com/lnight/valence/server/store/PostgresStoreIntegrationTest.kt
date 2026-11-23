package com.lnight.valence.server.store

import com.lnight.valence.server.DatabaseConfiguration
import com.lnight.valence.server.domain.AppendResult
import com.lnight.valence.server.domain.Mailbox
import com.lnight.valence.server.domain.NewMessage
import com.lnight.valence.server.domain.OneTimePreKey
import com.lnight.valence.server.domain.SignedPublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("MagicNumber", "TooManyFunctions")
class PostgresStoreIntegrationTest {
    @BeforeEach
    fun clearStore() {
        val configuration = databaseConfiguration()
        PostgresDatabase.open(configuration).close()
        DriverManager
            .getConnection(configuration.url, configuration.username, configuration.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("TRUNCATE TABLE inbox_message, one_time_prekey, mailbox RESTART IDENTITY CASCADE")
                }
            }
    }

    @Test
    fun mailboxCreationAndInitialPrekeysAreAtomic() =
        runTest {
            database().use { database ->
                val mailbox = mailbox("mailbox-atomic")
                val duplicateKeys = listOf(OneTimePreKey(1, bytes(11)), OneTimePreKey(1, bytes(12)))

                assertFails { database.mailboxStore.create(mailbox, duplicateKeys) }

                assertNull(database.mailboxStore.find(mailbox.id))
            }
        }

    @Test
    fun mailboxKeyUpdatesAndOneTimePrekeysPreserveInsertionOrder() =
        runTest {
            database().use { database ->
                val mailbox = mailbox("mailbox-prekeys")
                assertTrue(
                    database.mailboxStore.create(
                        mailbox,
                        listOf(OneTimePreKey(20, bytes(20)), OneTimePreKey(10, bytes(10))),
                    ),
                )
                assertEquals(2, database.mailboxStore.oneTimePreKeyCount(mailbox.id))

                val first = database.mailboxStore.takeBundle(mailbox.id)
                val second = database.mailboxStore.takeBundle(mailbox.id)
                val exhausted = database.mailboxStore.takeBundle(mailbox.id)

                assertEquals(20, first?.oneTimePreKey?.id)
                assertEquals(10, second?.oneTimePreKey?.id)
                assertNull(exhausted?.oneTimePreKey)
                assertEquals(0, database.mailboxStore.oneTimePreKeyCount(mailbox.id))

                assertEquals(
                    com.lnight.valence.server.domain.KeyStoreOutcome.UPDATED,
                    database.mailboxStore.replaceSignedPreKeys(mailbox.id, signed(2), signed(2)),
                )
                assertEquals(
                    com.lnight.valence.server.domain.KeyStoreOutcome.ID_EXISTS,
                    database.mailboxStore.replaceSignedPreKeys(mailbox.id, signed(2), signed(3)),
                )
                assertEquals(
                    2,
                    database.mailboxStore
                        .find(mailbox.id)
                        ?.signedPreKey
                        ?.id,
                )
            }
        }

    @Test
    fun quotaRejectionsDoNotConsumeMailboxSequenceNumbers() =
        runTest {
            database().use { database ->
                val mailbox = mailbox("mailbox-quota")
                database.mailboxStore.create(mailbox, emptyList())
                val first =
                    database.inboxStore.append(
                        mailbox.id,
                        NewMessage(bytes(1), 1_000L, 10_000L),
                        maxQueued = 1,
                    )
                val rejected =
                    database.inboxStore.append(
                        mailbox.id,
                        NewMessage(bytes(2), 1_001L, 10_000L),
                        maxQueued = 1,
                    )

                assertEquals(AppendResult.Appended(1), first)
                assertEquals(AppendResult.QuotaExceeded, rejected)
                database.inboxStore.deleteUpTo(mailbox.id, 1)

                val next =
                    database.inboxStore.append(
                        mailbox.id,
                        NewMessage(bytes(3), 1_002L, 10_000L),
                        maxQueued = 1,
                    )
                assertEquals(AppendResult.Appended(2), next)
            }
        }

    @Test
    fun concurrentAppendsReceiveStrictlyIncreasingUniqueSequences() =
        runTest {
            database().use { database ->
                val mailbox = mailbox("mailbox-concurrent-appends")
                database.mailboxStore.create(mailbox, emptyList())

                val results =
                    (1..40)
                        .map { value ->
                            async(Dispatchers.IO) {
                                database.inboxStore.append(
                                    mailbox.id,
                                    NewMessage(bytes(value), 2_000L + value, 100_000L),
                                    maxQueued = 100,
                                )
                            }
                        }.awaitAll()

                val sequences = results.map { assertIs<AppendResult.Appended>(it).serverSeq }.sorted()
                assertEquals((1L..40L).toList(), sequences)
            }
        }

    @Test
    fun concurrentPrekeyDrainsNeverReturnTheSameKey() =
        runTest {
            database().use { database ->
                val mailbox = mailbox("mailbox-concurrent-prekeys")
                val keys = (1..20).map { OneTimePreKey(it, bytes(it)) }
                database.mailboxStore.create(mailbox, keys)

                val results =
                    (1..20)
                        .map {
                            async(Dispatchers.IO) {
                                database.mailboxStore
                                    .takeBundle(mailbox.id)
                                    ?.oneTimePreKey
                                    ?.id
                            }
                        }.awaitAll()

                assertEquals((1..20).toSet(), results.toSet())
                assertEquals(0, database.mailboxStore.oneTimePreKeyCount(mailbox.id))
            }
        }

    @Test
    fun expiredMessagesArePurgedAndCursorsReplayInSequenceOrder() =
        runTest {
            database().use { database ->
                val mailbox = mailbox("mailbox-replay")
                database.mailboxStore.create(mailbox, emptyList())
                database.inboxStore.append(mailbox.id, NewMessage(bytes(1), 1_000L, 2_000L), 10)
                database.inboxStore.append(mailbox.id, NewMessage(bytes(2), 1_001L, 10_000L), 10)
                database.inboxStore.append(mailbox.id, NewMessage(bytes(3), 1_002L, 10_000L), 10)

                assertEquals(1, database.inboxStore.purgeExpired(2_000L))
                val replay = database.inboxStore.messagesAfter(mailbox.id, 1, nowMillis = 2_000L, limit = 10)

                assertEquals(listOf(2L, 3L), replay.map { it.serverSeq })
                assertContentEquals(bytes(2), replay.first().envelope)
                assertEquals(Instant.ofEpochMilli(1_001L).toEpochMilli(), replay.first().serverTsMillis)
                assertEquals(2, database.inboxStore.queueDepth())
            }
        }

    private fun database() = PostgresDatabase.open(databaseConfiguration())

    private fun databaseConfiguration() =
        DatabaseConfiguration(
            url = testDatabaseUrl ?: postgres.jdbcUrl,
            username = testDatabaseCredential(TEST_DATABASE_USER) ?: postgres.username,
            password = testDatabaseCredential(TEST_DATABASE_PASSWORD) ?: postgres.password,
            maxConnections = 10,
            autoMigrate = true,
        )

    private fun mailbox(id: String) =
        Mailbox(
            id = id,
            identityKey = bytes(31),
            signedPreKey = signed(1),
            kyberPreKey = signed(1),
            deliveryKeyHash = bytes(32),
            createdAtMillis = 1_000L,
        )

    private fun signed(id: Int) = SignedPublicKey(id, bytes(33), bytes(64))

    private fun bytes(value: Int) = ByteArray(32) { value.toByte() }

    private companion object {
        val postgres = PostgreSQLContainer<Nothing>("postgres:17")
        val testDatabaseUrl =
            System.getenv(TEST_DATABASE_URL)?.also { url ->
                val host = url.removePrefix("jdbc:postgresql://").substringBefore('/').substringBefore(':')
                val name = url.substringBefore('?').substringAfterLast('/')
                check(host in setOf("localhost", "127.0.0.1") && name.endsWith("_test")) {
                    "$TEST_DATABASE_URL must target localhost and a database ending in _test"
                }
            }

        @BeforeAll
        @JvmStatic
        fun startPostgres() {
            if (testDatabaseUrl == null) postgres.start()
        }

        @AfterAll
        @JvmStatic
        fun stopPostgres() {
            if (testDatabaseUrl == null) postgres.stop()
        }

        private fun testDatabaseCredential(variable: String): String? =
            testDatabaseUrl?.let {
                checkNotNull(System.getenv(variable)) { "$variable is required with $TEST_DATABASE_URL" }
            }

        private const val TEST_DATABASE_URL = "VALENCE_TEST_DATABASE_URL"
        private const val TEST_DATABASE_USER = "VALENCE_TEST_DATABASE_USER"
        private const val TEST_DATABASE_PASSWORD = "VALENCE_TEST_DATABASE_PASSWORD"
    }
}
