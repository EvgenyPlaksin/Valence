package com.lnight.valence.core.database

import com.lnight.valence.core.model.ContactId
import com.lnight.valence.core.model.ConversationId
import com.lnight.valence.core.model.MailboxId
import com.lnight.valence.core.model.MessageDirection
import com.lnight.valence.core.model.MessageId
import com.lnight.valence.core.model.MessageKind
import com.lnight.valence.core.model.MessageStatus
import com.lnight.valence.core.model.OutboxState
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomTypeConvertersTest {
    private val identifierConverters = IdentifierTypeConverters()
    private val enumConverters = EnumTypeConverters()

    @Test
    fun convertsValueObjects() {
        assertEquals("c1", identifierConverters.contactIdToStorage(ContactId("c1")))
        assertEquals(ContactId("c1"), identifierConverters.contactIdFromStorage("c1"))

        assertEquals("conv1", identifierConverters.conversationIdToStorage(ConversationId("conv1")))
        assertEquals(ConversationId("conv1"), identifierConverters.conversationIdFromStorage("conv1"))

        assertEquals("mb1", identifierConverters.mailboxIdToStorage(MailboxId("mb1")))
        assertEquals(MailboxId("mb1"), identifierConverters.mailboxIdFromStorage("mb1"))

        assertEquals("m1", identifierConverters.messageIdToStorage(MessageId("m1")))
        assertEquals(MessageId("m1"), identifierConverters.messageIdFromStorage("m1"))
    }

    @Test
    fun convertsEnums() {
        MessageDirection.entries.forEach { dir ->
            val storage = enumConverters.messageDirectionToStorage(dir)
            assertEquals(dir, enumConverters.messageDirectionFromStorage(storage))
        }

        MessageKind.entries.forEach { kind ->
            val storage = enumConverters.messageKindToStorage(kind)
            assertEquals(kind, enumConverters.messageKindFromStorage(storage))
        }

        MessageStatus.entries.forEach { status ->
            val storage = enumConverters.messageStatusToStorage(status)
            assertEquals(status, enumConverters.messageStatusFromStorage(storage))
        }

        OutboxState.entries.forEach { state ->
            val storage = enumConverters.outboxStateToStorage(state)
            assertEquals(state, enumConverters.outboxStateFromStorage(storage))
        }
    }
}
