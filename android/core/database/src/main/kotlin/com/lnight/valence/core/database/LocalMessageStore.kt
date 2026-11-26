package com.lnight.valence.core.database

import com.lnight.valence.core.model.ConversationId
import com.lnight.valence.core.model.Message
import com.lnight.valence.core.model.MessageId
import com.lnight.valence.core.model.repository.MessageEnqueueResult
import kotlinx.coroutines.flow.Flow

interface LocalMessageStore {
    suspend fun enqueue(message: Message): MessageEnqueueResult

    fun observeMessages(conversationId: ConversationId): Flow<List<Message>>

    suspend fun getMessage(messageId: MessageId): Message?

    suspend fun commitInbound(commit: InboundMessageCommit): InboundCommitResult

    suspend fun sealOutbound(
        messageId: MessageId,
        session: SignalSessionStorage,
        envelope: ImmutableBytes,
    )

    suspend fun markSent(
        messageId: MessageId,
        serverTsMillis: Long,
    )
}
