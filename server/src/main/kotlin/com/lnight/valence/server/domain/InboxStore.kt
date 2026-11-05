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
    ): List<QueuedMessage>
}
