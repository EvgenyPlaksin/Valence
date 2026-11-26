package com.lnight.valence.core.model

data class Conversation(
    val id: ConversationId,
    val contactId: ContactId,
    val lastMessageId: MessageId?,
    val lastMessageAtMillis: Long?,
    val unreadCount: Int,
    val pinned: Boolean,
    val mutedUntilMillis: Long?,
)
