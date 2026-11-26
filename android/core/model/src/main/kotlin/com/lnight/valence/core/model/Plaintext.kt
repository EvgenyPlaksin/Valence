package com.lnight.valence.core.model

data class Plaintext(
    val kind: MessageKind,
    val messageId: MessageId,
    val sentAt: Long,
    val conversationId: ConversationId,
    val body: String?,
    val replyToId: MessageId?,
)
