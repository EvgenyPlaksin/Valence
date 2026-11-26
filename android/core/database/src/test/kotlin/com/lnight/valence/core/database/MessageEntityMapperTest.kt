package com.lnight.valence.core.database

import com.lnight.valence.core.model.ConversationId
import com.lnight.valence.core.model.Message
import com.lnight.valence.core.model.MessageDirection
import com.lnight.valence.core.model.MessageId
import com.lnight.valence.core.model.MessageKind
import com.lnight.valence.core.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MessageEntityMapperTest {
    private val mapper = MessageEntityMapper()

    @Test
    fun mapsInboundMessageCorrectly() {
        val domain =
            Message(
                id = MessageId("msg-in"),
                conversationId = ConversationId("conv-1"),
                direction = MessageDirection.Inbound,
                status = MessageStatus.Delivered,
                kind = MessageKind.Text,
                body = "Hello",
                replyToId = MessageId("msg-prev"),
                sentAtMillis = 1000L,
                serverSeq = 42L,
                serverTsMillis = 1050L,
                receivedAtMillis = 1100L,
                createdAtMillis = 1100L,
            )

        val entity = mapper.toEntity(domain)
        assertEquals("msg-in", entity.messageId)
        assertEquals(MessageDirection.Inbound.ordinal, entity.direction)
        assertEquals(42L, entity.serverSeq)

        val mapped = mapper.toDomain(entity)
        assertEquals(domain, mapped)
    }

    @Test
    fun mapsOutboundMessageWithNullServerSeq() {
        val domain =
            Message(
                id = MessageId("msg-out"),
                conversationId = ConversationId("conv-1"),
                direction = MessageDirection.Outbound,
                status = MessageStatus.Pending,
                kind = MessageKind.Text,
                body = "Hi",
                replyToId = null,
                sentAtMillis = 2000L,
                serverSeq = null,
                serverTsMillis = null,
                receivedAtMillis = null,
                createdAtMillis = 2000L,
            )

        val entity = mapper.toEntity(domain)
        assertEquals(MessageDirection.Outbound.ordinal, entity.direction)
        assertEquals(null, entity.serverSeq)

        val mapped = mapper.toDomain(entity)
        assertEquals(domain, mapped)
    }

    @Test
    fun rejectsOutboundMessageWithNonNullServerSeq() {
        val domain =
            Message(
                id = MessageId("msg-out-invalid"),
                conversationId = ConversationId("conv-1"),
                direction = MessageDirection.Outbound,
                status = MessageStatus.Pending,
                kind = MessageKind.Text,
                body = "Hi",
                replyToId = null,
                sentAtMillis = 2000L,
                serverSeq = 99L,
                serverTsMillis = null,
                receivedAtMillis = null,
                createdAtMillis = 2000L,
            )

        assertThrows(IllegalArgumentException::class.java) {
            mapper.toEntity(domain)
        }
    }
}
