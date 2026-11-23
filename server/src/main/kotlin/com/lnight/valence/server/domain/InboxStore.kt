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

    suspend fun deleteUpTo(
        mailboxId: String,
        upToServerSeq: Long,
    )

    suspend fun purgeExpired(nowMillis: Long): Int

    suspend fun queueDepth(): Int
}
