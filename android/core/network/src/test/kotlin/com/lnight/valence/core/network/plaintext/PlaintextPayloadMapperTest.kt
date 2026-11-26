package com.lnight.valence.core.network.plaintext

import com.lnight.valence.core.model.ConversationId
import com.lnight.valence.core.model.MessageId
import com.lnight.valence.core.model.MessageKind
import com.lnight.valence.core.model.Plaintext
import com.lnight.valence.protocol.plaintext.PlaintextPayload
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaintextPayloadMapperTest {
    private val mapper = PlaintextPayloadMapper()

    @Test
    fun `maps domain fields to protocol payload`() {
        val plaintext =
            Plaintext(
                kind = MessageKind.Text,
                messageId = MessageId("message-1"),
                sentAt = 1_760_000_000_000L,
                conversationId = ConversationId("conversation-1"),
                body = "hello",
                replyToId = MessageId("message-0"),
            )

        assertEquals(
            PlaintextPayload(
                kind = "text",
                messageId = "message-1",
                sentAt = 1_760_000_000_000L,
                conversationId = "conversation-1",
                body = "hello",
                replyTo = "message-0",
            ),
            mapper.toPayload(plaintext),
        )
    }

    @Test
    fun `maps protocol payload to domain fields`() {
        val payload =
            PlaintextPayload(
                v = 1,
                kind = "system",
                messageId = "message-1",
                sentAt = 1_760_000_000_000L,
                conversationId = "conversation-1",
                body = null,
                replyTo = "message-0",
            )

        assertEquals(
            Plaintext(
                kind = MessageKind.System,
                messageId = MessageId("message-1"),
                sentAt = 1_760_000_000_000L,
                conversationId = ConversationId("conversation-1"),
                body = null,
                replyToId = MessageId("message-0"),
            ),
            mapper.toPlaintext(payload),
        )
    }
}
