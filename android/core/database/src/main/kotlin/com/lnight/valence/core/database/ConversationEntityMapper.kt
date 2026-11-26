package com.lnight.valence.core.database

import com.lnight.valence.core.model.ContactId
import com.lnight.valence.core.model.Conversation
import com.lnight.valence.core.model.ConversationId
import com.lnight.valence.core.model.MessageId
import javax.inject.Inject

internal class ConversationEntityMapper
    @Inject
    constructor() {
        fun toDomain(entity: ConversationEntity): Conversation =
            Conversation(
                id = ConversationId(entity.conversationId),
                contactId = ContactId(entity.contactId),
                lastMessageId = entity.lastMessageId?.let { MessageId(it) },
                lastMessageAtMillis = entity.lastMessageAtMillis,
                unreadCount = entity.unreadCount,
                pinned = entity.pinned != 0,
                mutedUntilMillis = entity.mutedUntilMillis,
            )

        fun toEntity(domain: Conversation): ConversationEntity =
            ConversationEntity(
                conversationId = domain.id.value,
                contactId = domain.contactId.value,
                lastMessageId = domain.lastMessageId?.value,
                lastMessageAtMillis = domain.lastMessageAtMillis,
                unreadCount = domain.unreadCount,
                pinned = if (domain.pinned) 1 else 0,
                mutedUntilMillis = domain.mutedUntilMillis,
            )
    }
