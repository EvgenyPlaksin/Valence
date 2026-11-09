package com.lnight.valence.server.store

import com.lnight.valence.server.domain.AppendResult
import com.lnight.valence.server.domain.InboxStore
import com.lnight.valence.server.domain.NewMessage
import com.lnight.valence.server.domain.QueuedMessage
import java.util.concurrent.ConcurrentHashMap

class InMemoryInboxStore : InboxStore {
    private val queues = ConcurrentHashMap<String, Queue>()

    override fun append(
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

    override fun messagesAfter(
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

    override fun deleteUpTo(
        mailboxId: String,
        upToServerSeq: Long,
    ) {
        queues[mailboxId]?.let { queue ->
            synchronized(queue) { queue.messages.removeAll { it.serverSeq <= upToServerSeq } }
        }
    }

    private class Queue {
        var lastSeq = 0L
        val messages = mutableListOf<QueuedMessage>()
    }
}
