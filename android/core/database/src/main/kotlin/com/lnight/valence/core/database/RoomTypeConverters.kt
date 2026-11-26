package com.lnight.valence.core.database

import androidx.room.TypeConverter
import com.lnight.valence.core.model.ContactId
import com.lnight.valence.core.model.ConversationId
import com.lnight.valence.core.model.MailboxId
import com.lnight.valence.core.model.MessageDirection
import com.lnight.valence.core.model.MessageId
import com.lnight.valence.core.model.MessageKind
import com.lnight.valence.core.model.MessageStatus
import com.lnight.valence.core.model.OutboxState

internal class IdentifierTypeConverters {
    @TypeConverter
    fun contactIdFromStorage(value: String): ContactId = ContactId(value)

    @TypeConverter
    fun contactIdToStorage(value: ContactId): String = value.value

    @TypeConverter
    fun conversationIdFromStorage(value: String): ConversationId = ConversationId(value)

    @TypeConverter
    fun conversationIdToStorage(value: ConversationId): String = value.value

    @TypeConverter
    fun mailboxIdFromStorage(value: String): MailboxId = MailboxId(value)

    @TypeConverter
    fun mailboxIdToStorage(value: MailboxId): String = value.value

    @TypeConverter
    fun messageIdFromStorage(value: String): MessageId = MessageId(value)

    @TypeConverter
    fun messageIdToStorage(value: MessageId): String = value.value
}

internal class EnumTypeConverters {
    @TypeConverter
    fun messageDirectionFromStorage(value: Int): MessageDirection = MessageDirection.entries[value]

    @TypeConverter
    fun messageDirectionToStorage(value: MessageDirection): Int = value.ordinal

    @TypeConverter
    fun messageKindFromStorage(value: Int): MessageKind = MessageKind.entries[value]

    @TypeConverter
    fun messageKindToStorage(value: MessageKind): Int = value.ordinal

    @TypeConverter
    fun messageStatusFromStorage(value: Int): MessageStatus = MessageStatus.entries[value]

    @TypeConverter
    fun messageStatusToStorage(value: MessageStatus): Int = value.ordinal

    @TypeConverter
    fun outboxStateFromStorage(value: Int): OutboxState = OutboxState.entries[value]

    @TypeConverter
    fun outboxStateToStorage(value: OutboxState): Int = value.ordinal
}
