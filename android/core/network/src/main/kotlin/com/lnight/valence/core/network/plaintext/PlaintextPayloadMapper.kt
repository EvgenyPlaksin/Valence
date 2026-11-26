package com.lnight.valence.core.network.plaintext

import com.lnight.valence.core.model.ConversationId
import com.lnight.valence.core.model.MessageId
import com.lnight.valence.core.model.MessageKind
import com.lnight.valence.core.model.Plaintext
import com.lnight.valence.protocol.plaintext.PlaintextPayload
import javax.inject.Inject

class PlaintextPayloadMapper
    @Inject
    constructor() {
        fun toPayload(plaintext: Plaintext): PlaintextPayload =
            PlaintextPayload(
                kind = plaintext.kind.toWireValue(),
                messageId = plaintext.messageId.value,
                sentAt = plaintext.sentAt,
                conversationId = plaintext.conversationId.value,
                body = plaintext.body,
                replyTo = plaintext.replyToId?.value,
            )

        fun toPlaintext(payload: PlaintextPayload): Plaintext? =
            payload.kind.toDomainValue()?.let { kind ->
                Plaintext(
                    kind = kind,
                    messageId = MessageId(payload.messageId),
                    sentAt = payload.sentAt,
                    conversationId = ConversationId(payload.conversationId),
                    body = payload.body,
                    replyToId = payload.replyTo?.let(::MessageId),
                )
            }

        private fun MessageKind.toWireValue(): String =
            when (this) {
                MessageKind.Text -> "text"
                MessageKind.Attachment -> "attachment"
                MessageKind.System -> "system"
            }

        private fun String.toDomainValue(): MessageKind? =
            when (this) {
                "text" -> MessageKind.Text
                "attachment" -> MessageKind.Attachment
                "system" -> MessageKind.System
                else -> null
            }
    }
