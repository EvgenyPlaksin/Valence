package com.lnight.valence.server.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock

class ReceiveSession(
    private val mailboxId: String,
    private val generation: Long,
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

    suspend fun checkForUpdates(): InboxPollResult? =
        mutex.withLock {
            val position = cursor ?: return null
            inbox.checkForUpdates(mailboxId, position, clock.millis())
        }

    suspend fun confirm(upToServerSeq: Long): ConfirmResult =
        mutex.withLock {
            val sent = cursor
            if (sent == null || upToServerSeq < 0) {
                ConfirmResult.INVALID
            } else {
                inbox.confirm(mailboxId, generation, minOf(upToServerSeq, sent))
            }
        }

    private companion object {
        const val BATCH_SIZE = 100
        const val FRAMES_KEY = "frames"
    }
}
