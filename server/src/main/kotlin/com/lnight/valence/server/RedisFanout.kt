package com.lnight.valence.server

import com.lnight.valence.server.domain.InboxStore
import com.lnight.valence.server.domain.MailboxSubscriptions
import io.lettuce.core.ClientOptions
import io.lettuce.core.ClientOptions.DisconnectedBehavior
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisConnectionStateListener
import io.lettuce.core.SocketOptions
import io.lettuce.core.TimeoutOptions
import io.lettuce.core.codec.StringCodec
import io.lettuce.core.pubsub.RedisPubSubAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.net.SocketAddress
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection as PubSubConnection

@Suppress("MagicNumber", "TooManyFunctions")
class RedisFanout(
    private val configuration: RedisConfiguration?,
    private val subscriptions: MailboxSubscriptions,
    private val inbox: InboxStore,
    private val metrics: com.lnight.valence.server.routes.ServerMetrics,
) : CrossNodeNotifier {
    private val publisherQueue = Channel<Notification>(PUBLISH_QUEUE_SIZE)
    private val subscriberQueue = Channel<Notification>(SUBSCRIBER_QUEUE_SIZE)
    private val catchUpQueue = Channel<SocketCheck>(CATCH_UP_QUEUE_SIZE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean()
    private val subscriberConnected = AtomicBoolean()
    private val publisherConnected = AtomicBoolean()
    private val publisherQueueDepth = AtomicInteger()
    private var client: RedisClient? = null

    override fun start() {
        val redisConfiguration = configuration ?: return
        if (!started.compareAndSet(false, true)) return
        val redisClient = RedisClient.create(redisConfiguration.url)
        redisClient.setOptions(
            ClientOptions
                .builder()
                .autoReconnect(true)
                .disconnectedBehavior(DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(
                    SocketOptions.builder().connectTimeout(Duration.ofSeconds(COMMAND_TIMEOUT_SECONDS)).build(),
                ).timeoutOptions(TimeoutOptions.enabled(Duration.ofSeconds(COMMAND_TIMEOUT_SECONDS)))
                .build(),
        )
        client = redisClient
        repeat(CATCH_UP_WORKERS) {
            scope.launch { catchUpWorker() }
        }
        scope.launch { publisherLoop(redisClient, redisConfiguration.channel) }
        scope.launch { subscriberLoop(redisClient, redisConfiguration.channel) }
        scope.launch { subscriberEventLoop() }
    }

    override fun messageStored(mailboxId: String) {
        subscriptions.messageStored(mailboxId)
        enqueuePublisher(Notification.Wake(mailboxId))
    }

    override fun generationAdvanced(
        mailboxId: String,
        generation: Long,
    ) {
        enqueuePublisher(Notification.SocketChanged(mailboxId, generation))
    }

    override fun gauges(): Map<String, Long> =
        mapOf(
            REDIS_SUBSCRIBER_CONNECTED to if (subscriberConnected.get()) 1L else 0L,
            REDIS_PUBLISHER_CONNECTED to if (publisherConnected.get()) 1L else 0L,
            REDIS_PUBLISH_QUEUE_DEPTH to publisherQueueDepth.get().toLong(),
        )

    override fun close() {
        publisherQueue.close()
        subscriberQueue.close()
        catchUpQueue.close()
        scope.cancel()
        client?.shutdown()
        client = null
    }

    private fun enqueue(
        queue: Channel<Notification>,
        notification: Notification,
    ) {
        if (!queue.trySend(notification).isSuccess) metrics.increment(REDIS_QUEUE_OVERFLOWS)
    }

    private fun enqueuePublisher(notification: Notification) {
        if (configuration == null || !started.get()) return
        publisherQueueDepth.incrementAndGet()
        if (!publisherQueue.trySend(notification).isSuccess) {
            publisherQueueDepth.decrementAndGet()
            metrics.increment(REDIS_QUEUE_OVERFLOWS)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun publisherLoop(
        redisClient: RedisClient,
        channel: String,
    ) {
        var connection: io.lettuce.core.api.StatefulRedisConnection<String, String>? = null
        try {
            for (notification in publisherQueue) {
                publisherQueueDepth.decrementAndGet()
                try {
                    if (connection?.isOpen != true) {
                        connection?.close()
                        connection = redisClient.connect(StringCodec.UTF8)
                        connection.addListener(
                            object : RedisConnectionStateListener {
                                override fun onRedisConnected(
                                    connection: io.lettuce.core.RedisChannelHandler<*, *>,
                                    socketAddress: SocketAddress,
                                ) {
                                    publisherConnected.set(true)
                                }

                                override fun onRedisDisconnected(
                                    connection: io.lettuce.core.RedisChannelHandler<*, *>,
                                ) {
                                    publisherConnected.set(false)
                                }
                            },
                        )
                    }
                    connection
                        .async()
                        .publish(
                            channel,
                            notification.encode(),
                        ).get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    publisherConnected.set(true)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    publisherConnected.set(false)
                    connection?.close()
                    connection = null
                    metrics.increment(REDIS_PUBLISH_FAILURES)
                    logger.warn("Redis publish failed: {}", failure.javaClass.simpleName)
                }
            }
        } finally {
            connection?.close()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun subscriberLoop(
        redisClient: RedisClient,
        channel: String,
    ) {
        var delayMillis = INITIAL_RECONNECT_DELAY_MILLIS
        while (scope.isActive) {
            var connection: PubSubConnection<String, String>? = null
            try {
                connection = redisClient.connectPubSub(StringCodec.UTF8)
                connection.addListener(
                    object : RedisPubSubAdapter<String, String>() {
                        override fun message(
                            channel: String,
                            message: String,
                        ) {
                            if (channel != this@RedisFanout.configuration?.channel) return
                            val parsed = Notification.decode(message)
                            if (parsed == null) {
                                metrics.increment(REDIS_INVALID_NOTIFICATIONS)
                            } else if (parsed is Notification.Wake) {
                                subscriptions.messageStored(parsed.mailboxId)
                            } else {
                                enqueue(subscriberQueue, parsed)
                            }
                        }
                    },
                )
                connection.addListener(
                    object : RedisConnectionStateListener {
                        override fun onRedisConnected(
                            connection: io.lettuce.core.RedisChannelHandler<*, *>,
                            socketAddress: SocketAddress,
                        ) {
                            subscriberConnected.set(true)
                            subscriberQueue.trySend(Notification.CatchUp)
                        }

                        override fun onRedisDisconnected(connection: io.lettuce.core.RedisChannelHandler<*, *>) {
                            subscriberConnected.set(false)
                        }
                    },
                )
                connection.async().subscribe(channel).get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                subscriberConnected.set(true)
                subscriberQueue.trySend(Notification.CatchUp)
                delayMillis = INITIAL_RECONNECT_DELAY_MILLIS
                while (scope.isActive && connection.isOpen) delay(SUBSCRIBER_CHECK_INTERVAL_MILLIS)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                subscriberConnected.set(false)
                metrics.increment(REDIS_SUBSCRIBER_FAILURES)
                logger.warn("Redis subscriber unavailable: {}", failure.javaClass.simpleName)
                delay(delayMillis)
                delayMillis = (delayMillis * 2).coerceAtMost(MAX_RECONNECT_DELAY_MILLIS)
            } finally {
                connection?.close()
            }
        }
    }

    private suspend fun subscriberEventLoop() {
        for (notification in subscriberQueue) {
            when (notification) {
                is Notification.Wake -> {
                    subscriptions.messageStored(notification.mailboxId)
                }

                is Notification.SocketChanged -> {
                    enqueueCatchUp(SocketCheck(notification.mailboxId, notification.generation, false))
                }

                Notification.CatchUp -> {
                    subscriptions.activeMailboxIds().forEach { mailboxId ->
                        enqueueCatchUp(SocketCheck(mailboxId, null, true))
                    }
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun catchUpWorker() {
        for (check in catchUpQueue) {
            try {
                verifyCurrentSocket(check)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                metrics.increment(REDIS_CATCH_UP_FAILURES)
                logger.warn("Redis catch-up check failed: {}", failure.javaClass.simpleName)
            }
        }
    }

    private fun enqueueCatchUp(check: SocketCheck) {
        if (!catchUpQueue.trySend(check).isSuccess) metrics.increment(REDIS_CATCH_UP_DROPPED)
    }

    private suspend fun verifyCurrentSocket(check: SocketCheck) {
        val current = inbox.currentSocketGeneration(check.mailboxId) ?: return
        if (check.minimumGeneration != null && current < check.minimumGeneration) return
        subscriptions.supersedeOlder(check.mailboxId, current)
        if (check.wake) subscriptions.wakeIfCurrent(check.mailboxId, current)
    }

    private data class SocketCheck(
        val mailboxId: String,
        val minimumGeneration: Long?,
        val wake: Boolean,
    )

    private sealed interface Notification {
        fun encode(): String

        data class Wake(
            val mailboxId: String,
        ) : Notification {
            override fun encode(): String = "wake|$mailboxId"
        }

        data class SocketChanged(
            val mailboxId: String,
            val generation: Long,
        ) : Notification {
            override fun encode(): String = "socket|$mailboxId|$generation"
        }

        data object CatchUp : Notification {
            override fun encode(): String = ""
        }

        companion object {
            @Suppress("MagicNumber")
            fun decode(value: String): Notification? {
                val parts = value.split('|')
                return when {
                    parts.size == 2 && parts[0] == "wake" && parts[1].isNotBlank() -> Wake(parts[1])

                    parts.size == 3 &&
                        parts[0] == "socket" &&
                        parts[1].isNotBlank() &&
                        parts[2].toLongOrNull() != null -> SocketChanged(parts[1], parts[2].toLong())

                    else -> null
                }
            }
        }
    }

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 2L
        const val INITIAL_RECONNECT_DELAY_MILLIS = 1_000L
        const val MAX_RECONNECT_DELAY_MILLIS = 30_000L
        const val SUBSCRIBER_CHECK_INTERVAL_MILLIS = 1_000L
        const val PUBLISH_QUEUE_SIZE = 1_024
        const val SUBSCRIBER_QUEUE_SIZE = 2_048
        const val CATCH_UP_QUEUE_SIZE = 2_048
        const val CATCH_UP_WORKERS = 8
        const val REDIS_QUEUE_OVERFLOWS = "valence_redis_queue_overflows_total"
        const val REDIS_PUBLISH_FAILURES = "valence_redis_publish_failures_total"
        const val REDIS_SUBSCRIBER_FAILURES = "valence_redis_subscriber_failures_total"
        const val REDIS_INVALID_NOTIFICATIONS = "valence_redis_invalid_notifications_total"
        const val REDIS_CATCH_UP_DROPPED = "valence_redis_catch_up_dropped_total"
        const val REDIS_CATCH_UP_FAILURES = "valence_redis_catch_up_failures_total"
        const val REDIS_SUBSCRIBER_CONNECTED = "valence_redis_subscriber_connected"
        const val REDIS_PUBLISHER_CONNECTED = "valence_redis_publisher_connected"
        const val REDIS_PUBLISH_QUEUE_DEPTH = "valence_redis_publish_queue_depth"
        val logger = LoggerFactory.getLogger(RedisFanout::class.java)
    }
}
