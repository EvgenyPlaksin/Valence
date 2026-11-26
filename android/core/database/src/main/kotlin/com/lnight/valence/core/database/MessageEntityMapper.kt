package com.lnight.valence.core.database

import com.lnight.valence.core.model.ConversationId
import com.lnight.valence.core.model.Message
import com.lnight.valence.core.model.MessageDirection
import com.lnight.valence.core.model.MessageId
import com.lnight.valence.core.model.MessageKind
import com.lnight.valence.core.model.MessageStatus
import javax.inject.Inject

internal class MessageEntityMapper
    @Inject
    constructor() {
        fun toDomain(entity: MessageEntity): Message =
            Message(
                id = MessageId(entity.messageId),
                conversationId = ConversationId(entity.conversationId),
                direction = MessageDirection.entries[entity.direction],
                status = MessageStatus.entries[entity.status],
                kind = MessageKind.entries[entity.kind],
                body = entity.body,
                replyToId = entity.replyToId?.let { MessageId(it) },
                sentAtMillis = entity.sentAtMillis,
                serverSeq = entity.serverSeq,
                serverTsMillis = entity.serverTsMillis,
                receivedAtMillis = entity.receivedAtMillis,
                createdAtMillis = entity.createdAtMillis,
            )

        fun toEntity(domain: Message): MessageEntity {
            require(domain.direction == MessageDirection.Inbound || domain.serverSeq == null) {
                "Outbound message must have null serverSeq"
            }
            return MessageEntity(
                messageId = domain.id.value,
                conversationId = domain.conversationId.value,
                direction = domain.direction.ordinal,
                status = domain.status.ordinal,
                kind = domain.kind.ordinal,
                body = domain.body,
                replyToId = domain.replyToId?.value,
                sentAtMillis = domain.sentAtMillis,
                serverSeq = domain.serverSeq,
                serverTsMillis = domain.serverTsMillis,
                receivedAtMillis = domain.receivedAtMillis,
                createdAtMillis = domain.createdAtMillis,
            )
        }
    }
