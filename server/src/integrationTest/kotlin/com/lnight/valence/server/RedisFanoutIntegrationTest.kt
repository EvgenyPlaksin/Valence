package com.lnight.valence.server

import com.lnight.valence.server.domain.ConfirmResult
import com.lnight.valence.server.domain.Mailbox
import com.lnight.valence.server.domain.MailboxSubscriptions
import com.lnight.valence.server.domain.NewMessage
import com.lnight.valence.server.domain.SignedPublicKey
import com.lnight.valence.server.routes.ServerMetrics
import com.lnight.valence.server.store.PostgresDatabase
import io.lettuce.core.RedisClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Suppress("MagicNumber")
class RedisFanoutIntegrationTest {
    @Test
    fun instancesReceiveCrossNodeWakesAndVerifySupersessionHintsAgainstPostgres() =
        runBlocking {
            database().use { database ->
                clearStore()
                val mailbox = mailbox()
                database.mailboxStore.create(mailbox, emptyList())
                val subscriptions = MailboxSubscriptions()
                val subscriber = RedisFanout(redisConfiguration(), subscriptions, database.inboxStore, ServerMetrics())
                val publisher =
                    RedisFanout(redisConfiguration(), MailboxSubscriptions(), database.inboxStore, ServerMetrics())
                subscriber.start()
                publisher.start()
                try {
                    awaitUntil { subscriber.gauges()["valence_redis_subscriber_connected"] == 1L }
                    awaitUntil { publisher.gauges()["valence_redis_subscriber_connected"] == 1L }
                    delay(200)

                    val generation = checkNotNull(database.inboxStore.advanceSocketGeneration(mailbox.id))
                    val socket = subscriptions.subscribe(mailbox.id, generation)
                    publisher.messageStored(mailbox.id)
                    withTimeout(10.seconds) { socket.wake.receive() }

                    redisPublish("socket|${mailbox.id}|0")
                    redisPublish("socket|${mailbox.id}|$generation")
                    redisPublish("socket|${mailbox.id}|999")
                    delay(200)
                    assertFalse(socket.superseded.isCompleted)

                    val now = System.currentTimeMillis()
                    database.inboxStore.append(
                        mailbox.id,
                        mailbox.deliveryKeyHash,
                        NewMessage(byteArrayOf(1), now, now + 60_000L),
                        10,
                    )
                    val nextGeneration = checkNotNull(database.inboxStore.advanceSocketGeneration(mailbox.id))
                    publisher.generationAdvanced(mailbox.id, nextGeneration)
                    withTimeout(10.seconds) { socket.superseded.await() }
                    assertEquals(ConfirmResult.STALE, database.inboxStore.confirm(mailbox.id, generation, 1))
                    assertEquals(listOf(1L), database.inboxStore.messagesAfter(mailbox.id, 0).map { it.serverSeq })
                } finally {
                    publisher.close()
                    subscriber.close()
                }
            }
        }

    @Test
    fun publisherFailureDoesNotWakeAnotherConnectedInstanceButPostgresPollingRecovers() =
        runBlocking {
            database().use { database ->
                clearStore()
                val mailbox = mailbox()
                database.mailboxStore.create(mailbox, emptyList())
                val generation = checkNotNull(database.inboxStore.advanceSocketGeneration(mailbox.id))
                val subscriptions = MailboxSubscriptions()
                val subscriberMetrics = ServerMetrics()
                val subscriber =
                    RedisFanout(redisConfiguration(), subscriptions, database.inboxStore, subscriberMetrics)
                val failedPublisherMetrics = ServerMetrics()
                val failedPublisher =
                    RedisFanout(
                        RedisConfiguration("redis://127.0.0.1:1", CHANNEL),
                        MailboxSubscriptions(),
                        database.inboxStore,
                        failedPublisherMetrics,
                    )
                subscriber.start()
                failedPublisher.start()
                try {
                    awaitUntil { subscriber.gauges()["valence_redis_subscriber_connected"] == 1L }
                    delay(200)
                    val socket = subscriptions.subscribe(mailbox.id, generation)
                    val session =
                        database.inboxStore.checkForUpdates(
                            mailbox.id,
                            0,
                            Instant.now().toEpochMilli(),
                        )

                    assertEquals(false, session?.hasMessages)
                    database.inboxStore.append(
                        mailbox.id,
                        mailbox.deliveryKeyHash,
                        NewMessage(
                            byteArrayOf(1),
                            System.currentTimeMillis(),
                            System.currentTimeMillis() + 60_000L,
                        ),
                        10,
                    )
                    failedPublisher.messageStored(mailbox.id)
                    awaitUntil {
                        failedPublisherMetrics
                            .render()
                            .contains("valence_redis_publish_failures_total")
                    }

                    assertTrue(subscriber.gauges()["valence_redis_subscriber_connected"] == 1L)
                    assertTrue(socket.wake.tryReceive().isFailure)
                    assertEquals(
                        true,
                        database.inboxStore.checkForUpdates(mailbox.id, 0, Instant.now().toEpochMilli())?.hasMessages,
                    )
                } finally {
                    failedPublisher.close()
                    subscriber.close()
                }
            }
        }

    @Test
    fun redisUnavailableAtStartupDoesNotBlockTheNotifier() =
        runBlocking {
            database().use { database ->
                val notifier =
                    RedisFanout(
                        RedisConfiguration("redis://127.0.0.1:1", CHANNEL),
                        MailboxSubscriptions(),
                        database.inboxStore,
                        ServerMetrics(),
                    )

                notifier.start()
                notifier.close()
            }
        }

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(10.seconds) {
            while (!condition()) delay(20)
        }
    }

    private fun database() =
        PostgresDatabase.open(
            DatabaseConfiguration(postgres.jdbcUrl, postgres.username, postgres.password, 10, true),
        )

    private fun clearStore() {
        java.sql.DriverManager
            .getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("TRUNCATE TABLE inbox_message, one_time_prekey, mailbox RESTART IDENTITY CASCADE")
                }
            }
    }

    private suspend fun redisPublish(message: String) {
        val client = RedisClient.create(redisUri)
        try {
            client.connect().use { connection ->
                connection.async().publish(CHANNEL, message).get()
            }
        } finally {
            client.shutdown()
        }
    }

    private fun redisConfiguration() = RedisConfiguration(redisUri, CHANNEL)

    private fun mailbox() =
        Mailbox(
            id = "mbx_g4r2k7x5qmwv6bnzdcs3n4pe5a",
            identityKey = ByteArray(32) { 1 },
            signedPreKey = SignedPublicKey(1, ByteArray(32) { 2 }, ByteArray(64) { 3 }),
            kyberPreKey = SignedPublicKey(1, ByteArray(32) { 4 }, ByteArray(64) { 5 }),
            deliveryKeyHash = ByteArray(32) { 6 },
            createdAtMillis = 1_000L,
        )

    private companion object {
        const val CHANNEL = "valence:integration:notifications"
        val postgres = PostgreSQLContainer<Nothing>("postgres:17")
        val redis = GenericContainer<Nothing>("redis:8.12.0").apply { withExposedPorts(6379) }
        val redisUri: String
            get() = "redis://${redis.host}:${redis.getMappedPort(6379)}"

        @BeforeAll
        @JvmStatic
        fun startContainers() {
            postgres.start()
            redis.start()
        }

        @AfterAll
        @JvmStatic
        fun stopContainers() {
            redis.stop()
            postgres.stop()
        }
    }
}
