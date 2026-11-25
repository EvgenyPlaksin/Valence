package com.lnight.valence.server.store

import com.lnight.valence.server.DatabaseConfiguration
import com.lnight.valence.server.domain.AppendResult
import com.lnight.valence.server.domain.ConfirmResult
import com.lnight.valence.server.domain.InboxPollResult
import com.lnight.valence.server.domain.Mailbox
import com.lnight.valence.server.domain.NewMessage
import com.lnight.valence.server.domain.OneTimeKyberPreKey
import com.lnight.valence.server.domain.OneTimePreKey
import com.lnight.valence.server.domain.SignedPublicKey
import com.lnight.valence.server.routes.ServerMetrics
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CompletableDeferred
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
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
                    statement.execute(
                        """
                        TRUNCATE TABLE inbox_message, one_time_kyber_prekey, one_time_prekey,
                            mailbox RESTART IDENTITY CASCADE
                        """.trimIndent(),
                    )
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

                val duplicateKyberKeys =
                    listOf(
                        OneTimeKyberPreKey(101, bytes(11), bytes(64)),
                        OneTimeKyberPreKey(101, bytes(12), bytes(64)),
                    )
                assertFails { database.mailboxStore.create(mailbox, emptyList(), duplicateKyberKeys) }
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
                        listOf(
                            OneTimeKyberPreKey(30, bytes(30), bytes(64)),
                            OneTimeKyberPreKey(25, bytes(25), bytes(64)),
                        ),
                    ),
                )
                assertEquals(2, database.mailboxStore.oneTimePreKeyCount(mailbox.id))
                assertEquals(2, database.mailboxStore.oneTimeKyberPreKeyCount(mailbox.id))

                assertEquals(
                    com.lnight.valence.server.domain.KeyStoreOutcome.ID_EXISTS,
                    database.mailboxStore.addOneTimeKyberPreKeys(
                        mailbox.id,
                        listOf(OneTimeKyberPreKey(1, bytes(1), bytes(64))),
                        200,
                    ),
                )
                assertEquals(
                    com.lnight.valence.server.domain.KeyStoreOutcome.ID_EXISTS,
                    database.mailboxStore.replaceSignedPreKeys(mailbox.id, signed(2), signed(30)),
                )

                val first = database.mailboxStore.takeBundle(mailbox.id)
                val second = database.mailboxStore.takeBundle(mailbox.id)
                val exhausted = database.mailboxStore.takeBundle(mailbox.id)

                assertEquals(20, first?.oneTimePreKey?.id)
                assertEquals(30, first?.oneTimeKyberPreKey?.id)
                assertEquals(10, second?.oneTimePreKey?.id)
                assertEquals(25, second?.oneTimeKyberPreKey?.id)
                assertNull(exhausted?.oneTimePreKey)
                assertNull(exhausted?.oneTimeKyberPreKey)
                assertEquals(0, database.mailboxStore.oneTimePreKeyCount(mailbox.id))
                assertEquals(0, database.mailboxStore.oneTimeKyberPreKeyCount(mailbox.id))

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
                val generation = checkNotNull(database.inboxStore.advanceSocketGeneration(mailbox.id))
                assertEquals(ConfirmResult.CONFIRMED, database.inboxStore.confirm(mailbox.id, generation, 1))

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
                val kyberKeys = (101..120).map { OneTimeKyberPreKey(it, bytes(it), bytes(64)) }
                database.mailboxStore.create(mailbox, keys, kyberKeys)

                val bundles =
                    (1..20)
                        .map {
                            async(Dispatchers.IO) {
                                database.mailboxStore.takeBundle(mailbox.id)
                            }
                        }.awaitAll()
                val results = bundles.mapNotNull { it?.oneTimePreKey?.id }
                val kyberResults = bundles.mapNotNull { it?.oneTimeKyberPreKey?.id }

                assertEquals((1..20).toSet(), results.toSet())
                assertEquals((101..120).toSet(), kyberResults.toSet())
                assertEquals(0, database.mailboxStore.oneTimePreKeyCount(mailbox.id))
                assertEquals(0, database.mailboxStore.oneTimeKyberPreKeyCount(mailbox.id))
            }
        }

    @Test
    fun bundleFetchRollbackPreservesBothOneTimePrekeyQueues() =
        runTest {
            database().use { database ->
                val mailbox = mailbox("mailbox-prekey-rollback")
                database.mailboxStore.create(
                    mailbox,
                    listOf(OneTimePreKey(10, bytes(10))),
                    listOf(OneTimeKyberPreKey(110, bytes(110), bytes(64))),
                )
                installKyberDeleteFailure()
                try {
                    assertFails { database.mailboxStore.takeBundle(mailbox.id) }
                } finally {
                    removeKyberDeleteFailure()
                }

                assertEquals(1, database.mailboxStore.oneTimePreKeyCount(mailbox.id))
                assertEquals(1, database.mailboxStore.oneTimeKyberPreKeyCount(mailbox.id))
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

    @Test
    fun socketGenerationFencesConfirmationAndPollingReadsBothStates() =
        runTest {
            database().use { database ->
                val mailbox = mailbox("mailbox-generation")
                database.mailboxStore.create(mailbox, emptyList())
                database.inboxStore.append(mailbox.id, NewMessage(bytes(1), 1_000L, 10_000L), 10)
                val originalGeneration = database.inboxStore.advanceSocketGeneration(mailbox.id)

                assertEquals(1L, originalGeneration)
                assertEquals(
                    InboxPollResult(1L, true),
                    database.inboxStore.checkForUpdates(mailbox.id, 0, 2_000L),
                )

                val replacementGeneration = database.inboxStore.advanceSocketGeneration(mailbox.id)

                assertEquals(2L, replacementGeneration)
                assertEquals(ConfirmResult.STALE, database.inboxStore.confirm(mailbox.id, 1, 1))
                assertEquals(listOf(1L), database.inboxStore.messagesAfter(mailbox.id, 0).map { it.serverSeq })
                assertEquals(ConfirmResult.CONFIRMED, database.inboxStore.confirm(mailbox.id, 2, 1))
                assertEquals(emptyList(), database.inboxStore.messagesAfter(mailbox.id, 0))
            }
        }

    @Test
    fun concurrentConnectAndConfirmSerializeOnTheSocketStateRow() =
        runTest {
            database().use { database ->
                val mailbox = mailbox("mailbox-generation-race")
                database.mailboxStore.create(mailbox, emptyList())
                database.inboxStore.append(mailbox.id, NewMessage(bytes(1), 1_000L, 10_000L), 10)
                val originalGeneration = checkNotNull(database.inboxStore.advanceSocketGeneration(mailbox.id))

                val start = CompletableDeferred<Unit>()
                val connect =
                    async(Dispatchers.IO) {
                        start.await()
                        database.inboxStore.advanceSocketGeneration(mailbox.id)
                    }
                val confirm =
                    async(Dispatchers.IO) {
                        start.await()
                        database.inboxStore.confirm(mailbox.id, originalGeneration, 1)
                    }
                start.complete(Unit)
                val newGeneration = connect.await()
                val confirmResult = confirm.await()
                val messages = database.inboxStore.messagesAfter(mailbox.id, 0).map { it.serverSeq }

                assertEquals(originalGeneration + 1, newGeneration)
                when (confirmResult) {
                    ConfirmResult.CONFIRMED -> assertEquals(emptyList(), messages)
                    ConfirmResult.STALE -> assertEquals(listOf(1L), messages)
                    ConfirmResult.INVALID -> error("The valid confirmation was rejected as invalid")
                }
            }
        }

    @Test
    fun concurrentReconnectsAdvanceTheGenerationWithoutLostUpdates() =
        runTest {
            database().use { database ->
                val mailbox = mailbox("mailbox-reconnect-storm")
                database.mailboxStore.create(mailbox, emptyList())
                val generations =
                    (1..32)
                        .map {
                            async(Dispatchers.IO) { database.inboxStore.advanceSocketGeneration(mailbox.id) }
                        }.awaitAll()

                assertEquals((1L..32L).toSet(), generations.toSet())
                assertEquals(32L, database.inboxStore.currentSocketGeneration(mailbox.id))
            }
        }

    @Test
    fun purgerSkipsWhenAnotherInstanceHoldsTheAdvisoryLock() =
        runTest {
            database().use { first ->
                database().use { second ->
                    val mailbox = mailbox("mailbox-purge-lock")
                    first.mailboxStore.create(mailbox, emptyList())
                    first.inboxStore.append(mailbox.id, NewMessage(bytes(1), 1_000L, 2_000L), 10)
                    val configuration = databaseConfiguration()
                    DriverManager
                        .getConnection(
                            configuration.url,
                            configuration.username,
                            configuration.password,
                        ).use { lockConnection ->
                            lockConnection.autoCommit = false
                            lockConnection
                                .prepareStatement("SELECT pg_advisory_xact_lock(?)")
                                .use { statement ->
                                    statement.setLong(1, PURGE_LOCK_ID)
                                    statement.executeQuery().use { result -> result.next() }
                                }
                            assertEquals(0, second.inboxStore.purgeExpired(2_000L))
                            lockConnection.commit()
                        }
                    assertEquals(1, second.inboxStore.purgeExpired(2_000L))
                }
            }
        }

    @Test
    fun hikariMetricsExposePoolWaitersAndClearThemAfterRelease() =
        runTest {
            database().use { database ->
                val dataSource =
                    checkNotNull(
                        PostgresDatabase::class.java
                            .getDeclaredField("dataSource")
                            .apply { isAccessible = true }
                            .get(database) as? HikariDataSource,
                    )
                val poolMetrics =
                    PostgresDatabase::class.java
                        .getDeclaredField("poolMetrics")
                        .apply { isAccessible = true }
                        .get(database)
                val metrics = ServerMetrics()
                ServerMetrics::class.java
                    .getDeclaredField("databasePoolMetrics")
                    .apply { isAccessible = true }
                    .set(metrics, poolMetrics)
                val heldConnections =
                    (1..dataSource.hikariConfigMXBean.maximumPoolSize)
                        .map { dataSource.connection }
                val borrower =
                    CompletableFuture.supplyAsync {
                        dataSource.connection.use { true }
                    }
                try {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    var waitingMetrics = metrics.render()
                    while (!waitingMetrics.contains("valence_hikari_threads_awaiting_connection 1")) {
                        if (System.nanoTime() >= deadline) break
                        Thread.sleep(10)
                        waitingMetrics = metrics.render()
                    }
                    assertTrue(waitingMetrics.contains("valence_hikari_connections_active 10"))
                    assertTrue(waitingMetrics.contains("valence_hikari_threads_awaiting_connection 1"))

                    heldConnections.first().close()
                    assertTrue(borrower.get(5, TimeUnit.SECONDS))
                } finally {
                    heldConnections.forEach { it.close() }
                }

                val completedMetrics = metrics.render()
                assertTrue(completedMetrics.contains("valence_hikari_connection_timeouts_total 0"))
                assertTrue(completedMetrics.contains("valence_hikari_threads_awaiting_connection 0"))
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

    private fun installKyberDeleteFailure() {
        val configuration = databaseConfiguration()
        DriverManager
            .getConnection(configuration.url, configuration.username, configuration.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE FUNCTION fail_one_time_kyber_delete() RETURNS trigger
                        LANGUAGE plpgsql AS $$
                        BEGIN
                            RAISE EXCEPTION 'injected Kyber prekey delete failure';
                        END;
                        $$
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        CREATE TRIGGER fail_one_time_kyber_delete
                        BEFORE DELETE ON one_time_kyber_prekey
                        FOR EACH ROW EXECUTE FUNCTION fail_one_time_kyber_delete()
                        """.trimIndent(),
                    )
                }
            }
    }

    private fun removeKyberDeleteFailure() {
        val configuration = databaseConfiguration()
        DriverManager
            .getConnection(configuration.url, configuration.username, configuration.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP TRIGGER IF EXISTS fail_one_time_kyber_delete ON one_time_kyber_prekey")
                    statement.execute("DROP FUNCTION IF EXISTS fail_one_time_kyber_delete()")
                }
            }
    }

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
        private const val PURGE_LOCK_ID = 0x56414C454E4345L
    }
}
