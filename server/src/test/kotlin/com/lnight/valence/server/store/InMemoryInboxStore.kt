package com.lnight.valence.server.store

import com.lnight.valence.server.domain.AppendResult
import com.lnight.valence.server.domain.ConfirmResult
import com.lnight.valence.server.domain.DeliveryAuthorizationResult
import com.lnight.valence.server.domain.InboxPollResult
import com.lnight.valence.server.domain.InboxStore
import com.lnight.valence.server.domain.NewMessage
import com.lnight.valence.server.domain.QueuedMessage
import com.lnight.valence.server.domain.RateLimitDecision
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class InMemoryInboxStore(
    private val currentDeliveryKeyHash: ((String) -> ByteArray?)? = null,
) : InboxStore {
    private val queues = ConcurrentHashMap<String, Queue>()

    override suspend fun authorize(
        mailboxId: String,
        expectedDeliveryKeyHash: ByteArray,
        rateLimit: () -> RateLimitDecision,
    ): DeliveryAuthorizationResult {
        val storedHash = currentDeliveryKeyHash?.invoke(mailboxId)
        if (
            currentDeliveryKeyHash != null &&
            (storedHash == null || !MessageDigest.isEqual(expectedDeliveryKeyHash, storedHash))
        ) {
            return DeliveryAuthorizationResult.Unauthorized
        }
        return when (val decision = rateLimit()) {
            RateLimitDecision.Allowed -> DeliveryAuthorizationResult.Authorized
            is RateLimitDecision.Limited -> DeliveryAuthorizationResult.RateLimited(decision.retryAfterMillis)
        }
    }

    override suspend fun append(
        mailboxId: String,
        expectedDeliveryKeyHash: ByteArray,
        message: NewMessage,
        maxQueued: Int,
        rateLimit: () -> RateLimitDecision,
    ): AppendResult {
        val queue = queues.computeIfAbsent(mailboxId) { Queue() }
        return synchronized(queue) {
            val storedHash = currentDeliveryKeyHash?.invoke(mailboxId)
            if (
                currentDeliveryKeyHash != null &&
                (
                    storedHash == null ||
                        !MessageDigest.isEqual(expectedDeliveryKeyHash, storedHash)
                )
            ) {
                return@synchronized AppendResult.Unauthorized
            }
            queue.messages.removeAll { it.expiresAtMillis <= message.serverTsMillis }
            when (val decision = rateLimit()) {
                is RateLimitDecision.Limited -> {
                    AppendResult.RateLimited(decision.retryAfterMillis)
                }

                RateLimitDecision.Allowed -> {
                    if (queue.messages.size >= maxQueued) {
                        AppendResult.QuotaExceeded
                    } else {
                        queue.lastSeq += 1
                        queue.messages +=
                            QueuedMessage(
                                queue.lastSeq,
                                message.serverTsMillis,
                                message.envelope,
                                message.expiresAtMillis,
                            )
                        AppendResult.Appended(queue.lastSeq)
                    }
                }
            }
        }
    }

    override suspend fun messagesAfter(
        mailboxId: String,
        afterServerSeq: Long,
        nowMillis: Long,
        limit: Int,
    ): List<QueuedMessage> {
        val queue = queues[mailboxId]
        return if (queue == null) {
            emptyList()
        } else {
            synchronized(queue) {
                queue.messages.filter { it.serverSeq > afterServerSeq && it.expiresAtMillis > nowMillis }.take(limit)
            }
        }
    }

    override suspend fun advanceSocketGeneration(mailboxId: String): Long {
        val queue = queues.computeIfAbsent(mailboxId) { Queue() }
        return synchronized(queue) { ++queue.generation }
    }

    override suspend fun currentSocketGeneration(mailboxId: String): Long? =
        queues[mailboxId]?.let { queue -> synchronized(queue) { queue.generation } }

    override suspend fun checkForUpdates(
        mailboxId: String,
        afterServerSeq: Long,
        nowMillis: Long,
    ): InboxPollResult? =
        queues[mailboxId]?.let { queue ->
            synchronized(queue) {
                InboxPollResult(
                    generation = queue.generation,
                    hasMessages =
                        queue.messages.any {
                            it.serverSeq > afterServerSeq && it.expiresAtMillis > nowMillis
                        },
                )
            }
        }

    override suspend fun confirm(
        mailboxId: String,
        generation: Long,
        upToServerSeq: Long,
    ): ConfirmResult {
        val queue = queues[mailboxId] ?: return ConfirmResult.STALE
        return synchronized(queue) {
            if (queue.generation != generation) {
                ConfirmResult.STALE
            } else {
                queue.messages.removeAll { it.serverSeq <= upToServerSeq }
                ConfirmResult.CONFIRMED
            }
        }
    }

    override suspend fun purgeExpired(nowMillis: Long): Int =
        queues.values.sumOf { queue ->
            synchronized(queue) {
                val before = queue.messages.size
                queue.messages.removeAll { it.expiresAtMillis <= nowMillis }
                before - queue.messages.size
            }
        }

    override suspend fun queueDepth(): Int =
        queues.values.sumOf { queue -> synchronized(queue) { queue.messages.size } }

    private class Queue {
        var lastSeq = 0L
        var generation = 0L
        val messages = mutableListOf<QueuedMessage>()
    }
}
