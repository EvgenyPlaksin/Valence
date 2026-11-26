package com.lnight.valence.core.model

data class Message(
    val id: MessageId,
    val conversationId: ConversationId,
    val direction: MessageDirection,
    val status: MessageStatus,
    val kind: MessageKind,
    val body: String?,
    val replyToId: MessageId?,
    val sentAtMillis: Long,
    val serverSeq: Long?,
    val serverTsMillis: Long?,
    val receivedAtMillis: Long?,
    val createdAtMillis: Long,
)
