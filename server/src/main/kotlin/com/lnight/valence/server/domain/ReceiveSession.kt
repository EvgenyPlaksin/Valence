package com.lnight.valence.server.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock

class ReceiveSession(
    private val mailboxId: String,
    private val inbox: InboxStore,
    private val clock: Clock,
    private val framePolicy: RateLimitPolicy = RateLimitPolicies().socketFrames,
) {
    private val frames = TokenBucketRateLimiter(clock)
    private val mutex = Mutex()
    private var cursor: Long? = null

    fun allowFrame(): RateLimitDecision = frames.tryAcquire(FRAMES_KEY, framePolicy)

    suspend fun resume(afterServerSeq: Long): Boolean =
        mutex.withLock {
            val accepted = cursor == null && afterServerSeq >= 0
            if (accepted) cursor = afterServerSeq
            accepted
        }

    suspend fun nextBatch(): List<QueuedMessage> =
        mutex.withLock {
            val position = cursor ?: return emptyList()
            val batch = inbox.messagesAfter(mailboxId, position, clock.millis(), BATCH_SIZE)
            batch.lastOrNull()?.let { cursor = it.serverSeq }
            batch
        }

    suspend fun confirm(upToServerSeq: Long): Boolean =
        mutex.withLock {
            val sent = cursor
            if (sent == null || upToServerSeq < 0) {
                false
            } else {
                inbox.deleteUpTo(mailboxId, minOf(upToServerSeq, sent))
                true
            }
        }

    private companion object {
        const val BATCH_SIZE = 100
        const val FRAMES_KEY = "frames"
    }
}
