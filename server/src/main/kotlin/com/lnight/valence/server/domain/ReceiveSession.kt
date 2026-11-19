package com.lnight.valence.server.domain

import java.time.Clock

class ReceiveSession(
    private val mailboxId: String,
    private val inbox: InboxStore,
    private val clock: Clock,
    private val framePolicy: RateLimitPolicy = RateLimitPolicies().socketFrames,
) {
    private val frames = TokenBucketRateLimiter(clock)
    private var cursor: Long? = null

    fun allowFrame(): RateLimitDecision = frames.tryAcquire(FRAMES_KEY, framePolicy)

    @Synchronized
    fun resume(afterServerSeq: Long): Boolean {
        val accepted = cursor == null && afterServerSeq >= 0
        if (accepted) cursor = afterServerSeq
        return accepted
    }

    @Synchronized
    fun nextBatch(): List<QueuedMessage> {
        val position = cursor ?: return emptyList()
        val batch = inbox.messagesAfter(mailboxId, position, clock.millis(), BATCH_SIZE)
        batch.lastOrNull()?.let { cursor = it.serverSeq }
        return batch
    }

    @Synchronized
    fun confirm(upToServerSeq: Long): Boolean {
        val sent = cursor
        return if (sent == null || upToServerSeq < 0) {
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
