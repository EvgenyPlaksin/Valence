package com.lnight.valence.server.domain

interface InboxStore {
    suspend fun append(
        mailboxId: String,
        message: NewMessage,
        maxQueued: Int,
    ): AppendResult

    suspend fun messagesAfter(
        mailboxId: String,
        afterServerSeq: Long,
        nowMillis: Long = 0L,
        limit: Int = Int.MAX_VALUE,
    ): List<QueuedMessage>

    suspend fun advanceSocketGeneration(mailboxId: String): Long?

    suspend fun currentSocketGeneration(mailboxId: String): Long?

    suspend fun checkForUpdates(
        mailboxId: String,
        afterServerSeq: Long,
        nowMillis: Long,
    ): InboxPollResult?

    suspend fun confirm(
        mailboxId: String,
        generation: Long,
        upToServerSeq: Long,
    ): ConfirmResult

    suspend fun purgeExpired(nowMillis: Long): Int

    suspend fun queueDepth(): Int
}
