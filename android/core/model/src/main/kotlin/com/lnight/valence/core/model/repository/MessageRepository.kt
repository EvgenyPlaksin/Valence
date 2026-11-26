package com.lnight.valence.core.model.repository

import com.lnight.valence.core.model.ConversationId
import com.lnight.valence.core.model.Message
import com.lnight.valence.core.model.MessageId
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    suspend fun enqueue(message: Message): MessageEnqueueResult

    fun observeMessages(conversationId: ConversationId): Flow<List<Message>>

    suspend fun findMessage(messageId: MessageId): Message?
}

sealed interface MessageEnqueueResult {
    data object Enqueued : MessageEnqueueResult

    data class Failed(
        val reason: MessageEnqueueFailure,
    ) : MessageEnqueueResult
}

enum class MessageEnqueueFailure {
    Persistence,
}
