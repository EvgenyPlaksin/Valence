package com.lnight.valence.core.database

import com.lnight.valence.core.model.ContactId
import com.lnight.valence.core.model.Conversation
import com.lnight.valence.core.model.ConversationId
import com.lnight.valence.core.model.MessageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationEntityMapperTest {
    private val mapper = ConversationEntityMapper()

    @Test
    fun mapsToDomainAndEntityCorrectly() {
        val domain =
            Conversation(
                id = ConversationId("conv-1"),
                contactId = ContactId("contact-1"),
                lastMessageId = MessageId("msg-1"),
                lastMessageAtMillis = 2000L,
                unreadCount = 5,
                pinned = true,
                mutedUntilMillis = 5000L,
            )

        val entity = mapper.toEntity(domain)

        assertEquals("conv-1", entity.conversationId)
        assertEquals("contact-1", entity.contactId)
        assertEquals("msg-1", entity.lastMessageId)
        assertEquals(2000L, entity.lastMessageAtMillis)
        assertEquals(5, entity.unreadCount)
        assertEquals(1, entity.pinned)
        assertEquals(5000L, entity.mutedUntilMillis)

        val mapped = mapper.toDomain(entity)
        assertEquals(domain, mapped)
    }

    @Test
    fun mapsNullableFields() {
        val domain =
            Conversation(
                id = ConversationId("conv-2"),
                contactId = ContactId("contact-2"),
                lastMessageId = null,
                lastMessageAtMillis = null,
                unreadCount = 0,
                pinned = false,
                mutedUntilMillis = null,
            )

        val entity = mapper.toEntity(domain)
        assertNull(entity.lastMessageId)
        assertNull(entity.lastMessageAtMillis)
        assertEquals(0, entity.pinned)
        assertNull(entity.mutedUntilMillis)

        val mapped = mapper.toDomain(entity)
        assertEquals(domain, mapped)
    }
}
