package com.lnight.valence.server.domain

interface InboxStore {
    fun append(
        mailboxId: String,
        message: NewMessage,
        maxQueued: Int,
    ): AppendResult

    fun messagesAfter(
        mailboxId: String,
        afterServerSeq: Long,
        nowMillis: Long = 0L,
        limit: Int = Int.MAX_VALUE,
    ): List<QueuedMessage>

    fun deleteUpTo(
        mailboxId: String,
        upToServerSeq: Long,
    )
}
