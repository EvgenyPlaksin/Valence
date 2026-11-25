package com.lnight.valence.server.store

import com.lnight.valence.server.domain.AppendResult
import com.lnight.valence.server.domain.ConfirmResult
import com.lnight.valence.server.domain.InboxPollResult
import com.lnight.valence.server.domain.InboxStore
import com.lnight.valence.server.domain.NewMessage
import com.lnight.valence.server.domain.QueuedMessage
import java.util.concurrent.ConcurrentHashMap

class InMemoryInboxStore : InboxStore {
    private val queues = ConcurrentHashMap<String, Queue>()

    override suspend fun append(
        mailboxId: String,
        message: NewMessage,
        maxQueued: Int,
    ): AppendResult {
        val queue = queues.computeIfAbsent(mailboxId) { Queue() }
        return synchronized(queue) {
            queue.messages.removeAll { it.expiresAtMillis <= message.serverTsMillis }
            if (queue.messages.size >= maxQueued) {
                AppendResult.QuotaExceeded
            } else {
                queue.lastSeq += 1
                queue.messages +=
                    QueuedMessage(queue.lastSeq, message.serverTsMillis, message.envelope, message.expiresAtMillis)
                AppendResult.Appended(queue.lastSeq)
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
