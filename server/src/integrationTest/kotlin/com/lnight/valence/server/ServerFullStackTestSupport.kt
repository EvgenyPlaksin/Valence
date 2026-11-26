package com.lnight.valence.server

import com.lnight.valence.server.crypto.FullStackCertificateFixtures
import com.lnight.valence.server.crypto.JwtAccessTokenIssuer
import com.lnight.valence.server.crypto.JwtAccessTokenVerifier
import com.lnight.valence.server.crypto.LibsignalPublicKeyVerifier
import com.lnight.valence.server.crypto.SecureRandomBytes
import com.lnight.valence.server.domain.ChallengeService
import com.lnight.valence.server.domain.DeliveryKeyAuthenticator
import com.lnight.valence.server.domain.InboxPurger
import com.lnight.valence.server.domain.KeyManagementService
import com.lnight.valence.server.domain.LoginService
import com.lnight.valence.server.domain.MailboxSubscriptions
import com.lnight.valence.server.domain.PreKeyBundleService
import com.lnight.valence.server.domain.RateLimitPolicies
import com.lnight.valence.server.domain.ReceiveService
import com.lnight.valence.server.domain.RegistrationService
import com.lnight.valence.server.domain.SendMessageService
import com.lnight.valence.server.domain.SenderCertificateService
import com.lnight.valence.server.domain.TokenBucketRateLimiter
import com.lnight.valence.server.routes.ServerMetrics
import com.lnight.valence.server.store.PostgresDatabase
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import java.net.ServerSocket
import java.security.SecureRandom
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Suppress("MagicNumber")
internal object ServerFullStackContainers {
    private const val DATABASE_URL = "VALENCE_TEST_DATABASE_URL"
    private const val DATABASE_USER = "VALENCE_TEST_DATABASE_USER"
    private const val DATABASE_PASSWORD = "VALENCE_TEST_DATABASE_PASSWORD"
    private val postgres = PostgreSQLContainer<Nothing>("postgres:17")
    private val redis =
        GenericContainer<Nothing>("redis:8.12.0").apply {
            withExposedPorts(6379)
        }
    private var externalDatabase: DatabaseConfiguration? = null
    private val channelSequence = AtomicInteger()

    val redisUri: String
        get() = "redis://${redis.host}:${redis.getMappedPort(6379)}"

    fun start() {
        externalDatabase = externalDatabaseConfiguration()
        if (externalDatabase == null) postgres.start()
        redis.start()
    }

    fun stop() {
        redis.stop()
        if (externalDatabase == null) postgres.stop()
    }

    fun databaseConfiguration() =
        externalDatabase
            ?: DatabaseConfiguration(
                url = postgres.jdbcUrl,
                username = postgres.username,
                password = postgres.password,
                maxConnections = 10,
                autoMigrate = true,
            )

    fun redisConfiguration() =
        RedisConfiguration(
            url = redisUri,
            channel = "valence:full-stack-test:${channelSequence.incrementAndGet()}",
        )

    fun isolatedRedis(): GenericContainer<*> = FixedHostPortRedisContainer(ServerSocket(0).use { it.localPort })

    fun clearDatabase() {
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

    private fun externalDatabaseConfiguration(): DatabaseConfiguration? {
        val environment = System.getenv()
        val url = environment[DATABASE_URL] ?: return null
        val host = url.removePrefix("jdbc:postgresql://").substringBefore('/').substringBefore(':')
        val name = url.substringBefore('?').substringAfterLast('/')
        check(host in setOf("localhost", "127.0.0.1") && name.endsWith("_test")) {
            "$DATABASE_URL must target localhost and a database ending in _test"
        }
        return DatabaseConfiguration(
            url = url,
            username = checkNotNull(environment[DATABASE_USER]) { "$DATABASE_USER is required with $DATABASE_URL" },
            password =
                checkNotNull(environment[DATABASE_PASSWORD]) {
                    "$DATABASE_PASSWORD is required with $DATABASE_URL"
                },
            maxConnections = 10,
            autoMigrate = true,
        )
    }
}

private const val REDIS_CONTAINER_PORT = 6379

private class FixedHostPortRedisContainer(
    hostPort: Int,
) : GenericContainer<FixedHostPortRedisContainer>("redis:8.12.0") {
    init {
        addFixedExposedPort(hostPort, REDIS_CONTAINER_PORT)
    }
}

@Suppress("LongMethod", "MagicNumber")
internal class ServerFullStackNode(
    private val databaseConfiguration: DatabaseConfiguration,
    redisConfiguration: RedisConfiguration?,
    private val rateLimitPolicies: RateLimitPolicies = RateLimitPolicies(),
    metricsToken: String? = null,
    certificateFixtures: FullStackCertificateFixtures = FullStackCertificateFixtures(),
    private val tokenSigningKey: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes),
) : AutoCloseable {
    private val database = PostgresDatabase.open(databaseConfiguration)
    val mailboxStore = database.mailboxStore
    val inboxStore = database.inboxStore
    private val clock = java.time.Clock.systemUTC()
    private val subscriptions = MailboxSubscriptions()
    val metrics = ServerMetrics()
    private val port = ServerSocket(0).use { it.localPort }
    val crossNodeNotifier = RedisFanout(redisConfiguration, subscriptions, database.inboxStore, metrics)
    private val limiter = TokenBucketRateLimiter(clock)
    private val issuer = JwtAccessTokenIssuer(clock, tokenSigningKey)
    val trustRootPublicKey = certificateFixtures.trustRootPublicKey
    private val poolMetrics =
        PostgresDatabase::class.java
            .getDeclaredField("poolMetrics")
            .apply { isAccessible = true }
            .get(database)

    init {
        ServerMetrics::class.java
            .getDeclaredField("databasePoolMetrics")
            .apply { isAccessible = true }
            .set(metrics, poolMetrics)
    }

    private val applicationRef = arrayOfNulls<Application>(1)
    private val engine =
        embeddedServer(Netty, host = "127.0.0.1", port = port) {
            applicationRef[0] = this
            module(services(certificateFixtures, metricsToken))
        }
    private var started = false
    private var closed = false
    val application: Application
        get() = checkNotNull(applicationRef[0])
    val baseUrl: String
        get() {
            check(started)
            return "http://127.0.0.1:$port"
        }
    val webSocketUrl: String
        get() = baseUrl.replaceFirst("http", "ws")

    fun start() {
        if (started) return
        engine.start(wait = false)
        started = true
        checkNotNull(applicationRef[0])
    }

    fun tokenFor(mailboxId: String): String = issuer.issue(mailboxId).value

    fun expiredTokenFor(mailboxId: String): String =
        com.lnight.valence.server.crypto
            .expiredToken(mailboxId, tokenSigningKey)

    fun hasStoredMessages(mailboxId: String): Boolean =
        runBlocking { inboxStore.messagesAfter(mailboxId, 0).isNotEmpty() }

    fun seedInbox(
        mailboxId: String,
        count: Int,
    ) {
        val now = Instant.now()
        val nowTimestamp = Timestamp.from(now)
        val expiresAt = Timestamp.from(now.plusSeconds(60))
        DriverManager
            .getConnection(
                databaseConfiguration.url,
                databaseConfiguration.username,
                databaseConfiguration.password,
            ).use { connection ->
                connection.autoCommit = false
                connection
                    .prepareStatement("UPDATE mailbox SET next_server_seq = ? WHERE mailbox_id = ?")
                    .use { statement ->
                        statement.setLong(1, count.toLong())
                        statement.setString(2, mailboxId)
                        check(statement.executeUpdate() == 1)
                    }
                connection
                    .prepareStatement(
                        """
                        INSERT INTO inbox_message (mailbox_id, server_seq, server_ts, envelope, expires_at)
                        VALUES (?, ?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        repeat(count) { index ->
                            statement.setString(1, mailboxId)
                            statement.setLong(2, index + 1L)
                            statement.setTimestamp(3, nowTimestamp)
                            statement.setBytes(4, byteArrayOf(index.toByte()))
                            statement.setTimestamp(5, expiresAt)
                            statement.addBatch()
                        }
                        statement.executeBatch()
                    }
                connection.commit()
            }
    }

    fun stop() {
        if (closed) return
        if (started) {
            engine.stop(0, 5_000, TimeUnit.MILLISECONDS)
            started = false
        }
        database.close()
        closed = true
    }

    override fun close() = stop()

    private fun services(
        certificateFixtures: FullStackCertificateFixtures,
        metricsToken: String?,
    ): ServerServices {
        val keyVerifier = LibsignalPublicKeyVerifier()
        val store = database.mailboxStore
        val inbox = database.inboxStore
        val challengeService =
            ChallengeService(
                clock = clock,
                random = SecureRandomBytes(),
                authenticator =
                    com.lnight.valence.server.crypto.HmacChallengeAuthenticator(
                        SecureRandomBytes().next(32),
                    ),
            )
        val deliveryAuthenticator = DeliveryKeyAuthenticator(store)
        return ServerServices(
            challenges = challengeService,
            registration =
                RegistrationService(
                    challengeService,
                    keyVerifier,
                    store,
                    issuer,
                    clock,
                ),
            login = LoginService(challengeService, keyVerifier, store, issuer),
            keys = KeyManagementService(keyVerifier, store),
            bundles =
                PreKeyBundleService(
                    deliveryAuthenticator,
                    store,
                    limiter,
                    rateLimitPolicies.bundlePerMailbox,
                ),
            messages =
                SendMessageService(
                    deliveryAuthenticator,
                    inbox,
                    crossNodeNotifier,
                    limiter,
                    rateLimitPolicies.sendPerMailbox,
                    clock,
                ),
            receive = ReceiveService(inbox, clock, rateLimitPolicies.socketFrames),
            subscriptions = subscriptions,
            certificates =
                SenderCertificateService(
                    store,
                    certificateFixtures.senderCertificateIssuer,
                    clock,
                ),
            tokenVerifier = JwtAccessTokenVerifier(clock, tokenSigningKey),
            rateLimiter = limiter,
            rateLimits = rateLimitPolicies,
            trustProxyHeaders = true,
            purger = InboxPurger(inbox, clock),
            inbox = inbox,
            crossNodeNotifier = crossNodeNotifier,
            metrics = metrics,
            metricsToken = metricsToken,
            databaseReady = database::isReady,
        )
    }
}
